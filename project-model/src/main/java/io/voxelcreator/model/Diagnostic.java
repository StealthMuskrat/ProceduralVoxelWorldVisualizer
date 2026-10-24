package io.voxelcreator.model;

import java.util.UUID;

public record Diagnostic(
    String code,
    Severity severity,
    String message,
    String jsonPointer,
    UUID sourceUuid,
    UUID itemUuid,
    String parameterKey) {
  public enum Severity {
    INFORMATION,
    WARNING,
    ERROR,
    CRITICAL
  }

  public static Diagnostic error(String code, String path, String message) {
    return new Diagnostic(code, Severity.ERROR, message, path, null, null, null);
  }

  @Override
  public String toString() {
    return code + " " + jsonPointer + ": " + message;
  }
}
