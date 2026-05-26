package fi.natroutter.baudbound.event;

import fi.natroutter.baudbound.BaudBound;
import fi.natroutter.baudbound.enums.ActionType;
import fi.natroutter.baudbound.enums.ConditionType;
import fi.natroutter.baudbound.http.HttpHandler;
import fi.natroutter.baudbound.serial.DeviceConnectionManager;
import fi.natroutter.baudbound.storage.DataStore;
import fi.natroutter.baudbound.storage.StorageProvider;
import fi.natroutter.foxlib.FoxLib;
import fi.natroutter.foxlib.logger.FoxLogger;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;

/**
 * Processes a single line of serial input against the configured event list and fires
 * the matching actions.
 * <p>
 * Each call to {@link #process} evaluates every enabled event in order, checks all of
 * its conditions against {@code input}, and dispatches each action on a virtual thread
 * so that slow actions (HTTP, file I/O, audio) never block the serial read loop.
 * <p>
 * Variable substitution ({@code {input}}, {@code {timestamp}}) is applied to action
 * values immediately before execution via {@link #resolve}.
 */
public class EventHandler {

    private final FoxLogger logger = BaudBound.getLogger();
    private final StorageProvider storage = BaudBound.getStorageProvider();
    private final DeviceConnectionManager deviceConnectionManager = BaudBound.getDeviceConnectionManager();

    /**
     * Named state map. Keys are state names; the special name {@value DEFAULT_STATE} is
     * used when no explicit name is provided. Written synchronously on the serial thread
     * so every state change is visible to the very next incoming line.
     * <p>
     * Format for action/condition values:
     * <ul>
     *   <li>{@code SET_STATE} — {@code "value"} (default state) or {@code "name|value"}</li>
     *   <li>{@code CLEAR_STATE} — blank (default state) or {@code "name"}</li>
     *   <li>{@code STATE_EQUALS} — {@code "value"} (default) or {@code "name|value"}</li>
     *   <li>{@code STATE_IS_EMPTY} — blank (default) or {@code "name"}</li>
     * </ul>
     */
    private static final String DEFAULT_STATE = "default";
    private final Map<String, String> states = new ConcurrentHashMap<>();

    /** Cache of compiled regex patterns keyed by pattern string to avoid recompilation on every condition check. */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    /** Cache of the condition-first sorted event list. Invalidated via {@link #invalidateSortCache()}. */
    private volatile List<DataStore.Event> sortedEventsCache = null;

    /** Tracks all currently playing {@link Clip} instances for bulk stop support. */
    private final Set<Clip> activeClips = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Returns an unmodifiable snapshot of the current state map for display purposes.
     * Keys are state names; values are the current state values.
     */
    public Map<String, String> getStates() {
        return Map.copyOf(states);
    }

    /**
     * Sets the named state to the given value. Safe to call from any thread.
     *
     * @param name  the state name; must not be blank
     * @param value the value to assign
     */
    public void setState(String name, String value) {
        states.put(name, value);
    }

    /**
     * Stops and closes all currently playing sound clips.
     * Safe to call from any thread.
     */
    public void stopAllSounds() {
        for (Clip clip : activeClips) {
            clip.stop();
        }
        logger.info("Stopped all playing sounds");
    }

    /**
     * Removes the named state entry. If {@code name} is blank, clears the default state.
     * Safe to call from the GLFW thread since {@link ConcurrentHashMap} handles concurrent access.
     *
     * @param name the state name to clear, or blank for the default state
     */
    public void clearState(String name) {
        states.remove(name == null || name.isBlank() ? DEFAULT_STATE : name.trim());
    }

    /** Removes all state entries. */
    public void clearAllStates() {
        states.clear();
    }

    /**
     * Invalidates the cached condition-first sorted event list.
     * Must be called whenever the event list is modified (add, edit, remove, reorder).
     */
    public void invalidateSortCache() {
        sortedEventsCache = null;
    }

    /**
     * Convenience overload for serial input — wraps the call in a {@link TriggerContext}.
     *
     * @param input  the trimmed line read from the serial port
     * @param device the device that produced this input
     */
    public void process(String input, DataStore.Device device) {
        process(TriggerContext.serial(input, device));
    }

