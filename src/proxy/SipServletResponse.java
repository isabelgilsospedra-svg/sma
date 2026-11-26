package proxy;

import sipServlet.SipServletResponseInterface;

/**
 * Respuesta de error que crea el servlet con createResponse(). send() no envia
 * nada todavia: apunta en la llamada que el servlet la rechaza con este codigo,
 * y ProxyUserLayer envia la respuesta cuando termina doInvite.
 */
public class SipServletResponse implements SipServletResponseInterface {
	private final SipServletRequest request;
	private final int statusCode;

	/**
	 * Lo crea SipServletRequest.createResponse().
	 *
	 * @param request llamada a la que pertenece la respuesta
	 * @param statusCode codigo de la respuesta
	 */
	SipServletResponse(SipServletRequest request, int statusCode) {
		this.request = request;
		this.statusCode = statusCode;
	}

	@Override
	public void send() {
		request.decideResponse(statusCode);
	}
}
