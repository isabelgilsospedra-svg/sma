package ua;

import java.io.IOException;
import java.net.SocketException;
import java.util.Timer;
import java.util.TimerTask;

import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SIPMessage;

import common.InviteState;
import mensajesSIP.RingingMessage;
import mensajesSIP.TryingMessage;

public class UaTransactionLayer {
	//private static final int IDLE = 0;
	//private int state = IDLE;

	/** Cada cuanto se reenvia el REGISTER si el proxy no contesta (ms). */
	private static final long REGISTER_RETRY_MS = 2000;

	private UaUserLayer userLayer;
	private UaTransportLayer transportLayer;

	/** Temporizador de reenvio del REGISTER; null si no hay ninguno pendiente. */
	private Timer registerTimer = null;
	private RegisterMessage pendingRegister = null; // guarda el mensaje cuyo 200  OK o 404 estamos esperando

	/** Estado de la transaccion INVITE en curso (IDLE si no hay ninguna). */
	private InviteState inviteState = InviteState.IDLE;
	/** true si en la transaccion INVITE en curso somos el llamante; false si somos el llamado. */
	private boolean caller = false;
	/** Call-ID de la transaccion INVITE en curso: los mensajes de otro Call-ID no son de esta llamada. */
	private String inviteCallId = null;

	/** Tiempo en Completed antes de pasar a Terminated (ms). */
	private static final long COMPLETED_MS = 1000;
	/** Llamado: a donde se envian las respuestas al INVITE (la primera Via del INVITE: el proxy). */
	private String responseAddress = null;
	private int responsePort = 0;
	/** Temporizador de 1 s del estado Completed. */
	private Timer completedTimer = null;

	public UaTransactionLayer(int listenPort, String proxyAddress, int proxyPort, boolean debug,
							  UaUserLayer userLayer) throws SocketException {
		this.userLayer = userLayer;
		this.transportLayer = new UaTransportLayer(listenPort, proxyAddress, proxyPort, debug, this);
	}

	public synchronized void onMessageReceived(SIPMessage sipMessage) throws IOException {
		if (sipMessage instanceof OKMessage
		        && isRegister(((OKMessage) sipMessage).getcSeqStr())) {
		    if (finishRegister(
		            ((OKMessage) sipMessage).getCallId(),
		            ((OKMessage) sipMessage).getcSeqNumber())) {
		        userLayer.onRegisterOk((OKMessage) sipMessage);
		    }
		} else if (sipMessage instanceof NotFoundMessage
		        && isRegister(((NotFoundMessage) sipMessage).getcSeqStr())) {
		    if (finishRegister(
		            ((NotFoundMessage) sipMessage).getCallId(),
		            ((NotFoundMessage) sipMessage).getcSeqNumber())) {
		        userLayer.onRegisterNotFound((NotFoundMessage) sipMessage);
		    }
		} else if (sipMessage instanceof NotFoundMessage
		        && "INVITE".equals(((NotFoundMessage) sipMessage).getcSeqStr())) {

			// comprobamos que el 404 corresppnde a INVITE, no al REGISTER. 
		    NotFoundMessage notFound = (NotFoundMessage) sipMessage;

		    if (caller && notFound.getCallId().equals(inviteCallId) // miramos el call-id
		            && (inviteState == InviteState.CALLING
		                || inviteState == InviteState.PROCEEDING)) { // y comprobamos que la transacción espera respuesta

		        setInviteState(InviteState.COMPLETED);
		        userLayer.onInviteNotFound();
		        setInviteState(InviteState.TERMINATED);
		        setInviteState(InviteState.IDLE);
		        inviteCallId = null;

		    } else {
		        System.err.println("Unexpected message, throwing away");
		    }
		} else if (sipMessage instanceof InviteMessage) {
			onInvite((InviteMessage) sipMessage);
		} else if (sipMessage instanceof TryingMessage) {
			onProvisional(((TryingMessage) sipMessage).getCallId(), false);
		} else if (sipMessage instanceof RingingMessage) {
			onProvisional(((RingingMessage) sipMessage).getCallId(), true);
		} else {
			System.err.println("Unexpected message, throwing away");
		}
	}

	/** Llamado: llega un INVITE. Si no hay transaccion en curso, empieza una como llamado. */
	private void onInvite(InviteMessage inviteMessage) throws IOException {
		if (inviteState == InviteState.IDLE) {
			caller = false;
			inviteCallId = inviteMessage.getCallId();
			// Las respuestas vuelven por donde vino el INVITE: la primera Via.
			String[] via = inviteMessage.getVias().get(0).split(":");
			responseAddress = via[0];
			responsePort = Integer.parseInt(via[1]);
			// Diagrama del llamado: INVITE -> Proceeding, pass INV to TU (el UA llamado no envia 100).
			setInviteState(InviteState.PROCEEDING);
			userLayer.onInviteReceived(inviteMessage);
		} else {
			// Ya hay una llamada en curso: se tratara en el paso 6 (486 Busy Here).
			System.err.println("Unexpected message, throwing away");
		}
	}

