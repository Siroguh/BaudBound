package fi.natroutter.baudbound.event;

import fi.natroutter.baudbound.enums.TriggerSource;
import fi.natroutter.baudbound.storage.DataStore;
import org.java_websocket.WebSocket;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable context object passed to {@link EventHandler#process(TriggerContext)} by every
 * trigger source. Carries the payload string, the originating device (if applicable), the
 * source type, the WebSocket channel path, and the originating WebSocket connection.
 *
 * <p>Substitution tokens:
 * <ul>
 *   <li>{@code {input}} — the payload string for all sources</li>
 *   <li>{@code {sequence}} — monotonic in-process trigger sequence</li>
 *   <li>{@code {channel}} — the WebSocket URL path (e.g. {@code /sensors/temp}); empty string for non-WS sources</li>
 *   <li>{@code {timestamp}} — ISO local date-time at action execution time</li>
 * </ul>
 *
 * <p>Source-specific input values:
 * <ul>
 *   <li>{@link TriggerSource#SERIAL} — the trimmed serial line</li>
 *   <li>{@link TriggerSource#WEBSOCKET} — the raw WebSocket message body</li>
 *   <li>{@link TriggerSource#DEVICE_CONNECTED} / {@link TriggerSource#DEVICE_DISCONNECTED}
 *       — the device's display name</li>
 * </ul>
 *
 * @param input      the payload string; never {@code null}
 * @param device     the originating device, or {@code null} for non-device sources (e.g. WebSocket)
 * @param source     the trigger source that produced this context; never {@code null}
 * @param channel    the WebSocket URL path (e.g. {@code /sensors/temp}), or {@code null} for non-WS sources
 * @param connection the originating WebSocket connection for sending replies, or {@code null} for non-WS sources
 * @param sequence   monotonic in-process trigger sequence
 */
public record TriggerContext(
        String input,
        DataStore.Device device,
        TriggerSource source,
        String channel,
        WebSocket connection,
        long sequence
) {
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis());

    private static long nextSequence() {
        return SEQUENCE.incrementAndGet();
    }

    /** Creates a context for a serial input line. */
    public static TriggerContext serial(String input, DataStore.Device device) {
        return new TriggerContext(input, device, TriggerSource.SERIAL, null, null, nextSequence());
    }

    /**
     * Creates a context for an incoming WebSocket message.
     *
     * @param message    the message payload
     * @param channel    the URL path the client connected on (e.g. {@code /sensors/temp})
     * @param connection the originating connection, used by the {@code SEND_WEBSOCKET} action
     */
    public static TriggerContext webSocket(String message, String channel, WebSocket connection) {
        return new TriggerContext(message, null, TriggerSource.WEBSOCKET, channel, connection, nextSequence());
    }

    /** Creates a context fired when {@code device} successfully connects. */
    public static TriggerContext deviceConnected(DataStore.Device device) {
        return new TriggerContext(device.getName(), device, TriggerSource.DEVICE_CONNECTED, null, null, nextSequence());
    }

    /** Creates a context fired when {@code device} disconnects (expected or unexpected). */
    public static TriggerContext deviceDisconnected(DataStore.Device device) {
        return new TriggerContext(device.getName(), device, TriggerSource.DEVICE_DISCONNECTED, null, null, nextSequence());
    }

    /**
     * Sends a reply message back to the WebSocket connection that triggered this event.
     * No-op if this is not a WebSocket context or the connection is no longer open.
     *
     * @param message the text to send back to the client
     */
    public void reply(String message) {
        if (connection != null && connection.isOpen()) {
            connection.send(message);
        }
    }
}
