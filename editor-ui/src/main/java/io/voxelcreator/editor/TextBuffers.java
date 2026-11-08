package io.voxelcreator.editor;

import imgui.type.ImString;
import java.util.Objects;

/** Editable text without an implicit fixed byte limit; semantic limits belong to its owner. */
final class TextBuffers {
  private TextBuffers() {}

  static ImString editable(String value, int initialCapacity) {
    var buffer = new ImString(initialCapacity);
    // Enable growth before set: ImString otherwise clips UTF-8 bytes, including partial characters.
    // The same flag enables the native InputText resize callback during typing and paste.
    buffer.inputData.isResizable = true;
    buffer.set(Objects.requireNonNullElse(value, ""));
    return buffer;
  }
}
