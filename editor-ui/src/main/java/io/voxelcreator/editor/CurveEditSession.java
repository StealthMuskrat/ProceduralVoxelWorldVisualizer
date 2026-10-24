package io.voxelcreator.editor;

import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.function.Consumer;

/** Isolated, bounded local curve history; a complete gesture contributes one history entry. */
public final class CurveEditSession {
  private Curve curve, gesture;
  private final Deque<Curve> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private String error = "";

  public CurveEditSession(Curve source) {
    curve = copy(source);
    new ScalarCurve(curve);
  }

  public Curve curve() {
    return curve;
  }

  public String error() {
    return error;
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }

  public boolean edit(Consumer<Curve> change) {
    var next = copy(curve);
    try {
      change.accept(next);
      new ScalarCurve(next);
      if (!equal(curve, next)) {
        undo.push(curve);
        trim();
        redo.clear();
        curve = next;
      }
      error = "";
      return true;
    } catch (RuntimeException invalid) {
      error = invalid.getMessage();
      return false;
    }
  }

  public void beginGesture() {
    gesture = copy(curve);
  }

  public void endGesture() {
    if (gesture != null && !equal(gesture, curve)) {
      undo.push(gesture);
      trim();
      redo.clear();
    }
    gesture = null;
  }

  public boolean move(
      Set<UUID> selection,
      double dx,
      double dy,
      boolean snapX,
      boolean snapY,
      double stepX,
      double stepY) {
    if (gesture == null) beginGesture();
    Curve next = copy(gesture);
    Point anchor =
        next.points.stream().filter(p -> selection.contains(p.uuid)).findFirst().orElse(null);
    if (anchor == null) return false;
    if (snapX && stepX > 0) dx = StrictMath.rint((anchor.x + dx) / stepX) * stepX - anchor.x;
    if (snapY && stepY > 0) dy = StrictMath.rint((anchor.y + dy) / stepY) * stepY - anchor.y;
    for (int i = 0; i < next.points.size(); i++) {
      Point p = next.points.get(i);
      if (selection.contains(p.uuid)) {
        if (i > 0 && i < next.points.size() - 1) p.x += dx;
        p.y += dy;
      }
    }
    return preview(next);
  }

  public boolean tangent(UUID id, int side, double slope) {
    if (gesture == null) beginGesture();
    Curve next = copy(gesture);
    Point p = point(next, id);
    if (p.handleMode.equals("AUTO")) {
      error = "Choose Aligned or Free to move a tangent";
      return false;
    }
    if (side < 0) p.inTangent = slope;
    else p.outTangent = slope;
    if (p.handleMode.equals("ALIGNED")) {
      p.inTangent = slope;
      p.outTangent = slope;
    }
    return preview(next);
  }

  public void undo() {
    endGesture();
    if (!undo.isEmpty()) {
      redo.push(curve);
      curve = undo.pop();
      error = "";
    }
  }

  public void redo() {
    endGesture();
    if (!redo.isEmpty()) {
      undo.push(curve);
      curve = redo.pop();
      error = "";
    }
  }

  private boolean preview(Curve next) {
    try {
      new ScalarCurve(next);
      curve = next;
      error = "";
      return true;
    } catch (RuntimeException invalid) {
      error = invalid.getMessage();
      return false;
    }
  }

  private void trim() {
    while (undo.size() > 100) undo.removeLast();
  }

  public static Point point(Curve c, UUID id) {
    return c.points.stream().filter(p -> p.uuid.equals(id)).findFirst().orElseThrow();
  }

  private static Curve copy(Curve c) {
    return ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(c), Curve.class);
  }

  private static boolean equal(Curve a, Curve b) {
    return ProjectIO.JSON.valueToTree(a).equals(ProjectIO.JSON.valueToTree(b));
  }
}
