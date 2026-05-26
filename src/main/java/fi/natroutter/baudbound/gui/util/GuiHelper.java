package fi.natroutter.baudbound.gui.util;

import fi.natroutter.baudbound.BaudBound;
import fi.natroutter.baudbound.gui.dialog.components.DialogButton;
import fi.natroutter.baudbound.gui.theme.GuiTheme;
import fi.natroutter.baudbound.storage.DataStore;
import fi.natroutter.foxlib.FoxLib;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiSelectableFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTableBgTarget;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Shared ImGui helper utilities used across all windows and dialogs.
 * <p>
 * Provides reusable widgets:
 * <ul>
 *   <li>{@link #listAndEditorButtons} — scrollable list with Create/Edit/Duplicate/Delete/reorder buttons</li>
 *   <li>{@link #clickableLink} — underlined, hand-cursor hyperlink text</li>
 *   <li>{@link #keyValueTable} — editable two-column key/value table with row reorder and delete</li>
 *   <li>{@link #toolTip} — inline {@code (?)} label with a hover tooltip</li>
 * </ul>
 */
public class GuiHelper {

    /**
     * Convenience overload without move buttons or reserved height.
     * Used by list windows that don't need ordering controls (e.g. WebhooksWindow, ProgramsWindow).
     */
    public static <T extends DataStore.Named> void listAndEditorButtons(String id, List<T> data, ImInt selected, boolean fillHeight, Runnable onCreate, Runnable onEdit, Runnable onDuplicate, Runnable onDelete, Runnable onError) {
        listAndEditorButtons(id, data, selected, fillHeight, 0f, onCreate, onEdit, onDuplicate, onDelete, ()->{}, ()->{}, onError);
    }

    /**
     * Full variant with move-up / move-down buttons and a configurable reserved height.
     * Used by MainWindow where the event list needs ordering controls and must leave
     * room for the Connect button and status label below.
     *
     * @param id             unique ImGui widget ID prefix
     * @param data           the list to display; items must implement {@link DataStore.Named}
     * @param selected       holds the currently selected index
     * @param fillHeight     if {@code true} the list box stretches to fill available height
     * @param reservedHeight additional pixels to subtract from the list height when filling
     * @param onCreate       called when "Create" is clicked
     * @param onEdit         called when "Edit" is clicked (only if list is non-empty)
     * @param onDuplicate    called when "Duplicate" is clicked (only if list is non-empty)
     * @param onDelete       called when "Delete" is clicked (only if list is non-empty)
     * @param onMoveUp       called when "^" is clicked (disabled at top)
     * @param onMoveDown     called when "v" is clicked (disabled at bottom)
     * @param onError        called as the button action of the auto-shown error dialog
     */
    public static <T extends DataStore.Named> void listAndEditorButtons(String id, List<T> data, ImInt selected, boolean fillHeight, float reservedHeight, Runnable onCreate, Runnable onEdit, Runnable onDuplicate, Runnable onDelete, Runnable onMoveUp, Runnable onMoveDown, Runnable onError) {
        float itemSpacing = ImGui.getStyle().getItemSpacingY();
        float lineHeight = ImGui.getTextLineHeightWithSpacing();

        float footerHeight = itemSpacing * 4 + GuiTheme.BUTTON_HEIGHT + 1; // +1 for separator line
        float listHeight = fillHeight
                ? ImGui.getContentRegionAvailY() - footerHeight - reservedHeight
                : lineHeight * Math.max(data.size(), 3) + ImGui.getStyle().getFramePaddingY() * 2;

        if (ImGui.beginListBox(id, new ImVec2(ImGui.getContentRegionAvailX(), listHeight))) {
            ImGui.spacing();
            for (int n = 0; n < data.size(); n++) {
                boolean isSelected = (selected.get() == n);
                if (isSelected) {
                    ImGui.pushStyleColor(ImGuiCol.Header,        GuiTheme.COLOR_ACCENT_HEADER);
                    ImGui.pushStyleColor(ImGuiCol.HeaderHovered, GuiTheme.COLOR_ACCENT_HEADER_HOVERED);
                }
                if (ImGui.selectable(data.get(n).getName(), isSelected)) {
                    selected.set(n);
                }
                if (isSelected) {
                    ImGui.popStyleColor(2);
                    ImGui.setItemDefaultFocus();
                }
            }
            ImGui.spacing();
            ImGui.endListBox();
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        float spacing   = ImGui.getStyle().getItemSpacingX();
        float arrowWidth = 26;
        float remaining  = ImGui.getContentRegionAvailX() - (arrowWidth + spacing) * 2;
        float btnWidth   = (remaining - spacing * 3) / 4;

        if (ImGui.button("Create", new ImVec2(btnWidth, GuiTheme.BUTTON_HEIGHT))) {
            onCreate.run();
        }
        ImGui.sameLine();
        if (ImGui.button("Edit", new ImVec2(btnWidth, GuiTheme.BUTTON_HEIGHT))) {
            if (!data.isEmpty()) {
                onEdit.run();
            } else {
                BaudBound.getMessageDialog().show("Error", "No items to edit.", new DialogButton("OK", onError));
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Duplicate", new ImVec2(btnWidth, GuiTheme.BUTTON_HEIGHT))) {
            if (!data.isEmpty()) {
                onDuplicate.run();
            } else {
                BaudBound.getMessageDialog().show("Error", "No items to duplicate.", new DialogButton("OK", onError));
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Delete", new ImVec2(btnWidth, GuiTheme.BUTTON_HEIGHT))) {
            if (!data.isEmpty()) {
                onDelete.run();
            } else {
                BaudBound.getMessageDialog().show("Error", "No items to delete.", new DialogButton("OK", onError));
            }
        }
        ImGui.sameLine();
        ImGui.beginDisabled(data.isEmpty() || selected.get() == 0);
        if (ImGui.button("^##up" + id, new ImVec2(arrowWidth, GuiTheme.BUTTON_HEIGHT))) {
            onMoveUp.run();
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        ImGui.beginDisabled(data.isEmpty() || selected.get() >= data.size() - 1);
        if (ImGui.button("v##dn" + id, new ImVec2(arrowWidth, GuiTheme.BUTTON_HEIGHT))) {
            onMoveDown.run();
        }
        ImGui.endDisabled();
    }

    /**
     * Renders a blue underlined hyperlink that opens {@code url} in the default browser when clicked.
     *
     * @param label the display text
     * @param url   the URL to open
     */
    public static void clickableLink(String label, String url) {
        ImGui.textColored(GuiTheme.COLOR_LINK.x, GuiTheme.COLOR_LINK.y, GuiTheme.COLOR_LINK.z, GuiTheme.COLOR_LINK.w, label);
        if (ImGui.isItemHovered()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
            ImVec2 min = ImGui.getItemRectMin();
            ImVec2 max = ImGui.getItemRectMax();
            ImGui.getWindowDrawList().addLine(min.x, max.y, max.x, max.y, GuiTheme.colorU32(GuiTheme.COLOR_LINK));
        }
        if (ImGui.isItemClicked()) {
            try {
                FoxLib.openURL(url);
            } catch (IOException e) {
                BaudBound.getLogger().error("Failed to open URL '" + url + "': " + e.getMessage());
            }
        }
    }

    /**
     * Renders a disabled {@code (?)} label on the same line; hovering shows {@code content}
     * as a tooltip.
     */
    public static void toolTip(String content) {
        ImGui.sameLine();
        ImGui.textDisabled("(?)");
        ImGui.setItemTooltip(content);
    }

    /**
     * Renders an editable two-column key/value table with per-row move and delete controls.
     * Mutations (remove, swap) are applied after the table loop to avoid modifying the list
     * while iterating.
     *
     * @param id          unique ImGui widget ID prefix
     * @param col0Header  header label for the key column
     * @param col1Header  header label for the value column
     * @param rows        mutable list of {@code [key, value]} ImString pairs; modified in place
     */
    public static void keyValueTable(String id, String col0Header, String col1Header, List<ImString[]> rows) {
        int[] result = renderTable(id, col0Header, col1Header, rows,
                row -> ImGui.inputText("##c0" + id + row, rows.get(row)[0]));
        int size = rows.size();
        if (result[0] >= 0)                        rows.remove(result[0]);
        if (result[1] > 0)                         Collections.swap(rows, result[1], result[1] - 1);
        if (result[2] >= 0 && result[2] < size - 1) Collections.swap(rows, result[2], result[2] + 1);
    }

    /**
     * Renders a combo dropdown where each item is a checkbox, allowing multiple selections.
     * The preview label shows comma-separated selected names, or {@code "None"} when nothing is selected.
     * Call {@code ImGui.setNextItemWidth()} before this method to control the combo width.
     *
     * @param id       unique ImGui widget ID (e.g. {@code "##cdev0"})
     * @param options  display labels for each item
     * @param selected parallel boolean state; must be same length as {@code options}
     */
    public static void multiSelectCombo(String id, String[] options, ImBoolean[] selected) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selected.length; i++) {
            if (selected[i].get()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(options[i]);
            }
        }
        if (ImGui.beginCombo(id, sb.isEmpty() ? "None" : sb.toString())) {
            for (int i = 0; i < options.length; i++) {
                ImGui.checkbox(options[i] + "##" + id + i, selected[i]);
            }
            ImGui.endCombo();
        }
    }

    /**
     * Renders a read-only table with Create/Edit/Duplicate/Delete/reorder buttons below.
     * Row click selects via {@code selected}. Uses SpanAllColumns selectable so clicking
     * anywhere on a row selects it.
     * <p>
     * The first column always shows {@link DataStore.Named#getName()}; {@code rowData} supplies
     * the values for the remaining columns.
     *
     * @param id             unique ImGui widget ID prefix
     * @param data           items to display; must implement {@link DataStore.Named}
     * @param selected       holds the currently selected index
     * @param fillHeight     stretch table to fill available vertical space
     * @param reservedHeight pixels to subtract from height when filling
     * @param headers        column header labels; {@code headers[0]} is the Name column
     * @param columnWidths   widths per column; {@code <= 0} means stretch, {@code > 0} means fixed pixels
     * @param rowData        maps each item to a String[] of extra column values (length = headers.length - 1)
     * @param onCreate       called when "Create" is clicked
     * @param onEdit         called when "Edit" is clicked (only if list is non-empty)
     * @param onDuplicate    called when "Duplicate" is clicked (only if list is non-empty)
     * @param onDelete       called when "Delete" is clicked (only if list is non-empty)
     * @param onMoveUp       called when "^" is clicked (disabled at top)
     * @param onMoveDown     called when "v" is clicked (disabled at bottom)
     * @param onError        called as the button action of the auto-shown error dialog
     */
    public static <T extends DataStore.Named> void tableAndEditorButtons(
            String id, List<T> data, ImInt selected,
            boolean fillHeight, float reservedHeight,
            String[] headers, float[] columnWidths,
            Function<T, String[]> rowData,
            Runnable onCreate, Runnable onEdit, Runnable onDuplicate, Runnable onDelete,
            Runnable onMoveUp, Runnable onMoveDown, Runnable onError) {

        float footerHeight = ImGui.getStyle().getItemSpacingY() * 4 + GuiTheme.BUTTON_HEIGHT + 1;
        float tableHeight  = fillHeight
                ? ImGui.getContentRegionAvailY() - footerHeight - reservedHeight
                : ImGui.getTextLineHeightWithSpacing() * Math.max(data.size(), 3)
                  + ImGui.getStyle().getFramePaddingY() * 2;

        int tableFlags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.ScrollY | ImGuiTableFlags.SizingStretchSame;

        ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, 4, 6);
        if (ImGui.beginTable(id, headers.length, tableFlags, new ImVec2(ImGui.getContentRegionAvailX(), tableHeight))) {
            for (int col = 0; col < headers.length; col++) {
                int colFlags = columnWidths[col] > 0
                        ? ImGuiTableColumnFlags.WidthFixed
                        : ImGuiTableColumnFlags.WidthStretch;
                float w = columnWidths[col] > 0 ? columnWidths[col] : 0;
                ImGui.tableSetupColumn(headers[col], colFlags, w);
            }
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            for (int n = 0; n < data.size(); n++) {
                boolean isSelected = selected.get() == n;
                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0);

                float selectableH = ImGui.getFrameHeight();
                ImVec2 cellCursor = ImGui.getCursorScreenPos();

                ImGui.pushStyleColor(ImGuiCol.Header,        0f, 0f, 0f, 0f);
                ImGui.pushStyleColor(ImGuiCol.HeaderHovered, 0f, 0f, 0f, 0f);
                ImGui.pushStyleColor(ImGuiCol.HeaderActive,  0f, 0f, 0f, 0f);
                if (ImGui.selectable("##row" + id + n, isSelected,
                        ImGuiSelectableFlags.None, ImGui.getContentRegionAvailX(), selectableH)) {
                    selected.set(n);
                }
                ImGui.popStyleColor(3);

                if (ImGui.isItemActive()) {
                    ImGui.tableSetBgColor(ImGuiTableBgTarget.CellBg, GuiTheme.colorU32(GuiTheme.COLOR_ACCENT_ACTIVE));
                } else if (isSelected) {
                    ImGui.tableSetBgColor(ImGuiTableBgTarget.CellBg, GuiTheme.colorU32(GuiTheme.COLOR_ACCENT_SELECTED));
                } else if (ImGui.isItemHovered()) {
                    ImGui.tableSetBgColor(ImGuiTableBgTarget.CellBg, GuiTheme.colorU32(GuiTheme.COLOR_ACCENT_HOVERED));
                }
                if (isSelected) ImGui.setItemDefaultFocus();

                float textH = ImGui.getTextLineHeight();
                ImGui.setCursorScreenPos(cellCursor.x, cellCursor.y + (selectableH - textH) / 2f);
                ImGui.text(data.get(n).getName());

                String[] extra = rowData.apply(data.get(n));
                for (int col = 1; col < headers.length; col++) {
                    ImGui.tableSetColumnIndex(col);
                    String cellText = extra[col - 1];
                    ImGui.alignTextToFramePadding();
                    ImGui.text(cellText);
                    if (ImGui.isItemHovered() && ImGui.calcTextSizeX(cellText) > ImGui.getContentRegionAvailX()) {
                        ImGui.setItemTooltip(cellText);
                    }
                }
            }
            ImGui.endTable();
        }
        ImGui.popStyleVar();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        float spacing    = ImGui.getStyle().getItemSpacingX();
        float arrowWidth = 26;
        float remaining  = ImGui.getContentRegionAvailX() - (arrowWidth + spacing) * 2;
        float btnWidth   = (remaining - spacing * 3) / 4;

        if (ImGui.button("Create", new ImVec2(btnWidth, GuiTheme.BUTTON_HEIGHT))) onCreate.run();
        ImGui.sameLine();
        if (ImGui.button("Edit", new ImVec2(btnWidth, GuiTheme.BUTTON_HEIGHT))) {
            if (!data.isEmpty()) onEdit.run();
            else BaudBound.getMessageDialog().show("Error", "No items to edit.", new DialogButton("OK", onError));
        }
        ImGui.sameLine();
        if (ImGui.button("Duplicate", new ImVec2(btnWidth, GuiTheme.BUTTON_HEIGHT))) {
            if (!data.isEmpty()) onDuplicate.run();
            else BaudBound.getMessageDialog().show("Error", "No items to duplicate.", new DialogButton("OK", onError));
        }
        ImGui.sameLine();
        if (ImGui.button("Delete", new ImVec2(btnWidth, GuiTheme.BUTTON_HEIGHT))) {
            if (!data.isEmpty()) onDelete.run();
            else BaudBound.getMessageDialog().show("Error", "No items to delete.", new DialogButton("OK", onError));
        }
        ImGui.sameLine();
        ImGui.beginDisabled(data.isEmpty() || selected.get() == 0);
        if (ImGui.button("^##up" + id, new ImVec2(arrowWidth, GuiTheme.BUTTON_HEIGHT))) onMoveUp.run();
        ImGui.endDisabled();
        ImGui.sameLine();
        ImGui.beginDisabled(data.isEmpty() || selected.get() >= data.size() - 1);
        if (ImGui.button("v##dn" + id, new ImVec2(arrowWidth, GuiTheme.BUTTON_HEIGHT))) onMoveDown.run();
        ImGui.endDisabled();
    }

    // Returns int[]{removeIndex, moveUpIndex, moveDownIndex}
    private static int[] renderTable(String id, String col0Header, String col1Header,
                                     List<ImString[]> rows, Consumer<Integer> col0Renderer) {
        int removeIndex = -1, moveUpIndex = -1, moveDownIndex = -1;

        ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, 4, 6);
        if (ImGui.beginTable(id, 5, ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchSame)) {
            ImGui.tableSetupColumn(col0Header, ImGuiTableColumnFlags.WidthStretch);
            ImGui.tableSetupColumn(col1Header, ImGuiTableColumnFlags.WidthStretch);
            ImGui.tableSetupColumn("##up" + id, ImGuiTableColumnFlags.WidthFixed, 22);
            ImGui.tableSetupColumn("##dn" + id, ImGuiTableColumnFlags.WidthFixed, 22);
            ImGui.tableSetupColumn("##rm" + id, ImGuiTableColumnFlags.WidthFixed, 22);
            ImGui.tableHeadersRow();

            for (int row = 0; row < rows.size(); row++) {
                ImGui.tableNextRow();

                ImGui.tableSetColumnIndex(0);
                ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
                col0Renderer.accept(row);

                ImGui.tableSetColumnIndex(1);
                ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
                ImGui.inputText("##c1" + id + row, rows.get(row)[1]);

                ImGui.tableSetColumnIndex(2);
                ImGui.beginDisabled(row == 0);
                if (ImGui.button("^##u" + id + row)) moveUpIndex = row;
                ImGui.endDisabled();

                ImGui.tableSetColumnIndex(3);
                ImGui.beginDisabled(row == rows.size() - 1);
                if (ImGui.button("v##d" + id + row)) moveDownIndex = row;
                ImGui.endDisabled();

                ImGui.tableSetColumnIndex(4);
                ImGui.pushStyleColor(ImGuiCol.Button, GuiTheme.COLOR_DELETE_BUTTON);
                if (ImGui.button("X##r" + id + row)) removeIndex = row;
                ImGui.popStyleColor();
            }
            ImGui.endTable();
        }
        ImGui.popStyleVar();

        return new int[]{removeIndex, moveUpIndex, moveDownIndex};
    }
}
