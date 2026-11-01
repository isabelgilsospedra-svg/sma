package proxy;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

import mensajesSIP.SIPMessage;

/**
 * Capa de transporte del Proxy: un socket UDP por el que salen y entran todos
 * los mensajes SIP.
 *
 * Como todo pasa por aqui, aqui se imprimen las trazas. Con debug se ve el
 * mensaje entero; sin debug, solo su primera linea. La flecha indica el
 * sentido: {@code -->} enviado y {@code <--} recibido.
 */
public class ProxyTransportLayer {
	/** Bytes que caben en el buffer de recepcion; sobra para cualquier mensaje de la practica. */
	private static final int BUFSIZE = 4 * 1024;

	private final int listenPort;
	private final DatagramSocket socket;
	private final ProxyTransactionLayer transactionLayer;
	/** true: se imprime el mensaje completo; false: solo la primera linea. */
	private final boolean debug;

	public ProxyTransportLayer(int listenPort, boolean debug, ProxyTransactionLayer transactionLayer)
			throws SocketException {
		this.transactionLayer = transactionLayer;
		this.debug = debug;
		this.listenPort = listenPort;
		this.socket = new DatagramSocket(listenPort);
	}

	/** Envia un mensaje a la direccion y el puerto indicados. */
	public void send(SIPMessage sipMessage, String address, int port) throws IOException {
		String msg = sipMessage.toStringMessage();
		trace("-->", "a " + address + ":" + port, msg);
		sendBytes(msg.getBytes(), address, port);
	}

	/**
	 * Bucle del hilo de red. Espera un datagrama, lo convierte en un SIPMessage y
	 * se lo pasa a la capa de transaccion. Si un mensaje no se puede leer, se
	 * muestra el error y se sigue escuchando.
	 */
	public void startListening() {
		System.out.println("Listening at " + listenPort + "...");
		while (true) {
			try {
				byte[] buf = new byte[BUFSIZE];
				DatagramPacket packet = new DatagramPacket(buf, buf.length);
				socket.receive(packet);
				// Solo los bytes recibidos: el resto del buffer son ceros y el
				// parser los tomaria como parte del mensaje (decision T3).
				String msg = new String(packet.getData(), 0, packet.getLength());
				trace("<--", "de " + packet.getAddress().getHostAddress() + ":" + packet.getPort(), msg);
				SIPMessage sipMessage = SIPMessage.parseMessage(msg);
				transactionLayer.onMessageReceived(sipMessage);
			} catch (Exception e) {
				System.err.println(e.getMessage());
				e.printStackTrace();
			}
		}
	}

	/** Imprime un mensaje enviado o recibido, entero o solo su primera linea segun el modo debug. */
	private void trace(String arrow, String peer, String msg) {
		if (debug) {
			System.out.println(arrow + " (" + peer + ")");
			System.out.println(msg.trim());
			System.out.println();
		} else {
			System.out.println(arrow + " " + msg.split("\n", 2)[0].trim() + "  (" + peer + ")");
		}
	}

	private void sendBytes(byte[] bytes, String address, int port) throws IOException {
		InetAddress inetAddress = InetAddress.getByName(address);
		DatagramPacket packet = new DatagramPacket(bytes, bytes.length, inetAddress, port);
		socket.send(packet);
	}
}
