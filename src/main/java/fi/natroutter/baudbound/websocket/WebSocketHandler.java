package fi.natroutter.baudbound.websocket;

import fi.natroutter.baudbound.BaudBound;
import fi.natroutter.baudbound.event.TriggerContext;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Built-in WebSocket server that fires {@link TriggerContext#webSocket} events for each
 * incoming message.
 *
 * <p><b>Channel routing:</b> The URL path the client connects on (e.g. {@code /sensors/temp})
 * is treated as the "channel". It is available in events via the {@code {channel}} substitution
 * token and can be filtered with the {@code WEBSOCKET_CHANNEL_*} condition types.
 *
 * <p><b>Authentication:</b> When an {@code authToken} is configured, connecting clients must
 * send {@code AUTH:<token>} as their first message before any subsequent messages are processed.
 * Unauthenticated messages result in the connection being closed.
 * Authenticated clients may send {@code ACK:<delivery-id>} to acknowledge a pending durable
 * webhook delivery, or {@code ACK_RESET} / {@code ACK_RESET:<delivery-id>} to clear pending
 * deliveries after the downstream queue has been reset, without firing a normal WebSocket event.
 *
 * <p>Incoming messages are appended to a bounded {@link #getMessageLog() message log}
 * (max {@value #MAX_LOG_ENTRIES} entries) for display in {@code WebSocketWindow}.
 *
 * <p>Start via {@link #startServer()}; stop via {@link #stopServer()}.
 */
public class WebSocketHandler extends WebSocketServer {

    private static final int MAX_LOG_ENTRIES = 200;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String authToken;
    private final String host;
    private final Set<WebSocket> authenticated = Collections.synchronizedSet(new HashSet<>());
    private final Map<WebSocket, String> channelMap = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> messageLog = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    /**
     * @param host      the interface address to bind to (e.g. {@code "0.0.0.0"} for all interfaces,
     *                  {@code "127.0.0.1"} for loopback only)
     * @param port      the TCP port to listen on
     * @param authToken optional auth token; blank/null disables authentication
     */
    public WebSocketHandler(String host, int port, String authToken) {
        super(new InetSocketAddress(host, port));
        this.host = host;
        this.authToken = authToken;
        setReuseAddr(true);
        setConnectionLostTimeout(0);
    }

    /** Returns the host/interface this server is bound to. */
    public String getHost() {
        return host;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String channel = handshake.getResourceDescriptor();
        if (channel == null || channel.isBlank()) channel = "/";
        channelMap.put(conn, channel);

        if (authToken == null || authToken.isBlank()) {
            authenticated.add(conn);
            BaudBound.getLogger().info("WebSocket: client connected from "
                    + conn.getRemoteSocketAddress() + " on channel " + channel);
        } else {
            BaudBound.getLogger().info("WebSocket: client connected from "
                    + conn.getRemoteSocketAddress() + " on channel " + channel
                    + " — awaiting AUTH");
        }
        appendLog("[CONNECT] " + conn.getRemoteSocketAddress() + "  channel=" + channel);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String channel = channelMap.getOrDefault(conn, "/");

        if (authToken != null && !authToken.isBlank() && !authenticated.contains(conn)) {
            if (message.equals("AUTH:" + authToken)) {
                authenticated.add(conn);
                appendLog("[AUTH] " + conn.getRemoteSocketAddress() + " authenticated on " + channel);
                BaudBound.getLogger().info("WebSocket: client authenticated from "
                        + conn.getRemoteSocketAddress() + " on channel " + channel);
            } else {
                appendLog("[AUTH FAIL] " + conn.getRemoteSocketAddress() + " — closing connection");
                BaudBound.getLogger().warn("WebSocket: authentication failed from "
                        + conn.getRemoteSocketAddress() + " — closing connection");
                conn.close();
            }
            return;
        }

        if (message.equals("ACK_RESET") || message.equals("QUEUE_RESET")) {
            int removed = BaudBound.getWebhookDeliveryQueue()
                    .resetAll("WebSocket " + conn.getRemoteSocketAddress());
            appendLog("[ACK RESET] [" + channel + "] all  removed=" + removed);
            BaudBound.getLogger().warn("WebSocket: [" + channel + "] durable queue reset accepted — removed "
                    + removed + " pending delivery item(s)");
            return;
        }

        if (message.startsWith("ACK_RESET:") || message.startsWith("QUEUE_RESET:")) {
            String prefix = message.startsWith("ACK_RESET:") ? "ACK_RESET:" : "QUEUE_RESET:";
            String deliveryId = message.substring(prefix.length()).trim();
            boolean reset = BaudBound.getWebhookDeliveryQueue()
                    .reset(deliveryId, "WebSocket " + conn.getRemoteSocketAddress());
            appendLog("[ACK RESET] [" + channel + "] " + deliveryId + "  " + (reset ? "accepted" : "not found"));
            BaudBound.getLogger().warn("WebSocket: [" + channel + "] delivery reset " + deliveryId
                    + (reset ? " accepted" : " ignored — no pending delivery"));
            return;
        }

        if (message.startsWith("ACK:")) {
            String deliveryId = message.substring("ACK:".length()).trim();
            boolean acknowledged = BaudBound.getWebhookDeliveryQueue()
                    .acknowledge(deliveryId, "WebSocket " + conn.getRemoteSocketAddress());
            appendLog("[ACK] [" + channel + "] " + deliveryId + "  " + (acknowledged ? "accepted" : "not found"));
            BaudBound.getLogger().info("WebSocket: [" + channel + "] delivery ack " + deliveryId
                    + (acknowledged ? " accepted" : " ignored — no pending delivery"));
            return;
        }

        appendLog("[MSG] [" + channel + "] " + message);
        BaudBound.getLogger().info("WebSocket: [" + channel + "] received: \"" + message + "\"");
        Thread.ofVirtual().start(() ->
                BaudBound.getEventHandler().process(TriggerContext.webSocket(message, channel, conn)));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String channel = channelMap.remove(conn);
        authenticated.remove(conn);

        String channelInfo = channel != null ? "  channel=" + channel : "";
        String reasonInfo = reason != null && !reason.isBlank() ? "  reason=" + reason : "";
        BaudBound.getLogger().info("WebSocket: client disconnected from "
                + conn.getRemoteSocketAddress() + channelInfo
                + "  code=" + code + reasonInfo);
        appendLog("[DISCONNECT] " + conn.getRemoteSocketAddress() + channelInfo + "  code=" + code);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        String addr = conn != null ? conn.getRemoteSocketAddress().toString() : "unknown";
        BaudBound.getLogger().error("WebSocket: error from " + addr + ": " + ex.getMessage());
        appendLog("[ERROR] " + addr + ": " + ex.getMessage());
    }

    @Override
    public void onStart() {
        running = true;
        BaudBound.getLogger().info("WebSocket: server started on " + host + ":" + getPort());
        appendLog("[SERVER] Started on " + host + ":" + getPort());
    }

    // -------------------------------------------------------------------------
    // Reply / send helpers
    // -------------------------------------------------------------------------

    /**
     * Sends a message to all currently authenticated clients connected on the given channel path.
     * Silently skips closed connections.
     *
     * @param channel the channel path to broadcast to (e.g. {@code /sensors/temp})
     * @param message the text to send
     */
    public void sendToChannel(String channel, String message) {
        int sent = 0;
        for (Map.Entry<WebSocket, String> entry : channelMap.entrySet()) {
            if (channel.equals(entry.getValue()) && authenticated.contains(entry.getKey())
                    && entry.getKey().isOpen()) {
                entry.getKey().send(message);
                sent++;
            }
        }
        BaudBound.getLogger().info("WebSocket: sent to " + sent
                + " client(s) on channel " + channel + ": \"" + message + "\"");
    }

    // -------------------------------------------------------------------------
    // Log
    // -------------------------------------------------------------------------

    private void appendLog(String message) {
        String entry = "[" + LocalTime.now().format(TIME_FMT) + "] " + message;
        messageLog.add(entry);
        if (messageLog.size() > MAX_LOG_ENTRIES) {
            messageLog.remove(0);
        }
    }

    /**
     * Returns an unmodifiable view of recent incoming messages for display in the UI.
     * Thread-safe; safe to read from the GLFW render thread.
     */
    public List<String> getMessageLog() {
        return Collections.unmodifiableList(messageLog);
    }

    /** Clears the in-memory message log. */
    public void clearMessageLog() {
        messageLog.clear();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts the WebSocket server. {@code WebSocketServer} extends {@link Thread};
     * this calls {@link Thread#start()} to run the server loop on a new platform thread.
     */
    public void startServer() {
        start();
    }

    /** Stops the WebSocket server, waiting up to 1 second for a clean shutdown. */
    public void stopServer() {
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
        BaudBound.getLogger().info("WebSocket: server stopped");
        appendLog("[SERVER] Stopped");
    }

    /** Returns {@code true} if the server has started and is listening. */
    public boolean isRunning() {
        return running;
    }

    /** Returns the number of currently connected WebSocket clients. */
    public int getConnectedCount() {
        return getConnections().size();
    }
}
