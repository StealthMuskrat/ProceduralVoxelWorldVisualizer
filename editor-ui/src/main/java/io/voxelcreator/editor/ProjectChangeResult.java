package io.voxelcreator.editor;

import io.voxelcreator.model.Diagnostic;
import java.util.*;

/** Bounded feedback belonging to one exact attempt, independent of the shared Validation view. */
record ProjectChangeResult(
    boolean applied, int issueCount, List<Diagnostic> issues, String failure) {
  static final int MAX_ISSUES = 256;
  static final int MAX_FAILURE = 2048;

  ProjectChangeResult {
    issues = List.copyOf(issues);
    failure = Objects.requireNonNull(failure);
  }

  static ProjectChangeResult accepted() {
    return new ProjectChangeResult(true, 0, List.of(), "");
  }

  static ProjectChangeResult rejected(List<Diagnostic> issues) {
    return new ProjectChangeResult(
        false, issues.size(), issues.stream().limit(MAX_ISSUES).toList(), "");
  }

  static ProjectChangeResult failed(RuntimeException failure) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) message = failure.toString();
    if (message.length() > MAX_FAILURE) message = message.substring(0, MAX_FAILURE - 1) + "…";
    return new ProjectChangeResult(false, 0, List.of(), message);
  }
}
