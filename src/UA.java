import ua.UaUserLayer;

/**
 * Punto de entrada del agente de usuario (UA).
 *
 * Comprueba los seis argumentos y crea la capa de usuario, que a su vez crea la
 * de transaccion y la de transporte. Despues reparte el trabajo en dos hilos:
 * uno escucha la red y el principal se queda leyendo comandos del teclado.
 */
public class UA {
	private static final String USAGE = "Uso: java UA usuarioSIP puertoescuchaUA IPProxy puertoescuchaProxy debug(true/false) tiempo_registro";

	public static void main(String[] args) throws Exception {
		if (args.length != 6) {
			System.err.println(USAGE);
			System.exit(1);
		}
		System.out.println("UA launching with args: " + String.join(", ", args));

		String sipUser = args[0];
		// El usuario tiene que llevar dominio, por ejemplo alice@SMA. Es el mismo
		// formato que acepta SIPMessage al leer las cabeceras To y From: letras,
		// numeros y _, y el dominio puede llevar puntos (decision D4).
		if (!sipUser.matches("\\w+@[\\w\\.]+")) {
			System.err.println("El usuario SIP debe tener formato nombre@dominio (por ejemplo alice@SMA); recibido: " + sipUser);
			System.err.println(USAGE);
			System.exit(1);
		}
		int listenPort = Integer.parseInt(args[1]);
		String proxyAddress = args[2];
		int proxyPort = Integer.parseInt(args[3]);
		boolean debug = parseBoolean(args[4]);
		int registerTime = Integer.parseInt(args[5]);

		UaUserLayer userLayer = new UaUserLayer(sipUser, listenPort, proxyAddress, proxyPort, debug, registerTime);

		// Hilo de red: recibe los mensajes SIP y se los pasa a la capa de transaccion.
		new Thread(userLayer::startListeningNetwork, "red-sip").start();

		// El primer REGISTER sale nada mas arrancar. Los comandos no se aceptan
		// hasta que llega su 200 OK.
		userLayer.register();

		// El hilo principal se queda leyendo el teclado.
		userLayer.startListeningKeyboard();
	}

	/** Convierte "true" o "false" en booleano. Cualquier otro valor es un error de uso y el UA termina. */
	private static boolean parseBoolean(String value) {
		if (value.equalsIgnoreCase("true")) {
			return true;
		}
		if (value.equalsIgnoreCase("false")) {
			return false;
		}
		System.err.println("El parametro debug solo puede ser true o false (recibido: " + value + ")");
		System.err.println(USAGE);
		System.exit(1);
		return false; // no se llega aqui: System.exit termina el programa
	}
}
