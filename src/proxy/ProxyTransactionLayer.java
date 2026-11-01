package proxy;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import common.InviteState;
import mensajesSIP.ACKMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.SIPMessage;

/**
 * Capa de transaccion del Proxy.
 *
 * El REGISTER se atiende sin estado. Cada INVITE, en cambio, se procesa con
 * dos transacciones a la vez: una con A (el llamante), en la que el proxy hace
 * de llamado, y otra con B (el llamado), en la que hace de llamante.
 *
 * El proxy solo procesa un INVITE cada vez. Mientras tanto, un INVITE con otro
 * Call-ID recibe un 503 sin abrir transacciones nuevas. Sin loose routing, el
 * proxy queda libre en cuanto terminan sus dos transacciones.
 */
public class ProxyTransactionLayer {
	/** Tiempo que se pasa en Completed antes de ir a Terminated (ms). */
	private static final long COMPLETED_MS = 1000;
	/** Cada cuanto se reenvia a A el error mientras no llega su ACK (ms). */
	private static final long RETRANSMIT_MS = 200;

	private final ProxyUserLayer userLayer;
	private final ProxyTransportLayer transportLayer;

	/** Transaccion con A (llamante): el proxy recibe su INVITE y le responde. */
	private InviteState stateA = InviteState.IDLE;
	/** Transaccion con B (llamado): el proxy le reenvia el INVITE y recibe sus respuestas. */
	private InviteState stateB = InviteState.IDLE;
	/** Call-ID del INVITE que se esta procesando. */
	private String inviteCallId = null;

	/** Donde esta A (la primera Via de su INVITE). */
	private String addressA = null;
	private int portA = 0;
	/** Donde esta B (su Contact registrado). */
	private String addressB = null;
	private int portB = 0;
	/** El INVITE tal como se reenvio a B. De el salen los campos del ACK que el proxy envia a B. */
	private InviteMessage inviteToB = null;

	/** Transaccion con A en Completed: temporizador de 1 s. */
	private Timer completedTimerA = null;
	/** Transaccion con A en Completed: reenvio del error cada 200 ms. */
	private Timer retransmitTimerA = null;
	/** Transaccion con B en Completed: temporizador de 1 s. */
	private Timer completedTimerB = null;

	public ProxyTransactionLayer(int listenPort, boolean debug, ProxyUserLayer userLayer) throws SocketException {
		this.userLayer = userLayer;
		this.transportLayer = new ProxyTransportLayer(listenPort, debug, this);
	}

	public void startListening() {
		transportLayer.startListening();
	}

	// =========================================================================
	// Mensajes que llegan de la red
	// =========================================================================

	/** Aqui entra todo lo que llega por la red; cada mensaje se reparte segun su tipo. */
	public synchronized void onMessageReceived(SIPMessage sipMessage) throws IOException {
		if (sipMessage instanceof RegisterMessage) {
			// El REGISTER no depende de ninguna llamada: se atiende siempre.
			userLayer.onRegisterReceived((RegisterMessage) sipMessage);
		} else if (sipMessage instanceof InviteMessage) {
			onInvite((InviteMessage) sipMessage);
		} else if (sipMessage instanceof ACKMessage) {
			onAckFromA((ACKMessage) sipMessage);
		} else if (sipMessage instanceof RingingMessage) {
			RingingMessage ringing = (RingingMessage) sipMessage;
			onResponseFromB(ringing, ringing.getCallId(), ringing.getVias(), 180);
		} else if (sipMessage instanceof OKMessage && "INVITE".equals(((OKMessage) sipMessage).getcSeqStr())) {
			OKMessage ok = (OKMessage) sipMessage;
			onResponseFromB(ok, ok.getCallId(), ok.getVias(), 200);
		} else if (sipMessage instanceof BusyHereMessage) {
			BusyHereMessage busy = (BusyHereMessage) sipMessage;
			onResponseFromB(busy, busy.getCallId(), busy.getVias(), 486);
		} else if (sipMessage instanceof RequestTimeoutMessage) {
			RequestTimeoutMessage timeout = (RequestTimeoutMessage) sipMessage;
			onResponseFromB(timeout, timeout.getCallId(), timeout.getVias(), 408);
		} else {
			System.err.println("Unexpected message, throwing away");
		}
	}

	/**
	 * Llega un INVITE. Si el proxy esta libre, lo procesa la capa de usuario.
	 * Si ya esta con otro (paso 6): si trae el mismo Call-ID es una repeticion
	 * y se descarta; si trae otro, se responde 503 sin abrir transacciones.
	 */
	private void onInvite(InviteMessage inviteMessage) throws IOException {
		if (stateA == InviteState.IDLE) {
			userLayer.onInviteReceived(inviteMessage);
		} else if (inviteMessage.getCallId().equals(inviteCallId)) {
			System.err.println("Unexpected message, throwing away");
		} else {
			userLayer.rejectBusy(inviteMessage);
		}
	}