    /**
     * Evaluates all configured events against the trigger context and fires matching actions.
     * Events are filtered by their configured trigger sources before condition matching.
     * Respects the {@code runFirstOnly}, {@code conditionEventsFirst}, and
     * {@code skipEmptyConditions} settings from {@link DataStore.Settings.Event}.
     *
     * @param context the trigger context carrying the input payload, source, and optional device
     */
    public void process(TriggerContext context) {
        String input  = context.input();
        DataStore.Device device = context.device();

        DataStore data = storage.getData();
        DataStore.Settings.Event eventSettings = data.getSettings().getEvent();
        boolean runFirstOnly = eventSettings.isRunFirstOnly();

        List<DataStore.Event> events = data.getEvents();
        if (eventSettings.isConditionEventsFirst()) {
            if (sortedEventsCache == null) {
                sortedEventsCache = events.stream()
                        .sorted((a, b) -> {
                            boolean aHas = a.getConditions() != null && !a.getConditions().isEmpty();
                            boolean bHas = b.getConditions() != null && !b.getConditions().isEmpty();
                            return Boolean.compare(bHas, aHas); // events with conditions first
                        })
                        .toList();
            }
            events = sortedEventsCache;
        }

        boolean skipEmpty = eventSettings.isSkipEmptyConditions();
        List<String> firedNames = new java.util.ArrayList<>();

        for (DataStore.Event event : events) {
            boolean allowed = event.getEffectiveTriggerSources().stream()
                    .anyMatch(s -> context.source().name().equalsIgnoreCase(s));
            if (!allowed) continue;

            boolean hasConditions = event.getConditions() != null && !event.getConditions().isEmpty();
            if (skipEmpty && !hasConditions) continue;

            if (matchesConditions(event, context)) {
                fireAction(event, context);
                firedNames.add(event.getName());
                if (runFirstOnly) break;
            }
        }

        String tag = "[" + context.source().name() + "]"
                + (device != null ? " (device: " + device.getName() + ")" : "");
        if (firedNames.isEmpty()) {
            if (!events.isEmpty()) {
                logger.warn(tag + " No events matched input: \"" + input + "\"");
            }
        } else {
            logger.info(tag + " Input: \"" + input + "\" — fired " + firedNames.size()
                    + " event(s): " + String.join(", ", firedNames));
        }
    }

