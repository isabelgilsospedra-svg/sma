package ua;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import common.FindMyIPv4;
import mensajesSIP.ACKMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.SDPMessage;

/**
 * Capa de usuario del UA (el "TU" de los diagramas).
 *
 * Es la parte que habla con la persona: lee los comandos del teclado, muestra
 * lo que pasa y decide que responder. Tambien construye los mensajes que la
 * transaccion no genera por si sola: el REGISTER, el INVITE, las respuestas al
 * INVITE, el ACK del 200 y el BYE con su 200 OK.
 *
 * Lleva el estado de la llamada tal como lo ve el usuario (CallState), que no
 * es lo mismo que el estado de la transaccion INVITE: la transaccion termina
 * con el 200 OK, pero la llamada sigue hasta que alguien cuelga.
 */
public class UaUserLayer {

	/** Estado de la llamada tal como lo ve el usuario. */
	private enum CallState {
		IDLE, // sin llamada
		OUTGOING, // hemos enviado un INVITE y esperamos respuesta
		INCOMING, // nos ha llegado un INVITE y esta sonando
		IN_CALL, // llamada establecida
		HANGING_UP // hemos enviado el BYE y esperamos su 200 OK
	}

	/** Tiempo que suena una llamada entrante antes de responder 408 (ms). */
	private static final long ANSWER_TIMEOUT_MS = 10000;

	/** Formatos RTP que se anuncian en el SDP. */
	public static final ArrayList<Integer> RTPFLOWS = new ArrayList<Integer>(Arrays.asList(96, 97, 98));

	private final UaTransactionLayer transactionLayer;

	// ---- Quienes somos ----

	private final String sipUser; // usuario@dominio, tal como llega por argumento
	private final String userName; // la parte de antes de la @
	private final String domain; // la parte de despues de la @
	private final String myAddress; // nuestra IP, para la Via, el Contact y el SDP
	private final int listenPort; // puerto SIP
	private final int rtpPort; // puerto RTP: el siguiente al SIP
	private final int registerTime; // Expires que pedimos en el REGISTER (s)

	// ---- Registro ----

	/** true desde que el proxy acepta el primer REGISTER con un 200 OK. */
	private volatile boolean registered = false;
	/** Instante (ms) en que caduca el registro, segun el Expires del 200 OK. */
	private volatile long registerExpiresAt = 0;
	/** true si ya se mostro "Esperando el registro": al llegar el 200 OK hay que mostrar el menu. */
	private volatile boolean waitingPromptShown = false;
	/**
	 * Usuario al que queremos llamar mientras esperamos un re-registro. Si al
	 * hacer INVITE el registro habia caducado, primero se registra de nuevo y el
	 * INVITE sale al llegar el 200 OK. null si no hay ninguno.
	 */
	private volatile String pendingInviteTarget = null;

	// ---- Llamada ----

	private volatile CallState callState = CallState.IDLE;
	/** INVITE de la llamada entrante que espera S o N; null si no hay ninguna. */
	private volatile InviteMessage incomingInvite = null;
	/** Temporizador de 10 s para contestar la llamada entrante. */
	private Timer answerTimer = null;
	/** Call-ID de la llamada establecida; null si no hay ninguna. */
	private volatile String currentCallId = null;
	/**
	 * true cuando la llamada esta activa: para el llamante, al recibir el 200 OK;
	 * para el llamado, al recibir el ACK. Solo entonces se puede colgar con BYE.
	 */
	private volatile boolean callConfirmed = false;

	// Datos de la llamada establecida que hacen falta para el BYE: nosotros
	// (From) y el otro extremo (To, destination y a donde enviarlo).
	private volatile String remoteContact = null; // "ip:puerto" del otro UA
	private volatile String route = null;
	private String localUri = null;
	private String localName = null;
	private String remoteUri = null;
	private String remoteName = null;

	/**
	 * Contador de CSeq. Cada peticion nueva (REGISTER, INVITE, BYE) usa el
	 * siguiente numero; el ACK reutiliza el de su INVITE.
	 *
	 * Cada UA numera solo sus propias peticiones: si cuelga el llamado, su BYE
	 * lleva el siguiente numero de su contador aunque sea menor que el del
	 * INVITE. El CSeq es numero mas metodo, asi que "2 INVITE" y "2 BYE" no se
	 * confunden.
	 */
	private int cSeq = 0;

