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

	/** Estado de la transaccion INVITE en curso (IDLE si no hay ninguna). */
	private InviteState inviteState = InviteState.IDLE;
	/** true si en la transaccion INVITE en curso somos el llamante; false si somos el llamado. */
	private boolean caller = false;
	/** Call-ID de la transaccion INVITE en curso: los mensajes de otro Call-ID no son de esta llamada. */
	private String inviteCallId = null;

	public UaTransactionLayer(int listenPort, String proxyAddress, int proxyPort, boolean debug,
							  UaUserLayer userLayer) throws SocketException {
		this.userLayer = userLayer;
		this.transportLayer = new UaTransportLayer(listenPort, proxyAddress, proxyPort, debug, this);
	}

	public synchronized void onMessageReceived(SIPMessage sipMessage) throws IOException {
		if (sipMessage instanceof OKMessage
				&& isRegister(((OKMessage) sipMessage).getcSeqStr())) {
			stopRegisterTimer();
			userLayer.onRegisterOk((OKMessage) sipMessage);
		} else if (sipMessage instanceof NotFoundMessage
				&& isRegister(((NotFoundMessage) sipMessage).getcSeqStr())) {
			stopRegisterTimer();
			userLayer.onRegisterNotFound((NotFoundMessage) sipMessage);
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
	 * Envia el REGISTER y lo reenvia cada 2 s hasta que llegue una respuesta
	 * (200 o 404). El reenvio es el mismo mensaje: mismo Call-ID y mismo CSeq.
	 */
	public synchronized void register(RegisterMessage registerMessage) throws IOException {
		stopRegisterTimer();
		transportLayer.sendToProxy(registerMessage);

		registerTimer = new Timer("register-retry", true);
		registerTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					System.out.println("Sin respuesta del proxy: reenviando REGISTER");
					transportLayer.sendToProxy(registerMessage);
				} catch (IOException e) {
					System.err.println("Error reenviando REGISTER: " + e.getMessage());
				}
			}
		}, REGISTER_RETRY_MS, REGISTER_RETRY_MS);
	}

	private synchronized void stopRegisterTimer() {
		if (registerTimer != null) {
			registerTimer.cancel();
			registerTimer = null;
		}
	}
}