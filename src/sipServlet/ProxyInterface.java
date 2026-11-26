package sipServlet;

/** Permite al servlet dejar seguir la llamada. */
public interface ProxyInterface {

	/**
	 * Deja seguir la llamada hacia uri: el propio llamado (getCalleeURI()) u
	 * otro usuario, para desviarla. El proxy reenvia el INVITE cuando termina
	 * doInvite. Si uri no esta registrado en el proxy, el llamante recibe un 404.
	 * Al desviar no se ejecuta el servlet del nuevo destino.
	 *
	 * @param uri URI SIP del destino, por ejemplo "sip:carol@SMA"
	 */
	public void proxyTo(String uri);
}