	// ---- vitext ----
	// vitext es una integracion opcional con la parte RTSP. Queda desactivado
	// (comentado) hasta que toque esa integracion.

	// private Process vitextClient = null;
	// private Process vitextServer = null;

	public UaUserLayer(String sipUser, int listenPort, String proxyAddress, int proxyPort, boolean debug,
					   int registerTime) throws SocketException, UnknownHostException {
		this.sipUser = sipUser;
		this.userName = sipUser.substring(0, sipUser.indexOf('@'));
		this.domain = sipUser.substring(sipUser.indexOf('@') + 1);
		this.myAddress = FindMyIPv4.findMyIPv4Address().getHostAddress();
		this.listenPort = listenPort;
		this.rtpPort = listenPort + 1;
		this.registerTime = registerTime;
		// La capa de transaccion se crea la ultima, con todo lo nuestro ya inicializado.
		this.transactionLayer = new UaTransactionLayer(listenPort, proxyAddress, proxyPort, debug, this);
	}

	public void startListeningNetwork() {
		transactionLayer.startListeningNetwork();
	}

	// =========================================================================
	// Teclado
	// =========================================================================

	/**
	 * Bucle del hilo principal: muestra el menu, lee una linea y la ejecuta.
	 * Si se cierra la entrada de teclado (Ctrl+D en Linux, Ctrl+Z en Windows),
	 * el UA termina limpiamente, sin excepcion.
	 */
	public void startListeningKeyboard() {
		try (Scanner scanner = new Scanner(System.in)) {
			while (true) {
				prompt();
				if (!scanner.hasNextLine()) {
					System.out.println();
					System.out.println("Entrada de teclado cerrada: finalizando el UA");
					System.exit(0);
				}
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

	/** Muestra que se puede hacer en este momento, segun el registro y el estado de la llamada. */
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
					System.out.println("En llamada: BYE para colgar");
					break;
				case HANGING_UP:
					System.out.println("Colgando: esperando el 200 OK del BYE");
					break;
			}
		}
		System.out.print("> ");
	}

	/**
	 * Ejecuta un comando. Hasta que el UA esta registrado no se acepta ninguno.
	 * Mientras suena una llamada entrante, solo valen S y N.
	 */
	private void command(String line) throws IOException {
		if (!registered) {
			System.out.println("Comando ignorado: el UA no esta registrado en el proxy");
			return;
		}
		if (callState == CallState.INCOMING) {
			if (line.equalsIgnoreCase("S")) {
				acceptCall();
			} else if (line.equalsIgnoreCase("N")) {
				rejectCall();
			} else {
				System.out.println("Responde S (aceptar) o N (rechazar)");
			}
		} else if (line.toUpperCase().startsWith("INVITE")) {
			commandInvite(line);
		} else if (line.equalsIgnoreCase("BYE")) {
			commandBye();
		} else {
			System.out.println("Bad command");
		}
	}

	// =========================================================================
	// Registro
	// =========================================================================

