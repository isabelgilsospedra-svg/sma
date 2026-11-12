package proxy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import common.FindMyIPv4;
import mensajesSIP.ACKMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.ServiceUnavailableMessage;
import mensajesSIP.TryingMessage;
import sipServlet.User;
import sipServlet.UsersServletReader;

/**
 * Capa de usuario del Proxy.
 *
 * Decide que hacer con cada peticion: a quien se deja registrar (los usuarios
 * de users.xml), donde esta cada usuario registrado, si un INVITE se acepta o
 * se rechaza y por donde vuelven las respuestas. Los estados de las
 * transacciones los lleva la capa de transaccion.
 */
public class ProxyUserLayer {
	private final ProxyTransactionLayer transactionLayer;

	/** Si es true, el proxy se queda en el camino de la llamada: anade Record-Route al INVITE. */
	private final boolean looseRouting;
	/** "ip:puerto" del proxy: es la Via que pone en los mensajes que reenvia. */
	private final String myVia;
	/**
	 * Lo que el proxy pone en Record-Route y lo que espera encontrar en la Route
	 * del ACK y del BYE: su direccion como URI SIP con el parametro lr de loose
	 * routing, por ejemplo "<sip:192.168.1.34:5060;lr>".
	 */
	private final String myRoute;
	/** Usuarios que pueden registrarse (las URIs de users.xml), por ejemplo "sip:alice@SMA". */
	private final Set<String> allowedUsers = new HashSet<String>();
	/** Tabla de registros: URI del usuario, donde esta y hasta cuando. */
	private final Map<String, Registration> registry = new HashMap<String, Registration>();

	/** Una entrada de la tabla de registros. */
	private static class Registration {
		final String contact; // "ip:puerto" del UA
		final long expiresAt; // instante (ms) en que caduca el registro

		Registration(String contact, long expiresAt) {
			this.contact = contact;
			this.expiresAt = expiresAt;
		}
	}

	public ProxyUserLayer(int listenPort, boolean looseRouting, boolean debug) throws Exception {
		this.looseRouting = looseRouting;
		this.myVia = FindMyIPv4.findMyIPv4Address().getHostAddress() + ":" + listenPort;
		this.myRoute = "<sip:" + myVia + ";lr>";
		loadAllowedUsers();
		// La capa de transaccion se crea la ultima, con todo lo nuestro ya inicializado.
		this.transactionLayer = new ProxyTransactionLayer(listenPort, looseRouting, debug, this);
	}

	public void startListening() {
		transactionLayer.startListening();
	}

	/** Lee users.xml y se queda con las URIs de los usuarios permitidos. */
	private void loadAllowedUsers() throws Exception {
		for (User user : UsersServletReader.readUsers().getListUsers()) {
			allowedUsers.add(user.getId());
		}
		System.out.println("Usuarios permitidos: " + allowedUsers);
	}

	// =========================================================================
	// REGISTER
	// =========================================================================

	/**
	 * Llega un REGISTER. Si el usuario no esta en users.xml se responde 404. Si
	 * esta, se guarda (o se actualiza) donde esta y hasta cuando, y se responde 200 OK.
	 */
	public void onRegisterReceived(RegisterMessage registerMessage) throws IOException {
		String user = registerMessage.getToUri();

		// La respuesta vuelve por donde vino la peticion: su primera Via.
		String[] origin = registerMessage.getVias().get(0).split(":");
		String originAddress = origin[0];
		int originPort = Integer.parseInt(origin[1]);

		if (!allowedUsers.contains(user)) {
			System.out.println("REGISTER de " + user + " rechazado: no esta en la lista de permitidos");
			transactionLayer.sendResponse(buildNotFound(registerMessage), originAddress, originPort);
			return;
		}

		int expires = Integer.parseInt(registerMessage.getExpires());
		long expiresAt = System.currentTimeMillis() + expires * 1000L;
		// put() sustituye la entrada anterior si el usuario ya estaba registrado.
		registry.put(user, new Registration(registerMessage.getContact(), expiresAt));
		System.out.println("Registrado " + user + " en " + registerMessage.getContact() + " durante " + expires + " s");
		transactionLayer.sendResponse(buildOk(registerMessage), originAddress, originPort);
	}

