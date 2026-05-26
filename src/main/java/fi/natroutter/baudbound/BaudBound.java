package fi.natroutter.baudbound;


import fi.natroutter.baudbound.gui.dialog.device.DeviceEditorDialog;
import fi.natroutter.baudbound.gui.dialog.program.ProgramEditorDialog;
import fi.natroutter.baudbound.event.EventHandler;
import fi.natroutter.foxlib.FoxLib;
import fi.natroutter.foxlib.logger.FoxLogger;
import fi.natroutter.baudbound.gui.windows.LogsWindow;
import fi.natroutter.baudbound.gui.windows.SimulateWindow;
import fi.natroutter.baudbound.gui.windows.WebSocketWindow;
import fi.natroutter.baudbound.http.WebhookDeliveryQueue;
import fi.natroutter.baudbound.gui.windows.DocumentationWindow;
import fi.natroutter.baudbound.gui.windows.StatesWindow;
import fi.natroutter.baudbound.gui.windows.DevicesWindow;
import fi.natroutter.baudbound.gui.windows.ProgramsWindow;
import fi.natroutter.baudbound.gui.windows.WebhooksWindow;
import fi.natroutter.baudbound.websocket.WebSocketHandler;
import fi.natroutter.foxlib.logger.types.LogLevel;
import fi.natroutter.baudbound.gui.DebugOverlay;
import fi.natroutter.baudbound.gui.MainWindow;
import fi.natroutter.baudbound.gui.dialog.AboutDialog;
import fi.natroutter.baudbound.gui.dialog.EventEditorDialog;
import fi.natroutter.baudbound.gui.dialog.MessageDialog;
import fi.natroutter.baudbound.gui.dialog.SettingsDialog;
import fi.natroutter.baudbound.gui.dialog.UpdateDialog;
import fi.natroutter.baudbound.gui.windows.EventsWindow;
import fi.natroutter.baudbound.system.UpdateManager;
import fi.natroutter.baudbound.gui.dialog.webhook.WebhookEditorDialog;
import fi.natroutter.baudbound.gui.theme.GuiTheme;
import fi.natroutter.baudbound.serial.DeviceConnectionManager;
import fi.natroutter.baudbound.storage.DataStore;
import fi.natroutter.baudbound.storage.StorageProvider;
import fi.natroutter.baudbound.command.CommandHandler;
import fi.natroutter.baudbound.command.StatusRegistry;
import fi.natroutter.baudbound.command.commands.DevicesCommand;
import fi.natroutter.baudbound.command.commands.EventsCommand;
import fi.natroutter.baudbound.command.commands.ExitCommand;
import fi.natroutter.baudbound.command.commands.PortsCommand;
import fi.natroutter.baudbound.command.commands.ProgramsCommand;
import fi.natroutter.baudbound.command.commands.ReloadCommand;
import fi.natroutter.baudbound.command.commands.SendCommand;
import fi.natroutter.baudbound.command.commands.SimulateCommand;
import fi.natroutter.baudbound.command.commands.StatesCommand;
import fi.natroutter.baudbound.command.commands.StatusCommand;
import fi.natroutter.baudbound.command.commands.UpdateCommand;
import fi.natroutter.baudbound.command.commands.VersionCommand;
import fi.natroutter.baudbound.command.commands.WebhookCommand;
import fi.natroutter.baudbound.system.SingleInstanceManager;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.flag.ImGuiConfigFlags;
import fi.natroutter.baudbound.system.AppArgs;
import lombok.Getter;
import lombok.Setter;
import org.lwjgl.glfw.GLFW;
import picocli.CommandLine;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWImage;

/**
 * Application entry point and singleton registry for all major subsystems.
 * <p>
 * Extends imgui-java's {@link Application} to drive the GLFW + ImGui render loop.
 * All singleton services (storage, device connections, dialogs) are initialized in
 * {@link #main} before {@link #launch} is called and are accessible via static getters
 * throughout the application.
 * <p>
 * Cross-thread notes:
 * <ul>
 *   <li>GLFW calls must happen on the GLFW main thread only.</li>
 *   <li>AWT tray callbacks set {@code volatile} flags ({@code pendingShow},
 *       {@code pendingExit}) that are consumed in {@link #process()} on the GLFW thread.</li>
 * </ul>
 */
public class BaudBound extends Application {

