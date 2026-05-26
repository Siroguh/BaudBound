package fi.natroutter.baudbound.serial;

import com.fazecast.jSerialComm.SerialPort;
import fi.natroutter.baudbound.BaudBound;
import fi.natroutter.baudbound.enums.ConnectionStatus;
import fi.natroutter.baudbound.enums.FlowControl;
import fi.natroutter.baudbound.enums.Parity;
import fi.natroutter.baudbound.event.EventHandler;
import fi.natroutter.baudbound.event.TriggerContext;
import fi.natroutter.baudbound.storage.DataStore;
import fi.natroutter.foxlib.logger.FoxLogger;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Manages the serial port lifecycle for a single {@link DataStore.Device}: connecting, reading,
 * and disconnecting.
 * <p>
 * On connection a virtual-thread read loop ({@link #readLoop}) continuously polls the port,
 * accumulates bytes into a line buffer, and dispatches complete lines to
 * {@link EventHandler#process}. Disconnection from either end sets
 * {@code status} to {@link ConnectionStatus#NO_DEVICE} and, when the device's
 * {@code autoConnect} flag is set, starts a background retry loop
 * ({@link #startReconnectLoop}) that attempts to reconnect every 5 seconds.
 * <p>
 * {@code status} is {@code volatile} so that the GLFW render thread can read it safely
 * without synchronization.
 */
public class SerialHandler {

    private final FoxLogger logger = BaudBound.getLogger();
    private final EventHandler eventHandler = BaudBound.getEventHandler();

    private final DataStore.Device device;

    @Getter
    private volatile ConnectionStatus status = ConnectionStatus.DISCONNECTED;
    private volatile boolean shuttingDown = false;

    private SerialPort port;
    private Thread listenerThread;
    private volatile boolean reconnectLoopRunning = false;

    /**
     * Creates a handler for the given device. Auto-connect (if configured) is triggered
     * externally by {@link DeviceConnectionManager}.
     *
     * @param device the device configuration to use for this connection
     */
    public SerialHandler(DataStore.Device device) {
        this.device = device;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown = true;
            disconnect();
        }));
    }

    /**
     * Opens the configured serial port and starts the background read loop.
     * No-op if already connected. Sets {@code status} to {@link ConnectionStatus#NO_DEVICE}
     * or {@link ConnectionStatus#FAILED_TO_CONNECT} on failure.
     */
    public void connect() {
        if (status == ConnectionStatus.CONNECTED) return;

        if (device.getPort() == null || device.getPort().isBlank()) {
            status = ConnectionStatus.NO_DEVICE;
            logger.error("[" + device.getName() + "] No port configured.");
            return;
        }

        try {
            port = SerialPort.getCommPort(device.getPort());
        } catch (Exception e) {
            status = ConnectionStatus.NO_DEVICE;
            logger.error("[" + device.getName() + "] Invalid or unavailable port: " + device.getPort());
            if (!shuttingDown && device.isAutoReconnect()) startReconnectLoop();
            return;
        }
        port.setBaudRate(device.getBaudRate() > 0 ? device.getBaudRate() : 9600);
        port.setNumDataBits(device.getDataBits() > 0 ? device.getDataBits() : 8);
        port.setNumStopBits(device.getStopBits() > 0 ? device.getStopBits() : 1);

        if (device.getParity() != null) {
            try {
                Parity parity = Parity.valueOf(device.getParity());
                port.setParity(parity.getBit());
            } catch (IllegalArgumentException e) {
                logger.error("[" + device.getName() + "] Unknown parity value: " + device.getParity());
            }
        }

        if (device.getFlowControl() != null) {
            try {
                FlowControl flowControl = FlowControl.valueOf(device.getFlowControl());
                port.setFlowControl(flowControl.getBit());
            } catch (IllegalArgumentException e) {
                logger.error("[" + device.getName() + "] Unknown flow control value: " + device.getFlowControl());
            }
        }

        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        if (!port.openPort()) {
            status = ConnectionStatus.FAILED_TO_CONNECT;
            logger.error("[" + device.getName() + "] Failed to open port: " + device.getPort());
            if (!shuttingDown && device.isAutoReconnect()) startReconnectLoop();
            return;
        }

        status = ConnectionStatus.CONNECTED;
        logger.info("[" + device.getName() + "] Connected to " + device.getPort());
        Thread.ofVirtual().start(() ->
                BaudBound.getEventHandler().process(TriggerContext.deviceConnected(device)));

        listenerThread = Thread.ofVirtual().start(this::readLoop);
    }

    /**
     * Stops the read loop, closes the port, and sets status to
     * {@link ConnectionStatus#DISCONNECTED}. No-op if not connected.
     */
    public void disconnect() {
        if (status != ConnectionStatus.CONNECTED) return;

        status = ConnectionStatus.DISCONNECTED;

        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }

        if (port != null && port.isOpen()) {
            port.closePort();
            port = null;
        }

        logger.info("[" + device.getName() + "] Disconnected from serial port.");
        if (!shuttingDown) {
            Thread.ofVirtual().start(() ->
                    BaudBound.getEventHandler().process(TriggerContext.deviceDisconnected(device)));
        }
    }

    // -------------------------------------------------------------------------
    // Background read loop
    // -------------------------------------------------------------------------

    private void readLoop() {
        StringBuilder lineBuffer = new StringBuilder();
        byte[] buf = new byte[1024];

        while (status == ConnectionStatus.CONNECTED && !Thread.currentThread().isInterrupted()) {
            try {
                if (!port.isOpen()) {
                    handleUnexpectedDisconnect("Serial port closed unexpectedly — device disconnected.");
                    break;
                }

                int available = port.bytesAvailable();
                if (available < 0) {
                    handleUnexpectedDisconnect("Serial port read failed — device disconnected.");
                    break;
                }

                if (available == 0) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    continue;
                }

                int bytesRead = port.readBytes(buf, Math.min(available, buf.length));
                if (bytesRead <= 0) continue;

                lineBuffer.append(normalizeLineEndings(new String(buf, 0, bytesRead)));

                int newlineIndex;
                while ((newlineIndex = lineBuffer.indexOf("\n")) >= 0) {
                    String line = lineBuffer.substring(0, newlineIndex).strip();
                    lineBuffer.delete(0, newlineIndex + 1);
                    if (!line.isEmpty()) {
                        processCompleteLine(line);
                    }
                }
            } catch (Exception e) {
                if (!shuttingDown && status == ConnectionStatus.CONNECTED) {
                    logger.error("[" + device.getName() + "] Serial read error: " + e.getMessage());
                    status = ConnectionStatus.FAILED_TO_CONNECT;
                }
                break;
            }
        }

        cleanupPort();

        if (!shuttingDown && device.isAutoReconnect()
                && (status == ConnectionStatus.NO_DEVICE || status == ConnectionStatus.FAILED_TO_CONNECT)) {
            startReconnectLoop();
        }
    }

    /** Normalizes all line endings (\r\n, \r, \n) to \n so line splitting works regardless of device output. */
    private String normalizeLineEndings(String raw) {
        return raw.replace("\r\n", "\n").replace("\r", "\n");
    }

    private void processCompleteLine(String line) {
        logger.info("[" + device.getName() + "] Serial input: " + line);
        eventHandler.process(line, device);
    }

    private void handleUnexpectedDisconnect(String message) {
        if (!shuttingDown && status == ConnectionStatus.CONNECTED) {
            logger.error("[" + device.getName() + "] " + message);
            status = ConnectionStatus.NO_DEVICE;
            Thread.ofVirtual().start(() ->
                    BaudBound.getEventHandler().process(TriggerContext.deviceDisconnected(device)));
        }
    }

    private void cleanupPort() {
        if (port != null && port.isOpen()) {
            port.closePort();
        }
        port = null;
        listenerThread = null;
    }

    private void startReconnectLoop() {
        if (reconnectLoopRunning) return;
        reconnectLoopRunning = true;
        Thread.ofVirtual().start(() -> {
            try {
                long delayMs = device.getEffectiveReconnectDelay() * 1000L;
                int maxRetries = device.getEffectiveMaxRetries();
                int attempts = 0;
                logger.info("[" + device.getName() + "] Auto-reconnect enabled — retrying every "
                        + device.getEffectiveReconnectDelay() + " seconds"
                        + (maxRetries > 0 ? " up to " + maxRetries + " attempt(s)..." : "...") );
                while (status == ConnectionStatus.NO_DEVICE || status == ConnectionStatus.FAILED_TO_CONNECT) {
                    if (maxRetries > 0 && attempts >= maxRetries) {
                        status = ConnectionStatus.DISCONNECTED;
                        logger.warn("[" + device.getName() + "] Auto-reconnect stopped after "
                                + attempts + " failed attempt(s).");
                        break;
                    }
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (status != ConnectionStatus.NO_DEVICE && status != ConnectionStatus.FAILED_TO_CONNECT) break;
                    if (device.isVerifyDevice() && !isExpectedDevice()) {
                        logger.info("[" + device.getName() + "] Skipping reconnect — USB identity does not match.");
                        attempts++;
                        continue;
                    }
                    attempts++;
                    logger.info("[" + device.getName() + "] Attempting to reconnect"
                            + (maxRetries > 0 ? " (" + attempts + "/" + maxRetries + ")" : "") + "...");
                    connect();
                }
            } finally {
                reconnectLoopRunning = false;
            }
        });
    }

    /**
     * Returns {@code true} if the port currently listed by the OS for {@code device.getPort()}
     * matches the USB identity stored on the device.
     * <p>
     * Matches by serial number when one is stored; falls back to vendor + product ID pair when
     * the serial number is blank (common for cheap USB-Serial adapters).
     * Returns {@code false} if the port is not currently visible to the OS.
     */
    private boolean isExpectedDevice() {
        String portName = device.getPort();
        if (portName == null || portName.isBlank()) return false;
        for (SerialPort p : SerialPort.getCommPorts()) {
            if (!portName.equals(p.getSystemPortName())) continue;
            String storedSerial = device.getDeviceSerial();
            if (storedSerial != null && !storedSerial.isBlank()) {
                return storedSerial.equals(p.getSerialNumber());
            }
            return device.getDeviceVendorId() == p.getVendorID()
                    && device.getDeviceProductId() == p.getProductID();
        }
        return false;
    }

    /**
     * Writes {@code data} to the open serial port.
     * Returns {@code true} if all bytes were written successfully, {@code false} if the port
     * is not connected or the write fails.
     *
     * @param data the string to send; no line ending is appended automatically
     */
    public boolean send(String data) {
        if (status != ConnectionStatus.CONNECTED || port == null || !port.isOpen()) return false;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        return port.writeBytes(bytes, bytes.length) == bytes.length;
    }

    /**
     * Returns all serial ports currently visible to the OS.
     *
     * @return a list of available ports, or an empty list if none are found
     */
    public static List<SerialPort> getAvailablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        return ports.length == 0 ? List.of() : Arrays.asList(ports);
    }
}
