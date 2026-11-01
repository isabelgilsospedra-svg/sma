package ua;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import common.InviteState;
import mensajesSIP.ACKMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.ServiceUnavailableMessage;
import mensajesSIP.TryingMessage;

/**
 * Capa de transaccion del UA.
 *
 * Se encarga de dos cosas: del REGISTER, que se reenvia cada 2 s hasta que el
 * proxy contesta, y de la maquina de estados de la transaccion INVITE, tanto
 * cuando el UA llama como cuando le llaman. Lo que no pertenece a ninguna
 * transaccion (el ACK del 200, el BYE y su 200 OK) pasa tal cual a la capa de
 * usuario.
 *
 * Los metodos que tocan el estado son synchronized porque los usan tres hilos:
 * el de red, el del teclado (a traves de la capa de usuario) y los temporizadores.
 */
public class UaTransactionLayer {
	/** Cada cuanto se reenvia el REGISTER mientras el proxy no contesta (ms). */
	private static final long REGISTER_RETRY_MS = 2000;
	/** Cada cuanto reenvia el llamado su respuesta de error mientras no llega el ACK (ms). */
	private static final long RETRANSMIT_MS = 200;
	/** Tiempo que se pasa en Completed antes de ir a Terminated (ms). */
	private static final long COMPLETED_MS = 1000;

	private final UaUserLayer userLayer;
	private final UaTransportLayer transportLayer;

	// ---- REGISTER ----

	/** REGISTER cuyo 200 OK o 404 estamos esperando; null si no hay ninguno pendiente. */
	private RegisterMessage pendingRegister = null;
	/** Temporizador que reenvia el REGISTER pendiente. */
	private Timer registerTimer = null;

	// ---- Transaccion INVITE ----

	/** Estado de la transaccion INVITE en curso (IDLE si no hay ninguna). */
	private InviteState inviteState = InviteState.IDLE;
	/** true si en la transaccion en curso somos el llamante; false si somos el llamado. */
	private boolean caller = false;
	/** Call-ID de la transaccion en curso: lo que llegue con otro Call-ID no es de esta llamada. */
	private String inviteCallId = null;
	/** Llamante: el INVITE que enviamos. De el salen los campos del ACK a un error. */
	private InviteMessage sentInvite = null;
	/** Llamado: a donde van nuestras respuestas, que es la primera Via del INVITE (el proxy). */
	private String responseAddress = null;
	private int responsePort = 0;
	/** Temporizador de 1 s del estado Completed. */
	private Timer completedTimer = null;
	/** Llamado: temporizador que reenvia el error cada 200 ms hasta que llega el ACK. */
	private Timer retransmitTimer = null;

	public UaTransactionLayer(int listenPort, String proxyAddress, int proxyPort, boolean debug,
							  UaUserLayer userLayer) throws SocketException {
		this.userLayer = userLayer;
		this.transportLayer = new UaTransportLayer(listenPort, proxyAddress, proxyPort, debug, this);
	}

	public void startListeningNetwork() {
		transportLayer.startListening();
	}

	// =========================================================================
	// Mensajes que llegan de la red
	// =========================================================================

	/**
	 * Aqui entra todo lo que llega por la red. Cada mensaje se reparte segun a
	 * quien le toca: al REGISTER pendiente, a la transaccion INVITE o
	 * directamente a la capa de usuario.
	 */
	public synchronized void onMessageReceived(SIPMessage sipMessage) throws IOException {
		if (sipMessage instanceof OKMessage) {
			onOk((OKMessage) sipMessage);
		} else if (sipMessage instanceof NotFoundMessage) {
			onNotFound((NotFoundMessage) sipMessage);
		} else if (sipMessage instanceof RequestTimeoutMessage) {
			onErrorResponse(((RequestTimeoutMessage) sipMessage).getCallId(), 408);
		} else if (sipMessage instanceof BusyHereMessage) {
			onErrorResponse(((BusyHereMessage) sipMessage).getCallId(), 486);
		} else if (sipMessage instanceof ServiceUnavailableMessage) {
			onErrorResponse(((ServiceUnavailableMessage) sipMessage).getCallId(), 503);
		} else if (sipMessage instanceof TryingMessage) {
			onProvisional(((TryingMessage) sipMessage).getCallId(), false);
		} else if (sipMessage instanceof RingingMessage) {
			onProvisional(((RingingMessage) sipMessage).getCallId(), true);
		} else if (sipMessage instanceof InviteMessage) {
			onInvite((InviteMessage) sipMessage);
		} else if (sipMessage instanceof ACKMessage) {
			onAck((ACKMessage) sipMessage);
		} else if (sipMessage instanceof ByeMessage) {
			// El BYE no tiene maquina de estados (asi lo pide el enunciado): lo trata la capa de usuario.
			userLayer.onByeReceived((ByeMessage) sipMessage);
		} else {
			System.err.println("Unexpected message, throwing away");
		}
	}

