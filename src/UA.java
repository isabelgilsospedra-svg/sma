import ua.UaUserLayer;

public class UA {
	private static final String USAGE = "Uso: java UA usuarioSIP puertoescuchaUA IPProxy puertoescuchaProxy debug(true/false) tiempo_registro";

	public static void main(String[] args) throws Exception {
		if (args.length != 6) {
			System.err.println(USAGE);
			System.exit(1);
		}
		System.out.println("UA launching with args: " + String.join(", ", args));

		String sipUser = args[0];
		int listenPort = Integer.parseInt(args[1]);
		String proxyAddress = args[2];
		int proxyPort = Integer.parseInt(args[3]);
		boolean debug = parseBoolean(args[4]);
		int registerTime = Integer.parseInt(args[5]);

		UaUserLayer userLayer = new UaUserLayer(sipUser, listenPort, proxyAddress, proxyPort, debug, registerTime);

		new Thread() {
			@Override
			public void run() {
				userLayer.startListeningNetwork();
			}
		}.start();

		userLayer.register();

		userLayer.startListeningKeyboard();
	}

	/** Solo admite "true" o "false"; cualquier otro valor es un error de uso. */
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
		return false; // no se llega aqui
	}
}