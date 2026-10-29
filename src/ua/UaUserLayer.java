package ua;

import java.io.IOException;

import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.*;

import common.FindMyIPv4;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.SDPMessage;

import mensajesSIP.RegisterMessage;

import mensajesSIP.BusyHereMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;

public class UaUserLayer {
	//private static final int IDLE = 0;
	//private int state = IDLE;

	/** Estado de la llamada visto por el usuario (distinto del estado de la transaccion INVITE). */
	private enum CallState {
		IDLE, // sin llamada
		OUTGOING, // hemos enviado un INVITE y esperamos respuesta
		INCOMING, // nos ha llegado un INVITE
		IN_CALL //Llamada aceptada
	}

	/** Tiempo que suena una llamada entrante antes de responder 408 (ms). */
	private static final long ANSWER_TIMEOUT_MS = 10000;
	/** INVITE de la llamada entrante que espera S/N; null si no hay ninguna. */
	private volatile InviteMessage incomingInvite = null;
	/** Temporizador de 10 s para contestar la llamada entrante. */
	private Timer answerTimer = null;

	private volatile CallState callState = CallState.IDLE;

	public static final ArrayList<Integer> RTPFLOWS = new ArrayList<Integer>(
			Arrays.asList(new Integer[] { 96, 97, 98 }));

	private UaTransactionLayer transactionLayer;

	private String myAddress = FindMyIPv4.findMyIPv4Address().getHostAddress();
	private int rtpPort;
	private int listenPort;

	private String sipUser;
	private String userName;
	private String domain;
	private int registerTime;

	/** true desde que el proxy acepta el REGISTER con un 200 OK. */
	private volatile boolean registered = false;
	/** Instante (ms) en que caduca el registro segun el Expires del 200 OK. */
	private volatile long registerExpiresAt = 0;

	/** true si el teclado mostro "Esperando el registro": al registrarse hay que mostrar el menu. */
	private volatile boolean waitingPromptShown = false;

	/**
	 * Contador de CSeq. Cada peticion nueva (REGISTER, INVITE, BYE) usa el
	 * siguiente numero; las retransmisiones y el ACK reutilizan el de su peticion.
	 */
	private int cSeq = 0;

	/**
	 * Destino de un INVITE que espera a que termine un re-registro (decision 2b:
	 * si el registro ha caducado, se registra de nuevo y al recibir el 200 OK se
	 * envia el INVITE). null si no hay ninguno pendiente.
	 */
	private volatile String pendingInviteTarget = null;

	private Process vitextClient = null;
	private Process vitextServer = null;



	public UaUserLayer(String sipUser, int listenPort, String proxyAddress, int proxyPort, boolean debug,
					   int registerTime) throws SocketException, UnknownHostException {
		this.transactionLayer = new UaTransactionLayer(listenPort, proxyAddress, proxyPort, debug, this);
		this.listenPort = listenPort;
		this.rtpPort = listenPort + 1;
		this.sipUser = sipUser;
		this.userName = sipUser.substring(0, sipUser.indexOf('@'));
		this.domain = sipUser.substring(sipUser.indexOf('@') + 1);
		this.registerTime = registerTime;
	}

	private synchronized String nextCSeq() {
		cSeq++;
		return String.valueOf(cSeq);
	}
	
	/** Llamante: el destino esta sonando (180 Ringing). */
	public void onRinging() {
		System.out.println("El destino esta sonando...");
	}

	/** Llamante: el destino ha descolgado (200 OK al INVITE). El ACK se envia en el paso 4. */
	public void onCallAccepted(OKMessage ok) {
		callState = CallState.IN_CALL;
		System.out.println("Llamada establecida con " + ok.getToUri());
		prompt();
	}

	/** Llamante: el INVITE ha recibido una respuesta de error (404, 408, 486 o 503). */
	public void onCallRejected(int code) {
		String reason;
		switch (code) {
			case 404:
				reason = "el destino no esta registrado";
				break;
			case 408:
				reason = "el destino no contesta";
				break;
			case 486:
				reason = "el destino esta ocupado o ha rechazado la llamada";
				break;
			case 503:
				reason = "el proxy esta ocupado con otra llamada";
				break;
			default:
				reason = "error";
		}
		System.out.println("Llamada rechazada (" + code + "): " + reason);
		callState = CallState.IDLE;
		stopVitextClient();
		prompt();
	}

