package io.voxelcreator.api;

import java.util.concurrent.CompletionException;

/** Host Throwable methods cannot replace the failure being described or unbound retained text. */
final class FailureDetails {
  static final int LIMIT = 2048;

  private FailureDetails() {}

  static Throwable cause(Throwable failure) {
    if (failure instanceof CompletionException) {
      try {
        var cause = failure.getCause();
        if (cause != null) return cause;
      } catch (Throwable ignored) {
        // Describe the original envelope when its host-defined cause accessor is unreadable.
      }
    }
    return failure;
  }

  static String describe(Throwable failure) {
    String detail;
    try {
      detail = failure.getMessage();
    } catch (Throwable unreadable) {
      detail = null;
    }
    if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
    if (detail.isBlank()) detail = failure.getClass().getName();
    if (detail.length() <= LIMIT) return detail;
    int end = LIMIT - 1;
    if (Character.isHighSurrogate(detail.charAt(end - 1))) end--;
    return detail.substring(0, end) + "…";
  }
}
