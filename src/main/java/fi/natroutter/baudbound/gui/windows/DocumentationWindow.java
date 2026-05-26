package fi.natroutter.baudbound.gui.windows;

import fi.natroutter.baudbound.gui.BaseWindow;
import imgui.ImGui;
import imgui.flag.ImGuiCond;

/**
 * Floating panel window showing comprehensive BaudBound documentation:
 * variables, trigger sources, conditions, actions, states, and WebSocket.
 * Opened from Help → Documentation.
 */
public class DocumentationWindow extends BaseWindow {

    @Override
    public void render() {
        if (!open.get()) return;

        ImGui.setNextWindowSize(760, 600, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSizeConstraints(520, 350, Float.MAX_VALUE, Float.MAX_VALUE);

        if (ImGui.begin("Documentation##docwindow", open)) {
            if (ImGui.beginTabBar("##doctabs")) {
                if (ImGui.beginTabItem("Variables")) {
                    renderVariablesTab();
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Trigger Sources")) {
                    renderTriggerSourcesTab();
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Conditions")) {
                    renderConditionsTab();
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Actions")) {
                    renderActionsTab();
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("States")) {
                    renderStatesTab();
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("WebSocket")) {
                    renderWebSocketTab();
                    ImGui.endTabItem();
                }
                ImGui.endTabBar();
            }
        }
        ImGui.end();
    }

    // -------------------------------------------------------------------------
    // Variables tab
    // -------------------------------------------------------------------------

    private void renderVariablesTab() {
        if (!ImGui.beginChild("##vars_scroll")) { ImGui.endChild(); return; }

        ImGui.textWrapped("Variables can be used in most text fields (action values, file paths, URLs, webhook bodies, etc.). They are replaced with their current value each time an event fires.");
        ImGui.spacing();

        ImGui.separatorText("Input");
        varRow("{input}",                   "The raw input that triggered the event.",                              "hello world");
        varRow("{input.upper}",             "Input converted to uppercase.",                                        "HELLO WORLD");
        varRow("{input.lower}",             "Input converted to lowercase.",                                        "hello world");
        varRow("{input.trim}",              "Input with leading and trailing whitespace removed.",                  "hello");
        varRow("{input.length}",            "Number of characters in the input.",                                   "11");
        varRow("{input.word[N]}",           "Nth word, 0-indexed, split on whitespace.",                           "{input.word[0]}  ->  hello");
        varRow("{input.line[N]}",           "Nth line, 0-indexed, split on newline.",                              "{input.line[1]}  ->  second line");
        varRow("{input.replace[old|new]}",  "Replace the first occurrence of 'old' with 'new' in input.",          "{input.replace[hello|hi]}  ->  hi world");
        varRow("{input.urlencoded}",        "Input URL-encoded. Useful in webhook URLs.",                          "hello%20world");

        ImGui.spacing();
        ImGui.separatorText("Date and Time");
        varRow("{timestamp}",      "Full ISO-8601 date and time.",           "2026-04-02T14:30:00");
        varRow("{timestamp.unix}", "Unix epoch seconds.",                    "1743596400");
        varRow("{date}",           "Date only in yyyy-MM-dd format.",        "2026-04-02");
        varRow("{time}",           "Time only in HH:mm:ss format.",          "14:30:00");
        varRow("{year}",           "Four-digit year.",                       "2026");
        varRow("{month}",          "Two-digit month (01-12).",               "04");
        varRow("{day}",            "Two-digit day of month (01-31).",        "02");
        varRow("{hour}",           "Two-digit hour in 24h format (00-23).",  "14");
        varRow("{minute}",         "Two-digit minute (00-59).",              "30");
        varRow("{second}",         "Two-digit second (00-59).",              "00");

        ImGui.spacing();
        ImGui.separatorText("Trigger and Event");
        varRow("{source}",  "Name of the trigger source that fired the event.",                       "SERIAL");
        varRow("{channel}", "WebSocket channel path. Empty string for non-WebSocket triggers.",       "/sensors/temp");
        varRow("{event}",   "Name of the event that fired.",                                          "My Event");

        ImGui.spacing();
        ImGui.separatorText("Device");
        ImGui.textWrapped("Device variables are empty when the trigger has no device context (e.g. WebSocket messages).");
        ImGui.spacing();
        varRow("{device}",      "Custom name of the device that triggered the event.",  "Arduino Uno");
        varRow("{device.port}", "Serial port the device is connected to.",              "COM3");
        varRow("{device.baud}", "Baud rate configured for the device.",                 "9600");

        ImGui.spacing();
        ImGui.separatorText("States");
        varRow("{state}",        "Current value of the default state.",              "active");
        varRow("{state[name]}",  "Current value of a named state.",                  "{state[temperature]}  ->  42.5");

        ImGui.spacing();
        ImGui.separatorText("System");
        varRow("{hostname}",       "Network hostname of this machine.",                                  "MY-PC");
        varRow("{username}",       "OS username of the current user.",                                   "NATroutter");
        varRow("{env[VAR]}",       "Value of an OS environment variable.",                               "{env[APPDATA]}");
        varRow("{uuid}",           "A new random UUID generated on each trigger.",                       "a1b2c3d4-...");
        varRow("{random[min,max]}","Random integer between min and max inclusive.",                      "{random[1,100]}  ->  47");

        ImGui.endChild();
    }

    private static void varRow(String var, String desc, String example) {
        ImGui.bulletText(var);
        ImGui.indent(16);
        ImGui.textDisabled(desc);
        ImGui.textDisabled("Example: " + example);
        ImGui.unindent(16);
        ImGui.spacing();
    }

    // -------------------------------------------------------------------------
    // Trigger Sources tab
    // -------------------------------------------------------------------------

    private static void renderTriggerSourcesTab() {
        if (!ImGui.beginChild("##trig_scroll")) { ImGui.endChild(); return; }

        ImGui.textWrapped("Each event can be configured to fire from one or more trigger sources. All sources can be active simultaneously. Use the Trigger Sources combo in the event editor to select which sources can fire a given event.");
        ImGui.spacing();

        ImGui.separatorText("Serial Input");
        ImGui.textWrapped("Fires when a complete newline-terminated line is received from a connected serial device.");
        ImGui.bulletText("{input}  =  the trimmed line read from the port");
        ImGui.bulletText("{device}  =  the custom name of the device that sent the line");
        ImGui.bulletText("{device.port}  =  COM port of that device");
        ImGui.bulletText("{device.baud}  =  baud rate of that device");
        ImGui.textWrapped("Configure serial devices under Triggers -> Serial Devices.");

        ImGui.spacing();
        ImGui.separatorText("WebSocket");
        ImGui.textWrapped("Fires when a message arrives on the built-in WebSocket server.");
        ImGui.bulletText("{input}  =  the raw message body");
        ImGui.bulletText("{channel}  =  the URL path the client connected on (e.g. /sensors/temp)");
        ImGui.textWrapped("Configure the server under Triggers -> WebSocket. When an auth token is set, clients must send AUTH:<token> as their first message before any other messages are processed.");

        ImGui.spacing();
        ImGui.separatorText("Device Connected");
        ImGui.textWrapped("Fires when a configured serial device successfully connects to its port.");
        ImGui.bulletText("{input}  =  the custom name of the device");
        ImGui.bulletText("{device}  =  same as {input} in this context");

        ImGui.spacing();
        ImGui.separatorText("Device Disconnected");
        ImGui.textWrapped("Fires when a configured serial device disconnects (expected or unexpected).");
        ImGui.bulletText("{input}  =  the custom name of the device");
        ImGui.bulletText("{device}  =  same as {input} in this context");

        ImGui.endChild();
    }

    // -------------------------------------------------------------------------
    // Conditions tab
    // -------------------------------------------------------------------------

    private static void renderConditionsTab() {
        if (!ImGui.beginChild("##cond_scroll")) { ImGui.endChild(); return; }

        ImGui.textWrapped("All conditions in an event must be satisfied for the event to fire. An event with no conditions always matches. String conditions have an optional Case Sensitive toggle.");
        ImGui.spacing();

        ImGui.separatorText("Input Conditions");
        condRow("Input Equals",            "Input must exactly match the value.");
        condRow("Input Starts With",       "Input must start with the value.");
        condRow("Input Ends With",         "Input must end with the value.");
        condRow("Input Contains",          "Input must contain the value.");
        condRow("Input Not Contains",      "Input must not contain the value.");
        condRow("Input Not Starts With",   "Input must not start with the value.");
        condRow("Input Regex Match",       "Input must match the given Java regular expression.");
        condRow("Input Is Numeric",        "Input must be parseable as a number (integer or decimal). No value needed.");
        condRow("Input Greater Than",      "Input (as a number) must be greater than the value.");
        condRow("Input Less Than",         "Input (as a number) must be less than the value.");
        condRow("Input Between (min,max)", "Input (as a number) must fall within the range. Value format: min,max  e.g. 10,50");
        condRow("Input Length Equals",     "The character count of input must equal the value (whole number).");

        ImGui.spacing();
        ImGui.separatorText("State Conditions");
        ImGui.textWrapped("The State name field is optional — leave it blank to check the default state.");
        ImGui.spacing();
        condRow("State Equals",            "The named state value must equal the expected value.");
        condRow("State Not Equals",        "The named state value must not equal the expected value.");
        condRow("State Is Empty",          "The named state must be unset or blank. No value needed.");
        condRow("State Is Numeric",        "The named state value must be parseable as a number. No value needed.");
        condRow("State Less Than",         "The named state value (as a number) must be less than the threshold.");
        condRow("State Greater Than",      "The named state value (as a number) must be greater than the threshold.");
        condRow("State Between (min,max)", "The named state value (as a number) must be within the range. Threshold format: min,max");

        ImGui.spacing();
        ImGui.separatorText("Device Conditions");
        ImGui.textWrapped("Only evaluated when the trigger source has device context: Serial Input, Device Connected, or Device Disconnected.");
        ImGui.spacing();
        condRow("Device Equals",     "Input must have come from one of the selected devices.");
        condRow("Device Not Equals", "Input must not have come from any of the selected devices.");

        ImGui.spacing();
        ImGui.separatorText("WebSocket Parameter Conditions");
        ImGui.textWrapped("Parses the input as a URL-style query string (key=value&key2=value2) and matches against named parameters.");
        ImGui.spacing();
        condRow("WebSocket Has Parameter",       "Input must contain a parameter with the given name.");
        condRow("WebSocket Parameter Equals",    "Named parameter must equal the expected value. Format: paramName | expectedValue");
        condRow("WebSocket Parameter Not Equals","Named parameter must not equal the expected value. Format: paramName | expectedValue");
        condRow("WebSocket Parameter Contains",  "Named parameter must contain the expected value. Format: paramName | expectedValue");
        condRow("WebSocket Parameter Starts With","Named parameter must start with the expected value. Format: paramName | expectedValue");
        condRow("WebSocket Parameter Ends With", "Named parameter must end with the expected value. Format: paramName | expectedValue");

        ImGui.spacing();
        ImGui.separatorText("WebSocket Channel Conditions");
        ImGui.textWrapped("Matches the URL path the client connected on. Example: a client at ws://host:8765/sensors/temp has channel /sensors/temp");
        ImGui.spacing();
        condRow("WebSocket Channel Equals",      "Channel must exactly equal the value.");
        condRow("WebSocket Channel Not Equals",  "Channel must not equal the value.");
        condRow("WebSocket Channel Starts With", "Channel must start with the value (e.g. /sensors to match all sensor channels).");
        condRow("WebSocket Channel Contains",    "Channel must contain the value.");

        ImGui.endChild();
    }

    private static void condRow(String name, String desc) {
        ImGui.bulletText(name);
        ImGui.indent(16);
        ImGui.textDisabled(desc);
        ImGui.unindent(16);
        ImGui.spacing();
    }

    // -------------------------------------------------------------------------
    // Actions tab
    // -------------------------------------------------------------------------

    private static void renderActionsTab() {
        if (!ImGui.beginChild("##act_scroll")) { ImGui.endChild(); return; }

        ImGui.textWrapped("When all conditions match, all actions in the event are executed. State actions (Set State, Clear State) run synchronously; all others run in parallel background threads. All value fields support variable substitution.");
        ImGui.spacing();

        ImGui.separatorText("Call Webhook");
        ImGui.textWrapped("Fires an HTTP request to a saved webhook definition. Select the webhook from the dropdown. Configure webhooks under Actions -> Webhooks.");
        ImGui.bulletText("Durable delivery queues the resolved request before sending and retries until acknowledged.");
        ImGui.bulletText("Each durable attempt includes X-BaudBound-Delivery-Id and supports {delivery.id} in URL, headers, and body.");
        ImGui.bulletText("Acknowledgement defaults to HTTP 2xx, and can additionally require a response body substring or response header.");
        ImGui.bulletText("A WebSocket client can also acknowledge a pending delivery by sending ACK:<delivery.id>.");
        ImGui.bulletText("Input regex/replacement can transform the COM message before {input} is resolved.");

        ImGui.spacing();
        ImGui.separatorText("Open Program");
        ImGui.textWrapped("Launches a saved program entry (path + optional arguments). Select the program from the dropdown. Configure programs under Actions -> Programs.");

        ImGui.spacing();
        ImGui.separatorText("Open URL");
        ImGui.textWrapped("Opens a URL in the default system browser. Variables are supported in the URL.");
        ImGui.bulletText("Example: https://example.com/item/{input}");

        ImGui.spacing();
        ImGui.separatorText("Type Text");
        ImGui.textWrapped("Simulates keyboard input by placing text on the clipboard and pressing Ctrl+V. The target window must be focused before the event fires. Clipboard contents are replaced each time.");

        ImGui.spacing();
        ImGui.separatorText("Copy to Clipboard");
        ImGui.textWrapped("Places text on the system clipboard without pasting. Variables are substituted before copying.");

        ImGui.spacing();
        ImGui.separatorText("Show Notification");
        ImGui.textWrapped("Displays a system-tray balloon notification.");
        ImGui.bulletText("Format: message  (defaults to INFO type)");
        ImGui.bulletText("Format: TYPE|message  (TYPE: INFO, WARNING, ERROR, NONE)");
        ImGui.bulletText("Example: WARNING|Sensor value out of range: {input}");

        ImGui.spacing();
        ImGui.separatorText("Write to File");
        ImGui.textWrapped("Overwrites a file with new content on each trigger.");
        ImGui.bulletText("Path field: the file to write (supports variables)");
        ImGui.bulletText("Content field: optional template; defaults to {timestamp}: {input}");

        ImGui.spacing();
        ImGui.separatorText("Append to File");
        ImGui.textWrapped("Appends a line to a file on each trigger. Same format as Write to File.");

        ImGui.spacing();
        ImGui.separatorText("Play Sound");
        ImGui.textWrapped("Plays a .wav file. Leave the value blank to play the system beep instead.");

        ImGui.spacing();
        ImGui.separatorText("Set State");
        ImGui.textWrapped("Sets a named state variable to a value. States persist until cleared and can be checked by condition types.");
        ImGui.bulletText("Format: value  (sets the default state)");
        ImGui.bulletText("Format: name|value  (sets a named state)");
        ImGui.bulletText("Variables are resolved in the value before storing.");

        ImGui.spacing();
        ImGui.separatorText("Clear State");
        ImGui.textWrapped("Removes a state variable.");
        ImGui.bulletText("Leave blank to clear the default state.");
        ImGui.bulletText("Enter a state name to clear that specific state.");

        ImGui.spacing();
        ImGui.separatorText("Send to Device");
        ImGui.textWrapped("Writes a string to a connected serial device.");
        ImGui.bulletText("Format: deviceName|data");
        ImGui.bulletText("No line ending is appended automatically.");
        ImGui.bulletText("Variables are resolved in the data field.");

        ImGui.spacing();
        ImGui.separatorText("Send WebSocket");
        ImGui.textWrapped("Sends a WebSocket message.");
        ImGui.bulletText("Format: message  — replies to the client that triggered this event (WebSocket only)");
        ImGui.bulletText("Format: channel|message  — broadcasts to all authenticated clients on that channel");
        ImGui.bulletText("All variables are supported in both fields.");

        ImGui.spacing();
        ImGui.separatorText("Run Command");
        ImGui.textWrapped("Executes a shell command. Uses cmd.exe /c on Windows, sh -c on other platforms. Variables are resolved in the command string.");
        ImGui.bulletText("Example: echo {input} >> C:\\logs\\output.txt");

        ImGui.endChild();
    }

    // -------------------------------------------------------------------------
    // States tab
    // -------------------------------------------------------------------------

    private static void renderStatesTab() {
        if (!ImGui.beginChild("##states_scroll")) { ImGui.endChild(); return; }

        ImGui.textWrapped("States are named string variables that persist across events for the lifetime of the application. They allow events to communicate with each other and enable stateful automation logic.");
        ImGui.spacing();

        ImGui.separatorText("How States Work");
        ImGui.bulletText("States are set by the Set State action and cleared by the Clear State action.");
        ImGui.bulletText("A state has a name and a value (both strings).");
        ImGui.bulletText("The 'default' state is used when no name is specified.");
        ImGui.bulletText("States are stored in memory only — they reset when BaudBound restarts.");
        ImGui.bulletText("You can view and manually edit states under Debug -> States.");

        ImGui.spacing();
        ImGui.separatorText("Using States in Actions");
        ImGui.textWrapped("The {state} and {state[name]} variables let you embed state values into action fields:");
        ImGui.bulletText("{state}  =  value of the default state");
        ImGui.bulletText("{state[temperature]}  =  value of the 'temperature' state");
        ImGui.bulletText("Example Set State value: {input}  (stores the incoming input as the state value)");

        ImGui.spacing();
        ImGui.separatorText("Using States in Conditions");
        ImGui.textWrapped("State conditions let you gate events based on current state values:");
        ImGui.bulletText("State Equals / Not Equals  —  compare the state value to a fixed string");
        ImGui.bulletText("State Is Empty  —  true when the state is unset or blank");
        ImGui.bulletText("State Is Numeric  —  true when the state value is a valid number");
        ImGui.bulletText("State Less Than / Greater Than  —  numeric comparison against a threshold");
        ImGui.bulletText("State Between  —  numeric range check (min,max inclusive)");

        ImGui.spacing();
        ImGui.separatorText("Example: Toggle Logic");
        ImGui.textWrapped("Event A: Set State  name=mode  value=active");
        ImGui.textWrapped("Event B: condition State Equals  name=mode  value=active  ->  perform action");
        ImGui.textWrapped("Event C: Clear State  name=mode  ->  resets the gate");

        ImGui.endChild();
    }

    // -------------------------------------------------------------------------
    // WebSocket tab
    // -------------------------------------------------------------------------

    private static void renderWebSocketTab() {
        if (!ImGui.beginChild("##ws_scroll")) { ImGui.endChild(); return; }

        ImGui.textWrapped("BaudBound includes a built-in WebSocket server that can receive messages from external tools, web pages, or scripts and fire events just like a serial device.");
        ImGui.spacing();

        ImGui.separatorText("Setup");
        ImGui.bulletText("Open Triggers -> WebSocket to configure the server.");
        ImGui.bulletText("Enable the server and set the port (default: 8765).");
        ImGui.bulletText("Set the bind host/interface (default: 0.0.0.0 = all interfaces).");
        ImGui.bulletText("Optionally set an auth token to require clients to authenticate.");
        ImGui.bulletText("Click Save — the server starts or restarts automatically.");

        ImGui.spacing();
        ImGui.separatorText("Connecting Clients");
        ImGui.textWrapped("Connect to the server at: ws://<host>:<port>/<channel>");
        ImGui.bulletText("The URL path becomes the channel (e.g. /sensors/temp).");
        ImGui.bulletText("If no path is given, the channel is /");
        ImGui.bulletText("When an auth token is configured, the first message sent must be: AUTH:<token>");
        ImGui.bulletText("After successful auth, any subsequent message fires a WebSocket event.");

        ImGui.spacing();
        ImGui.separatorText("Authentication Flow");
        ImGui.bulletText("Client connects to ws://localhost:8765/myapp");
        ImGui.bulletText("Client sends: AUTH:my-secret-token");
        ImGui.bulletText("Server authenticates the client — subsequent messages fire events.");
        ImGui.bulletText("If the first message is not AUTH:<token>, the connection is closed.");
        ImGui.bulletText("Leave auth token blank to allow unauthenticated connections.");

        ImGui.spacing();
        ImGui.separatorText("Sending Replies");
        ImGui.textWrapped("Use the Send WebSocket action to send messages back to clients:");
        ImGui.bulletText("Format: message  — replies to the client that sent the trigger message");
        ImGui.bulletText("Format: channel|message  — broadcasts to all clients on that channel");
        ImGui.bulletText("{channel} variable = the channel the trigger client is on");

        ImGui.spacing();
        ImGui.separatorText("Channel Conditions");
        ImGui.textWrapped("Use WebSocket Channel conditions to route events based on which channel a message arrived on:");
        ImGui.bulletText("Channel Equals /sensors/temp  — only this exact path");
        ImGui.bulletText("Channel Starts With /sensors  — any path under /sensors");
        ImGui.bulletText("Channel Contains dashboard  — any path containing 'dashboard'");

        ImGui.spacing();
        ImGui.separatorText("Incoming Message Log");
        ImGui.textWrapped("The WebSocket window shows a real-time log of all incoming messages, connection events, and auth results. Use the Clear button to reset it.");

        ImGui.endChild();
    }
}
