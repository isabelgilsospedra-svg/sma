package sipServlet;

/**
 * La llamada que recibe el servlet en doInvite: quien llama, a quien, y las
 * dos formas de decidir que pasa con ella.
 */
public interface SipServletRequestInterface {

	/**
	 * URI del llamante, tal como viene en la cabecera From del INVITE.
	 *
	 * @return por ejemplo "sip:alice@SMA"
	 */
	public String getCallerURI();

	/**
	 * URI del llamado, tal como viene en la cabecera To del INVITE.
	 *
	 * @return por ejemplo "sip:bob@SMA"
	 */
	public String getCalleeURI();

	/**
	 * Prepara la respuesta de error con la que se rechazara la llamada. No se
	 * envia hasta llamar a send(). Valen 404, 408, 486 y 503, las respuestas de
	 * error que define el proyecto; con cualquier otro codigo la llamada se
	 * rechaza con 503.
	 *
	 * @param statuscode codigo de la respuesta
	 * @return la respuesta, lista para send()
	 */
	public SipServletResponseInterface createResponse(int statuscode);

	/**
	 * El proxy, para dejar seguir la llamada con proxyTo().
	 *
	 * @return el proxy de esta llamada
	 */
	public ProxyInterface getProxy();
}
