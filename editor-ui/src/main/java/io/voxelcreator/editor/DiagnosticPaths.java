package io.voxelcreator.editor;

import java.util.*;

/** Pointer syntax contributes hints only; historical indices never look up a current object. */
final class DiagnosticPaths {
  private DiagnosticPaths() {}

  static String[] tokens(String pointer) {
    return Objects.requireNonNullElse(pointer, "").split("/", -1);
  }

  static boolean noisePath(String[] tokens, UUID resource) {
    return tokens.length >= 4
        && tokens[0].isEmpty()
        && Set.of("noise2D", "noise3D", "noise").contains(tokens[1])
        && identity(tokens[2], resource);
  }

  static boolean controlPath(String[] tokens, UUID resource, UUID control) {
    return tokens.length >= 5 && noisePath(tokens, resource) && identity(tokens[4], control);
  }

  /** Full UUID tokens must agree with authority; ordinary decimal indices describe syntax only. */
  static boolean identity(String token, UUID expected) {
    if (expected == null) return false;
    if (expected.toString().equalsIgnoreCase(token)) return true;
    try {
      int index = Integer.parseInt(token);
      return index >= 0 && Integer.toString(index).equals(token);
    } catch (NumberFormatException ignored) {
      return false;
    }
  }
}