    /**
     * Returns {@code true} if every condition in the event is satisfied by the trigger context.
     * An event with no conditions always matches.
     */
    private boolean matchesConditions(DataStore.Event event, TriggerContext context) {
        String input  = context.input();
        DataStore.Device device = context.device();
        String channel = context.channel() != null ? context.channel() : "";

        List<DataStore.Event.Condition> conditions = event.getConditions();
        if (conditions == null || conditions.isEmpty()) return true;

        for (DataStore.Event.Condition condition : conditions) {
            ConditionType type = ConditionType.getByName(condition.getType());
            String value = condition.getValue();
            if (type == null) continue;
            if (type != ConditionType.INPUT_IS_NUMERIC && type != ConditionType.STATE_IS_NUMERIC && value == null) continue;

            boolean caseSensitive = condition.isCaseSensitive();
            String  normalizedInput = caseSensitive ? input : input.toLowerCase();
            String  normalizedValue = caseSensitive ? value : value.toLowerCase();

            boolean matches = switch (type) {
                case INPUT_STARTS_WITH -> normalizedInput.startsWith(normalizedValue);
                case INPUT_ENDS_WITH -> normalizedInput.endsWith(normalizedValue);
                case INPUT_CONTAINS -> normalizedInput.contains(normalizedValue);
                case INPUT_NOT_CONTAINS -> !normalizedInput.contains(normalizedValue);
                case INPUT_NOT_STARTS_WITH -> !normalizedInput.startsWith(normalizedValue);
                case INPUT_EQUALS -> normalizedInput.equals(normalizedValue);
                case INPUT_REGEX -> patternCache.computeIfAbsent(value, Pattern::compile).matcher(input).matches();
                case INPUT_IS_NUMERIC -> isNumeric(input);
                case INPUT_GREATER_THAN -> compareNumeric(input, value) > 0;
                case INPUT_LESS_THAN -> compareNumeric(input, value) < 0;
                case INPUT_BETWEEN -> isBetween(input, value);
                case INPUT_LENGTH_EQUALS -> parseLengthEquals(input, value);
                case STATE_EQUALS, STATE_NOT_EQUALS -> {
                    String[] p = value.split("\\|", 2);
                    boolean eq = p.length == 2
                            ? p[1].equals(states.get(p[0].trim()))
                            : value.equals(states.get(DEFAULT_STATE));
                    yield type == ConditionType.STATE_EQUALS ? eq : !eq;
                }
                case STATE_IS_EMPTY -> {
                    String name = value.isBlank() ? DEFAULT_STATE : value.trim();
                    String v = states.get(name);
                    yield v == null || v.isBlank();
                }
                case DEVICE_EQUALS, DEVICE_NOT_EQUALS -> {
                    if (device == null || device.getName() == null) yield false;
                    boolean matched = false;
                    for (String part : value.split(",")) {
                        if (part.trim().equalsIgnoreCase(device.getName())) { matched = true; break; }
                    }
                    yield type == ConditionType.DEVICE_EQUALS ? matched : !matched;
                }
                case WEBSOCKET_HAS_PARAM -> {
                    Map<String, String> params = parseWsParams(input);
                    yield params.containsKey(value.trim());
                }
                case WEBSOCKET_PARAM_EQUALS, WEBSOCKET_PARAM_NOT_EQUALS, WEBSOCKET_PARAM_CONTAINS,
                     WEBSOCKET_PARAM_STARTS_WITH, WEBSOCKET_PARAM_ENDS_WITH -> {
                    String[] p = value.split("\\|", 2);
                    if (p.length < 2) yield false;
                    Map<String, String> params = parseWsParams(input);
                    String paramVal = params.get(p[0].trim());
                    if (paramVal == null) yield false;
                    String pv = caseSensitive ? paramVal : paramVal.toLowerCase();
                    String cv = caseSensitive ? p[1] : p[1].toLowerCase();
                    yield switch (type) {
                        case WEBSOCKET_PARAM_EQUALS       -> pv.equals(cv);
                        case WEBSOCKET_PARAM_NOT_EQUALS   -> !pv.equals(cv);
                        case WEBSOCKET_PARAM_CONTAINS     -> pv.contains(cv);
                        case WEBSOCKET_PARAM_STARTS_WITH  -> pv.startsWith(cv);
                        case WEBSOCKET_PARAM_ENDS_WITH    -> pv.endsWith(cv);
                        default -> false;
                    };
                }
                case WEBSOCKET_CHANNEL_EQUALS -> {
                    String nch = caseSensitive ? channel : channel.toLowerCase();
                    yield nch.equals(normalizedValue);
                }
                case WEBSOCKET_CHANNEL_STARTS_WITH -> {
                    String nch = caseSensitive ? channel : channel.toLowerCase();
                    yield nch.startsWith(normalizedValue);
                }
                case WEBSOCKET_CHANNEL_CONTAINS -> {
                    String nch = caseSensitive ? channel : channel.toLowerCase();
                    yield nch.contains(normalizedValue);
                }
                case WEBSOCKET_CHANNEL_NOT_EQUALS -> {
                    String nch = caseSensitive ? channel : channel.toLowerCase();
                    yield !nch.equals(normalizedValue);
                }
                case STATE_IS_NUMERIC -> {
                    String sname = (value == null || value.isBlank()) ? DEFAULT_STATE : value.trim();
                    String sv = states.get(sname);
                    yield sv != null && isNumeric(sv);
                }
                case STATE_LESS_THAN, STATE_GREATER_THAN, STATE_BETWEEN -> {
                    String[] p = value.split("\\|", 2);
                    String sname = p.length == 2 ? p[0].trim() : DEFAULT_STATE;
                    String threshold = p.length == 2 ? p[1] : value;
                    String sv = states.get(sname);
                    if (sv == null || !isNumeric(sv)) yield false;
                    yield switch (type) {
                        case STATE_LESS_THAN    -> compareNumeric(sv, threshold) < 0;
                        case STATE_GREATER_THAN -> compareNumeric(sv, threshold) > 0;
                        case STATE_BETWEEN      -> isBetween(sv, threshold);
                        default -> false;
                    };
                }
            };

            if (!matches) return false;
        }
        return true;
    }

