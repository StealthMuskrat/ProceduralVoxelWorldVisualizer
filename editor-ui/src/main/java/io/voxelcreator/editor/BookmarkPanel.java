package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import java.util.*;

/** Tab-owned bookmark toolbar, clipped selector and private Add/Rename/Teleport dialogs. */
public final class BookmarkPanel {
  public interface Navigation {
    Coordinates current(Tab tab);
    default Coordinates picked(Tab tab) { return null; }
    void teleport(Tab tab, Coordinates coordinates);
  }

  private final EditorState state;
  private final Navigation navigation;
  private final BookmarkIndex.Cache cache = new BookmarkIndex.Cache();
  private final UiFields fields = new UiFields();
  private final Map<Tab, UUID> selected = new EnumMap<>(Tab.class);
  private final Map<Tab, ImString> filters = new EnumMap<>(Tab.class);
  private final Map<UUID, float[]> options = new HashMap<>();
  private final Map<String, float[]> controls = new HashMap<>();
  private final ImString name = new ImString(4096);
  private Draft draft;
  private Tab owner;
  private UUID projectUuid, worldUuid;
  private UUID observedProject;
  private Coordinates coordinates;
  private String action = "", error = "";
  private boolean pending;
  private int submitted;

  public BookmarkPanel(EditorState state, Navigation navigation) {
    this.state = state;
    this.navigation = navigation;
    for (Tab tab : Tab.values()) filters.put(tab, new ImString(256));
  }

  public void draw(Tab tab) {
    if (!state.project().project.uuid.equals(observedProject)) {
      selected.clear();
      filters.values().forEach(filter -> filter.set(""));
      observedProject = state.project().project.uuid;
    }
    var index = cache.get(state.project(), state.revision, tab);
    var current = index.get(selected.get(tab));
    ImGui.pushID("bookmarks-" + tab.key());
    String preview = current == null ? "Choose coordinates..." : current.label();
    if (ImGui.button(AppMetadata.uiText(preview) + "  v###bookmark-picker",
        Math.max(100, Math.min(360, ImGui.getContentRegionAvailX())), 0))
      ImGui.openPopup("bookmark-choice");
    record("picker");
    if (ImGui.beginPopup("bookmark-choice")) {
      drawChoices(tab, index);
      ImGui.endPopup();
    }
    if (button("Add Bookmark")) openAdd(tab, navigation.picked(tab));
    ImGui.sameLine();
    if (button("Teleport")) openTeleport(tab);
    ImGui.beginDisabled(current == null);
    if (button("Rename")) {
      try {
        draft = Draft.rename(state.project(), tab, current.uuid());
        open(tab, "Rename Bookmark", draft.coordinates());
        name.set(draft.name());
      } catch (RuntimeException failure) { state.notify("Error", failure.getMessage()); }
    }
    ImGui.sameLine();
    if (button("Update Coordinates")) {
      try {
        Coordinates captured = navigation.current(tab);
        state.change(p -> BookmarkTransactions.update(p, tab, current.uuid(), captured));
      } catch (RuntimeException failure) { state.notify("Error", failure.getMessage()); }
    }
    ImGui.sameLine();
    if (button("Delete Bookmark")) {
      if (state.change(p -> BookmarkTransactions.delete(p, tab, current.uuid()))) selected.remove(tab);
    }
    ImGui.endDisabled();
    ImGui.popID();
  }

  private void drawChoices(Tab tab, BookmarkIndex index) {
    if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
    ImGui.setNextItemWidth(350);
    ImGui.inputTextWithHint("##bookmark-query", "Filter names or coordinates...", filters.get(tab));
    var rows = index.rows(filters.get(tab).get());
    ImGui.text(rows.size() + " matching bookmarks");
    ImGui.beginChild("bookmark-options", 440, 220, true);
    var clipper = new ImGuiListClipper();
    submitted = 0;
    options.clear();
    try {
      clipper.begin(rows.size(), ImGui.getTextLineHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = rows.get(i);
          submitted++;
          if (ImGui.selectable(AppMetadata.uiText(row.label()) + "###" + row.uuid(),
              row.uuid().equals(selected.get(tab)))) {
            selected.put(tab, row.uuid());
            navigation.teleport(tab, row.coordinates());
            ImGui.closeCurrentPopup();
          }
          options.put(row.uuid(), bounds());
        }
      clipper.end();
    } finally { clipper.destroy(); }
    ImGui.endChild();
  }

