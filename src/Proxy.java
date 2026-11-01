import proxy.ProxyUserLayer;

/**
 * Punto de entrada del Proxy.
 *
 * Sin argumentos usa el puerto SIP estandar (5060) con loose routing y debug
 * activados. Con argumentos tienen que ir los tres: puerto, loose routing y debug.
 */
public class Proxy {
	private static final String USAGE = "Uso: java Proxy [puertoescuchaProxy loose-routing(true/false) debug(true/false)]";

	public static void main(String[] args) throws Exception {
		int listenPort = 5060;
		boolean looseRouting = true;
		boolean debug = true;

		if (args.length == 3) {
			listenPort = Integer.parseInt(args[0]);
			looseRouting = parseBoolean(args[1], "loose-routing");
			debug = parseBoolean(args[2], "debug");
		} else if (args.length != 0) {
			System.err.println(USAGE);
			System.exit(1);
		}

		System.out.println("Proxy: puerto " + listenPort + ", loose-routing " + looseRouting + ", debug " + debug);
		ProxyUserLayer userLayer = new ProxyUserLayer(listenPort, looseRouting, debug);
		// El proxy no lee el teclado: el hilo principal se queda escuchando la red.
		userLayer.startListening();
	}

	/** Convierte "true" o "false" en booleano. Cualquier otro valor es un error de uso y el Proxy termina. */
	private static boolean parseBoolean(String value, String name) {
		if (value.equalsIgnoreCase("true")) {
			return true;
		}
		if (value.equalsIgnoreCase("false")) {
			return false;
		}
		System.err.println("El parametro " + name + " solo puede ser true o false (recibido: " + value + ")");
		System.err.println(USAGE);
		System.exit(1);
		return false; // no se llega aqui: System.exit termina el programa
	}
}
