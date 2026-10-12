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

public class UaTransactionLayer {
	private static final int IDLE = 0;
	private int state = IDLE;

	/** Cada cuanto se reenvia el REGISTER si el proxy no contesta (ms). */
	private static final long REGISTER_RETRY_MS = 2000;

	private UaUserLayer userLayer;
	private UaTransportLayer transportLayer;

	/** Temporizador de reenvio del REGISTER; null si no hay ninguno pendiente. */
	private Timer registerTimer = null;

	public UaTransactionLayer(int listenPort, String proxyAddress, int proxyPort, boolean debug,
							  UaUserLayer userLayer) throws SocketException {
		this.userLayer = userLayer;
		this.transportLayer = new UaTransportLayer(listenPort, proxyAddress, proxyPort, debug, this);
	}

	public void onMessageReceived(SIPMessage sipMessage) throws IOException {
		if (sipMessage instanceof OKMessage
				&& isRegister(((OKMessage) sipMessage).getcSeqStr())) {
			stopRegisterTimer();
			userLayer.onRegisterOk((OKMessage) sipMessage);
		} else if (sipMessage instanceof NotFoundMessage
				&& isRegister(((NotFoundMessage) sipMessage).getcSeqStr())) {
			stopRegisterTimer();
			userLayer.onRegisterNotFound((NotFoundMessage) sipMessage);
		} else if (sipMessage instanceof InviteMessage) {
			InviteMessage inviteMessage = (InviteMessage) sipMessage;
			switch (state) {
				case IDLE:
					userLayer.onInviteReceived(inviteMessage);
					break;
				default:
					System.err.println("Unexpected message, throwing away");
					break;
			}
		} else {
			System.err.println("Unexpected message, throwing away");
		}
	}

	/** Una respuesta es del REGISTER si el metodo de su CSeq es REGISTER. */
	private boolean isRegister(String cSeqMethod) {
		return "REGISTER".equals(cSeqMethod);
	}

	public void startListeningNetwork() {
		transportLayer.startListening();
	}

	public void call(InviteMessage inviteMessage) throws IOException {
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