	/**
	 * Respuesta de B al INVITE que le reenviamos. Se actualizan las dos
	 * transacciones y la respuesta se reenvia a A (la capa de usuario quita
	 * nuestra Via y la manda a la siguiente).
	 * <ul>
	 * <li>180: con B se pasa de Calling a Proceeding; con A se sigue en Proceeding.</li>
	 * <li>200: las dos transacciones terminan. Sin loose routing, el proxy sale de la llamada.</li>
	 * <li>486 o 408: con B, Completed y ACK a B (salto a salto); a 1 s, Terminated.
	 * Con A, Completed: se reenvia el error cada 200 ms hasta el ACK de A; a 1 s, Terminated.</li>
	 * <li>Si B repite el error estando ya en Completed, es que nuestro ACK se perdio: se reenvia.</li>
	 * </ul>
	 */
	private void onResponseFromB(SIPMessage response, String callId, ArrayList<String> vias, int code)
			throws IOException {
		if (code >= 300 && stateB == InviteState.COMPLETED && callId.equals(inviteCallId)) {
			System.out.println("[Proxy] Error " + code + " repetido de B: se reenvia el ACK a B");
			sendAckToB();
			return;
		}
		boolean ours = callId.equals(inviteCallId)
				&& (stateB == InviteState.CALLING || stateB == InviteState.PROCEEDING);
		if (!ours) {
			System.err.println("Unexpected message, throwing away");
			return;
		}
		if (code < 200) {
			if (stateB == InviteState.CALLING) {
				setStateB(InviteState.PROCEEDING);
			}
			userLayer.forwardResponseToA(response, vias);
		} else if (code == 200) {
			setStateB(InviteState.TERMINATED);
			userLayer.forwardResponseToA(response, vias);
			setStateA(InviteState.TERMINATED);
			endInvite();
		} else {
			// Con B el proxy es el llamante: Completed y ACK a B.
			setStateB(InviteState.COMPLETED);
			sendAckToB();
			startCompletedTimerB();
			// Con A el proxy es el llamado: reenvia el error y espera el ACK de A.
			userLayer.forwardResponseToA(response, vias);
			enterCompletedA(response);
		}
	}

	/**
	 * Llega un ACK. Si la transaccion con A esta en Completed y es su Call-ID,
	 * es el ACK al error que le enviamos: se deja de reenviar y se pasa a
	 * Terminated. Cualquier otro ACK se descarta: sin loose routing el ACK del
	 * 200 va de A a B directamente, y el ACK de un 503 no es de la llamada en curso.
	 */
	private void onAckFromA(ACKMessage ack) {
		if (stateA == InviteState.COMPLETED && ack.getCallId().equals(inviteCallId)) {
			cancelTimer(retransmitTimerA);
			setStateA(InviteState.TERMINATED);
			tryEndInvite();
		} else {
			System.err.println("Unexpected message, throwing away");
		}
	}

	// =========================================================================
	// Acciones que pide la capa de usuario
	// =========================================================================

	/** Transaccion con A: el INVITE se acepta. Pasa a Proceeding y se envia el 100 Trying a A. */
	public synchronized void acceptInvite(String callId, SIPMessage trying, String addressA, int portA)
			throws IOException {
		inviteCallId = callId;
		this.addressA = addressA;
		this.portA = portA;
		setStateA(InviteState.PROCEEDING);
		transportLayer.send(trying, addressA, portA);
	}

	/**
	 * Transaccion con A: el INVITE se rechaza con un 404. Se pasa a Proceeding,
	 * se envia el error y se entra en Completed: se reenvia cada 200 ms hasta el
	 * ACK de A, y a 1 s se pasa a Terminated.
	 */
	public synchronized void rejectInvite(String callId, SIPMessage error, String addressA, int portA)
			throws IOException {
		inviteCallId = callId;
		this.addressA = addressA;
		this.portA = portA;
		setStateA(InviteState.PROCEEDING);
		transportLayer.send(error, addressA, portA);
		enterCompletedA(error);
	}

	/** Transaccion con B: el proxy reenvia el INVITE al llamado y pasa a Calling. */
	public synchronized void forwardInvite(InviteMessage inviteMessage, String addressB, int portB)
			throws IOException {
		this.inviteToB = inviteMessage;
		this.addressB = addressB;
		this.portB = portB;
		setStateB(InviteState.CALLING);
		transportLayer.send(inviteMessage, addressB, portB);
	}