	/** Llamante: llega un 100 Trying o un 180 Ringing de nuestro INVITE. */
	private void onProvisional(String callId, boolean ringing) {
		boolean ours = caller && callId.equals(inviteCallId)
				&& (inviteState == InviteState.CALLING || inviteState == InviteState.PROCEEDING);
		if (!ours) {
			System.err.println("Unexpected message, throwing away");
			return;
		}
		// Diagrama del llamante: Calling --100/180--> Proceeding; en Proceeding, 100/180 no cambian el estado.
		if (inviteState == InviteState.CALLING) {
			setInviteState(InviteState.PROCEEDING);
		}
		if (ringing) {
			userLayer.onRinging();
		}
	}
	/** Llamado: el usuario (TU) envia el 180 Ringing. La transaccion sigue en Proceeding. */
	public synchronized boolean sendRinging(RingingMessage ringing) throws IOException {
		if (caller || inviteState != InviteState.PROCEEDING) {
			return false;
		}
		transportLayer.send(ringing, responseAddress, responsePort);
		return true;
	}

	/** Llamado: "200 from TU": se envia el 200 OK y la transaccion pasa a Terminated. */
	public synchronized boolean sendOk(OKMessage ok) throws IOException {
		if (caller || inviteState != InviteState.PROCEEDING) {
			return false;
		}
		transportLayer.send(ok, responseAddress, responsePort);
		setInviteState(InviteState.TERMINATED);
		endInviteTransaction();
		return true;
	}

	/**
	 * Llamado: "404/408/486/503 from TU": se envia el error y la transaccion pasa
	 * a Completed; a 1 s, Terminated. La espera del ACK y el reenvio del error
	 * cada 200 ms se anaden en el paso 4.
	 */
	public synchronized boolean sendError(SIPMessage error) throws IOException {
		if (caller || inviteState != InviteState.PROCEEDING) {
			return false;
		}
		transportLayer.send(error, responseAddress, responsePort);
		setInviteState(InviteState.COMPLETED);
		startCompletedTimer();
		return true;
	}

	private void startCompletedTimer() {
		completedTimer = new Timer("ua-completed", true);
		completedTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (UaTransactionLayer.this) {
					setInviteState(InviteState.TERMINATED);
					endInviteTransaction();
				}
			}
		}, COMPLETED_MS);
	}

	/** Fin de la transaccion INVITE: se vuelve a IDLE para poder atender otra. */
	private void endInviteTransaction() {
		if (completedTimer != null) {
			completedTimer.cancel();
			completedTimer = null;
		}
		inviteState = InviteState.IDLE;
		inviteCallId = null;
	}
	/** Cambia el estado de la transaccion INVITE y lo muestra por pantalla (lo pide el enunciado). */
	private void setInviteState(InviteState newState) {
		String role = caller ? "llamante" : "llamado";
		System.out.println("[Transaccion INVITE " + role + "] " + inviteState + " -> " + newState);
		inviteState = newState;
	}

	/** Una respuesta es del REGISTER si el metodo de su CSeq es REGISTER. */
	private boolean isRegister(String cSeqMethod) {
		return "REGISTER".equals(cSeqMethod);
	}
	
	private synchronized boolean finishRegister(String callId, String cSeq) {

	    if (pendingRegister == null) {
	        return false;
	    }

	    if (!pendingRegister.getCallId().equals(callId)
	            || !pendingRegister.getcSeqNumber().equals(cSeq)) {

	        System.out.println("Respuesta REGISTER no coincidente");
	        return false;
	    }

	    stopRegisterTimer();
	    pendingRegister = null;

	    return true;
	}

	public void startListeningNetwork() {
		transportLayer.startListening();
	}

	/** Llamante: envia el INVITE al proxy y la transaccion pasa a Calling. */
	public synchronized void call(InviteMessage inviteMessage) throws IOException {
		caller = true;
		inviteCallId = inviteMessage.getCallId();
		setInviteState(InviteState.CALLING);
		transportLayer.sendToProxy(inviteMessage);
	}

	/**
	 * Envia el REGISTER y lo reenvia cada 2 s hasta recibir
	 * una respuesta 200 OK o 404.
	 */
	public synchronized void register(RegisterMessage registerMessage) throws IOException {

	    stopRegisterTimer();

	    pendingRegister = registerMessage; // guardamos el mensaje que estamos esperando confirmar

	    registerTimer = new Timer("register-retry", true);

	    registerTimer.schedule(new TimerTask() {
	        @Override
	        public void run() {

	        	/* aquí evitamos que el temporizador compruebe que hay un REGISTER pendiente y lo 
	        	 * envíe a otro hilo cuando se de por terminado
	        	 */
	            synchronized (UaTransactionLayer.this) {

	                if (pendingRegister == null) {
	                    return;
	                }

	                try {
	                    transportLayer.sendToProxy(pendingRegister);
	                } catch (IOException e) {
	                    System.err.println("Error enviando REGISTER: " + e.getMessage());
	                }
	            }
	        }
	    }, 0, REGISTER_RETRY_MS); // el primer envío realiza el temporizador inmediatamente y los siguientes se programan cada 2 segundos
	}

	private synchronized void stopRegisterTimer() {
		if (registerTimer != null) {
			registerTimer.cancel();
			registerTimer = null;
		}
	}
}