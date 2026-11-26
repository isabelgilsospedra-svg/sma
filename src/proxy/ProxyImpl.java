package proxy;

import sipServlet.ProxyInterface;

/**
 * El proxy tal como lo ve el servlet: solo permite dejar seguir la llamada.
 * proxyTo() no reenvia nada todavia: apunta en la llamada el destino elegido, y
 * ProxyUserLayer reenvia el INVITE cuando termina doInvite.
 */
public class ProxyImpl implements ProxyInterface {
	private final SipServletRequest request;

	/**
	 * Lo crea SipServletRequest, uno por llamada.
	 *
	 * @param request llamada cuyo destino decide el servlet
	 */
	ProxyImpl(SipServletRequest request) {
		this.request = request;
	}

	@Override
	public void proxyTo(String uri) {
		request.decideProxyTo(uri);
	}
}
