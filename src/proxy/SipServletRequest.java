package proxy;

import sipServlet.ProxyInterface;
import sipServlet.SipServletRequestInterface;
import sipServlet.SipServletResponseInterface;

/**
 * La llamada que el proxy entrega al servlet en doInvite.
 *
 * Ademas de lo que ve el servlet (las dos URIs y las dos formas de decidir),
 * guarda la decision que toma: send() y proxyTo() no envian nada, solo la
 * apuntan aqui. Cuando doInvite termina, ProxyUserLayer lee la decision y es
 * el quien responde al llamante o reenvia el INVITE. Asi el proxy sabe tambien
 * si el servlet no ha decidido nada o ha intentado decidir dos veces.
 *
 * Los metodos que guardan y leen la decision no son publicos: solo los usan las
 * clases de este paquete, y un servlet no puede llamarlos.
 */
public class SipServletRequest implements SipServletRequestInterface {
	private final String callerUri;
	private final String calleeUri;
	private final ProxyImpl proxy;

	/** Codigo de la respuesta con la que el servlet rechaza la llamada; 0 si no la rechaza. */
	private int responseCode = 0;
	/** URI hacia la que el servlet deja seguir la llamada; null si no la deja seguir. */
	private String targetUri = null;

	/**
	 * Lo crea ProxyUserLayer con los datos del INVITE, justo antes de llamar a
	 * doInvite.
	 *
	 * @param callerUri URI del llamante (From del INVITE)
	 * @param calleeUri URI del llamado (To del INVITE)
	 */
	SipServletRequest(String callerUri, String calleeUri) {
		this.callerUri = callerUri;
		this.calleeUri = calleeUri;
		this.proxy = new ProxyImpl(this);
	}

	@Override
	public String getCallerURI() {
		return callerUri;
	}

	@Override
	public String getCalleeURI() {
		return calleeUri;
	}

	@Override
	public SipServletResponseInterface createResponse(int statuscode) {
		return new SipServletResponse(this, statuscode);
	}

	@Override
	public ProxyInterface getProxy() {
		return proxy;
	}

	// =========================================================================
	// Decision del servlet (solo para este paquete)
	// =========================================================================

	/**
	 * El servlet rechaza la llamada (lo llama SipServletResponse.send()). Si ya
	 * habia decidido, se ignora: solo cuenta la primera decision.
	 *
	 * @param code codigo de la respuesta de error
	 */
	void decideResponse(int code) {
		if (hasDecision()) {
			System.out.println("[Proxy] El servlet ya habia decidido: se ignora la respuesta " + code);
			return;
		}
		responseCode = code;
	}

	/**
	 * El servlet deja seguir la llamada hacia uri (lo llama ProxyImpl.proxyTo()).
	 * Si ya habia decidido, se ignora: solo cuenta la primera decision.
	 *
	 * @param uri URI SIP del destino
	 */
	void decideProxyTo(String uri) {
		if (hasDecision()) {
			System.out.println("[Proxy] El servlet ya habia decidido: se ignora proxyTo(" + uri + ")");
			return;
		}
		targetUri = uri;
	}

	/** @return true si el servlet ha rechazado la llamada o la ha dejado seguir */
	boolean hasDecision() {
		return responseCode != 0 || targetUri != null;
	}

	/** @return codigo con el que el servlet rechaza la llamada, o 0 si no la rechaza */
	int getResponseCode() {
		return responseCode;
	}

	/** @return URI hacia la que el servlet deja seguir la llamada, o null si no la deja seguir */
	String getTargetUri() {
		return targetUri;
	}
}