    public static final String APP_NAME = "BaudBound";
    public static final String VERSION;
    public static final String BUILD_DATE;

    static {
        Properties props = new Properties();
        try (InputStream is = BaudBound.class.getResourceAsStream("/build.properties")) {
            if (is != null) props.load(is);
        } catch (IOException ignored) {}
        VERSION    = props.getProperty("version",    "unknown");
        BUILD_DATE = props.getProperty("build.date", "unknown");
    }

    private static final int MAX_LOG_ENTRIES = 2000;
    @Getter private static final List<LogEntry> logBuffer = new CopyOnWriteArrayList<>();

    /**
     * A single captured log entry delivered via {@link FoxLogger.Builder#setOnEntry}.
     *
     * @param level   the log level
     * @param message the formatted message — {@code [timestamp][loggerName][LEVEL] text}
     */
    public record LogEntry(LogLevel level, String message) {}

    @Getter private static AppArgs args;
    @Getter private static FoxLogger logger;
    @Getter private static StorageProvider storageProvider;
    @Getter private static EventHandler eventHandler;
    @Getter private static WebhookDeliveryQueue webhookDeliveryQueue;
    @Getter private static DeviceConnectionManager deviceConnectionManager;
    @Getter private static MessageDialog messageDialog;
    @Getter private static AboutDialog aboutDialog;
    @Getter private static SettingsDialog settingsDialog;
    @Getter private static DevicesWindow devicesWindow;
    @Getter private static DeviceEditorDialog deviceEditorDialog;
    @Getter private static WebhooksWindow webhooksWindow;
    @Getter private static WebhookEditorDialog webhookEditorDialog;
    @Getter private static ProgramsWindow programsWindow;
    @Getter private static ProgramEditorDialog programEditorDialog;
    @Getter private static EventEditorDialog eventEditorDialog;
    @Getter private static StatesWindow statesWindow;
    @Getter private static UpdateDialog updateDialog;
    @Getter private static LogsWindow logsWindow;
    @Getter private static SimulateWindow simulateWindow;
    @Getter private static WebSocketWindow webSocketWindow;
    @Getter private static DocumentationWindow documentationWindow;
    /**
     * -- SETTER --
     * Allows the WebSocket dialog to swap the handler instance when settings change.
     */
    @Setter
    @Getter private static WebSocketHandler webSocketHandler;
    @Getter private static EventsWindow eventsWindow;

    private static MainWindow mainWindow;
    private static DebugOverlay debugOverlay;

    private static TrayIcon trayIcon = null;

    private volatile boolean pendingShow = false;
    private volatile boolean pendingExit = false;
    /** Set from any thread; consumed in {@link #process()} on the GLFW thread to show/hide the window. */
    private static volatile Boolean pendingGuiVisibility = null;
    private static volatile boolean guiVisible = true;
    /** Set from any thread (e.g. {@link ExitCommand}); consumed in {@link #process()} to close the window. */
    private static volatile boolean pendingStaticExit = false;

    private long lastFrameNanos = System.nanoTime();

