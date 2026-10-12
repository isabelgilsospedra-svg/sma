package ua;

import java.io.IOException;

import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;
import java.util.UUID;

import common.FindMyIPv4;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.SDPMessage;

import mensajesSIP.RegisterMessage;

public class UaUserLayer {
	private static final int IDLE = 0;
	private int state = IDLE;

	public static final ArrayList<Integer> RTPFLOWS = new ArrayList<Integer>(
			Arrays.asList(new Integer[] { 96, 97, 98 }));

	private UaTransactionLayer transactionLayer;

	private String myAddress = FindMyIPv4.findMyIPv4Address().getHostAddress();
	private int rtpPort;
	private int listenPort;

	private String sipUser;
	private int registerTime;

	/** true desde que el proxy acepta el REGISTER con un 200 OK. */
	private volatile boolean registered = false;
	/** Instante (ms) en que caduca el registro segun el Expires del 200 OK. */
	private volatile long registerExpiresAt = 0;

	private Process vitextClient = null;
	private Process vitextServer = null;

	public UaUserLayer(String sipUser, int listenPort, String proxyAddress, int proxyPort, boolean debug,
					   int registerTime)
			throws SocketException, UnknownHostException {
		this.transactionLayer = new UaTransactionLayer(listenPort, proxyAddress, proxyPort, debug, this);
		this.listenPort = listenPort;
		this.rtpPort = listenPort + 1;
		this.sipUser = sipUser;
		this.registerTime = registerTime;
	}

	public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
		System.out.println("Received INVITE from " + inviteMessage.getFromName());
		runVitextServer();
	}

	public void onRegisterOk(OKMessage okMessage) {
		int expires = registerTime;
		if (okMessage.getExpires() != null) {
			expires = Integer.parseInt(okMessage.getExpires());
		}
		registerExpiresAt = System.currentTimeMillis() + expires * 1000L;
		registered = true;
		System.out.println("Registrado en el proxy como " + sipUser + " durante " + expires + " s");
		prompt(); // ya se pueden usar los comandos: se muestra el menu
	}

	public void onRegisterNotFound(NotFoundMessage notFoundMessage) {
		registered = false;
		System.out.println("Registro rechazado (404): " + sipUser + " no es un usuario permitido");
		System.out.println("Cierra el UA y arrancalo con un usuario de users.xml");
	}

	public void startListeningNetwork() {
		transactionLayer.startListeningNetwork();
	}

	public void register() throws IOException {

		RegisterMessage registerMessage = new RegisterMessage();

		registerMessage.setDestination("sip:SMA");
		registerMessage.setVias(new ArrayList<String>(
				Arrays.asList(myAddress + ":" + listenPort)));
		registerMessage.setMaxForwards(70);

		registerMessage.setToUri("sip:" + sipUser);
		registerMessage.setFromUri("sip:" + sipUser);

		registerMessage.setCallId(UUID.randomUUID().toString());
		registerMessage.setcSeqNumber("1");
		registerMessage.setcSeqStr("REGISTER");

		registerMessage.setContact(myAddress + ":" + listenPort);

		registerMessage.setExpires(String.valueOf(registerTime));
		registerMessage.setContentLength(0);

		transactionLayer.register(registerMessage);
	}

	public void startListeningKeyboard() {
		try (Scanner scanner = new Scanner(System.in)) {
			while (true) {
				prompt();
				String line = scanner.nextLine();
				if (!line.isEmpty()) {
					command(line);
				}
			}
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}

	private void prompt() {
		System.out.println("");
		if (!registered) {
			System.out.println("Esperando el registro en el proxy: los comandos se activan al recibir el 200 OK");
			System.out.print("> ");
			return;
		}
		switch (state) {
			case IDLE:
				promptIdle();
				break;
			default:
				throw new IllegalStateException("Unexpected state: " + state);
		}
		System.out.print("> ");
	}

	private void promptIdle() {
		System.out.println("INVITE xxx");
	}

	private void command(String line) throws IOException {
		if (!registered) {
			System.out.println("Comando ignorado: el UA no esta registrado en el proxy");
			return;
		}
		if (line.startsWith("INVITE")) {
			commandInvite(line);
		} else {
			System.out.println("Bad command");
		}
	}

	private void commandInvite(String line) throws IOException {
		stopVitextServer();
		stopVitextClient();

		System.out.println("Inviting...");

		runVitextClient();

		String callId = UUID.randomUUID().toString();

		SDPMessage sdpMessage = new SDPMessage();
		sdpMessage.setIp(this.myAddress);
		sdpMessage.setPort(this.rtpPort);
		sdpMessage.setOptions(RTPFLOWS);

		InviteMessage inviteMessage = new InviteMessage();
		inviteMessage.setDestination("sip:bob@SMA");
		inviteMessage.setVias(new ArrayList<String>(Arrays.asList(this.myAddress + ":" + this.listenPort)));
		inviteMessage.setMaxForwards(70);
		inviteMessage.setToName("Bob");
		inviteMessage.setToUri("sip:bob@SMA");
		inviteMessage.setFromName("Alice");
		inviteMessage.setFromUri("sip:alice@SMA");
		inviteMessage.setCallId(callId);
		inviteMessage.setcSeqNumber("1");
		inviteMessage.setcSeqStr("INVITE");
		inviteMessage.setContact(myAddress + ":" + listenPort);
		inviteMessage.setContentType("application/sdp");
		inviteMessage.setContentLength(sdpMessage.toStringMessage().getBytes().length);
		inviteMessage.setSdp(sdpMessage);

		transactionLayer.call(inviteMessage);
	}

	private void runVitextClient() throws IOException {
		vitextClient = Runtime.getRuntime().exec("xterm -e vitext/vitextclient -p 5000 239.1.2.3");
	}

	private void stopVitextClient() {
		if (vitextClient != null) {
			vitextClient.destroy();
		}
	}

	private void runVitextServer() throws IOException {
		vitextServer = Runtime.getRuntime().exec("xterm -iconic -e vitext/vitextserver -r 10 -p 5000 vitext/1.vtx 239.1.2.3");
	}

	private void stopVitextServer() {
		if (vitextServer != null) {
			vitextServer.destroy();
		}
	}

}