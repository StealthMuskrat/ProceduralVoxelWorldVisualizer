package io.voxelcreator.editor;

import java.util.Objects;

/** One metadata topic for both parts of a numeric row; presentation never changes its value. */
record NumericGuidance(String topic, String text) {
  static NumericGuidance from(
      AppMetadata metadata, String id, double value, double minimum, double maximum) {
    Objects.requireNonNull(metadata);
    var control = metadata.control(Objects.requireNonNull(id));
    String configured = control == null ? "" : Objects.requireNonNullElse(control.tooltip, "");
    String details =
        "Value: "
            + ScalarDisplay.exact(value)
            + "\nSuggested slider range ["
            + ScalarDisplay.exact(minimum)
            + ", "
            + ScalarDisplay.exact(maximum)
            + "]. Exact text keeps the full value.";
    String text = configured.isBlank() ? details : configured + "\n" + details;
    return new NumericGuidance(
        control == null ? null : id, control == null ? text : text + "\nRight-click for Help.");
  }

  String identity(int nativeId) {
    if (topic == null) throw new IllegalStateException("No metadata Help topic");
    return identity(topic, nativeId);
  }

  static String identity(String topic, int nativeId) {
    return Objects.requireNonNull(topic) + ":numeric:" + Integer.toUnsignedString(nativeId);
  }
}
