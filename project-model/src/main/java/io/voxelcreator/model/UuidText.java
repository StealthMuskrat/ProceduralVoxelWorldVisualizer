package io.voxelcreator.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;

/** Serialized UUIDs use the full hyphenated hexadecimal spelling; either hex case is legal. */
final class UuidText {
  private static final Pattern TEXT =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private UuidText() {}

  static boolean valid(JsonNode value) {
    return value != null && value.isTextual() && TEXT.matcher(value.textValue()).matches();
  }
}
