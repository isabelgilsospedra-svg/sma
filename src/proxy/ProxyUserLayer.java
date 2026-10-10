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

public class ProxyUserLayer {
	private ProxyTransactionLayer transactionLayer;

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

	public ProxyUserLayer(int listenPort) throws Exception {
		this.transactionLayer = new ProxyTransactionLayer(listenPort, this);
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

	public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
		System.out.println("Received INVITE from " + inviteMessage.getFromName());
		ArrayList<String> vias = inviteMessage.getVias();
		String origin = vias.get(0);
		String[] originParts = origin.split(":");
		String originAddress = originParts[0];
		int originPort = Integer.parseInt(originParts[1]);
		transactionLayer.echoInvite(inviteMessage, originAddress, originPort);
	}

	public void startListening() {
		transactionLayer.startListening();
	}
}