  private boolean button(String label) {
    boolean clicked = ImGui.smallButton(label);
    record(label);
    return clicked;
  }

  public void openAdd(Tab tab, Coordinates picked) {
    try {
      var captured = picked == null ? navigation.current(tab) : picked;
      draft = Draft.add(state.project(), tab, captured);
      name.set("");
      open(tab, "Add Bookmark", captured);
    } catch (RuntimeException failure) { state.notify("Error", failure.getMessage()); }
  }

  public void openTeleport(Tab tab) {
    try {
      draft = null;
      open(tab, "Teleport Coordinates", navigation.current(tab));
    } catch (RuntimeException failure) { state.notify("Error", failure.getMessage()); }
  }

  private void open(Tab tab, String action, Coordinates coordinates) {
    owner = tab;
    this.action = action;
    this.coordinates = coordinates;
    projectUuid = state.project().project.uuid;
    worldUuid = state.project().worldGenerator.uuid;
    error = "";
    fields.clear();
    pending = true;
  }

  public void drawDialogs() {
    if (pending) { ImGui.openPopup("Bookmark Coordinates"); pending = false; }
    if (ImGui.beginPopupModal("Bookmark Coordinates", ImGuiWindowFlags.AlwaysAutoResize)) {
      ImGui.text(action + " / " + owner.key());
      if (draft != null) {
        if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
        ImGui.inputText("Name", name, ImGuiInputTextFlags.AutoSelectAll);
      }
      if (draft != null && !draft.adding()) ImGui.text(draft.coordinates().label());
      else {
        fields.integer("X", coordinates.x(), x -> coordinates = new Coordinates(x, coordinates.y(), coordinates.z()));
        if (owner.threeDimensional())
          fields.integer("Y", coordinates.y(), y -> coordinates = new Coordinates(coordinates.x(), y, coordinates.z()));
        fields.integer("Z", coordinates.z(), z -> coordinates = new Coordinates(coordinates.x(), coordinates.y(), z));
      }
      if (!error.isEmpty()) ImGui.textWrapped("! " + error);
      if (ImGui.button("Apply")) {
        if (!projectUuid.equals(state.project().project.uuid)
            || !worldUuid.equals(state.project().worldGenerator.uuid)) {
          error = "Project changed; cancel and reopen this dialog";
        } else if (draft == null) {
          navigation.teleport(owner, coordinates);
          close();
        } else {
          draft.name(name.get());
          if (draft.adding()) draft.coordinates(coordinates);
          try {
            draft.checkCurrent(state.project());
            if (state.change(draft::applyTo)) {
              selected.put(owner, draft.uuid());
              close();
            } else error = "Bookmark edit rejected: " + state.issues;
          } catch (RuntimeException failure) { error = failure.getMessage(); }
        }
      }
      record("Apply");
      ImGui.sameLine();
      if (ImGui.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape)) close();
      record("Cancel");
      ImGui.endPopup();
    }
  }

  private void close() {
    draft = null;
    error = "";
    ImGui.closeCurrentPopup();
  }

  private static float[] bounds() {
    var min = ImGui.getItemRectMin();
    var max = ImGui.getItemRectMax();
    return new float[] {(min.x + max.x) / 2, (min.y + max.y) / 2};
  }

  private void record(String control) { controls.put(control, bounds()); }
  UUID selected(Tab tab) { return selected.get(tab); }
  float[] control(String id) {
    var bounds = controls.get(id);
    if (bounds == null) throw new AssertionError("Missing bookmark control " + id
        + "; action=" + action + "; pending=" + pending + "; owner=" + owner);
    return bounds.clone();
  }
  float[] option(UUID uuid) { return options.get(uuid).clone(); }
  int submitted() { return submitted; }
  int builds() { return cache.builds(); }
}
