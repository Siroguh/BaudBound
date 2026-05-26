package fi.natroutter.baudbound.gui.dialog.device;

import com.fazecast.jSerialComm.SerialPort;
import fi.natroutter.foxlib.logger.FoxLogger;
import fi.natroutter.baudbound.BaudBound;
import fi.natroutter.baudbound.enums.ConnectionStatus;
import fi.natroutter.baudbound.enums.DialogMode;
import fi.natroutter.baudbound.enums.EnumUtil;
import fi.natroutter.baudbound.enums.FlowControl;
import fi.natroutter.baudbound.enums.Parity;
import fi.natroutter.baudbound.gui.dialog.BaseDialog;
import fi.natroutter.baudbound.gui.dialog.components.DialogButton;
import fi.natroutter.baudbound.gui.theme.GuiTheme;
import fi.natroutter.baudbound.gui.util.GuiHelper;
import fi.natroutter.baudbound.serial.DeviceConnectionManager;
import fi.natroutter.baudbound.serial.SerialHandler;
import fi.natroutter.baudbound.storage.DataStore;
import fi.natroutter.baudbound.storage.StorageProvider;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiSelectableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Modal editor for creating and modifying {@link DataStore.Device} entries.
 * <p>
 * Supports both {@link DialogMode#CREATE} and {@link DialogMode#EDIT} modes. In EDIT mode
 * the existing device's data is pre-loaded into the ImGui state fields.
 * <p>
 * If the device being edited is currently connected, saving will disconnect it first so
 * the updated settings take effect on the next connection attempt.
 * When dismissed via the X button, {@link #onClose()} reopens {@link fi.natroutter.baudbound.gui.windows.DevicesWindow}.
 */
public class DeviceEditorDialog extends BaseDialog {

    private final FoxLogger logger = BaudBound.getLogger();
    private final StorageProvider storage = BaudBound.getStorageProvider();

    private final String[] baudRates = {"9600", "19200", "38400", "57600", "115200"};
    private final String[] dataBitsOpts  = {"8", "7", "6", "5"};
    private final String[] stopBitsOpts  = {"1", "2"};

    private final ImString  fieldName        = new ImString(128);
    private final ImInt     fieldPort        = new ImInt(0);
    private final ImInt     fieldBaudRate    = new ImInt(0);
    private final ImInt     fieldDataBits    = new ImInt(0);
    private final ImInt     fieldStopBits    = new ImInt(0);
    private final ImInt     fieldParity      = new ImInt(0);
    private final ImInt     fieldFlowControl = new ImInt(0);
    private final ImBoolean fieldAutoConnect    = new ImBoolean(false);
    private final ImBoolean fieldAutoReconnect  = new ImBoolean(false);
    private final ImInt     fieldReconnectDelay = new ImInt(5);
    private final ImInt     fieldMaxRetries     = new ImInt(0);
    private final ImBoolean fieldVerifyDevice   = new ImBoolean(false);

    private List<SerialPort> availablePorts;
    private String[] portNames;

    private DialogMode mode    = DialogMode.CREATE;
    private DataStore.Device editing = null;

    @Override
    public void show() {
        show(DialogMode.CREATE, null);
    }

    /**
     * Opens the dialog in the given mode, pre-loading the provided device's data for EDIT mode.
     *
     * @param dialogMode CREATE or EDIT
     * @param device     the device to edit, or {@code null} for CREATE mode
     */
    public void show(DialogMode dialogMode, DataStore.Device device) {
        this.mode = dialogMode;
        refreshPorts();

        if (dialogMode == DialogMode.EDIT && device != null) {
            this.editing = device;
            fieldName.set(device.getName());
            fieldPort.set(findPortIndex(device.getPort()));
            fieldBaudRate.set(findIntIndex(baudRates, device.getBaudRate()));
            fieldDataBits.set(findIntIndex(dataBitsOpts, device.getDataBits()));
            fieldStopBits.set(findIntIndex(stopBitsOpts, device.getStopBits()));
            fieldParity.set(EnumUtil.findIndex(Parity.class, device.getParity()));
            fieldFlowControl.set(EnumUtil.findIndex(FlowControl.class, device.getFlowControl()));
            fieldAutoConnect.set(device.isAutoConnect());
            fieldAutoReconnect.set(device.isAutoReconnect());
            fieldReconnectDelay.set(device.getEffectiveReconnectDelay());
            fieldMaxRetries.set(device.getEffectiveMaxRetries());
            fieldVerifyDevice.set(device.isVerifyDevice());
        } else {
            this.editing = null;
            fieldName.set("");
            fieldPort.set(firstAvailablePortIndex());
            fieldBaudRate.set(0);
            fieldDataBits.set(0);
            fieldStopBits.set(0);
            fieldParity.set(0);
            fieldFlowControl.set(0);
            fieldAutoConnect.set(false);
            fieldAutoReconnect.set(false);
            fieldReconnectDelay.set(5);
            fieldMaxRetries.set(0);
            fieldVerifyDevice.set(false);
        }

        requestOpen();
    }

    @Override
    protected void onClose() {
        BaudBound.getDevicesWindow().show();
    }

    @Override
    public void render() {
        String title = mode.getType() + " Device";
        if (beginModal(title)) {

            ImGui.text("Name");
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
            ImGui.inputText("##name", fieldName);

            ImGui.spacing();
            ImGui.separatorText("Serial Port");

            // Port selector with Refresh and Info buttons
            float spacing = ImGui.getStyle().getItemSpacingX();
            float refreshW = ImGui.calcTextSize("Refresh").x + ImGui.getStyle().getFramePaddingX() * 2;
            float infoW    = ImGui.calcTextSize("Info").x    + ImGui.getStyle().getFramePaddingX() * 2;
            String longestPort = portNames != null ? getLongestString(portNames) : null;
            float minComboW = longestPort != null
                    ? ImGui.calcTextSize(longestPort).x + ImGui.getStyle().getFramePaddingX() * 2 + GuiTheme.BUTTON_HEIGHT
                    : 150;
            float comboW = Math.max(minComboW, ImGui.getContentRegionAvailX() - refreshW - infoW - spacing * 2);

            ImGui.text("Port");
            ImGui.setNextItemWidth(comboW);
            renderPortCombo();
            ImGui.sameLine();
            if (ImGui.button("Refresh", new ImVec2(refreshW, GuiTheme.BUTTON_HEIGHT))) {
                refreshPorts();
            }
            ImGui.sameLine();
            boolean noPortSelected = availablePorts == null || availablePorts.isEmpty() || fieldPort.get() < 0;
            ImGui.beginDisabled(noPortSelected);
            if (ImGui.button("Info", new ImVec2(infoW, GuiTheme.BUTTON_HEIGHT)) && !noPortSelected) {
                BaudBound.getMessageDialog().show("Port Info", getDeviceInfo(),new DialogButton("OK", this::requestOpen));
            }
            ImGui.endDisabled();

            ImGui.text("Baud Rate");
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
            ImGui.combo("##baudrate", fieldBaudRate, baudRates);

            ImGui.text("Data Bits");
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
            ImGui.combo("##databits", fieldDataBits, dataBitsOpts);

            ImGui.text("Stop Bits");
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
            ImGui.combo("##stopbits", fieldStopBits, stopBitsOpts);

            ImGui.text("Parity");
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
            ImGui.combo("##parity", fieldParity, Parity.asArray());

            ImGui.text("Flow Control");
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
            ImGui.combo("##flowcontrol", fieldFlowControl, FlowControl.asArray());

            ImGui.spacing();
            ImGui.separatorText("Options");

            ImGui.checkbox("Auto connect on startup", fieldAutoConnect);

            ImGui.checkbox("Auto reconnect on disconnect", fieldAutoReconnect);
            GuiHelper.toolTip("Automatically attempt to reconnect if the device disconnects unexpectedly.\n"
                    + "This is separate from startup auto-connect.");

            ImGui.beginDisabled(!fieldAutoReconnect.get());

            ImGui.text("Reconnect delay");
            ImGui.sameLine(ImGui.getContentRegionAvailX() * 0.5f);
            ImGui.text("Max retries");
            float secLabelW = ImGui.calcTextSize("seconds").x + ImGui.getStyle().getItemSpacingX();
            float columnW = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) * 0.5f;
            ImGui.setNextItemWidth(columnW - secLabelW);
            if (ImGui.inputInt("##reconnectdelay", fieldReconnectDelay)) {
                if (fieldReconnectDelay.get() < 1) fieldReconnectDelay.set(1);
            }
            ImGui.sameLine();
            ImGui.textDisabled("seconds");
            GuiHelper.toolTip("Number of seconds to wait between reconnect attempts.");
            ImGui.sameLine();
            ImGui.setNextItemWidth(columnW);
            if (ImGui.inputInt("##maxretries", fieldMaxRetries)) {
                if (fieldMaxRetries.get() < 0) fieldMaxRetries.set(0);
            }
            GuiHelper.toolTip("Maximum reconnect attempts. 0 means retry forever.");

            ImGui.checkbox("Verify device identity on reconnect", fieldVerifyDevice);
            GuiHelper.toolTip("Only reconnect if the USB identity (serial number, or vendor/product ID)\n"
                    + "matches the device that was saved. Requires the device to be connected when saving.");

            ImGui.endDisabled();

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            if (ImGui.button("Save", new ImVec2(ImGui.getContentRegionAvailX(), GuiTheme.BUTTON_HEIGHT))) {
                save();
            }

            endModal();
        }
    }

    private String getDeviceInfo() {
        SerialPort p = availablePorts.get(fieldPort.get());
        String info = "\n"
                + "System Port Name:  " + p.getSystemPortName() + "\n"
                + "System Port Path:  " + p.getSystemPortPath() + "\n"
                + "Serial Number:  " + p.getSerialNumber() + "\n"
                + "Product ID:  " + p.getProductID() + "\n"
                + "Vendor ID:  " + p.getVendorID() + "\n"
                + "Location:  " + p.getPortLocation() + "\n"
                + "Description:  " + p.getDescriptivePortName() + "\n"
                + "Port Details: " + p.getPortDescription();
        return info;
    }

    /**
     * Renders a custom port combo that shows all available ports but grays out and disables
     * any port already assigned to another device, preventing duplicate port assignments.
     */
    private void renderPortCombo() {
        if (availablePorts == null || availablePorts.isEmpty()) {
            ImGui.beginDisabled();
            if (ImGui.beginCombo("##port", "No ports found")) {
                ImGui.endCombo();
            }
            ImGui.endDisabled();
            return;
        }

        java.util.Set<String> usedPorts = storage.getData().getDevices().stream()
                .filter(d -> d != editing)
                .map(DataStore.Device::getPort)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        boolean noneAvailable = fieldPort.get() < 0;
        String preview = noneAvailable ? "No available ports"
                : portLabel(availablePorts.get(fieldPort.get()), false);
        ImGui.beginDisabled(noneAvailable);
        if (ImGui.beginCombo("##port", preview)) {
            for (int i = 0; i < availablePorts.size(); i++) {
                SerialPort p = availablePorts.get(i);
                boolean inUse = usedPorts.contains(p.getSystemPortName());
                boolean isSelected = fieldPort.get() == i;

                ImGui.beginDisabled(inUse);
                String label = portLabel(p, inUse) + "##p" + i;
                if (ImGui.selectable(label, isSelected, ImGuiSelectableFlags.None)) {
                    fieldPort.set(i);
                }
                ImGui.endDisabled();

                if (isSelected) ImGui.setItemDefaultFocus();
            }
            ImGui.endCombo();
        }
        ImGui.endDisabled();
    }

    private void save() {
        String name = fieldName.get().trim();
        if (name.isEmpty()) {
            BaudBound.getMessageDialog().show("Error", "Name is required.",
                    new DialogButton("OK", this::requestOpen));
            return;
        }

        if (fieldPort.get() < 0) {
            BaudBound.getMessageDialog().show("Error", "No available port. All ports are already assigned to other devices.",
                    new DialogButton("OK", this::requestOpen));
            return;
        }

        List<DataStore.Device> devices = storage.getData().getDevices();
        if (devices.stream().anyMatch(d -> d != editing && d.getName().equalsIgnoreCase(name))) {
            BaudBound.getMessageDialog().show("Error", "A device named \"" + name + "\" already exists.",
                    new DialogButton("OK", this::requestOpen));
            return;
        }

        String port = (availablePorts != null && !availablePorts.isEmpty() && fieldPort.get() >= 0)
                ? availablePorts.get(fieldPort.get()).getSystemPortName()
                : null;
        int baudRate    = Integer.parseInt(baudRates[fieldBaudRate.get()]);
        int dataBits    = Integer.parseInt(dataBitsOpts[fieldDataBits.get()]);
        int stopBits    = Integer.parseInt(stopBitsOpts[fieldStopBits.get()]);
        String parity   = Parity.values()[fieldParity.get()].name();
        String flow     = FlowControl.values()[fieldFlowControl.get()].name();
        boolean autoConnect    = fieldAutoConnect.get();
        boolean autoReconnect  = fieldAutoReconnect.get();
        int     reconnectDelay = fieldReconnectDelay.get();
        int     maxRetries     = fieldMaxRetries.get();
        boolean verifyDevice   = fieldVerifyDevice.get();

        // Capture USB identity when verify-device is enabled
        String deviceSerial   = null;
        int    deviceVendorId = 0;
        int    deviceProductId = 0;
        if (verifyDevice && availablePorts != null && !availablePorts.isEmpty() && fieldPort.get() >= 0) {
            SerialPort selectedPort = availablePorts.get(fieldPort.get());
            deviceSerial    = selectedPort.getSerialNumber();
            deviceVendorId  = selectedPort.getVendorID();
            deviceProductId = selectedPort.getProductID();
        }

        if (mode == DialogMode.EDIT && editing != null) {
            // Disconnect before applying new settings
            DeviceConnectionManager manager = BaudBound.getDeviceConnectionManager();
            if (manager.getStatus(editing) == ConnectionStatus.CONNECTED) {
                manager.disconnect(editing);
            }
            editing.setName(name);
            editing.setPort(port);
            editing.setBaudRate(baudRate);
            editing.setDataBits(dataBits);
            editing.setStopBits(stopBits);
            editing.setParity(parity);
            editing.setFlowControl(flow);
            editing.setAutoConnect(autoConnect);
            editing.setAutoReconnect(autoReconnect);
            editing.setReconnectDelay(reconnectDelay);
            editing.setMaxRetries(maxRetries);
            editing.setVerifyDevice(verifyDevice);
            editing.setDeviceSerial(deviceSerial);
            editing.setDeviceVendorId(deviceVendorId);
            editing.setDeviceProductId(deviceProductId);
        } else {
            DataStore.Device d = new DataStore.Device();
            d.setName(name);
            d.setPort(port);
            d.setBaudRate(baudRate);
            d.setDataBits(dataBits);
            d.setStopBits(stopBits);
            d.setParity(parity);
            d.setFlowControl(flow);
            d.setAutoConnect(autoConnect);
            d.setAutoReconnect(autoReconnect);
            d.setReconnectDelay(reconnectDelay);
            d.setMaxRetries(maxRetries);
            d.setVerifyDevice(verifyDevice);
            d.setDeviceSerial(deviceSerial);
            d.setDeviceVendorId(deviceVendorId);
            d.setDeviceProductId(deviceProductId);
            devices.add(d);
        }

        storage.save();
        logger.info("Device \"" + name + "\" saved.");
        ImGui.closeCurrentPopup();
        BaudBound.getMessageDialog().show("Saved", "Device \"" + name + "\" saved successfully.",
                new DialogButton("OK", () -> BaudBound.getDevicesWindow().show()));
    }

    /** Returns the index of the first port not already assigned to another device, or {@code -1} if all are in use. */
    private int firstAvailablePortIndex() {
        if (availablePorts == null || availablePorts.isEmpty()) return -1;
        java.util.Set<String> usedPorts = storage.getData().getDevices().stream()
                .map(DataStore.Device::getPort)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (int i = 0; i < availablePorts.size(); i++) {
            if (!usedPorts.contains(availablePorts.get(i).getSystemPortName())) return i;
        }
        return -1;
    }

    private void refreshPorts() {
        availablePorts = SerialHandler.getAvailablePorts();
        portNames = availablePorts.isEmpty()
                ? null
                : availablePorts.stream().map(p -> portLabel(p, false)).toArray(String[]::new);
    }

    private int findPortIndex(String systemPortName) {
        if (systemPortName == null || availablePorts == null) return 0;
        for (int i = 0; i < availablePorts.size(); i++) {
            if (availablePorts.get(i).getSystemPortName().equals(systemPortName)) return i;
        }
        return 0;
    }

    private static int findIntIndex(String[] array, int value) {
        return IntStream.range(0, array.length)
                .filter(i -> { try { return Integer.parseInt(array[i]) == value; } catch (NumberFormatException e) { return false; } })
                .findFirst().orElse(0);
    }

    /** Returns a display label combining the descriptive name and system port name, e.g. "USB Keyboard (ttyUSB0)". */
    private static String portLabel(SerialPort port, boolean inUse) {
        String desc = port.getDescriptivePortName();
        String sys = port.getSystemPortName();
        String label = desc.equals(sys) || desc.contains(sys) ? desc : desc + " (" + sys + ")";
        return inUse ? label + " (in use)" : label;
    }

    private static String getLongestString(String[] array) {
        return Arrays.stream(array).max(Comparator.comparingInt(String::length)).orElse("");
    }
}