	public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
		incomingInvite = inviteMessage;
		callState = CallState.INCOMING;
		// Diagrama del llamado: "180 from TU if user B": el usuario hace sonar el terminal.
		transactionLayer.sendRinging(buildRinging(inviteMessage));
		startAnswerTimer();
		prompt(); // muestra "Llamada entrante de ...: S para aceptar, N para rechazar"
	}

	/** Arranca los 10 s que tiene el usuario para contestar; al vencer se responde 408. */
	private void startAnswerTimer() {
		answerTimer = new Timer("answer-timeout", true);
		answerTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				onAnswerTimeout();
			}
		}, ANSWER_TIMEOUT_MS);
	}

	private void cancelAnswerTimer() {
		if (answerTimer != null) {
			answerTimer.cancel();
			answerTimer = null;
		}
	}

	/** Han pasado 10 s sin respuesta del usuario: 408 Request Timeout. */
	private synchronized void onAnswerTimeout() {
		if (callState != CallState.INCOMING) {
			return; // el usuario contesto justo a tiempo
		}
		System.out.println("Sin respuesta en " + ANSWER_TIMEOUT_MS / 1000 + " s: se responde 408 Request Timeout");
		try {
			transactionLayer.sendError(buildRequestTimeout(incomingInvite));
		} catch (IOException e) {
			System.err.println("Error enviando 408: " + e.getMessage());
		}
		endIncomingCall(CallState.IDLE);
		prompt(); // aqui no hay comando de teclado en curso: hay que volver a mostrar el menu
	}

	/** El usuario descuelga (S): 200 OK con SDP. */
	private synchronized void acceptCall() throws IOException {
		if (callState != CallState.INCOMING) {
			return;
		}
		cancelAnswerTimer();
		System.out.println("Llamada aceptada");
		transactionLayer.sendOk(buildOk(incomingInvite));
		runVitextServer();
		endIncomingCall(CallState.IN_CALL);
	}

	/** El usuario rechaza (N): 486 Busy Here. */
	private synchronized void rejectCall() throws IOException {
		if (callState != CallState.INCOMING) {
			return;
		}
		cancelAnswerTimer();
		System.out.println("Llamada rechazada");
		transactionLayer.sendError(buildBusyHere(incomingInvite));
		endIncomingCall(CallState.IDLE);
	}

	private void endIncomingCall(CallState newState) {
		cancelAnswerTimer();
		incomingInvite = null;
		callState = newState;
	}

	/*
	 * Todas las respuestas copian del INVITE las Vias (para volver por el mismo
	 * camino), To, From, Call-ID y CSeq (para que el llamante sepa a que peticion
	 * responden).
	 */

	private RingingMessage buildRinging(InviteMessage invite) {
		RingingMessage ringing = new RingingMessage();
		ringing.setVias(new ArrayList<String>(invite.getVias()));
		ringing.setToName(invite.getToName());
		ringing.setToUri(invite.getToUri());
		ringing.setFromName(invite.getFromName());
		ringing.setFromUri(invite.getFromUri());
		ringing.setCallId(invite.getCallId());
		ringing.setcSeqNumber(invite.getcSeqNumber());
		ringing.setcSeqStr(invite.getcSeqStr());
		ringing.setContact(myAddress + ":" + listenPort);
		ringing.setContentLength(0);
		return ringing;
	}

	/** 200 OK al INVITE: lleva SDP con nuestra IP y puerto RTP, como el INVITE. */
	private OKMessage buildOk(InviteMessage invite) {
		SDPMessage sdp = buildSdp();
		OKMessage ok = new OKMessage();
		ok.setVias(new ArrayList<String>(invite.getVias()));
		ok.setToName(invite.getToName());
		ok.setToUri(invite.getToUri());
		ok.setFromName(invite.getFromName());
		ok.setFromUri(invite.getFromUri());
		ok.setCallId(invite.getCallId());
		ok.setcSeqNumber(invite.getcSeqNumber());
		ok.setcSeqStr(invite.getcSeqStr());
		ok.setContact(myAddress + ":" + listenPort);
		ok.setContentLength(sdp.toStringMessage().getBytes().length);
		ok.setSdp(sdp);
		return ok;
	}

	private BusyHereMessage buildBusyHere(InviteMessage invite) {
		BusyHereMessage busy = new BusyHereMessage();
		busy.setVias(new ArrayList<String>(invite.getVias()));
		busy.setToName(invite.getToName());
		busy.setToUri(invite.getToUri());
		busy.setFromName(invite.getFromName());
		busy.setFromUri(invite.getFromUri());
		busy.setCallId(invite.getCallId());
		busy.setcSeqNumber(invite.getcSeqNumber());
		busy.setcSeqStr(invite.getcSeqStr());
		busy.setContentLength(0);
		return busy;
	}

	private RequestTimeoutMessage buildRequestTimeout(InviteMessage invite) {
		RequestTimeoutMessage timeout = new RequestTimeoutMessage();
		timeout.setVias(new ArrayList<String>(invite.getVias()));
		timeout.setToName(invite.getToName());
		timeout.setToUri(invite.getToUri());
		timeout.setFromName(invite.getFromName());
		timeout.setFromUri(invite.getFromUri());
		timeout.setCallId(invite.getCallId());
		timeout.setcSeqNumber(invite.getcSeqNumber());
		timeout.setcSeqStr(invite.getcSeqStr());
		timeout.setContentLength(0);
		return timeout;
	}

	/** SDP con nuestra IP y puerto RTP (lo usan el INVITE y el 200 OK). */
	private SDPMessage buildSdp() {
		SDPMessage sdp = new SDPMessage();
		sdp.setIp(this.myAddress);
		sdp.setPort(this.rtpPort);
		sdp.setOptions(RTPFLOWS);
		return sdp;
	}

	public void onRegisterOk(OKMessage okMessage) throws IOException {
		boolean firstRegistration = !registered;
		int expires = registerTime;
		if (okMessage.getExpires() != null) {
			expires = Integer.parseInt(okMessage.getExpires());
		}
		registerExpiresAt = System.currentTimeMillis() + expires * 1000L;
		registered = true;
		System.out.println("Registrado en el proxy como " + sipUser + " durante " + expires + " s");

		if (pendingInviteTarget != null) {
			// Re-registro terminado: ya se puede enviar el INVITE que esperaba (decision 2b).
			String target = pendingInviteTarget;
			pendingInviteTarget = null;
			sendInvite(target);
		} else if (firstRegistration && waitingPromptShown) {
			prompt(); // ya se pueden usar los comandos: se muestra el menu
		}
	}


	public void onRegisterNotFound(NotFoundMessage notFoundMessage) {
		registered = false;
		System.out.println("Registro rechazado (404): " + sipUser + " no es un usuario permitido");
		// Enunciado: "Si recibe un 404 la aplicacion de agente de usuario finalizara."
		System.out.println("Finalizando el UA");
		System.exit(1);
	}

	public void startListeningNetwork() {
		transactionLayer.startListeningNetwork();
	}

	public void register() throws IOException {

		RegisterMessage registerMessage = new RegisterMessage();

		registerMessage.setDestination("sip:" + domain);
		registerMessage.setVias(new ArrayList<String>(
				Arrays.asList(myAddress + ":" + listenPort)));
		registerMessage.setMaxForwards(70);

		registerMessage.setToUri("sip:" + sipUser);
		registerMessage.setFromUri("sip:" + sipUser);

		registerMessage.setCallId(UUID.randomUUID().toString());
		registerMessage.setcSeqNumber(nextCSeq());
		registerMessage.setcSeqStr("REGISTER");

		registerMessage.setContact(myAddress + ":" + listenPort);

		registerMessage.setExpires(String.valueOf(registerTime));
		registerMessage.setContentLength(0);

		transactionLayer.register(registerMessage);
	}

	public void startListeningKeyboard() {
		try (Scanner scanner = new Scanner(System.in)) {
			while (true) {
				prompt();
				String line = scanner.nextLine().trim();
				if (!line.isEmpty()) {
					command(line);
				}
			}
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}

	private void prompt() {
		System.out.println("");
		if (!registered) {
			System.out.println("Esperando el registro en el proxy: los comandos se activan al recibir el 200 OK");
			waitingPromptShown = true;
		} else {
			switch (callState) {
				case IDLE:
					System.out.println("INVITE usuario   (llamar, p. ej. INVITE bob)");
					break;
				case OUTGOING:
					System.out.println("Llamada saliente en curso: esperando respuesta");
					break;
				case INCOMING:
					System.out.println("Llamada entrante de " + incomingInvite.getFromUri() + ": S para aceptar, N para rechazar");
					break;
				case IN_CALL:
					System.out.println("En llamada");
					break;
			}
		}
		System.out.print("> ");
	}

	private void command(String line) throws IOException {
		if (!registered) {
			System.out.println("Comando ignorado: el UA no esta registrado en el proxy");
			return;
		}
		if (callState == CallState.INCOMING) {
			// Con una llamada entrante sonando, el teclado solo acepta S o N.
			if (line.equalsIgnoreCase("S")) {
				acceptCall();
			} else if (line.equalsIgnoreCase("N")) {
				rejectCall();
			} else {
				System.out.println("Responde S (aceptar) o N (rechazar)");
			}
		} else if (line.toUpperCase().startsWith("INVITE")) {
			commandInvite(line);
		} else {
			System.out.println("Bad command");
		}
	}

	/** INVITE usuarioSIPDestino: el destino va sin dominio; se usa el del llamante. */
	private void commandInvite(String line) throws IOException {
		String[] parts = line.split("\\s+");
		if (parts.length != 2 || !parts[1].matches("\\w+")) {
			System.out.println("Uso: INVITE usuario   (sin dominio, p. ej. INVITE bob)");
			return;
		}
		if (callState != CallState.IDLE) {
			System.out.println("Solo se soporta una llamada simultaneamente");
			return;
		}
		String target = parts[1];
		callState = CallState.OUTGOING;

		if (System.currentTimeMillis() > registerExpiresAt) {
			// Decision 2b: el registro ha caducado; se re-registra y el INVITE sale al llegar el 200 OK.
			System.out.println("El registro ha caducado: se registra de nuevo antes de llamar");
			pendingInviteTarget = target;
			register();
			return;
		}
		sendInvite(target);
	}
	private void sendInvite(String target) throws IOException {
		stopVitextServer();
		stopVitextClient();

		System.out.println("Llamando a " + target + "@" + domain + "...");

		runVitextClient();

		String callId = UUID.randomUUID().toString();
		String targetUri = "sip:" + target + "@" + domain;

		SDPMessage sdpMessage = new SDPMessage();
		sdpMessage.setIp(this.myAddress);
		sdpMessage.setPort(this.rtpPort);
		sdpMessage.setOptions(RTPFLOWS);

		InviteMessage inviteMessage = new InviteMessage();
		inviteMessage.setDestination(targetUri);
		inviteMessage.setVias(new ArrayList<String>(Arrays.asList(this.myAddress + ":" + this.listenPort)));
		inviteMessage.setMaxForwards(70);
		inviteMessage.setToName(target);
		inviteMessage.setToUri(targetUri);
		inviteMessage.setFromName(userName);
		inviteMessage.setFromUri("sip:" + sipUser);
		inviteMessage.setCallId(callId);
		inviteMessage.setcSeqNumber(nextCSeq());
		inviteMessage.setcSeqStr("INVITE");
		inviteMessage.setContact(myAddress + ":" + listenPort);
		inviteMessage.setContentType("application/sdp");
		inviteMessage.setContentLength(sdpMessage.toStringMessage().getBytes().length);
		inviteMessage.setSdp(sdpMessage);

		transactionLayer.call(inviteMessage);
	}


	private void runVitextClient() {
		try {
			vitextClient = Runtime.getRuntime().exec("xterm -e vitext/vitextclient -p 5000 239.1.2.3");
		} catch (IOException e) {
			System.out.println("Aviso: no se pudo arrancar vitextclient (" + e.getMessage() + "); se sigue sin video");
		}
	}

	private void stopVitextClient() {
		if (vitextClient != null) {
			vitextClient.destroy();
		}
	}

	private void runVitextServer() {
		try {
			vitextServer = Runtime.getRuntime()
					.exec("xterm -iconic -e vitext/vitextserver -r 10 -p 5000 vitext/1.vtx 239.1.2.3");
		} catch (IOException e) {
			System.out.println("Aviso: no se pudo arrancar vitextserver (" + e.getMessage() + "); se sigue sin video");
		}
	}

	private void stopVitextServer() {
		if (vitextServer != null) {
			vitextServer.destroy();
		}
	}

}