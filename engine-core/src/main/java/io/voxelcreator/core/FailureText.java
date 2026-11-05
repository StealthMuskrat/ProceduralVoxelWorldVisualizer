package io.voxelcreator.core;

/** Core failure descriptions cannot replace their original fault or retain unbounded host text. */
final class FailureText {
  private static final int LIMIT = 2048;

  private FailureText() {}

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