	/** Un 200 OK puede responder a tres peticiones distintas; el metodo de su CSeq dice a cual. */
	private void onOk(OKMessage ok) throws IOException {
		String method = ok.getcSeqStr();
		if ("REGISTER".equals(method)) {
			if (finishRegister(ok.getCallId(), ok.getcSeqNumber())) {
				userLayer.onRegisterOk(ok);
			}
		} else if ("BYE".equals(method)) {
			// El BYE no tiene maquina de estados: su 200 OK va directo a la capa de usuario.
			userLayer.onByeOk(ok);
		} else {
			onFinalOk(ok);
		}
	}

	/** Un 404 puede responder al REGISTER (usuario no permitido) o al INVITE (destino no registrado). */
	private void onNotFound(NotFoundMessage notFound) throws IOException {
		if ("REGISTER".equals(notFound.getcSeqStr())) {
			if (finishRegister(notFound.getCallId(), notFound.getcSeqNumber())) {
				userLayer.onRegisterNotFound(notFound);
			}
		} else {
			onErrorResponse(notFound.getCallId(), 404);
		}
	}

	// =========================================================================
	// REGISTER
	// =========================================================================

	/**
	 * Envia el REGISTER y lo reenvia cada 2 s hasta que llega el 200 OK o el
	 * 404. El primer envio lo hace el propio temporizador, sin esperar.
	 */
	public synchronized void register(RegisterMessage registerMessage) {
		stopRegisterTimer();
		pendingRegister = registerMessage;
		registerTimer = new Timer("register-retry", true);
		registerTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				// Sincronizado con la capa: asi no se reenvia un REGISTER que el
				// hilo de red acaba de dar por respondido.
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
		}, 0, REGISTER_RETRY_MS);
	}

	/**
	 * Comprueba que la respuesta es del REGISTER pendiente (mismo Call-ID y
	 * mismo CSeq). Si lo es, para el reenvio y devuelve true. Si no, la
	 * respuesta se ignora: es repetida o de un REGISTER anterior.
	 */
	private boolean finishRegister(String callId, String cSeqNumber) {
		if (pendingRegister == null) {
			return false;
		}
		if (!pendingRegister.getCallId().equals(callId) || !pendingRegister.getcSeqNumber().equals(cSeqNumber)) {
			System.out.println("Respuesta REGISTER no coincidente");
			return false;
		}
		stopRegisterTimer();
		pendingRegister = null;
		return true;
	}

	private void stopRegisterTimer() {
		if (registerTimer != null) {
			registerTimer.cancel();
			registerTimer = null;
		}
	}

	// =========================================================================
	// Transaccion INVITE como llamante
	// =========================================================================

	/** Llamante: envia el INVITE al proxy y la transaccion pasa a Calling. */
	public synchronized void call(InviteMessage inviteMessage) throws IOException {
		caller = true;
		inviteCallId = inviteMessage.getCallId();
		sentInvite = inviteMessage;
		setInviteState(InviteState.CALLING);
		transportLayer.sendToProxy(inviteMessage);
	}

	/** Llamante: true si la respuesta es de nuestro INVITE y todavia esperamos la respuesta final. */
	private boolean isResponseToOurInvite(String callId) {
		return caller && callId.equals(inviteCallId)
				&& (inviteState == InviteState.CALLING || inviteState == InviteState.PROCEEDING);
	}

	/**
	 * Llamante: llega un 100 Trying o un 180 Ringing. Desde Calling se pasa a
	 * Proceeding; en Proceeding el estado no cambia. Del 180 se avisa al usuario.
	 */
	private void onProvisional(String callId, boolean ringing) {
		if (!isResponseToOurInvite(callId)) {
			System.err.println("Unexpected message, throwing away");
			return;
		}
		if (inviteState == InviteState.CALLING) {
			setInviteState(InviteState.PROCEEDING);
		}
		if (ringing) {
			userLayer.onRinging();
		}
	}

	/**
	 * Llamante: llega el 200 OK. Se pasa a Terminated y la transaccion acaba
	 * aqui. El ACK del 200 no es cosa de la transaccion: lo envia la capa de
	 * usuario, directamente al llamado.
	 */
	private void onFinalOk(OKMessage ok) throws IOException {
		if (!isResponseToOurInvite(ok.getCallId())) {
			System.err.println("Unexpected message, throwing away");
			return;
		}
		setInviteState(InviteState.TERMINATED);
		endInviteTransaction();
		userLayer.onCallAccepted(ok);
	}

	/**
	 * Llamante: llega un error (404, 408, 486 o 503). Se pasa a Completed, se
	 * envia el ACK y al cabo de 1 s se pasa a Terminated. Si el mismo error
	 * vuelve a llegar estando en Completed, es que nuestro ACK se perdio y el
	 * otro lado lo esta reenviando: se manda el ACK otra vez.
	 */
	private void onErrorResponse(String callId, int code) throws IOException {
		if (caller && inviteState == InviteState.COMPLETED && callId.equals(inviteCallId)) {
			System.out.println("Error " + code + " repetido: se reenvia el ACK");
			sendAckForError();
			return;
		}
		if (!isResponseToOurInvite(callId)) {
			System.err.println("Unexpected message, throwing away");
			return;
		}
		setInviteState(InviteState.COMPLETED);
		sendAckForError();
		userLayer.onCallRejected(code);
		startCompletedTimer();
	}

	/**
	 * Llamante: ACK a una respuesta de error. Va salto a salto, es decir, al
	 * proxy, por el mismo camino que el INVITE. Repite el Call-ID, To, From y el
	 * numero de CSeq del INVITE, pero con el metodo ACK.
	 */
	private void sendAckForError() throws IOException {
		ACKMessage ack = new ACKMessage();
		ack.setDestination(sentInvite.getDestination());
		ack.setVias(new ArrayList<String>(sentInvite.getVias()));
		ack.setMaxForwards(70);
		ack.setToName(sentInvite.getToName());
		ack.setToUri(sentInvite.getToUri());
		ack.setFromName(sentInvite.getFromName());
		ack.setFromUri(sentInvite.getFromUri());
		ack.setCallId(sentInvite.getCallId());
		ack.setcSeqNumber(sentInvite.getcSeqNumber());
		ack.setcSeqStr("ACK");
		ack.setContentLength(0);
		transportLayer.sendToProxy(ack);
	}

	// =========================================================================
	// Transaccion INVITE como llamado
	// =========================================================================

	/**
	 * Llega un INVITE.
	 *
	 * Si el UA esta libre, empieza una transaccion como llamado: pasa a
	 * Proceeding y avisa al usuario (el UA llamado no envia 100 Trying).
	 *
	 * Si ya esta con otra llamada, en cualquier momento hasta recibir el 200 OK
	 * del BYE, la capa de usuario responde 486 sin abrir otra maquina de
	 * estados (paso 6).
	 */
	private void onInvite(InviteMessage inviteMessage) throws IOException {
		if (inviteState != InviteState.IDLE || userLayer.isBusy()) {
			userLayer.rejectBusy(inviteMessage);
			return;
		}
		caller = false;
		inviteCallId = inviteMessage.getCallId();
		// Las respuestas vuelven por donde vino el INVITE: su primera Via.
		String[] via = inviteMessage.getVias().get(0).split(":");
		responseAddress = via[0];
		responsePort = Integer.parseInt(via[1]);
		setInviteState(InviteState.PROCEEDING);
		userLayer.onInviteReceived(inviteMessage);
	}

	/** Llamado: el usuario envia el 180 Ringing. La transaccion sigue en Proceeding. */
	public synchronized boolean sendRinging(RingingMessage ringing) throws IOException {
		if (caller || inviteState != InviteState.PROCEEDING) {
			return false;
		}
		transportLayer.send(ringing, responseAddress, responsePort);
		return true;
	}

	/** Llamado: el usuario descuelga. Se envia el 200 OK y la transaccion pasa a Terminated. */
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
	 * Llamado: el usuario rechaza (486) o no contesta a tiempo (408). Se envia
	 * el error y se pasa a Completed: el error se reenvia cada 200 ms hasta que
	 * llega el ACK, y al cabo de 1 s se pasa a Terminated.
	 */
	public synchronized boolean sendError(SIPMessage error) throws IOException {
		if (caller || inviteState != InviteState.PROCEEDING) {
			return false;
		}
		transportLayer.send(error, responseAddress, responsePort);
		setInviteState(InviteState.COMPLETED);
		startRetransmitTimer(error);
		startCompletedTimer();
		return true;
	}

	/**
	 * Llega un ACK. Si somos el llamado y estamos en Completed con ese Call-ID,
	 * es el ACK a nuestro error: se deja de reenviar y se pasa a Terminated.
	 * Cualquier otro ACK no es de la transaccion (normalmente, el ACK del 200,
	 * que va de extremo a extremo) y se le pasa a la capa de usuario.
	 */
	private void onAck(ACKMessage ack) {
		if (!caller && inviteState == InviteState.COMPLETED && ack.getCallId().equals(inviteCallId)) {
			setInviteState(InviteState.TERMINATED);
			endInviteTransaction();
		} else {
			userLayer.onAckReceived(ack);
		}
	}

	/** Llamado en Completed: reenvia el error cada 200 ms mientras no llegue el ACK. */
	private void startRetransmitTimer(SIPMessage error) {
		retransmitTimer = new Timer("ua-retransmit", true);
		retransmitTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (UaTransactionLayer.this) {
					if (inviteState != InviteState.COMPLETED) {
						return;
					}
					try {
						System.out.println("Sin ACK: se reenvia la respuesta de error");
						transportLayer.send(error, responseAddress, responsePort);
					} catch (IOException e) {
						System.err.println("Error reenviando la respuesta: " + e.getMessage());
					}
				}
			}
		}, RETRANSMIT_MS, RETRANSMIT_MS);
	}

	// =========================================================================
	// Partes comunes de la transaccion INVITE
	// =========================================================================

	/** Al cabo de 1 s en Completed se pasa a Terminated, haya llegado el ACK o no. */
	private void startCompletedTimer() {
		completedTimer = new Timer("ua-completed", true);
		completedTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (UaTransactionLayer.this) {
					if (inviteState != InviteState.COMPLETED) {
						return; // ya habia terminado, por ejemplo al llegar el ACK
					}
					setInviteState(InviteState.TERMINATED);
					endInviteTransaction();
				}
			}
		}, COMPLETED_MS);
	}

	/** Fin de la transaccion INVITE: se paran los temporizadores y se vuelve a IDLE para poder atender otra. */
	private void endInviteTransaction() {
		if (completedTimer != null) {
			completedTimer.cancel();
			completedTimer = null;
		}
		if (retransmitTimer != null) {
			retransmitTimer.cancel();
			retransmitTimer = null;
		}
		inviteState = InviteState.IDLE;
		inviteCallId = null;
	}

	/** Cambia el estado de la transaccion y lo imprime, como pide el enunciado. */
	private void setInviteState(InviteState newState) {
		String role = caller ? "llamante" : "llamado";
		System.out.println("[Transaccion INVITE " + role + "] " + inviteState + " -> " + newState);
		inviteState = newState;
	}

	// =========================================================================
	// Mensajes fuera de la transaccion
	// =========================================================================

	/**
	 * Envia un mensaje directamente al otro extremo, fuera de la transaccion
	 * INVITE: el ACK del 200, el BYE y su 200 OK (sin loose routing van de
	 * extremo a extremo) y el 486 a una segunda llamada.
	 *
	 * No es synchronized a proposito: lo usa el hilo del teclado, y asi nunca
	 * se queda esperando al hilo de red.
	 */
	public void sendDirect(SIPMessage message, String address, int port) throws IOException {
		transportLayer.send(message, address, port);
	}
}