    private void fireAction(DataStore.Event event, TriggerContext context) {
        if (event.getActions() == null || event.getActions().isEmpty()) return;

        String eventName = event.getName();

        for (DataStore.Event.Action action : event.getActions()) {
            ActionType type = ActionType.getByName(action.getType());
            if (type == null) continue;
            String value = action.getValue();

            if (type == ActionType.SET_STATE) {
                String[] p = value != null ? value.split("\\|", 2) : new String[]{""};
                if (p.length == 2) {
                    String resolved = resolve(p[1], context, eventName);
                    states.put(p[0].trim(), resolved);
                    logger.info("State \"" + p[0].trim() + "\" set to: \"" + resolved + "\"");
                } else {
                    String resolved = resolve(value != null ? value : "", context, eventName);
                    states.put(DEFAULT_STATE, resolved);
                    logger.info("State \"" + DEFAULT_STATE + "\" set to: \"" + resolved + "\"");
                }
                continue;
            }
            if (type == ActionType.CLEAR_STATE) {
                String name = (value != null && !value.isBlank())
                        ? resolve(value.trim(), context, eventName) : DEFAULT_STATE;
                states.remove(name);
                logger.info("State \"" + name + "\" cleared");
                continue;
            }

            Thread.ofVirtual().start(() -> {
                try {
                    switch (type) {
                        case CALL_WEBHOOK      -> callWebhook(value, context, eventName);
                        case OPEN_URL          -> openUrl(value, context, eventName);
                        case OPEN_PROGRAM      -> openProgram(value, context, eventName);
                        case TYPE_TEXT         -> typeText(value, context, eventName);
                        case COPY_TO_CLIPBOARD -> copyToClipboard(value, context, eventName);
                        case SHOW_NOTIFICATION -> showNotification(value, context, eventName);
                        case WRITE_TO_FILE     -> writeToFile(value, context, eventName);
                        case APPEND_TO_FILE    -> appendToFile(value, context, eventName);
                        case PLAY_SOUND        -> playSound(value);
                        case SEND_TO_DEVICE    -> sendToDevice(value, context, eventName);
                        case SEND_WEBSOCKET    -> sendWebSocket(value, context, eventName);
                        case RUN_COMMAND       -> runCommand(value, context, eventName);
                        default                -> logger.error("Unhandled action type: " + type);
                    }
                } catch (Exception e) {
                    logger.error("Action [" + type + "] failed for event \"" + eventName + "\": " + e.getMessage());
                }
            });
        }
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void callWebhook(String webhookName, TriggerContext context, String eventName) {
        if (webhookName == null) return;

        storage.getData().getActions().getWebhooks().stream()
                .filter(w -> w.getName().equals(webhookName))
                .findFirst()
                .ifPresentOrElse(webhook -> {
                    String deliveryId = webhook.isDurableDelivery() ? UUID.randomUUID().toString() : null;
                    DataStore.Actions.Webhook resolved = resolveWebhook(webhook, context, eventName, deliveryId);
                    if (webhook.isDurableDelivery()) {
                        BaudBound.getWebhookDeliveryQueue().enqueue(deliveryId, resolved, eventName);
                        return;
                    }
                    HttpHandler.WebhookResult result = HttpHandler.fireWebhook(resolved);
                    if (result.success()) {
                        logger.info("Webhook \"" + webhookName + "\" responded " + result.statusCode());
                    } else {
                        logger.error("Webhook \"" + webhookName + "\" failed: " +
                                (result.error() != null ? result.error() : result.statusCode()));
                    }
                }, () -> logger.error("Webhook not found: " + webhookName));
    }

    private void openUrl(String url, TriggerContext context, String eventName) throws IOException {
        url = resolve(url, context, eventName);
        if (url == null || url.isBlank()) return;
        FoxLib.openURL(url);
        logger.info("Opened URL: " + url);
    }

    private void openProgram(String programName, TriggerContext context, String eventName) throws IOException {
        if (programName == null) return;

        DataStore.Actions.Program program = storage.getData().getActions().getPrograms().stream()
                .filter(p -> p.getName().equals(programName))
                .findFirst().orElse(null);

        if (program == null) {
            logger.error("Program not found: " + programName);
            return;
        }

        String path = resolve(program.getPath(), context, eventName);
        String args = resolve(program.getArguments(), context, eventName);
        launchProgram(path, args, program.isRunAsAdmin());
        String adminTag = program.isRunAsAdmin() ? " [admin]" : "";
        String argsTag  = (args != null && !args.isBlank()) ? " args=\"" + args + "\"" : "";
        logger.info("Launched program: " + programName + argsTag + adminTag);
    }

    /**
     * Launches an external program directly without going through event processing or storage lookup.
     * Intended for ad-hoc use such as the "Test" button in the program editor.
     *
     * @param path       absolute path to the executable
     * @param args       argument string, or {@code null}/blank for none
     * @param runAsAdmin when {@code true}, launches via PowerShell {@code Start-Process … -Verb RunAs}
     */
    public void launchProgram(String path, String args, boolean runAsAdmin) throws IOException {
        ProcessBuilder pb;
        if (runAsAdmin) {
            pb = (args != null && !args.isBlank())
                    ? new ProcessBuilder("powershell", "-Command", "Start-Process", "\"" + path + "\"", "-ArgumentList", "\"" + args + "\"", "-Verb", "RunAs")
                    : new ProcessBuilder("powershell", "-Command", "Start-Process", "\"" + path + "\"", "-Verb", "RunAs");
        } else {
            pb = (args != null && !args.isBlank())
                    ? new ProcessBuilder(path, args)
                    : new ProcessBuilder(path);
        }
        pb.start();
    }

    /**
     * Executes {@code command} in the OS shell after resolving variable substitutions.
     * Uses {@code cmd.exe /c} on Windows and {@code sh -c} on all other platforms.
     */
    private void runCommand(String command, TriggerContext context, String eventName) throws IOException {
        command = resolve(command, context, eventName);
        if (command == null || command.isBlank()) return;
        ProcessBuilder pb = isWindows()
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        pb.start();
        logger.info("Ran command: " + command);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void typeText(String text, TriggerContext context, String eventName) throws AWTException {
        text = resolve(text, context, eventName);
        if (text == null || text.isBlank()) return;

        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);

        Robot robot = new Robot();
        robot.delay(50);
        robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
        robot.keyPress(java.awt.event.KeyEvent.VK_V);
        robot.keyRelease(java.awt.event.KeyEvent.VK_V);
        robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);

        logger.info("Typed text: " + text);
    }

