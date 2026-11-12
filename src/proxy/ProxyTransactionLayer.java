package proxy;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import common.InviteState;
import mensajesSIP.ACKMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.SIPMessage;

/**
 * Capa de transaccion del Proxy.
 *
 * El REGISTER se atiende sin estado. Cada INVITE, en cambio, se procesa con
 * dos transacciones a la vez: una con A (el llamante), en la que el proxy hace
 * de llamado, y otra con B (el llamado), en la que hace de llamante.
 *
 * El proxy solo procesa un INVITE cada vez. Mientras tanto, un INVITE con otro
 * Call-ID recibe un 503 sin abrir transacciones nuevas. Sin loose routing, el
 * proxy queda libre en cuanto terminan sus dos transacciones.
 *
 * Con loose routing, si la llamada se establece, el proxy sigue en ella: el
 * ACK del 200 y el BYE pasan por el, y sigue respondiendo 503 a otros INVITE
 * hasta que ve el 200 OK del BYE.
 */
public class ProxyTransactionLayer {
	/** Tiempo que se pasa en Completed antes de ir a Terminated (ms). */
	private static final long COMPLETED_MS = 1000;
	/** Cada cuanto se reenvia a A el error mientras no llega su ACK (ms). */
	private static final long RETRANSMIT_MS = 200;
	/**
	 * Tiempo maximo que se espera la respuesta final de B (ms). Es mayor que los
	 * 10 s que tarda B en responder 408 por su cuenta, para no adelantarse a el.
	 */
	private static final long NO_ANSWER_MS = 15000;
	/** Tiempo maximo que se espera el 200 OK de un BYE reenviado (ms). */
	private static final long BYE_MS = 15000;

	private final ProxyUserLayer userLayer;
	private final ProxyTransportLayer transportLayer;
	/** Si es true, el proxy sigue en la llamada despues del INVITE, hasta el 200 OK del BYE. */
	private final boolean looseRouting;

	/** Transaccion con A (llamante): el proxy recibe su INVITE y le responde. */
	private InviteState stateA = InviteState.IDLE;
	/** Transaccion con B (llamado): el proxy le reenvia el INVITE y recibe sus respuestas. */
	private InviteState stateB = InviteState.IDLE;
	/** Call-ID del INVITE que se esta procesando. */
	private String inviteCallId = null;

	/** Donde esta A (la primera Via de su INVITE). */
	private String addressA = null;
	private int portA = 0;
	/** Donde esta B (su Contact registrado). */
	private String addressB = null;
	private int portB = 0;
	/** El INVITE tal como se reenvio a B. De el salen los campos del ACK que el proxy envia a B. */
	private InviteMessage inviteToB = null;

	/** Transaccion con A en Completed: temporizador de 1 s. */
	private Timer completedTimerA = null;
	/** Transaccion con A en Completed: reenvio del error cada 200 ms. */
	private Timer retransmitTimerA = null;
	/** Transaccion con B en Completed: temporizador de 1 s. */
	private Timer completedTimerB = null;
	/** Transaccion con B: espera maxima de su respuesta final (15 s). */
	private Timer noAnswerTimerB = null;

	/**
	 * Loose routing: Call-ID de la llamada establecida en la que sigue el proxy.
	 * Vale null si no hay ninguna.
	 */
	private String callInProgressId = null;
	/** Loose routing: URI del llamante, para saber hacia que lado reenviar el BYE. */
	private String callerUri = null;
	/** Loose routing: espera maxima del 200 OK de un BYE reenviado (15 s). */
	private Timer byeTimer = null;

	public ProxyTransactionLayer(int listenPort, boolean looseRouting, boolean debug, ProxyUserLayer userLayer)
			throws SocketException {
		this.looseRouting = looseRouting;
		this.userLayer = userLayer;
		this.transportLayer = new ProxyTransportLayer(listenPort, debug, this);
	}

	public void startListening() {
		transportLayer.startListening();
	}

	// =========================================================================
	// Mensajes que llegan de la red
	// =========================================================================

