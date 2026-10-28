package io.voxelcreator.editor;

import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.ProjectData.Curve;
import io.voxelcreator.model.ProjectIO;
import java.util.*;
import java.util.function.Consumer;

/** Accepted staging is consumed before UI consequences; rejected candidates remain correctable. */
final class CurveApplySession {
  private final Consumer<Curve> stage;
  private final Runnable accepted;
  private boolean closed, applying;
  private String error = "";
  private RuntimeException followUpFailure;

  CurveApplySession(Consumer<Curve> stage, Runnable accepted) {
    this.stage = Objects.requireNonNull(stage);
    this.accepted = Objects.requireNonNull(accepted);
  }

  boolean apply(Curve curve) {
    if (closed || applying) throw new IllegalStateException("Curve publication is closed or busy");
    applying = true;
    try {
      try {
        var candidate = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(curve), Curve.class);
        new ScalarCurve(candidate);
        stage.accept(candidate);
      } catch (RuntimeException rejected) {
        String message = Objects.requireNonNullElse(rejected.getMessage(), rejected.toString());
        error = message.length() > 2048 ? message.substring(0, 2047) + "…" : message;
        return false;
      }
      closed = true;
      error = "";
      try {
        accepted.run();
      } catch (RuntimeException failure) {
        followUpFailure = failure;
      }
      return true;
    } finally {
      applying = false;
    }
  }

  boolean closed() {
    return closed;
  }

  String error() {
    return error;
  }

  RuntimeException followUpFailure() {
    return followUpFailure;
  }

  void cancel() {
    closed = true;
  }
}
