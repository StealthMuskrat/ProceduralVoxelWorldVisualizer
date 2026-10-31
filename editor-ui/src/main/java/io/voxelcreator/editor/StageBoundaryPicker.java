package io.voxelcreator.editor;

import imgui.ImGui;
import java.util.*;
import java.util.function.Consumer;

/** World-only UUID selection, independent of Properties, with searchable clipped stage rows. */
final class StageBoundaryPicker {
  private final EditorState state;
  private final StageBoundaryCatalog.Cache cache = new StageBoundaryCatalog.Cache();
  final DraftChoicePicker picker;
  private Object modeOwner = new Object();
  private String observedMode;

  StageBoundaryPicker(EditorState state) {
    this.state = Objects.requireNonNull(state);
    picker = new DraftChoicePicker(state, () -> modeOwner, () -> 0);
  }

  StageBoundaryCatalog catalog() {
    return cache.get(state.project(), state.revision);
  }

  UUID initial(UUID current, UUID preferred) {
    return catalog().initial(current, preferred);
  }

  boolean available(String mode, UUID current) {
    return mode.equals("Final") || catalog().available(mode, current);
  }

  void draw(String mode, UUID current, Consumer<UUID> change) {
    if (!Objects.equals(observedMode, mode)) {
      observedMode = mode;
      modeOwner = new Object();
    }
    if (mode.equals("Final")) return;
    var source = catalog();
    picker.draw(
        "Boundary",
        current == null ? null : current.toString(),
        source.choices(),
        null,
        key -> change.accept(UUID.fromString(key)));
    if (current != null && source.get(current) == null)
      ImGui.textWrapped(
          "The selected boundary no longer exists. Choose another boundary, undo its"
              + " deletion, or select Final.");
    else if (source.rows().isEmpty())
      ImGui.textWrapped("No stage boundaries yet. Add a layer in Resources or select Final.");
  }

  int builds() {
    return cache.builds();
  }
}
