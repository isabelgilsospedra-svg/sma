package proxy;

import java.io.IOException;

import java.net.SocketException;

import mensajesSIP.InviteMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SIPMessage;

import java.util.Timer;
import java.util.TimerTask;
import common.InviteState;

import java.util.ArrayList;

import mensajesSIP.BusyHereMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;

import mensajesSIP.ACKMessage;

public class ProxyTransactionLayer {
	//private static final int IDLE = 0;
	//private int state = IDLE;

	/** Tiempo en Completed antes de pasar a Terminated (ms). */
	private static final long COMPLETED_MS = 1000;

	/** Transaccion con A (llamante): el proxy recibe su INVITE y le responde. */
	private InviteState stateA = InviteState.IDLE;
	/** Transaccion con B (llamado): el proxy le reenvia el INVITE y recibe sus respuestas. */
	private InviteState stateB = InviteState.IDLE;
	/** Call-ID del INVITE que se esta procesando. */
	private String inviteCallId = null;

	private Timer completedTimerA = null;

	/** Cada cuanto se reenvia a A el error en Completed mientras no llega su ACK (ms). */
	private static final long RETRANSMIT_MS = 200;
	/** Transaccion con A en Completed: reenvio del error cada 200 ms. */
	private Timer retransmitTimerA = null;
	/** Transaccion con B en Completed: temporizador de 1 s. */
	private Timer completedTimerB = null;
	/** Donde esta A (primera Via de su INVITE) y donde esta B (su Contact registrado). */
	private String addressA = null;
	private int portA = 0;
	private String addressB = null;
	private int portB = 0;
	/** INVITE tal como se reenvio a B: de el salen los campos del ACK que el proxy envia a B. */
	private InviteMessage inviteToB = null;

	private ProxyUserLayer userLayer;
	private ProxyTransportLayer transportLayer;

	public ProxyTransactionLayer(int listenPort, boolean debug, ProxyUserLayer userLayer) throws SocketException {
		this.userLayer = userLayer;
		this.transportLayer = new ProxyTransportLayer(listenPort, debug, this);
	}

	public synchronized void onMessageReceived(SIPMessage sipMessage) throws IOException {
		if (sipMessage instanceof RegisterMessage) {
			// El REGISTER no depende del estado de la llamada: se atiende siempre.
			userLayer.onRegisterReceived((RegisterMessage) sipMessage);
		} else if (sipMessage instanceof InviteMessage) {
			InviteMessage inviteMessage = (InviteMessage) sipMessage;
			if (stateA == InviteState.IDLE) {
				userLayer.onInviteReceived(inviteMessage);
			} else {
				// Ya hay un INVITE en proceso: se tratara en el paso 6 (503 Service Unavailable).
				System.err.println("Unexpected message, throwing away");
			}
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
	 * Respuesta de B al INVITE reenviado. Cambia el estado de las dos
	 * transacciones y la respuesta se reenvia a A (la capa de usuario quita la
	 * Via del proxy y la envia a la Via siguiente).
	 *  - 180: con B, Calling -> Proceeding; con A sigue en Proceeding.
	 *  - 200: con B y con A, -> Terminated. Sin loose routing el proxy sale de la llamada.
	 *  - 486/408: con B, Completed + ACK a B (salto a salto); a 1 s, Terminated.
	 *    Con A, Completed: reenvio del error cada 200 ms hasta el ACK de A; a 1 s, Terminated.
	 *  - Error repetido de B con B en Completed: se reenvia el ACK a B.
	 */
	private void onResponseFromB(SIPMessage response, String callId, ArrayList<String> vias, int code)
			throws IOException {
		if (code >= 300 && stateB == InviteState.COMPLETED && callId.equals(inviteCallId)) {
			// Diagrama del llamante (proxy con B): en Completed, el error repetido -> se reenvia el ACK.
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
			// Con B: el proxy es el llamante -> Completed y ACK a B (salto a salto).
			setStateB(InviteState.COMPLETED);
			sendAckToB();
			startCompletedTimerB();
			// Con A: el proxy es el llamado -> reenvia el error y espera el ACK de A.
			userLayer.forwardResponseToA(response, vias);
			enterCompletedA(response);
		}
	}

	/** Envia a A una respuesta que llego de B (ya sin la Via del proxy). */
	public void sendToA(SIPMessage response, String addressA, int portA) throws IOException {
		transportLayer.send(response, addressA, portA);
	}

	public void sendResponse(SIPMessage response, String address, int port) throws IOException {
		transportLayer.send(response, address, port);
	}

	/*public void echoInvite(InviteMessage inviteMessage, String address, int port) throws IOException {
		transportLayer.send(inviteMessage, address, port);
	}*/

	/** Transaccion con A: el INVITE se acepta. INVITE -> Proceeding y se envia el 100 Trying a A. */
	public synchronized void acceptInvite(String callId, SIPMessage trying, String addressA, int portA)
			throws IOException {
		inviteCallId = callId;
		this.addressA = addressA;
		this.portA = portA;
		setStateA(InviteState.PROCEEDING);
		transportLayer.send(trying, addressA, portA);
	}

	/**
	 * Transaccion con A: el INVITE se rechaza (404). INVITE -> Proceeding, se
	 * envia el error y se pasa a Completed (reenvio cada 200 ms hasta el ACK de A;
	 * a 1 s, Terminated).
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
	 * Transaccion con A: el proxy ha enviado un error a A. Completed: se reenvia
	 * el error cada 200 ms hasta que llegue el ACK de A; a 1 s, Terminated.
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

	/** Llega un ACK. Con A en Completed es el ACK a nuestro error: Completed -> Terminated. */
	private void onAckFromA(ACKMessage ack) {
		if (stateA == InviteState.COMPLETED && ack.getCallId().equals(inviteCallId)) {
			cancelTimer(retransmitTimerA);
			setStateA(InviteState.TERMINATED);
			tryEndInvite();
		} else {
			// Sin loose routing el ACK del 200 va de A a B directamente; aqui no deberia llegar.
			System.err.println("Unexpected message, throwing away");
		}
	}

	/** Transaccion con B: ACK al error de B. Va al Contact de B con la Via del proxy y el CSeq del INVITE. */
	private void sendAckToB() throws IOException {
		ACKMessage ack = new ACKMessage();
		ack.setDestination(inviteToB.getDestination());
		ack.setVias(new ArrayList<String>(inviteToB.getVias().subList(0, 1))); // solo la Via del proxy
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
						return; // ya termino al llegar el ACK de A
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

	/** El INVITE termina cuando las dos transacciones han terminado (B puede no haber empezado: 404). */
	private void tryEndInvite() {
		boolean aDone = stateA == InviteState.TERMINATED;
		boolean bDone = stateB == InviteState.TERMINATED || stateB == InviteState.IDLE;
		if (aDone && bDone) {
			endInvite();
		}
	}

	private void cancelTimer(Timer timer) {
		if (timer != null) {
			timer.cancel();
		}
	}

	/** Fin del procesamiento del INVITE: el proxy queda libre para otra llamada. */
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

	private void setStateA(InviteState newState) {
		System.out.println("[Proxy - transaccion con A (llamante)] " + stateA + " -> " + newState);
		stateA = newState;
	}

	private void setStateB(InviteState newState) {
		System.out.println("[Proxy - transaccion con B (llamado)] " + stateB + " -> " + newState);
		stateB = newState;
	}

	public void startListening() {
		transportLayer.startListening();
	}

}