package proxy;

import java.io.IOException;

import java.net.SocketException;

import mensajesSIP.InviteMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SIPMessage;

import java.util.Timer;
import java.util.TimerTask;
import common.InviteState;

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
		} else {
			System.err.println("Unexpected message, throwing away");
		}
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
		setStateA(InviteState.PROCEEDING);
		transportLayer.send(trying, addressA, portA);
	}

	/**
	 * Transaccion con A: el INVITE se rechaza (404). INVITE -> Proceeding, se
	 * envia el error y se pasa a Completed. A 1 s, Terminated. La espera del ACK
	 * y el reenvio del error cada 200 ms se anaden en el paso 4.
	 */
	public synchronized void rejectInvite(String callId, SIPMessage error, String addressA, int portA)
			throws IOException {
		inviteCallId = callId;
		setStateA(InviteState.PROCEEDING);
		transportLayer.send(error, addressA, portA);
		setStateA(InviteState.COMPLETED);
		startCompletedTimerA();
	}

	/** Transaccion con B: el proxy reenvia el INVITE al llamado y pasa a Calling. */
	public synchronized void forwardInvite(InviteMessage inviteMessage, String addressB, int portB)
			throws IOException {
		setStateB(InviteState.CALLING);
		transportLayer.send(inviteMessage, addressB, portB);
	}

	private void startCompletedTimerA() {
		completedTimerA = new Timer("proxy-completed-A", true);
		completedTimerA.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (ProxyTransactionLayer.this) {
					setStateA(InviteState.TERMINATED);
					endInvite();
				}
			}
		}, COMPLETED_MS);
	}

	/** Fin del procesamiento del INVITE: el proxy queda libre para otra llamada. */
	private void endInvite() {
		if (completedTimerA != null) {
			completedTimerA.cancel();
			completedTimerA = null;
		}
		stateA = InviteState.IDLE;
		stateB = InviteState.IDLE;
		inviteCallId = null;
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