    private void copyToClipboard(String text, TriggerContext context, String eventName) {
        text = resolve(text, context, eventName);
        if (text == null || text.isBlank()) return;
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        logger.info("Copied to clipboard: " + text);
    }

    /**
     * Value format: "message" or "TYPE|message"
     * TYPE is one of: INFO (default), WARNING, ERROR, NONE
     * Example: WARNING|Sensor value out of range: {input}
     */
    private void showNotification(String value, TriggerContext context, String eventName) {
        if (value == null || value.isBlank()) return;
        String[] parts = value.split("\\|", 2);
        TrayIcon.MessageType type = TrayIcon.MessageType.INFO;
        String message;
        if (parts.length == 2) {
            try { type = TrayIcon.MessageType.valueOf(parts[0].trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
            message = resolve(parts[1], context, eventName);
        } else {
            message = resolve(parts[0], context, eventName);
        }
        if (message == null || message.isBlank()) return;
        BaudBound.showNotification(BaudBound.APP_NAME, message, type);
        logger.info("Showed notification [" + type + "]: " + message);
    }

    /**
     * Value format: "path" or "path|content template"
     * If no content template is provided, defaults to "{timestamp}: {input}".
     * Both path and content support all variable substitutions.
     * Overwrites the file on each call.
     */
    private void writeToFile(String value, TriggerContext context, String eventName) throws IOException {
        if (value == null || value.isBlank()) return;
        String[] parts = value.split("\\|", 2);
        String filePath = resolve(parts[0].trim(), context, eventName);
        String contentTemplate = parts.length == 2 ? parts[1] : "{timestamp}: {input}";
        String content = resolve(contentTemplate, context, eventName) + System.lineSeparator();
        Files.writeString(Path.of(filePath), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Wrote to file: " + filePath);
    }

    /**
     * Same format as writeToFile but appends instead of overwriting.
     */
    private void appendToFile(String value, TriggerContext context, String eventName) throws IOException {
        if (value == null || value.isBlank()) return;
        String[] parts = value.split("\\|", 2);
        String filePath = resolve(parts[0].trim(), context, eventName);
        String contentTemplate = parts.length == 2 ? parts[1] : "{timestamp}: {input}";
        String content = resolve(contentTemplate, context, eventName) + System.lineSeparator();
        Files.writeString(Path.of(filePath), content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        logger.info("Appended to file: " + filePath);
    }

    private void playSound(String filePath) throws Exception {
        if (filePath == null || filePath.isBlank()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        File file = new File(filePath);
        if (!file.exists()) {
            logger.error("Sound file not found: " + filePath + " — falling back to system beep.");
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        AudioInputStream stream = AudioSystem.getAudioInputStream(file);
        Clip clip = AudioSystem.getClip();
        clip.open(stream);
        activeClips.add(clip);
        clip.addLineListener(e -> {
            if (e.getType() == LineEvent.Type.STOP) {
                activeClips.remove(clip);
                clip.close();
            }
        });
        clip.start();
        logger.info("Playing sound: " + filePath);
    }

    /**
     * Value format: {@code "deviceName|data"}.
     * Resolves all variable tokens in the data portion before sending.
     */
    private void sendToDevice(String value, TriggerContext context, String eventName) {
        if (value == null || value.isBlank()) return;
        String[] parts = value.split("\\|", 2);
        if (parts.length < 2) {
            logger.error("Send to Device: value must be 'deviceName|data'.");
            return;
        }
        String deviceName = parts[0].trim();
        String data = resolve(parts[1], context, eventName);
        boolean sent = deviceConnectionManager.sendToDevice(deviceName, data);
        if (sent) {
            logger.info("Sent to device \"" + deviceName + "\": " + data);
        } else {
            logger.error("Send to Device: device \"" + deviceName + "\" is not connected or not found.");
        }
    }

    /**
     * Sends a WebSocket message. Value format: {@code "targetChannel|message"} or just
     * {@code "message"} to reply on the trigger's own channel.
     */
    private void sendWebSocket(String value, TriggerContext context, String eventName) {
        if (value == null || value.isBlank()) {
            logger.warn("Send WebSocket: message is empty — skipping.");
            return;
        }
        String channel = context.channel() != null ? context.channel() : "";
        String[] parts = value.split("\\|", 2);
        if (parts.length == 2) {
            String targetChannel = resolve(parts[0].trim(), context, eventName);
            String message       = resolve(parts[1], context, eventName);
            fi.natroutter.baudbound.websocket.WebSocketHandler handler = BaudBound.getWebSocketHandler();
            if (handler == null || !handler.isRunning()) {
                logger.warn("Send WebSocket: server is not running.");
                return;
            }
            handler.sendToChannel(targetChannel, message);
            logger.info("WebSocket sent to channel \"" + targetChannel + "\": \"" + message + "\"");
        } else {
            String message = resolve(value, context, eventName);
            context.reply(message);
            logger.info("WebSocket reply sent on channel \"" + (channel.isBlank() ? "/" : channel) + "\": \"" + message + "\"");
        }
    }

    // -------------------------------------------------------------------------
    // Condition helpers
    // -------------------------------------------------------------------------

    /**
     * Parses a URL-style query string ({@code key=value&key2=value2}) into a map.
     * Keys and values are trimmed. Parameters without {@code =} are stored with an empty value.
     */
    private Map<String, String> parseWsParams(String input) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : input.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0].trim(), kv[1].trim());
            } else if (kv.length == 1 && !kv[0].isBlank()) {
                params.put(kv[0].trim(), "");
            }
        }
        return params;
    }

    private boolean isNumeric(String input) {
        try { Double.parseDouble(input.trim()); return true; }
        catch (NumberFormatException e) { return false; }
    }

    /** Returns Double.compare(input, value), or 0 if either is non-numeric. */
    private int compareNumeric(String input, String value) {
        try {
            return Double.compare(Double.parseDouble(input.trim()), Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) { return 0; }
    }

    /** value format: "min,max" (inclusive on both ends) */
    private boolean isBetween(String input, String value) {
        String[] parts = value.split(",", 2);
        if (parts.length != 2) return false;
        try {
            double val = Double.parseDouble(input.trim());
            double min = Double.parseDouble(parts[0].trim());
            double max = Double.parseDouble(parts[1].trim());
            return val >= min && val <= max;
        } catch (NumberFormatException e) { return false; }
    }

    private boolean parseLengthEquals(String input, String value) {
        try { return input.length() == Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return false; }
    }

    // -------------------------------------------------------------------------
    // Variable substitution
    // -------------------------------------------------------------------------

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter TIME_ONLY_FORMAT  = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Resolves all supported variable tokens in {@code template} using the full trigger context.
     * <p>Supported tokens:
     * <ul>
     *   <li><b>Input:</b> {@code {input}}, {@code {input.raw}}, {@code {input.upper}}, {@code {input.lower}},
     *       {@code {input.trim}}, {@code {input.length}}, {@code {input.word[N]}},
     *       {@code {input.line[N]}}, {@code {input.replace[old|new]}}, {@code {input.urlencoded}}</li>
     *   <li><b>Date/time:</b> {@code {timestamp}}, {@code {timestamp.unix}}, {@code {date}},
     *       {@code {time}}, {@code {year}}, {@code {month}}, {@code {day}},
     *       {@code {hour}}, {@code {minute}}, {@code {second}}</li>
     *   <li><b>Trigger:</b> {@code {source}}, {@code {channel}}, {@code {event}},
     *       {@code {delivery.id}} for durable webhook templates</li>
     *   <li><b>Device:</b> {@code {device}}, {@code {device.port}}, {@code {device.baud}}</li>
     *   <li><b>States:</b> {@code {state}}, {@code {state[name]}}</li>
     *   <li><b>System:</b> {@code {hostname}}, {@code {username}}, {@code {env[VAR]}},
     *       {@code {uuid}}, {@code {random[min,max]}}</li>
     * </ul>
     *
     * @param template  the template string; may be {@code null}
     * @param context   the trigger context carrying input, device, source, and channel
     * @param eventName the name of the event that fired
     * @return the resolved string, or {@code null} if {@code template} is {@code null}
     */
    private String resolve(String template, TriggerContext context, String eventName) {
        if (template == null) return null;
        String input   = context.input();
        String channel = context.channel() != null ? context.channel() : "";
        DataStore.Device device = context.device();

        String result = template;

        // ---- Date / time (computed once) ----
        LocalDateTime now       = LocalDateTime.now();
        long          unixSecs  = Instant.now().getEpochSecond();
        result = result
                .replace("{timestamp}",      now.format(TIMESTAMP_FORMAT))
                .replace("{timestamp.unix}", String.valueOf(unixSecs))
                .replace("{date}",           now.toLocalDate().toString())
                .replace("{time}",           now.toLocalTime().format(TIME_ONLY_FORMAT))
                .replace("{year}",           String.format("%04d", now.getYear()))
                .replace("{month}",          String.format("%02d", now.getMonthValue()))
                .replace("{day}",            String.format("%02d", now.getDayOfMonth()))
                .replace("{hour}",           String.format("%02d", now.getHour()))
                .replace("{minute}",         String.format("%02d", now.getMinute()))
                .replace("{second}",         String.format("%02d", now.getSecond()));

        // ---- Input transforms (specific first, {input} last to avoid partial matches) ----
        result = result
                .replace("{input.upper}",      input.toUpperCase())
                .replace("{input.lower}",      input.toLowerCase())
                .replace("{input.trim}",       input.trim())
                .replace("{input.length}",     String.valueOf(input.length()))
                .replace("{input.urlencoded}", URLEncoder.encode(input, StandardCharsets.UTF_8));
        result = replaceIndexedToken(result, "input\\.word", input.trim().split("\\s+", -1));
        result = replaceIndexedToken(result, "input\\.line", input.split("\n", -1));
        result = replaceInputReplace(result, input);
        result = result.replace("{input}", input);

        // ---- Trigger / event ----
        result = result
                .replace("{source}",  context.source().name())
                .replace("{channel}", channel)
                .replace("{event}",   eventName != null ? eventName : "")
                .replace("{sequence}", String.valueOf(context.sequence()));

        // ---- Device (empty string when no device context) ----
        String devName = device != null && device.getName() != null ? device.getName() : "";
        String devPort = device != null && device.getPort() != null ? device.getPort() : "";
        String devBaud = device != null ? String.valueOf(device.getBaudRate()) : "";
        result = result
                .replace("{device}",      devName)
                .replace("{device.port}", devPort)
                .replace("{device.baud}", devBaud);

        // ---- States ----
        result = result.replace("{state}", states.getOrDefault(DEFAULT_STATE, ""));
        result = replaceStateToken(result);

        // ---- System ----
        result = result
                .replace("{hostname}", getHostname())
                .replace("{username}", System.getProperty("user.name", ""))
                .replace("{uuid}",     UUID.randomUUID().toString());
        result = replaceEnvToken(result);
        result = replaceRandomToken(result);

        return result;
    }

    /** Replaces {@code {prefix[N]}} tokens using a pre-split array (0-indexed, out-of-range = empty). */
    private static String replaceIndexedToken(String text, String tokenPattern, String[] parts) {
        return Pattern.compile("\\{" + tokenPattern + "\\[(\\d+)\\]\\}")
                .matcher(text)
                .replaceAll(m -> {
                    int idx = Integer.parseInt(m.group(1));
                    return (idx >= 0 && idx < parts.length)
                            ? Matcher.quoteReplacement(parts[idx]) : "";
                });
    }

    /** Replaces {@code {input.replace[old|new]}} tokens. */
    private static String replaceInputReplace(String text, String input) {
        return Pattern.compile("\\{input\\.replace\\[([^|\\]]*?)\\|([^\\]]*?)\\]\\}")
                .matcher(text)
                .replaceAll(m -> Matcher.quoteReplacement(
                        input.replace(m.group(1), m.group(2))));
    }

    /** Replaces {@code {state[name]}} tokens with their current state values. */
    private String replaceStateToken(String text) {
        return Pattern.compile("\\{state\\[([^\\]]+)\\]\\}")
                .matcher(text)
                .replaceAll(m -> Matcher.quoteReplacement(
                        states.getOrDefault(m.group(1).trim(), "")));
    }

    /** Replaces {@code {env[VAR]}} tokens with OS environment variable values. */
    private static String replaceEnvToken(String text) {
        return Pattern.compile("\\{env\\[([^\\]]+)\\]\\}")
                .matcher(text)
                .replaceAll(m -> {
                    String val = System.getenv(m.group(1).trim());
                    return Matcher.quoteReplacement(val != null ? val : "");
                });
    }

    /** Replaces {@code {random[min,max]}} tokens with a random integer in the inclusive range. */
    private static String replaceRandomToken(String text) {
        return Pattern.compile("\\{random\\[(\\d+),(\\d+)\\]\\}")
                .matcher(text)
                .replaceAll(m -> {
                    int min = Integer.parseInt(m.group(1));
                    int max = Integer.parseInt(m.group(2));
                    if (min > max) return m.group(0);
                    return String.valueOf(ThreadLocalRandom.current().nextInt(min, max + 1));
                });
    }

    private static String getHostname() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return ""; }
    }