	/** Aqui entra todo lo que llega por la red; cada mensaje se reparte segun su tipo. */
	public synchronized void onMessageReceived(SIPMessage sipMessage) throws IOException {
		if (sipMessage instanceof RegisterMessage) {
			// El REGISTER no depende de ninguna llamada: se atiende siempre.
			userLayer.onRegisterReceived((RegisterMessage) sipMessage);
		} else if (sipMessage instanceof InviteMessage) {
			onInvite((InviteMessage) sipMessage);
		} else if (sipMessage instanceof ACKMessage) {
			onAck((ACKMessage) sipMessage);
		} else if (sipMessage instanceof ByeMessage) {
			onBye((ByeMessage) sipMessage);
		} else if (sipMessage instanceof OKMessage && "BYE".equals(((OKMessage) sipMessage).getcSeqStr())) {
			onByeOk((OKMessage) sipMessage);
		} else if (sipMessage instanceof RingingMessage) {
			RingingMessage ringing = (RingingMessage) sipMessage;
			onResponseFromB(ringing, ringing.getCallId(), ringing.getVias(), 180);
		} else if (sipMessage instanceof OKMessage && "INVITE".equals(((OKMessage) sipMessage).getcSeqStr())) {
			OKMessage ok = (OKMessage) sipMessage;
			onResponseFromB(ok, ok.getCallId(), ok.getVias(), 200);
		} else if (sipMessage instanceof BusyHereMessage) {
			BusyHereMessage busy = (BusyHereMessage) sipMessage;
			onResponseFromB(busy, busy.getCallId(), busy.getVias(), 486);
		} else if (sipMessage instanceof RequestTimeoutMessage) {
			RequestTimeoutMessage timeout = (RequestTimeoutMessage) sipMessage;
			onResponseFromB(timeout, timeout.getCallId(), timeout.getVias(), 408);
		} else {
			System.err.println("Unexpected message, throwing away");
		}
	}

	/**
	 * Llega un INVITE. Si el proxy esta libre, lo procesa la capa de usuario.
	 * Si ya esta con otro: si trae el mismo Call-ID es una repeticion
	 * y se descarta; si trae otro, se responde 503 sin abrir transacciones.
	 * Con loose routing, el proxy tampoco esta libre mientras dura la llamada
	 * establecida.
	 */
	private void onInvite(InviteMessage inviteMessage) throws IOException {
		if (stateA == InviteState.IDLE && callInProgressId == null) {
			userLayer.onInviteReceived(inviteMessage);
		} else if (inviteMessage.getCallId().equals(inviteCallId)
				|| inviteMessage.getCallId().equals(callInProgressId)) {
			System.err.println("Unexpected message, throwing away");
		} else {
			userLayer.rejectBusy(inviteMessage);
		}
	}

	/**
	 * Respuesta de B al INVITE que le reenviamos. Se actualizan las dos
	 * transacciones y la respuesta se reenvia a A (la capa de usuario quita
	 * nuestra Via y la manda a la siguiente).
	 * <ul>
	 * <li>180: con B se pasa de Calling a Proceeding; con A se sigue en Proceeding.</li>
	 * <li>200: las dos transacciones terminan. Sin loose routing, el proxy sale de la llamada;
	 * con loose routing, sigue en ella hasta el 200 OK del BYE.</li>
	 * <li>486 o 408: con B, Completed y ACK a B (salto a salto); a 1 s, Terminated.
	 * Con A, Completed: se reenvia el error cada 200 ms hasta el ACK de A; a 1 s, Terminated.</li>
	 * <li>Si B repite el error estando ya en Completed, es que nuestro ACK se perdio: se reenvia.</li>
	 * </ul>
	 */
	private void onResponseFromB(SIPMessage response, String callId, ArrayList<String> vias, int code)
			throws IOException {
		if (code >= 300 && stateB == InviteState.COMPLETED && callId.equals(inviteCallId)) {
			System.out.println("[Proxy] Error " + code + " repetido de B: se reenvia el ACK a B");
			sendAckToB();
			return;
		}
		boolean ours = callId.equals(inviteCallId)
				&& (stateB == InviteState.CALLING || stateB == InviteState.PROCEEDING);
		if (!ours) {
			System.err.println("Unexpected message, throwing away");
			return;
		}
		if (code >= 200) {
			// B ha dado su respuesta final: ya no hay que vigilar si contesta.
			cancelTimer(noAnswerTimerB);
		}
		if (code < 200) {
			if (stateB == InviteState.CALLING) {
				setStateB(InviteState.PROCEEDING);
			}
			userLayer.forwardResponseToA(response, vias);
		} else if (code == 200) {
			setStateB(InviteState.TERMINATED);
			userLayer.forwardResponseToA(response, vias);
			setStateA(InviteState.TERMINATED);
			if (looseRouting) {
				// La llamada queda establecida y el proxy sigue en ella.
				callInProgressId = inviteCallId;
				callerUri = inviteToB.getFromUri();
			}
			endInvite();
		} else {
			// Con B el proxy es el llamante: Completed y ACK a B.
			setStateB(InviteState.COMPLETED);
			sendAckToB();
			startCompletedTimerB();
			// Con A el proxy es el llamado: reenvia el error y espera el ACK de A.
			userLayer.forwardResponseToA(response, vias);
			enterCompletedA(response);
		}
	}