    public static void main(String[] args) {
        AppArgs parsedArgs = new AppArgs();
        CommandLine cmd = new CommandLine(parsedArgs);
        cmd.parseArgs(args);

        // Let picocli handle --help and --version, then exit cleanly.
        if (cmd.isUsageHelpRequested())   { cmd.usage(System.out);          System.exit(0); }
        if (cmd.isVersionHelpRequested()) { cmd.printVersionHelp(System.out); System.exit(0); }

        BaudBound.args = parsedArgs;

        logger = new FoxLogger.Builder()
                .setDebug(false)
                .setPruneOlderThanDays(35)
                .setSaveIntervalSeconds(300)
                .setLoggerName(APP_NAME)
                .setOnEntry((level, msg) -> {
                    logBuffer.add(new LogEntry(level, msg));
                    if (logBuffer.size() > MAX_LOG_ENTRIES) logBuffer.remove(0);
                })
                .build();

        BaudBound app = new BaudBound();
        if (!SingleInstanceManager.tryAcquire(app::requestShow)) {
            FoxLib.println("{BRIGHT_RED}BaudBound is already running.");
            System.exit(0);
        }

        storageProvider = new StorageProvider();
        webhookDeliveryQueue = new WebhookDeliveryQueue(logger);
        webhookDeliveryQueue.start();
        eventHandler = new EventHandler();
        deviceConnectionManager = new DeviceConnectionManager();

        DataStore.Settings.WebSocket wsCfg = storageProvider.getData().getSettings().getWebSocket();
        webSocketHandler = new WebSocketHandler(wsCfg.getEffectiveHost(), wsCfg.getEffectivePort(), wsCfg.getAuthToken());
        if (wsCfg.isEnabled()) webSocketHandler.startServer();
        deviceConnectionManager.autoConnectAll(storageProvider.getData().getDevices());

        StatusRegistry statusRegistry = new StatusRegistry();
        statusRegistry.register("gui", "GUI window visibility",
                BaudBound::isGuiVisible,
                visible -> {
                    if (parsedArgs.isNoGui()) {
                        FoxLib.println("  {BRIGHT_RED}GUI is not available in headless mode (--nogui).{RESET}");
                        return;
                    }
                    BaudBound.requestGuiVisibility(visible);
                    FoxLib.println("  {BRIGHT_GREEN}GUI " + (visible ? "enabled" : "disabled") + ".{RESET}");
                }
        );

        CommandHandler commandHandler = new CommandHandler();
        commandHandler.register(new VersionCommand());
        commandHandler.register(new StatusCommand(statusRegistry));
        commandHandler.register(new UpdateCommand());
        commandHandler.register(new DevicesCommand());
        commandHandler.register(new SimulateCommand());
        commandHandler.register(new SendCommand());
        commandHandler.register(new ReloadCommand());
        commandHandler.register(new EventsCommand());
        commandHandler.register(new WebhookCommand());
        commandHandler.register(new ProgramsCommand());
        commandHandler.register(new PortsCommand());
        commandHandler.register(new StatesCommand());
        commandHandler.register(new ExitCommand());
        commandHandler.startListening();

        if (parsedArgs.isNoGui()) {
            logger.info(APP_NAME + " " + VERSION + " running in headless mode — press Ctrl+C to exit.");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (webhookDeliveryQueue != null) webhookDeliveryQueue.stop();
                storageProvider.save();
                deviceConnectionManager.disconnectAll();
                SingleInstanceManager.release();
            }));
            // In headless mode the background checker logs to console only.
            UpdateManager.startBackgroundChecker(storageProvider, info -> {});
            try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
            System.exit(0);
        }

        settingsDialog = new SettingsDialog();
        devicesWindow = new DevicesWindow();
        deviceEditorDialog = new DeviceEditorDialog();
        webhooksWindow = new WebhooksWindow();
        webhookEditorDialog = new WebhookEditorDialog();
        programsWindow = new ProgramsWindow();
        programEditorDialog = new ProgramEditorDialog();
        messageDialog = new MessageDialog();
        aboutDialog = new AboutDialog();
        eventEditorDialog = new EventEditorDialog();
        statesWindow = new StatesWindow();
        updateDialog = new UpdateDialog();
        logsWindow = new LogsWindow();
        simulateWindow = new SimulateWindow();
        webSocketWindow = new WebSocketWindow();
        documentationWindow = new DocumentationWindow();
        eventsWindow = new EventsWindow();
        eventsWindow.show(); // open by default on first launch
        mainWindow = new MainWindow();
        debugOverlay = new DebugOverlay();

        UpdateManager.startBackgroundChecker(storageProvider, updateDialog::showUpdate);

        launch(app);
        System.exit(0);
    }

    void requestShow() {
        pendingShow = true;
    }

    /**
     * Requests the GUI window to be shown or hidden on the next GLFW frame.
     * Safe to call from any thread. No-op when running in headless ({@code --nogui}) mode
     * since the GLFW loop is never started.
     *
     * @param visible {@code true} to show the window, {@code false} to hide it
     */
    public static void requestGuiVisibility(boolean visible) {
        pendingGuiVisibility = visible;
    }

    /** Returns {@code true} if the GUI window is currently visible. */
    public static boolean isGuiVisible() {
        return guiVisible;
    }

    /**
     * Requests the application to exit cleanly.
     * <p>
     * In GUI mode, sets a flag that is consumed by the GLFW thread on the next frame,
     * allowing {@link #dispose()} to run normally (saves config, disconnects devices).
     * In headless mode, calls {@link System#exit} directly — the registered shutdown hook
     * handles cleanup.
     */
    public static void requestExit() {
        if (args != null && args.isNoGui()) {
            System.exit(0);
        } else {
            pendingStaticExit = true;
        }
    }

    /**
     * Sleeps the GLFW thread to enforce the configured FPS limit when vsync is disabled.
     * No-op when vsync is enabled (swap interval already limits the frame rate).
     */
    private void limitFrameRate() {
        DataStore.Settings.Graphics g = storageProvider.getData().getSettings().getGraphics();
        if (g.isVsync() || g.getFpsLimit() <= 0) return;

        long targetNanos = 1_000_000_000L / g.getFpsLimit();
        long sleepNanos  = targetNanos - (System.nanoTime() - lastFrameNanos);
        if (sleepNanos > 1_000_000) {
            try { Thread.sleep(sleepNanos / 1_000_000); } catch (InterruptedException ignored) {}
        }
        lastFrameNanos = System.nanoTime();
    }

    /**
     * Applies the current vsync setting via {@code glfwSwapInterval}.
     * Must be called from the GLFW main thread.
     * <p>
     * If neither vsync nor fpsCap has been configured (both at their Gson-default zero/false),
     * vsync is enabled as a backwards-compatible default.
     */
    public static void applyVsync() {
        DataStore.Settings.Graphics g = storageProvider.getData().getSettings().getGraphics();
        boolean effectiveVsync = g.isVsync() || g.getFpsLimit() == 0;
        GLFW.glfwSwapInterval(effectiveVsync ? 1 : 0);
    }

    public static void showNotification(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, type);
        }
    }

    @Override
    public void process() {
        limitFrameRate();

        if (pendingExit || pendingStaticExit) {
            GLFW.glfwSetWindowShouldClose(getHandle(), true);
            return;
        }

        if (pendingShow) {
            pendingShow = false;
            GLFW.glfwShowWindow(getHandle());
            GLFW.glfwFocusWindow(getHandle());
        }

        Boolean pendingVis = pendingGuiVisibility;
        if (pendingVis != null) {
            pendingGuiVisibility = null;
            guiVisible = pendingVis;
            if (pendingVis) {
                GLFW.glfwShowWindow(getHandle());
                GLFW.glfwFocusWindow(getHandle());
            } else {
                GLFW.glfwHideWindow(getHandle());
            }
        }

        if (!guiVisible) return;

        ImGui.dockSpaceOverViewport(imgui.flag.ImGuiDockNodeFlags.PassthruCentralNode, ImGui.getMainViewport());

        mainWindow.render();

        // Floating panel windows
        eventsWindow.render();
        devicesWindow.render();
        webhooksWindow.render();
        programsWindow.render();
        statesWindow.render();
        logsWindow.render();
        simulateWindow.render();
        webSocketWindow.render();

        // Modal dialogs (always rendered so pending open flags are consumed)
        messageDialog.render();
        aboutDialog.render();
        settingsDialog.render();
        deviceEditorDialog.render();
        webhookEditorDialog.render();
        eventEditorDialog.render();
        programEditorDialog.render();
        updateDialog.render();
        documentationWindow.render();
        debugOverlay.render();
    }


    @Override
    protected void configure(final Configuration config) {
        forceX11OnWayland();
        config.setTitle(APP_NAME);
        config.setWidth(1280);
        config.setHeight(720);
    }

    /**
     * GLFW/ImGui can fail to create a window on some Wayland compositors when libdecor/EGL
     * negotiation breaks. Prefer XWayland there; headless mode never reaches this path.
     */
    private static void forceX11OnWayland() {
        if (System.getenv("WAYLAND_DISPLAY") == null || System.getenv("DISPLAY") == null) return;
        try {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        } catch (Throwable ignored) {
            // Older GLFW builds may not expose platform selection; keep default behavior.
        }
    }

    @Override
    protected void initWindow(final Configuration config) {
        super.initWindow(config);
        applyVsync();
        GLFW.glfwSetWindowSizeLimits(getHandle(), 400, 300, GLFW.GLFW_DONT_CARE, GLFW.GLFW_DONT_CARE);

        setWindowIcon(loadIcon());
        setupTray();

        // Intercept close and minimize — hide to tray instead (only if tray is available)
        if (trayIcon != null) {
            GLFW.glfwSetWindowCloseCallback(getHandle(), window -> {
                GLFW.glfwSetWindowShouldClose(window, false);
                GLFW.glfwHideWindow(window);
            });

            GLFW.glfwSetWindowIconifyCallback(getHandle(), (window, iconified) -> {
                if (iconified) {
                    GLFW.glfwRestoreWindow(window);
                    GLFW.glfwHideWindow(window);
                }
            });

            if (storageProvider.getData().getSettings().getGeneric().isStartHidden() || args.isHidden()) {
                GLFW.glfwHideWindow(getHandle());
            }
        }
    }

    @Override
    protected void initImGui(final Configuration config) {
        super.initImGui(config);

        final ImGuiIO io = ImGui.getIO();
        io.setIniFilename(new java.io.File(StorageProvider.getConfigDir(), "layout.ini").getAbsolutePath());
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
        GuiTheme.applyDarkRuda();

        // Match the OpenGL clear color to the dark theme WindowBg so no white
        // bleed-through appears at window edges on Linux.
        colorBg.set(GuiTheme.COLOR_WINDOW_BG.x, GuiTheme.COLOR_WINDOW_BG.y, GuiTheme.COLOR_WINDOW_BG.z, GuiTheme.COLOR_WINDOW_BG.w);

        // On HiDPI / fractional-scaled Linux displays (and macOS retina), the window
        // content scale can be > 1.  Scale ImGui's global font size and all style sizes
        // so the UI is legible at the physical resolution.
        float[] xScale = {1f}, yScale = {1f};
        GLFW.glfwGetWindowContentScale(getHandle(), xScale, yScale);
        float scale = Math.max(xScale[0], yScale[0]);
        if (scale > 1.0f) {
            io.setFontGlobalScale(scale);
            ImGui.getStyle().scaleAllSizes(scale);
        }
    }

    @Override
    public void dispose() {
        if (webSocketHandler != null) webSocketHandler.stopServer();
        if (webhookDeliveryQueue != null) webhookDeliveryQueue.stop();
        logger.info("Saving storage...");
        storageProvider.save();
        deviceConnectionManager.disconnectAll();
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        SingleInstanceManager.release();
        super.dispose();
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) {
            logger.info("System tray not supported on this platform — start hidden disabled.");
            return;
        }

        try {
            PopupMenu popup = new PopupMenu();

            MenuItem showItem = new MenuItem("Show " + APP_NAME);
            showItem.addActionListener(e -> pendingShow = true);

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> pendingExit = true);

            popup.add(showItem);
            popup.addSeparator();
            popup.add(exitItem);

            BufferedImage iconImage = loadIcon();
            Image trayImage = iconImage != null ? iconImage : createFallbackIcon();
            trayIcon = new TrayIcon(trayImage, APP_NAME, popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> pendingShow = true); // double-click to show

            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            logger.error("Failed to setup system tray: " + e.getMessage());
            trayIcon = null;
        }
    }

    private Image createFallbackIcon() {
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x3B82F6));
        g.fillOval(0, 0, size, size);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        FontMetrics fm = g.getFontMetrics();
        String letter = "B";
        g.drawString(letter, (size - fm.stringWidth(letter)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return img;
    }

    private BufferedImage loadIcon() {
        try (InputStream is = BaudBound.class.getResourceAsStream("/icon.png")) {
            if (is != null) return ImageIO.read(is);
        } catch (IOException e) {
            logger.error("Failed to load icon.png: " + e.getMessage());
        }
        return null;
    }

    private void setWindowIcon(BufferedImage img) {
        if (img == null) return;

        int width = img.getWidth();
        int height = img.getHeight();
        int[] pixels = img.getRGB(0, 0, width, height, null, 0, width);

        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int pixel : pixels) {
            buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
            buffer.put((byte) ((pixel >> 8)  & 0xFF)); // G
            buffer.put((byte) ( pixel        & 0xFF)); // B
            buffer.put((byte) ((pixel >> 24) & 0xFF)); // A
        }
        buffer.flip();

        GLFWImage glfwImage = GLFWImage.malloc();
        GLFWImage.Buffer iconBuffer = GLFWImage.malloc(1);
        glfwImage.set(width, height, buffer);
        iconBuffer.put(0, glfwImage);
        GLFW.glfwSetWindowIcon(getHandle(), iconBuffer);
        iconBuffer.free();
        glfwImage.free();
    }

}