	/**
	 * Devuelve el registro vigente del usuario, o null si no esta registrado o
	 * si su registro ha caducado (en ese caso, ademas, se borra de la tabla).
	 */
	private Registration activeRegistration(String user) {
		Registration registration = registry.get(user);
		if (registration != null && System.currentTimeMillis() > registration.expiresAt) {
			System.out.println("El registro de " + user + " ha caducado");
			registry.remove(user);
			return null;
		}
		return registration;
	}

	// =========================================================================
	// INVITE
	// =========================================================================

	/**
	 * Llega un INVITE de A para B con el proxy libre.
	 *
	 * Si A o B no estan registrados (o su registro ha caducado), se responde
	 * 404. Si los dos lo estan, se responde 100 Trying a A y el INVITE se
	 * reenvia a B con nuestra Via puesta delante y Max-Forwards uno menos. Con
	 * loose routing se le anade ademas nuestro Record-Route, para que el ACK del
	 * 200 y el BYE pasen por el proxy.
	 */
	public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
		String caller = inviteMessage.getFromUri();
		String callee = inviteMessage.getToUri();

		// La respuesta a A vuelve por donde vino el INVITE: su primera Via.
		String[] origin = inviteMessage.getVias().get(0).split(":");
		String addressA = origin[0];
		int portA = Integer.parseInt(origin[1]);

		// Con Max-Forwards a 0 el INVITE se descarta sin responder, como si se
		// hubiera perdido: el enunciado no incluye ninguna respuesta para este caso.
		int maxForwards = inviteMessage.getMaxForwards();
		if (maxForwards <= 0) {
			System.out.println("INVITE de " + caller + " descartado: Max-Forwards = 0");
			return;
		}

		Registration registrationA = activeRegistration(caller);
		Registration registrationB = activeRegistration(callee);
		if (registrationA == null || registrationB == null) {
			String missing = (registrationA == null) ? caller : callee;
			System.out.println("INVITE de " + caller + " a " + callee + " rechazado: " + missing + " no esta registrado");
			transactionLayer.rejectInvite(inviteMessage.getCallId(), buildNotFound(inviteMessage), addressA, portA);
			return;
		}

		// El 100 se construye antes de tocar las Vias: debe llevar solo las que traia el INVITE.
		transactionLayer.acceptInvite(inviteMessage.getCallId(), buildTrying(inviteMessage), addressA, portA);

