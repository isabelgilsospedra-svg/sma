package sipServlet;

/**
 * Servicio de llamada de un usuario: es lo que programa quien escribe un
 * servlet SIP.
 *
 * El proxy busca en users.xml la clase asociada al llamado (o, si el llamado no
 * tiene, al llamante), la carga por su nombre y llama a doInvite con cada
 * INVITE de una llamada nueva. Solo se atienden INVITE. La clase necesita un
 * constructor publico sin argumentos, porque el proxy la crea con
 * getDeclaredConstructor().newInstance().
 *
 * El servlet solo debe usar las cuatro interfaces de este API, para que
 * funcione igual en el proxy de cualquier grupo.
 */
public interface SIPServletInterface {

	/**
	 * Decide que pasa con una llamada nueva. Hay dos decisiones posibles:
	 * rechazarla con request.createResponse(codigo).send() o dejarla seguir con
	 * request.getProxy().proxyTo(uri). El proxy aplica la decision cuando este
	 * metodo termina; si hay mas de una, solo cuenta la primera, y si no hay
	 * ninguna (o el metodo lanza una excepcion), la llamada se rechaza con 503.
	 *
	 * @param request la llamada: quien llama, a quien, y las dos decisiones
	 */
	public void doInvite(SipServletRequestInterface request);
}