    private DataStore.Actions.Webhook resolveWebhook(DataStore.Actions.Webhook original, TriggerContext context, String eventName, String deliveryId) {
        String rawInput = context.input();
        String input = preprocessInput(original, context.input());
        String resolvedInput = original.isUrlEscape()
                ? URLEncoder.encode(input, StandardCharsets.UTF_8)
                : input;

        TriggerContext resolveCtx = new TriggerContext(resolvedInput, context.device(), context.source(), context.channel(), context.connection(), context.sequence());

        List<DataStore.Actions.Webhook.Header> resolvedHeaders = new java.util.ArrayList<>(original.getHeaders() == null ? List.of() :
                original.getHeaders().stream()
                        .map(h -> new DataStore.Actions.Webhook.Header(h.getKey(), resolve(h.getValue(), resolveCtx, eventName, deliveryId, rawInput)))
                        .toList());
        resolvedHeaders.add(new DataStore.Actions.Webhook.Header("X-BaudBound-Sequence", String.valueOf(context.sequence())));

        return new DataStore.Actions.Webhook(
                original.getName(),
                resolve(original.getUrl(), resolveCtx, eventName, deliveryId, rawInput),
                original.getMethod(),
                resolvedHeaders,
                resolve(original.getBody(), resolveCtx, eventName, deliveryId, rawInput),
                original.isUrlEscape(),
                original.isDurableDelivery(),
                original.getMaxAttempts(),
                original.getRetryInitialMs(),
                original.getRetryMaxMs(),
                original.getAckBodyContains(),
                original.getAckHeaderName(),
                original.getAckHeaderValue(),
                original.getInputRegex(),
                original.getInputReplacement()
        );
    }

    private String resolve(String template, TriggerContext context, String eventName, String deliveryId) {
        String resolved = resolve(template, context, eventName);
        if (resolved == null) return null;
        return resolved.replace("{delivery.id}", deliveryId != null ? deliveryId : "");
    }

    private String resolve(String template, TriggerContext context, String eventName, String deliveryId, String rawInput) {
        String resolved = resolve(template, context, eventName, deliveryId);
        if (resolved == null) return null;
        return resolved.replace("{input.raw}", rawInput != null ? rawInput : "");
    }

    private String preprocessInput(DataStore.Actions.Webhook webhook, String input) {
        String regex = webhook.getInputRegex();
        if (regex == null || regex.isBlank()) return input;
        try {
            String replacement = webhook.getInputReplacement();
            if (replacement == null) replacement = "$1";
            Matcher matcher = Pattern.compile(regex).matcher(input);
            return matcher.find() ? matcher.replaceFirst(replacement) : input;
        } catch (Exception e) {
            logger.error("Webhook \"" + webhook.getName() + "\" input preprocessing failed: " + e.getMessage());
            return input;
        }
    }

}
