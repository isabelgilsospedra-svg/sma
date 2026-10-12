import proxy.ProxyUserLayer;

public class Proxy {
	private static final String USAGE = "Uso: java Proxy [puertoescuchaProxy loose-routing(true/false) debug(true/false)]";

	public static void main(String[] args) throws Exception {
		// Sin parametros: puerto SIP estandar, loose-routing y debug activados.
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
		userLayer.startListening();
	}

	/** Solo admite "true" o "false"; cualquier otro valor es un error de uso. */
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
		return false; // no se llega aqui
	}
}