package proxy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import sipServlet.User;
import sipServlet.UsersServletReader;

import common.FindMyIPv4;
import mensajesSIP.TryingMessage;

public class ProxyUserLayer {
	private ProxyTransactionLayer transactionLayer;

	/** Si es true, el proxy se queda en la llamada (Record-Route). Se usa en el bloque 3. */
	private final boolean looseRouting;
	/** Direccion "ip:puerto" del proxy: es la Via que anade a los mensajes que reenvia. */
	private final String myVia;
	/** Usuarios permitidos (URIs leidas de users.xml), p. ej. "sip:alice@SMA". */
	private final Set<String> allowedUsers = new HashSet<String>();

	/** Tabla de registros: URI del usuario -> donde esta y hasta cuando. */
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
		this.transactionLayer = new ProxyTransactionLayer(listenPort, debug, this);
		loadAllowedUsers();
	}

	private void loadAllowedUsers() throws Exception {
		for (User user : UsersServletReader.readUsers().getListUsers()) {
			allowedUsers.add(user.getId());
		}
		System.out.println("Usuarios permitidos: " + allowedUsers);
	}

	public void onRegisterReceived(RegisterMessage registerMessage) throws IOException {
		String user = registerMessage.getToUri();

		// La respuesta vuelve por donde vino la peticion: la primera Via.
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
		
		/*prueba
		System.out.println("Contacto guardado: " + registry.get(user).contact);
		System.out.println("Numero de registros en la tabla: " + registry.size());
		*/
		
		transactionLayer.sendResponse(buildOk(registerMessage), originAddress, originPort);
	}

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

	/**
	 * Llega un INVITE de A para B. Si alguno de los dos no esta registrado (o su
	 * registro ha caducado), se responde 404. Si no, 100 Trying a A y el INVITE
	 * se reenvia a B con la Via del proxy anadida y Max-Forwards - 1.
	 */
	public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
		String caller = inviteMessage.getFromUri();
		String callee = inviteMessage.getToUri();

		// La respuesta a A vuelve por donde vino el INVITE: la primera Via.
		String[] origin = inviteMessage.getVias().get(0).split(":");
		String addressA = origin[0];
		int portA = Integer.parseInt(origin[1]);

		// Decision 1a (pendiente de consultar con el profesor): con Max-Forwards a 0 el INVITE se descarta.
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
		String[] contactB = registrationB.contact.split(":");
		transactionLayer.forwardInvite(inviteMessage, contactB[0], Integer.parseInt(contactB[1]));
	}
	/**
	 * Devuelve el registro vigente del usuario o null si no esta registrado o si
	 * su registro ha caducado (en ese caso se borra de la tabla).
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

	public void startListening() {
		transactionLayer.startListening();
	}
}