package fi.natroutter.baudbound.gui.windows;

import fi.natroutter.baudbound.BaudBound;
import fi.natroutter.baudbound.gui.BaseWindow;
import fi.natroutter.baudbound.gui.theme.GuiTheme;
import fi.natroutter.foxlib.logger.types.LogLevel;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;

import java.util.List;

/**
 * Floating panel window that displays the in-session log buffer captured by
 * {@link BaudBound}.
 * <p>
 * Each entry is color-coded by {@link LogLevel}: ERROR/FATAL in red, WARN in yellow,
 * INFO in green, and LOG in the default text color. The buffer holds up to
 * {@code BaudBound.MAX_LOG_ENTRIES} entries and can be cleared from within the window.
 */
public class LogsWindow extends BaseWindow {

    private final ImBoolean autoScroll = new ImBoolean(true);

    @Override
    public void render() {
        if (!open.get()) return;

        ImGui.setNextWindowSize(700, 400, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSizeConstraints(300, 150, Float.MAX_VALUE, Float.MAX_VALUE);

        if (ImGui.begin("Logs##logswindow", open)) {

            ImGui.checkbox("Auto-scroll", autoScroll);
            ImGui.sameLine();
            if (ImGui.button("Clear", new ImVec2(80, GuiTheme.BUTTON_HEIGHT))) {
                BaudBound.getLogBuffer().clear();
            }

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            float childH = ImGui.getContentRegionAvailY();
            if (ImGui.beginChild("##logs", new ImVec2(ImGui.getContentRegionAvailX(), childH), ImGuiChildFlags.Border)) {

                ImGui.pushTextWrapPos(0f); // 0 = wrap at window/child right edge
                List<BaudBound.LogEntry> entries = BaudBound.getLogBuffer();
                for (BaudBound.LogEntry entry : entries) {
                    float[] color = levelColor(entry.level());
                    ImGui.pushStyleColor(ImGuiCol.Text, color[0], color[1], color[2], 1.0f);
                    ImGui.textUnformatted(entry.message());
                    ImGui.popStyleColor();
                }
                ImGui.popTextWrapPos();

                if (autoScroll.get()) {
                    ImGui.setScrollHereY(1.0f);
                }
            }
            ImGui.endChild();
        }
        ImGui.end();
    }

    private static float[] levelColor(LogLevel level) {
        return switch (level) {
            case ERROR, FATAL -> new float[]{1.0f, 0.35f, 0.35f};
            case WARN         -> new float[]{1.0f, 0.85f, 0.3f};
            case INFO         -> new float[]{0.45f, 0.9f, 0.45f};
            default           -> new float[]{0.85f, 0.85f, 0.85f};
        };
    }
}
