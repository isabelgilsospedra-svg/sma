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

public class UaUserLayer {
	//private static final int IDLE = 0;
	//private int state = IDLE;

	/** Estado de la llamada visto por el usuario (distinto del estado de la transaccion INVITE). */
	private enum CallState {
		IDLE, // sin llamada
		OUTGOING, // hemos enviado un INVITE y esperamos respuesta
		INCOMING // nos ha llegado un INVITE
	}

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

	/** Llamado: ha llegado una llamada entrante. La respuesta (180, S/N) se implementa en el paso 2. */
	public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
		callState = CallState.INCOMING;
		System.out.println("Llamada entrante de " + inviteMessage.getFromUri());
		runVitextServer();
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
					System.out.println("Llamada entrante en curso");
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
		if (line.toUpperCase().startsWith("INVITE")) {
			commandInvite(line);
		} else {
			System.out.println("Bad command");
		}
	}

	/*private void commandInvite(String line) throws IOException {
		stopVitextServer();
		stopVitextClient();

		System.out.println("Inviting...");

		runVitextClient();

		String callId = UUID.randomUUID().toString();

		SDPMessage sdpMessage = new SDPMessage();
		sdpMessage.setIp(this.myAddress);
		sdpMessage.setPort(this.rtpPort);
		sdpMessage.setOptions(RTPFLOWS);

		InviteMessage inviteMessage = new InviteMessage();
		inviteMessage.setDestination("sip:bob@SMA");
		inviteMessage.setVias(new ArrayList<String>(Arrays.asList(this.myAddress + ":" + this.listenPort)));
		inviteMessage.setMaxForwards(70);
		inviteMessage.setToName("Bob");
		inviteMessage.setToUri("sip:bob@SMA");
		inviteMessage.setFromName("Alice");
		inviteMessage.setFromUri("sip:alice@SMA");
		inviteMessage.setCallId(callId);
		inviteMessage.setcSeqNumber("1");
		inviteMessage.setcSeqStr("INVITE");
		inviteMessage.setContact(myAddress + ":" + listenPort);
		inviteMessage.setContentType("application/sdp");
		inviteMessage.setContentLength(sdpMessage.toStringMessage().getBytes().length);
		inviteMessage.setSdp(sdpMessage);

		transactionLayer.call(inviteMessage);
	}*/

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