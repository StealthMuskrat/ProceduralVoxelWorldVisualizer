package io.voxelcreator.editor;

import imgui.*;
import imgui.callback.ImGuiInputTextCallback;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.*;
import java.util.*;
import java.util.function.*;

/** Uniform property rows. Text remains provisional until the parent Apply transaction. */
public final class UiFields {
  private final Map<String, ImString> texts = new HashMap<>();
  private final Runnable changed;

  public UiFields() {
    this(() -> {});
  }

  public UiFields(Runnable changed) {
    this.changed = Objects.requireNonNull(changed);
  }

  public void clear() {
    texts.clear();
  }

  public void text(String label, String value, Consumer<String> set) {
    text(label, value, set, null);
  }

  void text(String label, String value, Consumer<String> set, ImGuiInputTextCallback callback) {
    ImString b =
        texts.computeIfAbsent(
            ImGui.getID(label) + label,
            k ->
                new ImString(
                    value == null ? "" : value,
                    callback == null ? 16384 : 4 * ExpressionEditSession.MAX_CHARACTERS + 1));
    boolean edited =
        callback == null
            ? ImGui.inputText(label, b)
            : ImGui.inputText(label, b, ImGuiInputTextFlags.CallbackAlways, callback);
    if (edited) {
      set.accept(b.get());
      changed.run();
    }
  }

  public void number(String label, double value, DoubleConsumer set) {
    ImDouble b = new ImDouble(value);
    if (ImGui.inputDouble(label, b, 0, 0, "%.17g")) {
      set.accept(b.get());
      changed.run();
    }
  }

  public void integer(String label, int value, IntConsumer set) {
    ImInt b = new ImInt(value);
    if (ImGui.inputInt(label, b)) {
      set.accept(b.get());
      changed.run();
    }
  }

  public void bool(String label, boolean value, Consumer<Boolean> set) {
    ImBoolean b = new ImBoolean(value);
    if (ImGui.checkbox(label, b)) {
      set.accept(b.get());
      changed.run();
    }
  }

  public void choice(String label, String value, List<String> choices, Consumer<String> set) {
    if (ImGui.beginCombo(label, value)) {
      for (String c : choices)
        if (ImGui.selectable(c, c.equals(value))) {
          set.accept(c);
          changed.run();
        }
      ImGui.endCombo();
    }
  }

  public void tooltip(String message) {
    if (ImGui.isItemHovered()) ImGui.setTooltip(message);
  }
}
