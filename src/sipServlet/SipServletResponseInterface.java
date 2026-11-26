package sipServlet;

/** Respuesta de error creada con createResponse() para rechazar la llamada. */
public interface SipServletResponseInterface {

	/**
	 * Rechaza la llamada con esta respuesta. El proxy la envia al llamante
	 * cuando termina doInvite.
	 */
	public void send();
}
