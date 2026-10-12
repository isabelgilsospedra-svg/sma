package ua;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

import mensajesSIP.SIPMessage;

public class UaTransportLayer {
	private static final int BUFSIZE = 4 * 1024;

	private int listenPort;
	private String proxyAddress;
	private int proxyPort;
	private DatagramSocket socket;
	private UaTransactionLayer transactionLayer;
	/** true: traza con todas las cabeceras; false: solo la primera linea. */
	private boolean debug;

	public UaTransportLayer(int listenPort, String proxyAddress, int proxyPort, boolean debug,
							UaTransactionLayer transactionLayer) throws SocketException {
		this.transactionLayer = transactionLayer;
		this.debug = debug;
		this.listenPort = listenPort;
		this.proxyAddress = proxyAddress;
		this.proxyPort = proxyPort;
		this.socket = new DatagramSocket(listenPort);
	}

	public void sendToProxy(SIPMessage sipMessage) throws IOException {
		send(sipMessage, this.proxyAddress, this.proxyPort);
	}

	public void send(SIPMessage sipMessage, String address, int port) throws IOException {
		String msg = sipMessage.toStringMessage();
		trace("-->", "a " + address + ":" + port, msg);
		send(msg.getBytes(), address, port);
	}

	/**
	 * Todos los mensajes enviados y recibidos pasan por aqui. La flecha indica
	 * el sentido: --> enviado, <-- recibido.
	 */
	private void trace(String arrow, String peer, String msg) {
		if (debug) {
			System.out.println(arrow + " (" + peer + ")");
			System.out.println(msg.trim());
			System.out.println();
		} else {
			System.out.println(arrow + " " + msg.split("\n", 2)[0].trim() + "  (" + peer + ")");
		}
	}

	private void send(byte[] bytes, String address, int port) throws IOException {
		InetAddress inetAddress = InetAddress.getByName(address);
		DatagramPacket packet = new DatagramPacket(bytes, bytes.length, inetAddress, port);
		socket.send(packet);
	}

	public void startListening() {
		System.out.println("Listening at " + listenPort + "...");
		while (true) {
			try {
				byte[] buf = new byte[BUFSIZE];
				DatagramPacket packet = new DatagramPacket(buf, buf.length);
				socket.receive(packet);
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

}