package common;

/**
 * Estados de una transaccion INVITE, con los mismos nombres que los diagramas
 * del enunciado (una version simplificada del RFC 3261).
 *
 * La usan el UA, tanto de llamante como de llamado, y el Proxy en sus dos
 * transacciones: la que tiene con A y la que tiene con B.
 */
public enum InviteState {

	/** No hay ninguna transaccion INVITE en marcha. */
	IDLE,

	/** Llamante: el INVITE ya ha salido y todavia no ha llegado ninguna respuesta. */
	CALLING,

	/**
	 * Llamante: ya ha llegado un 100 o un 180 y falta la respuesta final.
	 * Llamado: ha llegado el INVITE y todavia no se ha enviado la respuesta final.
	 */
	PROCEEDING,

	/**
	 * Ha habido un error (recibido o enviado). Se espera 1 s antes de terminar;
	 * quien envio el error, ademas, lo reenvia cada 200 ms hasta que llega el ACK.
	 */
	COMPLETED,

	/** La transaccion ha terminado. */
	TERMINATED
}
