package io.voxelcreator.editor;

import imgui.*;
import imgui.type.ImString;
import java.util.*;
import java.util.function.Consumer;

/** Searchable clipped choices for local typed inputs, algorithms and operation catalogues. */
public final class DraftChoicePicker {
  public record Choice(String key, String label, String detail) {}

  private static final class Widget {
    final ImString query = new ImString(256);
    List<Choice> source, filtered = List.of();
    String text = "";

    List<Choice> rows(List<Choice> values) {
      String next = query.get().strip().toLowerCase(Locale.ROOT);
      if (source != values || !text.equals(next)) {
        source = values;
        text = next;
        filtered =
            values.stream()
                .filter(
                    v ->
                        (v.label() + " " + v.detail() + " " + v.key())
                            .toLowerCase(Locale.ROOT)
                            .contains(next))
                .toList();
      }
      return filtered;
    }
  }

  private final Map<Integer, Widget> widgets = new LinkedHashMap<>(16, .75f, true);
  private final Map<String, float[]> options = new HashMap<>();
  private boolean open;
  private int submitted;
  private float[] buttonCenter;

  public void draw(
      String label, String current, List<Choice> choices, String none, Consumer<String> change) {
    String preview =
        current == null
            ? Objects.requireNonNullElse(none, "Choose...")
            : choices.stream()
                .filter(c -> c.key().equals(current))
                .map(Choice::label)
                .findFirst()
                .orElse("Unavailable choice");
    int id = ImGui.getID(label);
    ImGui.pushID(id);
    boolean clicked =
        ImGui.button(
            AppMetadata.uiText(preview) + "  v###draft-choice",
            Math.max(100, Math.min(330, ImGui.getContentRegionAvailX() * .7f)),
            0);
    var lo = ImGui.getItemRectMin();
    var hi = ImGui.getItemRectMax();
    buttonCenter = new float[] {(lo.x + hi.x) / 2, (lo.y + hi.y) / 2};
    if (clicked || open) {
      ImGui.openPopup("draft-choices");
      open = false;
    }
    ImGui.sameLine();
    ImGui.text(label);
    if (ImGui.beginPopup("draft-choices")) {
      if (!widgets.containsKey(id) && widgets.size() >= 128)
        widgets.remove(widgets.keySet().iterator().next());
      var widget = widgets.computeIfAbsent(id, ignored -> new Widget());
      if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
      ImGui.setNextItemWidth(380);
      ImGui.inputTextWithHint("##query", "Filter names, types or categories...", widget.query);
      if (none != null && ImGui.selectable(none + "###none")) {
        change.accept(null);
        ImGui.closeCurrentPopup();
      }
      var rows = widget.rows(choices);
      ImGui.text(rows.size() + " matching choices");
      ImGui.beginChild("options", 460, 220, true);
      var clipper = new ImGuiListClipper();
      submitted = 0;
      options.clear();
      try {
        clipper.begin(rows.size(), ImGui.getTextLineHeightWithSpacing());
        while (clipper.step())
          for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
            var row = rows.get(i);
            submitted++;
            if (ImGui.selectable(
                AppMetadata.uiText(row.label() + " / " + row.detail()) + "###" + row.key(),
                row.key().equals(current))) {
              change.accept(row.key());
              ImGui.closeCurrentPopup();
            }
            var a = ImGui.getItemRectMin();
            var b = ImGui.getItemRectMax();
            options.put(row.key(), new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
          }
        clipper.end();
      } finally {
        clipper.destroy();
      }
      ImGui.endChild();
      ImGui.endPopup();
    }
    ImGui.popID();
  }

  float[] buttonCenter() {
    return buttonCenter.clone();
  }

  void probeOpen() {
    open = true;
  }

  float[] option(String id) {
    return options.get(id).clone();
  }

  int submitted() {
    return submitted;
  }

  int contexts() {
    return widgets.size();
  }
}
