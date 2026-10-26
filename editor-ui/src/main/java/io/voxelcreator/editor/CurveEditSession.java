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
    endGesture();
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
    if (gesture == null) gesture = copy(curve);
  }

  /** Restore the exact pre-drag curve without publishing history or clearing redo. */
  public void cancelGesture() {
    if (gesture != null) curve = gesture;
    gesture = null;
    error = "";
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

  /** Pointer deltas are relative to the exact baseline, avoiding a jump on handle selection. */
  public boolean moveTangent(UUID id, int side, double dy, double handleX) {
    if (gesture == null) beginGesture();
    if ((side != -1 && side != 1)
        || !Double.isFinite(dy)
        || !Double.isFinite(handleX)
        || handleX == 0) {
      error = "A finite nonzero tangent handle and finite movement are required";
      return false;
    }
    Point baseline = point(gesture, id);
    var compiled = new ScalarCurve(gesture);
    int index = gesture.points.indexOf(baseline);
    double slope = side < 0 ? compiled.incomingSlope(index) : compiled.outgoingSlope(index);
    return tangent(id, side, slope + dy / handleX);
  }

  /**
   * Insert at the canonical sampled value, with explicit X/Y snapping and inherited segment mode.
   */
  public UUID insert(double requestedX, boolean snapX, boolean snapY, double stepX, double stepY) {
    UUID id = UUID.randomUUID();
    boolean inserted =
        edit(
            c -> {
              double x = snap(requestedX, snapX, stepX);
              if (!Double.isFinite(x) || x <= c.min || x >= c.max)
                throw new IllegalArgumentException(
                    "New point X must lie strictly inside the domain");
              double y = snap(new ScalarCurve(c).sample(x), snapY, stepY);
              var next = new Point(x, y);
              next.uuid = id;
              for (int i = 0; i < c.points.size() - 1; i++)
                if (c.points.get(i).x <= x && x < c.points.get(i + 1).x) {
                  next.segmentInterpolation = c.points.get(i).segmentInterpolation;
                  break;
                }
              c.points.add(next);
              c.points.sort(Comparator.comparingDouble(p -> p.x));
            });
    return inserted ? id : null;
  }

  public boolean removeInterior(Set<UUID> selection) {
    return edit(
        c -> {
          UUID first = c.points.getFirst().uuid, last = c.points.getLast().uuid;
          c.points.removeIf(
              p -> !p.uuid.equals(first) && !p.uuid.equals(last) && selection.contains(p.uuid));
        });
  }

  private static double snap(double value, boolean enabled, double step) {
    if (!enabled) return value;
    if (!Double.isFinite(step) || step <= 0)
      throw new IllegalArgumentException("Snap increment must be finite and positive");
    return StrictMath.rint(value / step) * step;
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