		inviteMessage.addVia(myVia);
		inviteMessage.setMaxForwards(maxForwards - 1);
		if (looseRouting) {
			inviteMessage.setRecordRoute(myRoute);
		}
		String[] contactB = registrationB.contact.split(":");
		transactionLayer.forwardInvite(inviteMessage, contactB[0], Integer.parseInt(contactB[1]));
	}

	/**
	 * Llega un INVITE con otro Call-ID mientras el proxy procesa uno.
	 * Se responde 503 Service Unavailable sin abrir transacciones. El ACK que
	 * mande A despues se descartara, porque no es de la llamada en curso.
	 */
	public void rejectBusy(InviteMessage inviteMessage) throws IOException {
		System.out.println("INVITE de " + inviteMessage.getFromUri() + " a " + inviteMessage.getToUri()
				+ " rechazado con 503: el proxy ya esta procesando otra llamada");
		String[] origin = inviteMessage.getVias().get(0).split(":");
		transactionLayer.sendResponse(buildServiceUnavailable(inviteMessage), origin[0], Integer.parseInt(origin[1]));
	}

	/**
	 * Respuesta de B que hay que hacer llegar a A: el proxy quita su propia Via
	 * (la primera) y la envia a la siguiente, que es la de A.
	 */
	public void forwardResponseToA(SIPMessage response, ArrayList<String> vias) throws IOException {
		if (!vias.get(0).equals(myVia)) {
			System.err.println("Aviso: la primera Via (" + vias.get(0) + ") no es la del proxy (" + myVia + ")");
		}
		vias.remove(0);
		String[] next = vias.get(0).split(":");
		// PRUEBA DE PERDIDA: si se comenta la linea siguiente, ninguna de las
		// respuestas que el proxy reenvia a A llega.
		// Que se ve: un 486 o un 408 si se recupera, porque el proxy lo reenvia a A a
		// los 200 ms desde Completed. El 180 y el 200 no se recuperan, porque nadie los
		// reenvia: A se queda esperando en Proceeding y B, en llamada sin recibir el ACK.
		transactionLayer.sendResponse(response, next[0], Integer.parseInt(next[1]));
	}

	// =========================================================================
	// Loose routing: el ACK del 200, el BYE y su 200 OK pasan por el proxy
	// =========================================================================

	/** Dice si una cabecera Route es la del proxy, es decir, si el mensaje viene para pasar por nosotros. */
	public boolean isMyRoute(String route) {
		return myRoute.equals(route);
	}

	/**
	 * Reenvia el ACK del 200 al otro extremo de la llamada. Como con cualquier
	 * peticion que reenvia, el proxy pone su Via delante; y quita la cabecera
	 * Route, que solo llevaba su propia direccion y ya ha cumplido su funcion.
	 */
	public void forwardAck(ACKMessage ack, String address, int port) throws IOException {
		ack.setRoute(null);
		ack.addVia(myVia);
		// PRUEBA DE PERDIDA: si se comenta la linea siguiente, el ACK del 200 se
		// pierde en el proxy.
		// Que se ve: el llamado nunca da la llamada por confirmada y no puede colgar
		// ("No hay ninguna llamada activa"). El llamante si puede colgar, y su BYE
		// llega al llamado a traves del proxy.
		transactionLayer.forwardRequest(ack, address, port);
	}

	/**
	 * Reenvia el BYE al otro extremo de la llamada: el proxy quita su Route y
	 * pone su Via delante, para que el 200 OK del BYE vuelva pasando por el.
	 */
	public void forwardBye(ByeMessage bye, String address, int port) throws IOException {
		bye.setRoute(null);
		bye.addVia(myVia);
		// PRUEBA DE PERDIDA: si se comenta la linea siguiente, el BYE se pierde en
		// el proxy.
		// Que se ve: el otro UA sigue "En llamada" y quien colgo se queda en
		// "Colgando". A los 15 s el proxy muestra "Sin 200 OK del BYE" y queda libre.
		transactionLayer.forwardRequest(bye, address, port);
	}

	/**
	 * Reenvia el 200 OK del BYE a quien colgo. Como cualquier respuesta, vuelve
	 * por las Vias: el proxy quita la suya (la primera) y la envia a la siguiente.
	 */
	public void forwardByeOk(OKMessage ok) throws IOException {
		ArrayList<String> vias = ok.getVias();
		if (!vias.get(0).equals(myVia)) {
			System.err.println("Aviso: la primera Via (" + vias.get(0) + ") no es la del proxy (" + myVia + ")");
		}
		vias.remove(0);
		String[] next = vias.get(0).split(":");
		// PRUEBA DE PERDIDA: si se comenta la linea siguiente, el 200 OK del BYE se
		// pierde en el proxy.
		// Que se ve: el proxy queda libre, porque el ya ha visto el 200 OK, pero
		// quien colgo se queda en "Colgando" para siempre.
		transactionLayer.sendResponse(ok, next[0], Integer.parseInt(next[1]));
	}

	// =========================================================================
	// Construccion de respuestas
	// =========================================================================
	//
	// Todas copian de su peticion las Vias (para volver por el mismo camino),
	// To, From, Call-ID y CSeq (para que quien la recibe sepa a que responde).

	private OKMessage buildOk(RegisterMessage register) {
		OKMessage ok = new OKMessage();
		ok.setVias(new ArrayList<String>(register.getVias()));
		ok.setToName(register.getToName());
		ok.setToUri(register.getToUri());
		ok.setFromName(register.getFromName());
		ok.setFromUri(register.getFromUri());
		ok.setCallId(register.getCallId());
		ok.setcSeqNumber(register.getcSeqNumber());
		ok.setcSeqStr(register.getcSeqStr());
		ok.setContact(register.getContact());
		ok.setExpires(register.getExpires());
		ok.setContentLength(0);
		return ok;
	}

	private NotFoundMessage buildNotFound(RegisterMessage register) {
		NotFoundMessage nf = new NotFoundMessage();
		nf.setVias(new ArrayList<String>(register.getVias()));
		nf.setToName(register.getToName());
		nf.setToUri(register.getToUri());
		nf.setFromName(register.getFromName());
		nf.setFromUri(register.getFromUri());
		nf.setCallId(register.getCallId());
		nf.setcSeqNumber(register.getcSeqNumber());
		nf.setcSeqStr(register.getcSeqStr());
		nf.setContact(register.getContact());
		nf.setContentLength(0);
		return nf;
	}

	private TryingMessage buildTrying(InviteMessage invite) {
		TryingMessage trying = new TryingMessage();
		trying.setVias(new ArrayList<String>(invite.getVias()));
		trying.setToName(invite.getToName());
		trying.setToUri(invite.getToUri());
		trying.setFromName(invite.getFromName());
		trying.setFromUri(invite.getFromUri());
		trying.setCallId(invite.getCallId());
		trying.setcSeqNumber(invite.getcSeqNumber());
		trying.setcSeqStr(invite.getcSeqStr());
		trying.setContentLength(0);
		return trying;
	}

	private NotFoundMessage buildNotFound(InviteMessage invite) {
		NotFoundMessage nf = new NotFoundMessage();
		nf.setVias(new ArrayList<String>(invite.getVias()));
		nf.setToName(invite.getToName());
		nf.setToUri(invite.getToUri());
		nf.setFromName(invite.getFromName());
		nf.setFromUri(invite.getFromUri());
		nf.setCallId(invite.getCallId());
		nf.setcSeqNumber(invite.getcSeqNumber());
		nf.setcSeqStr(invite.getcSeqStr());
		nf.setContact(invite.getContact());
		nf.setContentLength(0);
		return nf;
	}

	/**
	 * 408 que el proxy envia a A cuando B no da respuesta final. Se construye a
	 * partir del INVITE tal como se reenvio a B, asi que hay que quitar la primera
	 * Via (la del proxy) para que queden las que traia el INVITE de A.
	 */
	public RequestTimeoutMessage buildRequestTimeout(InviteMessage forwardedInvite) {
		RequestTimeoutMessage timeout = new RequestTimeoutMessage();
		ArrayList<String> vias = new ArrayList<String>(forwardedInvite.getVias());
		vias.remove(0);
		timeout.setVias(vias);
		timeout.setToName(forwardedInvite.getToName());
		timeout.setToUri(forwardedInvite.getToUri());
		timeout.setFromName(forwardedInvite.getFromName());
		timeout.setFromUri(forwardedInvite.getFromUri());
		timeout.setCallId(forwardedInvite.getCallId());
		timeout.setcSeqNumber(forwardedInvite.getcSeqNumber());
		timeout.setcSeqStr(forwardedInvite.getcSeqStr());
		timeout.setContentLength(0);
		return timeout;
	}

	private ServiceUnavailableMessage buildServiceUnavailable(InviteMessage invite) {
		ServiceUnavailableMessage unavailable = new ServiceUnavailableMessage();
		unavailable.setVias(new ArrayList<String>(invite.getVias()));
		unavailable.setToName(invite.getToName());
		unavailable.setToUri(invite.getToUri());
		unavailable.setFromName(invite.getFromName());
		unavailable.setFromUri(invite.getFromUri());
		unavailable.setCallId(invite.getCallId());
		unavailable.setcSeqNumber(invite.getcSeqNumber());
		unavailable.setcSeqStr(invite.getcSeqStr());
		unavailable.setContentLength(0);
		return unavailable;
	}
}