	/**
	 * Envia una respuesta que no cambia el estado de ninguna transaccion: las
	 * respuestas al REGISTER, las de B que se reenvian a A y el 503 a un
	 * segundo INVITE.
	 */
	public void sendResponse(SIPMessage response, String address, int port) throws IOException {
		transportLayer.send(response, address, port);
	}

	// =========================================================================
	// Temporizadores y fin del INVITE
	// =========================================================================

	/**
	 * Transaccion con A: le hemos enviado un error. Completed: el error se
	 * reenvia cada 200 ms hasta que llega el ACK de A, y a 1 s se pasa a Terminated.
	 */
	private void enterCompletedA(SIPMessage error) {
		setStateA(InviteState.COMPLETED);
		retransmitTimerA = new Timer("proxy-retransmit-A", true);
		retransmitTimerA.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (ProxyTransactionLayer.this) {
					if (stateA != InviteState.COMPLETED) {
						return;
					}
					try {
						System.out.println("[Proxy] Sin ACK de A: se reenvia la respuesta de error");
						transportLayer.send(error, addressA, portA);
					} catch (IOException e) {
						System.err.println("Error reenviando a A: " + e.getMessage());
					}
				}
			}
		}, RETRANSMIT_MS, RETRANSMIT_MS);
		startCompletedTimerA();
	}

	/**
	 * Transaccion con B: ACK al error de B. Va al Contact de B, lleva solo la
	 * Via del proxy (somos nosotros quien lo envia) y el CSeq del INVITE.
	 */
	private void sendAckToB() throws IOException {
		ACKMessage ack = new ACKMessage();
		ack.setDestination(inviteToB.getDestination());
		ack.setVias(new ArrayList<String>(inviteToB.getVias().subList(0, 1)));
		ack.setMaxForwards(70);
		ack.setToName(inviteToB.getToName());
		ack.setToUri(inviteToB.getToUri());
		ack.setFromName(inviteToB.getFromName());
		ack.setFromUri(inviteToB.getFromUri());
		ack.setCallId(inviteToB.getCallId());
		ack.setcSeqNumber(inviteToB.getcSeqNumber());
		ack.setcSeqStr("ACK");
		ack.setContentLength(0);
		transportLayer.send(ack, addressB, portB);
	}

	private void startCompletedTimerA() {
		completedTimerA = new Timer("proxy-completed-A", true);
		completedTimerA.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (ProxyTransactionLayer.this) {
					if (stateA != InviteState.COMPLETED) {
						return; // ya habia terminado al llegar el ACK de A
					}
					cancelTimer(retransmitTimerA);
					setStateA(InviteState.TERMINATED);
					tryEndInvite();
				}
			}
		}, COMPLETED_MS);
	}

	private void startCompletedTimerB() {
		completedTimerB = new Timer("proxy-completed-B", true);
		completedTimerB.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (ProxyTransactionLayer.this) {
					if (stateB != InviteState.COMPLETED) {
						return;
					}
					setStateB(InviteState.TERMINATED);
					tryEndInvite();
				}
			}
		}, COMPLETED_MS);
	}

	/**
	 * El INVITE termina cuando han terminado las dos transacciones. La de B
	 * puede no haber empezado nunca, como en el 404.
	 */
	private void tryEndInvite() {
		boolean aDone = stateA == InviteState.TERMINATED;
		boolean bDone = stateB == InviteState.TERMINATED || stateB == InviteState.IDLE;
		if (aDone && bDone) {
			endInvite();
		}
	}

	/** Fin del procesamiento del INVITE: se paran los temporizadores y el proxy queda libre para otra llamada. */
	private void endInvite() {
		cancelTimer(completedTimerA);
		cancelTimer(retransmitTimerA);
		cancelTimer(completedTimerB);
		completedTimerA = null;
		retransmitTimerA = null;
		completedTimerB = null;
		stateA = InviteState.IDLE;
		stateB = InviteState.IDLE;
		inviteCallId = null;
		inviteToB = null;
		System.out.println("[Proxy] Procesamiento del INVITE terminado: proxy libre");
	}

	private void cancelTimer(Timer timer) {
		if (timer != null) {
			timer.cancel();
		}
	}

	/** Cambia el estado de la transaccion con A y lo imprime, como pide el enunciado. */
	private void setStateA(InviteState newState) {
		System.out.println("[Proxy - transaccion con A (llamante)] " + stateA + " -> " + newState);
		stateA = newState;
	}

	/** Cambia el estado de la transaccion con B y lo imprime, como pide el enunciado. */
	private void setStateB(InviteState newState) {
		System.out.println("[Proxy - transaccion con B (llamado)] " + stateB + " -> " + newState);
		stateB = newState;
	}
}