	/**
	 * Construye el REGISTER y se lo pasa a la transaccion, que lo reenvia cada
	 * 2 s hasta que el proxy conteste.
	 */
	public void register() {
		RegisterMessage registerMessage = new RegisterMessage();
		registerMessage.setDestination("sip:" + domain);
		registerMessage.setVias(new ArrayList<String>(Arrays.asList(myAddress + ":" + listenPort)));
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

	/**
	 * El proxy acepta el REGISTER. Se apunta cuando caduca el registro y, si
	 * habia un INVITE esperando a este re-registro, sale ahora.
	 */
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
			String target = pendingInviteTarget;
			pendingInviteTarget = null;
			sendInvite(target);
		} else if (firstRegistration && waitingPromptShown) {
			prompt(); // los comandos ya estan activos: se muestra el menu
		}
	}

	/** El proxy no nos conoce (404). El enunciado dice que en ese caso el UA termina. */
	public void onRegisterNotFound(NotFoundMessage notFoundMessage) {
		registered = false;
		System.out.println("Registro rechazado (404): " + sipUser + " no es un usuario permitido");
		System.out.println("Finalizando el UA");
		System.exit(1);
	}

	// =========================================================================
	// Llamada saliente
	// =========================================================================

	/**
	 * Comando "INVITE usuario". El destino se escribe sin dominio y se usa el
	 * nuestro, porque todas las llamadas son dentro del mismo dominio. Si el
	 * registro ha caducado, primero se registra de nuevo y el INVITE sale cuando
	 * llega el 200 OK de ese REGISTER.
	 */
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
			System.out.println("El registro ha caducado: se registra de nuevo antes de llamar");
			pendingInviteTarget = target;
			register();
			return;
		}
		sendInvite(target);
	}

	/** Construye el INVITE, con un Call-ID nuevo y nuestro SDP, y empieza la transaccion como llamante. */
	private void sendInvite(String target) throws IOException {
		// stopVitextServer(); // vitext desactivado
		// stopVitextClient(); // vitext desactivado
		System.out.println("Llamando a " + target + "@" + domain + "...");
		// runVitextClient(); // vitext desactivado

		String targetUri = "sip:" + target + "@" + domain;
		SDPMessage sdp = buildSdp();

		InviteMessage inviteMessage = new InviteMessage();
		inviteMessage.setDestination(targetUri);
		inviteMessage.setVias(new ArrayList<String>(Arrays.asList(myAddress + ":" + listenPort)));
		inviteMessage.setMaxForwards(70);
		inviteMessage.setToName(target);
		inviteMessage.setToUri(targetUri);
		inviteMessage.setFromName(userName);
		inviteMessage.setFromUri("sip:" + sipUser);
		inviteMessage.setCallId(UUID.randomUUID().toString());
		inviteMessage.setcSeqNumber(nextCSeq());
		inviteMessage.setcSeqStr("INVITE");
		inviteMessage.setContact(myAddress + ":" + listenPort);
		inviteMessage.setContentType("application/sdp");
		inviteMessage.setContentLength(sdp.toStringMessage().getBytes().length);
		inviteMessage.setSdp(sdp);

		transactionLayer.call(inviteMessage);
	}

	/** Llamante: el destino esta sonando (ha llegado un 180 Ringing). */
	public void onRinging() {
		System.out.println("El destino esta sonando...");
	}

	/**
	 * Llamante: el destino ha descolgado (200 OK al INVITE). Se guardan los
	 * datos de la llamada, que hacen falta para colgar, y se envia el ACK del
	 * 200. Para el llamante la llamada ya esta activa.
	 */
	public void onCallAccepted(OKMessage ok) throws IOException {
		callState = CallState.IN_CALL;
		currentCallId = ok.getCallId();
		// Somos el llamante: nosotros somos el From del INVITE y el otro extremo, el To.
		localUri = ok.getFromUri();
		localName = ok.getFromName();
		remoteUri = ok.getToUri();
		remoteName = ok.getToName();
		remoteContact = ok.getContact();
		// Con loose routing, el 200 OK trae el Record-Route que anadio el proxy.
		route = ok.getRecordRoute();
		callConfirmed = true;
		System.out.println("Llamada establecida con " + ok.getToUri());
		// Sin loose routing, el ACK del 200 va directo al llamado, a su Contact.
		// Con loose routing va al proxy, que lo reenvia.
		String[] nextHop = nextHop();
		// PRUEBA DE PERDIDA: si se comenta la linea siguiente, el ACK del 200 no llega.
		// Que se ve: el llamado nunca da la llamada por confirmada y no puede colgar
		// ("No hay ninguna llamada activa"); el 200 no se reenvia porque su transaccion
		// ya termino. Nosotros si podemos colgar, y el llamado acepta nuestro BYE.
		transactionLayer.sendDirect(buildAckForOk(ok), nextHop[0], Integer.parseInt(nextHop[1]));
		prompt();
	}

	/**
	 * A donde se envian el ACK del 200 y el BYE, como {ip, puerto}. Sin loose
	 * routing, al Contact del otro UA (extremo a extremo). Con loose routing, al
	 * proxy, cuya direccion se saca de la ruta: de "<sip:ip:puerto;lr>" se quita
	 * "<sip:" por delante y ";lr>" por detras.
	 */
	private String[] nextHop() {
		if (route == null) {
			return remoteContact.split(":");
		}
		String hostPort = route.substring("<sip:".length(), route.indexOf(';'));
		return hostPort.split(":");
	}

	/**
	 * ACK del 200. No lo genera la transaccion sino el usuario: repite Call-ID,
	 * To, From y el numero de CSeq del INVITE (no se incrementa), con el metodo
	 * ACK. Como destination lleva el To del 200, que es la URI del llamado. Con
	 * loose routing lleva ademas la cabecera Route con la direccion del proxy.
	 */
	private ACKMessage buildAckForOk(OKMessage ok) {
		ACKMessage ack = new ACKMessage();
		ack.setDestination(ok.getToUri());
		ack.setVias(new ArrayList<String>(Arrays.asList(myAddress + ":" + listenPort)));
		ack.setMaxForwards(70);
		ack.setToName(ok.getToName());
		ack.setToUri(ok.getToUri());
		ack.setFromName(ok.getFromName());
		ack.setFromUri(ok.getFromUri());
		ack.setCallId(ok.getCallId());
		ack.setcSeqNumber(ok.getcSeqNumber());
		ack.setcSeqStr("ACK");
		if (route != null) {
			ack.setRoute(route);
		}
		ack.setContentLength(0);
		return ack;
	}

	/**
	 * Llamante: el INVITE ha recibido un error. Los cuatro errores siguen el
	 * mismo camino; solo cambia el motivo que se muestra.
	 */
	public void onCallRejected(int code) {
		String reason;
		switch (code) {
			case 404:
				reason = "el destino, o nosotros, no esta registrado en el proxy";
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
		// stopVitextClient(); // vitext desactivado
		prompt();
	}

	// =========================================================================
	// Llamada entrante
	// =========================================================================

	/** true si el UA ya esta con una llamada: saliente, sonando, establecida o colgandose. */
	public boolean isBusy() {
		return callState != CallState.IDLE;
	}

	/**
	 * Llamado: llega un INVITE estando libres. El terminal suena (180 Ringing),
	 * se arrancan los 10 s para contestar y se pregunta S o N.
	 *
	 * No es synchronized a proposito: lo llama el hilo de red, que ya tiene
	 * bloqueada la capa de transaccion, y asi no puede cruzarse con el teclado.
	 */
	public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
		incomingInvite = inviteMessage;
		callState = CallState.INCOMING;
		transactionLayer.sendRinging(buildRinging(inviteMessage));
		startAnswerTimer();
		prompt();
	}

	/**
	 * Llega un INVITE de otra llamada estando ocupados. Se responde
	 * 486 Busy Here sin abrir otra maquina de estados. Si despues llega el ACK
	 * de ese 486, traera otro Call-ID y se descartara como cualquier mensaje
	 * fuera de contexto.
	 */
	public void rejectBusy(InviteMessage inviteMessage) throws IOException {
		System.out.println("Llamada de " + inviteMessage.getFromUri() + " rechazada (486): ya hay una llamada en curso");
		// La respuesta vuelve por donde vino el INVITE: su primera Via.
		String[] via = inviteMessage.getVias().get(0).split(":");
		transactionLayer.sendDirect(buildBusyHere(inviteMessage), via[0], Integer.parseInt(via[1]));
		prompt();
	}

	/** El usuario descuelga (S): se guardan los datos de la llamada y se responde 200 OK con SDP. */
	private synchronized void acceptCall() throws IOException {
		if (callState != CallState.INCOMING) {
			return;
		}
		cancelAnswerTimer();
		System.out.println("Llamada aceptada");
		InviteMessage invite = incomingInvite;
		currentCallId = invite.getCallId();
		// Somos el llamado: nosotros somos el To del INVITE y el otro extremo, el From.
		// Por eso, si colgamos nosotros, el BYE sale con To y From invertidos.
		localUri = invite.getToUri();
		localName = invite.getToName();
		remoteUri = invite.getFromUri();
		remoteName = invite.getFromName();
		remoteContact = invite.getContact();
		// Con loose routing, el INVITE trae el Record-Route que anadio el proxy.
		route = invite.getRecordRoute();
		callConfirmed = false; // la llamada estara activa cuando llegue el ACK
		// Se pasa a IN_CALL antes de enviar el 200: el ACK puede llegar enseguida.
		endIncomingCall(CallState.IN_CALL);
		transactionLayer.sendOk(buildOk(invite));
		// runVitextServer(); // vitext desactivado
	}

	/** El usuario rechaza la llamada (N): 486 Busy Here. */
	private synchronized void rejectCall() throws IOException {
		if (callState != CallState.INCOMING) {
			return;
		}
		cancelAnswerTimer();
		System.out.println("Llamada rechazada");
		transactionLayer.sendError(buildBusyHere(incomingInvite));
		endIncomingCall(CallState.IDLE);
	}

	/** Arranca los 10 s que tiene el usuario para contestar; si se agotan, se responde 408. */
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

	/** Han pasado 10 s sin que el usuario conteste: 408 Request Timeout. */
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
		prompt(); // aqui no hay ningun comando de teclado en curso que vuelva a mostrar el menu
	}

	private void endIncomingCall(CallState newState) {
		cancelAnswerTimer();
		incomingInvite = null;
		callState = newState;
	}

	/** Llamado: llega el ACK del 200 y la llamada queda activa. Un ACK de otra llamada se descarta. */
	public void onAckReceived(ACKMessage ack) {
		if (callState == CallState.IN_CALL && ack.getCallId().equals(currentCallId)) {
			callConfirmed = true;
			System.out.println("ACK recibido: llamada confirmada");
		} else {
			System.err.println("Unexpected message, throwing away");
		}
	}

	// =========================================================================
	// Colgar (BYE)
	// =========================================================================

	/**
	 * Comando BYE. Solo vale con la llamada activa (el llamante ya tiene el
	 * 200 OK y el llamado, el ACK). Sin loose routing el BYE va directo al
	 * Contact del otro UA; con loose routing va al proxy, que lo reenvia.
	 */
	private void commandBye() throws IOException {
		if (callState != CallState.IN_CALL || !callConfirmed) {
			System.out.println("No hay ninguna llamada activa: BYE solo se puede usar con la llamada establecida");
			return;
		}
		ByeMessage bye = buildBye();
		// El 200 OK del BYE se espera sin limite: no hay temporizador ni reenvio,
		// porque el enunciado no define maquina de estados para el BYE.
		callState = CallState.HANGING_UP; // antes de enviar: el 200 OK puede llegar enseguida
		System.out.println("Colgando la llamada con " + remoteUri);
		String[] nextHop = nextHop();
		// PRUEBA DE PERDIDA: si se comenta la linea siguiente, el BYE no llega.
		// Que se ve: el otro UA no se entera y sigue "En llamada", y nosotros nos
		// quedamos en "Colgando" para siempre, porque el BYE no se reenvia. Hay que
		// reiniciar los dos UA. Con loose routing, ademas, el proxy sigue en la
		// llamada y responde 503 a los demas.
		transactionLayer.sendDirect(bye, nextHop[0], Integer.parseInt(nextHop[1]));
	}

	/**
	 * BYE: es una peticion nueva, asi que lleva un CSeq nuevo, y el Call-ID de
	 * la llamada. From somos nosotros y To el otro extremo; si colgamos siendo
	 * el llamado, quedan al reves que en el INVITE. Con loose routing lleva
	 * ademas la cabecera Route con la direccion del proxy.
	 */
	private ByeMessage buildBye() {
		ByeMessage bye = new ByeMessage();
		bye.setDestination(remoteUri);
		bye.setVias(new ArrayList<String>(Arrays.asList(myAddress + ":" + listenPort)));
		bye.setMaxForwards(70);
		bye.setToName(remoteName);
		bye.setToUri(remoteUri);
		bye.setFromName(localName);
		bye.setFromUri(localUri);
		bye.setCallId(currentCallId);
		bye.setcSeqNumber(nextCSeq());
		bye.setcSeqStr("BYE");
		if (route != null) {
			bye.setRoute(route);
		}
		bye.setContentLength(0);
		return bye;
	}

	/** Llega un BYE. Si es de nuestra llamada, se confirma con un 200 OK y la llamada termina. */
	public void onByeReceived(ByeMessage bye) throws IOException {
		boolean ours = (callState == CallState.IN_CALL || callState == CallState.HANGING_UP)
				&& bye.getCallId().equals(currentCallId);
		if (!ours) {
			System.err.println("Unexpected message, throwing away");
			return;
		}
		// La respuesta vuelve por donde vino el BYE: su primera Via.
		String[] via = bye.getVias().get(0).split(":");
		// PRUEBA DE PERDIDA: si se comenta la linea siguiente, el 200 OK del BYE no llega.
		// Que se ve: nosotros terminamos la llamada y volvemos a IDLE, pero quien colgo
		// se queda en "Colgando" para siempre, porque el BYE no se reenvia.
		transactionLayer.sendDirect(buildByeOk(bye), via[0], Integer.parseInt(via[1]));
		System.out.println("El otro extremo ha colgado: llamada finalizada");
		endCall();
		prompt();
	}

	/** Llega el 200 OK de nuestro BYE y la llamada termina. Hasta entonces seguimos en HANGING_UP. */
	public void onByeOk(OKMessage ok) {
		if (callState != CallState.HANGING_UP || !ok.getCallId().equals(currentCallId)) {
			System.err.println("Unexpected message, throwing away");
			return;
		}
		System.out.println("Llamada finalizada");
		endCall();
		prompt();
	}

	/** Fin de la llamada: se olvidan sus datos y se vuelve a IDLE para poder hacer otra. */
	private void endCall() {
		// stopVitextClient(); // vitext desactivado
		// stopVitextServer(); // vitext desactivado
		currentCallId = null;
		callConfirmed = false;
		remoteContact = null;
		route = null;
		localUri = null;
		localName = null;
		remoteUri = null;
		remoteName = null;
		callState = CallState.IDLE;
	}

	// =========================================================================
	// Construccion de respuestas
	// =========================================================================
	//
	// Todas las respuestas copian de su peticion las Vias (para volver por el
	// mismo camino), To, From, Call-ID y CSeq (para que quien la recibe sepa a
	// que peticion responde). El 180 y el 200 OK del INVITE copian tambien el
	// Record-Route, si lo trae: asi le llega al llamante la ruta de la llamada.

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
		if (invite.getRecordRoute() != null) {
			ringing.setRecordRoute(invite.getRecordRoute());
		}
		ringing.setContentLength(0);
		return ringing;
	}

	/** 200 OK al INVITE: como el INVITE, lleva un SDP con nuestra IP y nuestro puerto RTP. */
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
		if (invite.getRecordRoute() != null) {
			ok.setRecordRoute(invite.getRecordRoute());
		}
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

	/** 200 OK al BYE: copia Vias, To, From, Call-ID y CSeq del BYE. */
	private OKMessage buildByeOk(ByeMessage bye) {
		OKMessage ok = new OKMessage();
		ok.setVias(new ArrayList<String>(bye.getVias()));
		ok.setToName(bye.getToName());
		ok.setToUri(bye.getToUri());
		ok.setFromName(bye.getFromName());
		ok.setFromUri(bye.getFromUri());
		ok.setCallId(bye.getCallId());
		ok.setcSeqNumber(bye.getcSeqNumber());
		ok.setcSeqStr(bye.getcSeqStr());
		ok.setContact(myAddress + ":" + listenPort);
		ok.setContentLength(0);
		return ok;
	}

	/** SDP con nuestra IP y nuestro puerto RTP. Lo llevan el INVITE y el 200 OK. */
	private SDPMessage buildSdp() {
		SDPMessage sdp = new SDPMessage();
		sdp.setIp(myAddress);
		sdp.setPort(rtpPort);
		sdp.setOptions(RTPFLOWS);
		return sdp;
	}

	// =========================================================================
	// CSeq
	// =========================================================================

	private synchronized String nextCSeq() {
		cSeq++;
		return String.valueOf(cSeq);
	}

	// =========================================================================
	// vitext (desactivado)
	// =========================================================================
	//
	// vitext es una integracion opcional con la parte RTSP. Queda comentado hasta
	// que toque esa integracion: para recuperarlo hay que descomentar estos
	// metodos, los dos campos Process y las llamadas marcadas con "vitext desactivado".
	//
	// private void runVitextClient() {
	// 	try {
	// 		vitextClient = Runtime.getRuntime().exec("xterm -e vitext/vitextclient -p 5000 239.1.2.3");
	// 	} catch (IOException e) {
	// 		System.out.println("Aviso: no se pudo arrancar vitextclient (" + e.getMessage() + "); se sigue sin video");
	// 	}
	// }
	//
	// private void stopVitextClient() {
	// 	if (vitextClient != null) {
	// 		vitextClient.destroy();
	// 	}
	// }
	//
	// private void runVitextServer() {
	// 	try {
	// 		vitextServer = Runtime.getRuntime()
	// 				.exec("xterm -iconic -e vitext/vitextserver -r 10 -p 5000 vitext/1.vtx 239.1.2.3");
	// 	} catch (IOException e) {
	// 		System.out.println("Aviso: no se pudo arrancar vitextserver (" + e.getMessage() + "); se sigue sin video");
	// 	}
	// }
	//
	// private void stopVitextServer() {
	// 	if (vitextServer != null) {
	// 		vitextServer.destroy();
	// 	}
	// }
}
