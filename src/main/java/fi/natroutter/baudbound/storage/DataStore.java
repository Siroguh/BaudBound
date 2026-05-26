package fi.natroutter.baudbound.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import fi.natroutter.baudbound.enums.TriggerSource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Root JSON model for all persisted application state.
 * <p>
 * The object graph mirrors the {@code storage.json} structure exactly:
 * <ul>
 *   <li>{@link Settings} — generic and event-processing configuration</li>
 *   <li>{@link Actions} — saved webhook and program definitions</li>
 *   <li>devices list — ordered list of named {@link Device} entries</li>
 *   <li>events list — ordered list of named {@link Event} entries</li>
 * </ul>
 * Serialization is handled by {@link #fromJson} / {@link #toJson}; all field names
 * are mapped via {@code @SerializedName} so that renaming Java fields won't break
 * existing config files.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataStore {

    @SerializedName("settings")
    private Settings settings = new Settings();

    @SerializedName("actions")
    private Actions actions = new Actions();

    @SerializedName("devices")
    private List<Device> devices = new ArrayList<>();

    @SerializedName("events")
    private List<Event> events = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Settings {

        @SerializedName("generic")
        private Generic generic = new Generic();

        @SerializedName("event")
        private Event event = new Event();

        @SerializedName("graphics")
        private Graphics graphics = new Graphics();

        @SerializedName("debug")
        private Debug debug = new Debug();

        @SerializedName("websocket")
        private WebSocket webSocket = new WebSocket();

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Generic {

            @SerializedName("start_hidden")
            private boolean startHidden;

            /**
             * Whether the background update checker is enabled.
             * Stored as a boxed {@link Boolean} so that a missing field in an existing config
             * file deserializes to {@code null} rather than primitive {@code false}, allowing
             * callers to distinguish "never set" (default-on) from "explicitly disabled".
             * Use {@link #isCheckForUpdatesEnabled()} for the effective value.
             */
            @SerializedName("check_for_updates")
            private Boolean checkForUpdates;

            /**
             * Returns the effective check-for-updates setting: {@code true} when the field
             * has never been written ({@code null}) or when it is explicitly enabled.
             */
            public boolean isCheckForUpdatesEnabled() {
                return checkForUpdates == null || checkForUpdates;
            }

        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Debug {

            /** Whether the debug overlay (FPS, frame time, memory, device status) is rendered. */
            @SerializedName("overlay")
            private boolean overlay;

            /**
             * Index into {@code DebugOverlay.INTERVAL_LABELS} / {@code INTERVAL_MS} that controls
             * how often the performance graphs are sampled. {@code 0} means every frame.
             * Defaults to {@code 2} (100 ms) via {@link SettingsDialog#load()}.
             */
            @SerializedName("sample_interval")
            private int sampleIntervalIdx;

        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Event {

            @SerializedName("run_first_only")
            private boolean runFirstOnly;

            @SerializedName("condition_events_first")
            private boolean conditionEventsFirst;

            @SerializedName("skip_empty_conditions")
            private boolean skipEmptyConditions;

        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Graphics {

            /**
             * Whether GLFW vertical sync is enabled ({@code glfwSwapInterval(1)}).
             * Missing from older configs defaults to {@code false} via Gson, but the
             * settings dialog treats both fields unset as vsync-on for backwards compatibility.
             */
            @SerializedName("vsync")
            private boolean vsync;

            /**
             * Target FPS limit when vsync is disabled. {@code 0} means uncapped.
             */
            @SerializedName("fps_limit")
            private int fpsLimit;

        }

        /**
         * Configuration for the built-in WebSocket server trigger source.
         * <p>
         * When {@code enabled} is {@code true}, BaudBound listens on {@link #getEffectiveHost()}:
         * {@link #getEffectivePort()} and fires events tagged with {@link TriggerSource#WEBSOCKET}
         * for each incoming message.
         * If {@code authToken} is non-blank, clients must send {@code AUTH:<token>} as their first
         * message before any subsequent messages are processed.
         */
        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class WebSocket {

            @SerializedName("enabled")
            private boolean enabled;

            @SerializedName("host")
            private String host;

            @SerializedName("port")
            private int port;

            @SerializedName("auth_token")
            private String authToken;

            /**
             * Returns the configured bind host, defaulting to {@code "0.0.0.0"} (all interfaces)
             * when the stored value is blank or null.
             */
            public String getEffectiveHost() {
                return (host != null && !host.isBlank()) ? host : "0.0.0.0";
            }

            /**
             * Returns the configured port, defaulting to {@code 8765} when the stored value is
             * zero (e.g. configs written before this field existed).
             */
            public int getEffectivePort() {
                return port > 0 ? port : 8765;
            }

        }

    }

    /**
     * A serial device configuration entry — name, port, serial parameters, auto-connect flag,
     * and optional auto-reconnect settings (reconnect on disconnect, configurable delay,
     * and USB identity verification).
     * <p>
     * Stored in the top-level {@code devices} list in {@code storage.json}.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Device implements Named {

        @SerializedName("name")
        private String name;

        @SerializedName("port")
        private String port;

        @SerializedName("baud_rate")
        private int baudRate;

        @SerializedName("data_bits")
        private int dataBits;

        @SerializedName("stop_bits")
        private int stopBits;

        @SerializedName("parity")
        private String parity;

        @SerializedName("flow_control")
        private String flowControl;

        @SerializedName("auto_connect")
        private boolean autoConnect;

        @SerializedName("auto_reconnect")
        private boolean autoReconnect;

        @SerializedName("reconnect_delay")
        private int reconnectDelay;

        @SerializedName("max_retries")
        private int maxRetries;

        @SerializedName("verify_device")
        private boolean verifyDevice;

        @SerializedName("device_serial")
        private String deviceSerial;

        @SerializedName("device_vendor_id")
        private int deviceVendorId;

        @SerializedName("device_product_id")
        private int deviceProductId;

        /**
         * Returns the reconnect delay in seconds, defaulting to {@code 5} when the stored value
         * is zero (e.g. devices loaded from an older {@code storage.json} that pre-dates this field).
         */
        public int getEffectiveReconnectDelay() {
            return reconnectDelay > 0 ? reconnectDelay : 5;
        }

        /** Returns the maximum reconnect attempts. {@code 0} means retry forever. */
        public int getEffectiveMaxRetries() {
            return Math.max(0, maxRetries);
        }

        /** Returns a fully independent deep copy of this device. */
        public Device deepCopy() {
            Device copy = new Device();
            copy.setName(name);
            copy.setPort(port);
            copy.setBaudRate(baudRate);
            copy.setDataBits(dataBits);
            copy.setStopBits(stopBits);
            copy.setParity(parity);
            copy.setFlowControl(flowControl);
            copy.setAutoConnect(autoConnect);
            copy.setAutoReconnect(autoReconnect);
            copy.setReconnectDelay(reconnectDelay);
            copy.setMaxRetries(maxRetries);
            copy.setVerifyDevice(verifyDevice);
            copy.setDeviceSerial(deviceSerial);
            copy.setDeviceVendorId(deviceVendorId);
            copy.setDeviceProductId(deviceProductId);
            return copy;
        }

    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event implements Named {

        @SerializedName("name")
        private String name;

        @SerializedName("conditions")
        private List<Condition> conditions = new ArrayList<>();

        @SerializedName("actions")
        private List<Action> actions = new ArrayList<>();

        /**
         * Which trigger sources may fire this event. Stored as a list of
         * {@link TriggerSource#name()} strings. When {@code null} or empty,
         * {@link #getEffectiveTriggerSources()} returns {@code ["SERIAL"]} for
         * backward compatibility with configs written before this field existed.
         */
        @SerializedName("trigger_sources")
        private List<String> triggerSources;

        /**
         * Returns the configured trigger sources, defaulting to {@code ["SERIAL"]} when
         * the field is null or empty so that existing events continue firing on serial input
         * without requiring a config migration.
         */
        public List<String> getEffectiveTriggerSources() {
            return (triggerSources == null || triggerSources.isEmpty())
                    ? List.of(TriggerSource.SERIAL.name())
                    : triggerSources;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Condition {

            @SerializedName("type")
            private String type;

            @SerializedName("value")
            private String value;

            @SerializedName("case_sensitive")
            private boolean caseSensitive;

        }

        /** Returns a fully independent deep copy of this event (no shared list or condition/action references). */
        public Event deepCopy() {
            List<Condition> conditionsCopy = conditions == null ? new ArrayList<>() :
                    conditions.stream().map(c -> new Condition(c.getType(), c.getValue(), c.isCaseSensitive())).toList();
            List<Action> actionsCopy = actions == null ? new ArrayList<>() :
                    actions.stream().map(a -> new Action(a.getType(), a.getValue())).toList();
            return new Event(name, conditionsCopy, actionsCopy, new ArrayList<>(getEffectiveTriggerSources()));
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Action {

            @SerializedName("type")
            private String type;

            @SerializedName("value")
            private String value;

        }

    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Actions {

        @SerializedName("webhooks")
        private List<Webhook> webhooks = new ArrayList<>();

        @SerializedName("programs")
        private List<Program> programs = new ArrayList<>();

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Webhook implements Named {

            @SerializedName("name")
            private String name;

            @SerializedName("url")
            private String url;

            @SerializedName("method")
            private String method;

            @SerializedName("headers")
            private List<Header> headers = new ArrayList<>();

            @SerializedName("body")
            private String body;

            /**
             * When {@code true}, the values substituted for {@code {input}} and
             * {@code {timestamp}} are URL-encoded before being inserted into the
             * URL, headers, and body templates.
             */
            @SerializedName("url_escape")
            private boolean urlEscape;

            @SerializedName("durable_delivery")
            private boolean durableDelivery;

            @SerializedName("max_attempts")
            private int maxAttempts;

            @SerializedName("retry_initial_ms")
            private int retryInitialMs;

            @SerializedName("retry_max_ms")
            private int retryMaxMs;

            @SerializedName("ack_body_contains")
            private String ackBodyContains;

            @SerializedName("ack_header_name")
            private String ackHeaderName;

            @SerializedName("ack_header_value")
            private String ackHeaderValue;

            @SerializedName("input_regex")
            private String inputRegex;

            @SerializedName("input_replacement")
            private String inputReplacement;

            @SerializedName("min_interval_ms")
            private Integer minIntervalMs;

            public int getEffectiveMaxAttempts() {
                return maxAttempts < 0 ? 0 : maxAttempts;
            }

            public int getEffectiveRetryInitialMs() {
                return retryInitialMs > 0 ? retryInitialMs : 1_000;
            }

            public int getEffectiveRetryMaxMs() {
                return retryMaxMs > 0 ? retryMaxMs : 60_000;
            }

            public int getEffectiveMinIntervalMs() {
                if (minIntervalMs != null) return Math.max(0, minIntervalMs);
                return "New Logi Scale Reading".equals(name) ? 1_000 : 0;
            }

            /** Returns a fully independent deep copy of this webhook (headers list is not shared). */
            public Webhook deepCopy() {
                List<Header> headersCopy = headers == null ? new ArrayList<>() :
                        headers.stream().map(h -> new Header(h.getKey(), h.getValue())).toList();
                return new Webhook(name, url, method, headersCopy, body, urlEscape,
                        durableDelivery, maxAttempts, retryInitialMs, retryMaxMs,
                        ackBodyContains, ackHeaderName, ackHeaderValue, inputRegex, inputReplacement, minIntervalMs);
            }

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class Header {

                @SerializedName("key")
                private String key;

                @SerializedName("value")
                private String value;

            }
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Program implements Named {

            /** Returns a fully independent deep copy of this program entry. */
            public Program deepCopy() {
                return new Program(name, path, arguments, runAsAdmin);
            }


            @SerializedName("name")
            private String name;

            @SerializedName("path")
            private String path;

            @SerializedName("arguments")
            private String arguments;

            @SerializedName("run_as_admin")
            private boolean runAsAdmin;

        }

    }

    /** Marker interface for list items that have a display name (used by {@code GuiHelper}). */
    public interface Named {
        String getName();
    }

    private static final Gson GSON = new Gson();
    private static final Gson GSON_PRETTY = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Deserializes a {@code DataStore} from the given JSON string.
     *
     * @param json the JSON content to parse
     * @return the populated {@code DataStore} instance
     */
    public static DataStore fromJson(String json) {
        return GSON.fromJson(json, DataStore.class);
    }

    /**
     * Serializes this instance to a pretty-printed JSON string suitable for writing to disk.
     */
    public String toJson() {
        return GSON_PRETTY.toJson(this);
    }

}