	/**
	 * Llega un ACK. Hay dos que le interesan al proxy:
	 * <ul>
	 * <li>El ACK de A al error que le enviamos (transaccion con A en Completed y
	 * su Call-ID): se deja de reenviar el error y se pasa a Terminated.</li>
	 * <li>Con loose routing, el ACK del 200 de la llamada establecida, que trae
	 * nuestra Route: se reenvia al llamado.</li>
	 * </ul>
	 * Cualquier otro se descarta: por ejemplo, el ACK de un 503, que no es de la
	 * llamada en curso.
	 */
	private void onAck(ACKMessage ack) throws IOException {
		if (stateA == InviteState.COMPLETED && ack.getCallId().equals(inviteCallId)) {
			cancelTimer(retransmitTimerA);
			setStateA(InviteState.TERMINATED);
			tryEndInvite();
		} else if (ack.getCallId().equals(callInProgressId) && userLayer.isMyRoute(ack.getRoute())) {
			// El ACK del 200 siempre lo envia el llamante: va hacia B.
			userLayer.forwardAck(ack, addressB, portB);
		} else {
			System.err.println("Unexpected message, throwing away");
		}
	}

	/**
	 * Loose routing: llega el BYE de la llamada establecida, con nuestra Route.
	 * Se reenvia al otro extremo: si lo envia el llamante (su URI esta en el
	 * From) va hacia B, y si lo envia el llamado, hacia A. El proxy sigue en la
	 * llamada hasta ver el 200 OK; por si no llega nunca, arranca una espera de
	 * 15 s. Sin loose routing el BYE no pasa por el proxy, asi que se descarta.
	 */
	private void onBye(ByeMessage bye) throws IOException {
		if (!bye.getCallId().equals(callInProgressId) || !userLayer.isMyRoute(bye.getRoute())) {
			System.err.println("Unexpected message, throwing away");
			return;
		}
		if (bye.getFromUri().equals(callerUri)) {
			userLayer.forwardBye(bye, addressB, portB);
		} else {
			userLayer.forwardBye(bye, addressA, portA);
		}
		startByeTimer();
	}

	/**
	 * Loose routing: llega el 200 OK del BYE de la llamada establecida. Se
	 * reenvia a quien colgo y la llamada termina: el proxy queda libre.
	 */
	private void onByeOk(OKMessage ok) throws IOException {
		if (!ok.getCallId().equals(callInProgressId)) {
			System.err.println("Unexpected message, throwing away");
			return;
		}
		userLayer.forwardByeOk(ok);
		endCall();
	}

	// =========================================================================
	// Acciones que pide la capa de usuario
	// =========================================================================

	/** Transaccion con A: el INVITE se acepta. Pasa a Proceeding y se envia el 100 Trying a A. */
	public synchronized void acceptInvite(String callId, SIPMessage trying, String addressA, int portA)
			throws IOException {
		inviteCallId = callId;
		this.addressA = addressA;
		this.portA = portA;
		setStateA(InviteState.PROCEEDING);
		transportLayer.send(trying, addressA, portA);
	}

	/**
	 * Transaccion con A: el INVITE se rechaza con un 404. Se pasa a Proceeding,
	 * se envia el error y se entra en Completed: se reenvia cada 200 ms hasta el
	 * ACK de A, y a 1 s se pasa a Terminated.
	 */
	public synchronized void rejectInvite(String callId, SIPMessage error, String addressA, int portA)
			throws IOException {
		inviteCallId = callId;
		this.addressA = addressA;
		this.portA = portA;
		setStateA(InviteState.PROCEEDING);
		// PRUEBA DE PERDIDA: si se comenta la linea siguiente, el primer envio del 404
		// a A no sale.
		// Que se ve: a los 200 ms aparece "[Proxy] Sin ACK de A: se reenvia la respuesta
		// de error", el 404 llega a A con ese reenvio y A responde con su ACK.
		transportLayer.send(error, addressA, portA);
		enterCompletedA(error);
	}

	/** Transaccion con B: el proxy reenvia el INVITE al llamado y pasa a Calling. */
	public synchronized void forwardInvite(InviteMessage inviteMessage, String addressB, int portB)
			throws IOException {
		this.inviteToB = inviteMessage;
		this.addressB = addressB;
		this.portB = portB;
		setStateB(InviteState.CALLING);
		transportLayer.send(inviteMessage, addressB, portB);
		startNoAnswerTimerB();
	}

	/**
	 * Envia una respuesta que no cambia el estado de ninguna transaccion: las
	 * respuestas al REGISTER, las de B que se reenvian a A, el 503 a un
	 * segundo INVITE y, con loose routing, el 200 OK del BYE.
	 */
	public void sendResponse(SIPMessage response, String address, int port) throws IOException {
		transportLayer.send(response, address, port);
	}

	/**
	 * Loose routing: reenvia una peticion de la llamada establecida (el ACK del
	 * 200 o el BYE). No tienen transaccion en el proxy.
	 */
	public void forwardRequest(SIPMessage request, String address, int port) throws IOException {
		transportLayer.send(request, address, port);
	}

	// =========================================================================
	// Temporizadores y fin del INVITE
	// =========================================================================

	/**
	 * Transaccion con A: le hemos enviado un error. Completed: el error se
	 * reenvia cada 200 ms hasta que llega el ACK de A, y a 1 s se pasa a Terminated.
	 */
	private void enterCompletedA(SIPMessage error) {
		setStateA(InviteState.COMPLETED);
		retransmitTimerA = new Timer("proxy-retransmit-A", true);
		retransmitTimerA.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (ProxyTransactionLayer.this) {
					if (stateA != InviteState.COMPLETED) {
						return;
					}
					try {
						System.out.println("[Proxy] Sin ACK de A: se reenvia la respuesta de error");
						transportLayer.send(error, addressA, portA);
					} catch (IOException e) {
						System.err.println("Error reenviando a A: " + e.getMessage());
					}
				}
			}
		}, RETRANSMIT_MS, RETRANSMIT_MS);
		startCompletedTimerA();
	}

	/**
	 * Transaccion con B: ACK al error de B. Va al Contact de B, lleva solo la
	 * Via del proxy (somos nosotros quien lo envia) y el CSeq del INVITE.
	 */
	private void sendAckToB() throws IOException {
		ACKMessage ack = new ACKMessage();
		ack.setDestination(inviteToB.getDestination());
		ack.setVias(new ArrayList<String>(inviteToB.getVias().subList(0, 1)));
		ack.setMaxForwards(70);
		ack.setToName(inviteToB.getToName());
		ack.setToUri(inviteToB.getToUri());
		ack.setFromName(inviteToB.getFromName());
		ack.setFromUri(inviteToB.getFromUri());
		ack.setCallId(inviteToB.getCallId());
		ack.setcSeqNumber(inviteToB.getcSeqNumber());
		ack.setcSeqStr("ACK");
		ack.setContentLength(0);
		// PRUEBA DE PERDIDA: si se comenta la linea siguiente, ninguno de los ACK que
		// el proxy envia a B llega.
		// Que se ve: B reenvia su error cada 200 ms ("Sin ACK: se reenvia la respuesta
		// de error") y el proxy contesta a cada reenvio con "[Proxy] Error X repetido de
		// B: se reenvia el ACK a B". Al cabo de 1 s, B y la transaccion del proxy con B
		// pasan a Terminated.
		transportLayer.send(ack, addressB, portB);
	}

	private void startCompletedTimerA() {
		completedTimerA = new Timer("proxy-completed-A", true);
		completedTimerA.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (ProxyTransactionLayer.this) {
					if (stateA != InviteState.COMPLETED) {
						return; // ya habia terminado al llegar el ACK de A
					}
					cancelTimer(retransmitTimerA);
					setStateA(InviteState.TERMINATED);
					tryEndInvite();
				}
			}
		}, COMPLETED_MS);
	}

	private void startCompletedTimerB() {
		completedTimerB = new Timer("proxy-completed-B", true);
		completedTimerB.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (ProxyTransactionLayer.this) {
					if (stateB != InviteState.COMPLETED) {
						return;
					}
					setStateB(InviteState.TERMINATED);
					tryEndInvite();
				}
			}
		}, COMPLETED_MS);
	}

	/**
	 * Transaccion con B: vigila que B de una respuesta final. Si B se cierra
	 * despues de recibir el INVITE (por ejemplo, mientras suena), nadie
	 * responderia nunca: A se quedaria esperando y el proxy, ocupado hasta
	 * reiniciarlo. Por eso, si a los 15 s B no ha dado respuesta final, el proxy
	 * da por terminada la transaccion con B y responde el mismo 408 a A. Con A se
	 * sigue el camino normal de un error: Completed, reenvio cada 200 ms hasta su
	 * ACK y Terminated.
	 */
	private void startNoAnswerTimerB() {
		noAnswerTimerB = new Timer("proxy-no-answer-B", true);
		noAnswerTimerB.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (ProxyTransactionLayer.this) {
					if (stateB != InviteState.CALLING && stateB != InviteState.PROCEEDING) {
						return; // B ya habia dado su respuesta final
					}
					System.out.println("[Proxy] B no da respuesta final en " + (NO_ANSWER_MS / 1000)
							+ " s: se responde 408 a A");
					// A B no se le envia ACK: no ha mandado ningun error que confirmar.
					setStateB(InviteState.TERMINATED);
					try {
						SIPMessage timeout = userLayer.buildRequestTimeout(inviteToB);
						// PRUEBA DE PERDIDA: si se comenta la linea siguiente, el primer envio
						// del 408 a A no sale.
						// Que se ve: a los 200 ms aparece "[Proxy] Sin ACK de A: se reenvia la
						// respuesta de error", el 408 llega a A con ese reenvio y A responde
						// con su ACK.
						transportLayer.send(timeout, addressA, portA);
						enterCompletedA(timeout);
					} catch (IOException e) {
						System.err.println("Error enviando el 408 a A: " + e.getMessage());
					}
				}
			}
		}, NO_ANSWER_MS);
	}

	/**
	 * El INVITE termina cuando han terminado las dos transacciones. La de B
	 * puede no haber empezado nunca, como en el 404.
	 */
	private void tryEndInvite() {
		boolean aDone = stateA == InviteState.TERMINATED;
		boolean bDone = stateB == InviteState.TERMINATED || stateB == InviteState.IDLE;
		if (aDone && bDone) {
			endInvite();
		}
	}

	/**
	 * Loose routing: espera el 200 OK del BYE que se acaba de reenviar. Si a los
	 * 15 s no ha llegado (se ha perdido, o el otro UA esta cerrado), el proxy da
	 * la llamada por terminada para no quedarse ocupado para siempre. Si llega
	 * otro BYE mientras tanto, la espera no se reinicia.
	 */
	private void startByeTimer() {
		if (byeTimer != null) {
			return;
		}
		byeTimer = new Timer("proxy-bye", true);
		byeTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (ProxyTransactionLayer.this) {
					if (callInProgressId == null) {
						return; // el 200 OK ya habia llegado
					}
					System.out.println("[Proxy] Sin 200 OK del BYE en " + (BYE_MS / 1000)
							+ " s: se da la llamada por terminada");
					endCall();
				}
			}
		}, BYE_MS);
	}

	/** Loose routing: fin de la llamada establecida. El proxy queda libre para otra. */
	private void endCall() {
		cancelTimer(byeTimer);
		byeTimer = null;
		callInProgressId = null;
		callerUri = null;
		System.out.println("[Proxy] Llamada terminada: proxy libre");
	}

	/**
	 * Fin del procesamiento del INVITE: se paran los temporizadores. El proxy
	 * queda libre para otra llamada, salvo que con loose routing se haya
	 * establecido la llamada: entonces sigue en ella.
	 */
	private void endInvite() {
		cancelTimer(completedTimerA);
		cancelTimer(retransmitTimerA);
		cancelTimer(completedTimerB);
		cancelTimer(noAnswerTimerB);
		completedTimerA = null;
		retransmitTimerA = null;
		completedTimerB = null;
		noAnswerTimerB = null;
		stateA = InviteState.IDLE;
		stateB = InviteState.IDLE;
		inviteCallId = null;
		inviteToB = null;
		if (callInProgressId == null) {
			System.out.println("[Proxy] Procesamiento del INVITE terminado: proxy libre");
		} else {
			System.out.println("[Proxy] Procesamiento del INVITE terminado: el proxy sigue en la llamada"
					+ " hasta el 200 OK del BYE");
		}
	}

	private void cancelTimer(Timer timer) {
		if (timer != null) {
			timer.cancel();
		}
	}

	/** Cambia el estado de la transaccion con A y lo imprime, como pide el enunciado. */
	private void setStateA(InviteState newState) {
		System.out.println("[Proxy - transaccion con A (llamante)] " + stateA + " -> " + newState);
		stateA = newState;
	}

	/** Cambia el estado de la transaccion con B y lo imprime, como pide el enunciado. */
	private void setStateB(InviteState newState) {
		System.out.println("[Proxy - transaccion con B (llamado)] " + stateB + " -> " + newState);
		stateB = newState;
	}
}
