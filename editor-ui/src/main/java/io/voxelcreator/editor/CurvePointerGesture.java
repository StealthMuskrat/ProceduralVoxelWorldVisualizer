package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData.Curve;
import java.util.*;

/** Plot-owned curve editing/panning with frozen geometry and complete release coordinates. */
final class CurvePointerGesture {
  record Plot(double x, double y, double width, double height, CurveView.Bounds bounds) {
    Plot {
      Objects.requireNonNull(bounds);
      if (!Double.isFinite(x)
          || !Double.isFinite(y)
          || !Double.isFinite(width)
          || !Double.isFinite(height)
          || width <= 0
          || height <= 0)
        throw new IllegalArgumentException("A finite positive curve plot is required");
    }

    boolean sameGeometry(Plot other) {
      return x == other.x && y == other.y && width == other.width && height == other.height;
    }
  }

  private enum Mode {
    POINTS,
    TANGENT,
    PAN
  }

  private final CurveEditSession session;
  private final CurveView view;
  private Mode mode;
  private Plot baseline;
  private Curve lastCurve;
  private CurveView.Bounds lastView;
  private double startX, startY, handleX;
  private Set<UUID> selected = Set.of();
  private UUID tangent;
  private int side;
  private boolean dragged;
  private long lastVersion;

  CurvePointerGesture(CurveEditSession session, CurveView view) {
    this.session = Objects.requireNonNull(session);
    this.view = Objects.requireNonNull(view);
  }

  boolean active() {
    return mode != null;
  }

  boolean editing() {
    return mode == Mode.POINTS || mode == Mode.TANGENT;
  }

  boolean panning() {
    return mode == Mode.PAN;
  }

  boolean beginPoints(Plot plot, double x, double y, Set<UUID> selection) {
    var ids = Set.copyOf(selection);
    if (ids.isEmpty() || !begin(Mode.POINTS, plot, x, y)) return false;
    selected = ids;
    session.beginGesture();
    lastVersion = session.interactionVersion();
    return true;
  }

  boolean beginTangent(Plot plot, double x, double y, UUID point, int side, double handleX) {
    if ((side != -1 && side != 1) || !Double.isFinite(handleX) || handleX == 0) return false;
    var value = CurveEditSession.point(session.curve(), point);
    if (value.handleMode.equals("AUTO") || !begin(Mode.TANGENT, plot, x, y)) return false;
    tangent = point;
    this.side = side;
    this.handleX = handleX;
    session.beginGesture();
    lastVersion = session.interactionVersion();
    return true;
  }

  boolean beginPan(Plot plot, double x, double y) {
    if (!begin(Mode.PAN, plot, x, y)) return false;
    view.beginPan();
    return true;
  }

  private boolean begin(Mode mode, Plot plot, double x, double y) {
    if (active()
        || !Double.isFinite(x)
        || !Double.isFinite(y)
        || !view.bounds().equals(plot.bounds())) return false;
    this.mode = mode;
    baseline = plot;
    startX = x;
    startY = y;
    dragged = false;
    lastCurve = session.curve();
    lastVersion = session.interactionVersion();
    lastView = view.bounds();
    return true;
  }

  boolean current(Plot plot) {
    return active()
        && baseline.sameGeometry(plot)
        && session.curve() == lastCurve
        && session.interactionVersion() == lastVersion
        && view.bounds().equals(lastView)
        && plot.bounds().equals(lastView);
  }

  boolean move(
      Plot plot, double x, double y, boolean snapX, boolean snapY, double stepX, double stepY) {
    if (!current(plot) || !Double.isFinite(x) || !Double.isFinite(y)) {
      cancel();
      return false;
    }
    double dx = (x - startX) / baseline.width(), dy = (startY - y) / baseline.height();
    if (!dragged) {
      dragged = Math.hypot(x - startX, y - startY) > 3;
      if (!dragged) return true;
    }
    boolean accepted =
        switch (mode) {
          case POINTS ->
              session.move(
                  selected,
                  dx * baseline.bounds().width(),
                  dy * baseline.bounds().height(),
                  snapX,
                  snapY,
                  stepX,
                  stepY);
          case TANGENT ->
              session.moveTangent(tangent, side, dy * baseline.bounds().height(), handleX);
          case PAN -> view.panFromStart(-dx, -dy);
        };
    lastCurve = session.curve();
    lastVersion = session.interactionVersion();
    lastView = view.bounds();
    return accepted;
  }

  boolean release(
      Plot plot, double x, double y, boolean snapX, boolean snapY, double stepX, double stepY) {
    boolean accepted = move(plot, x, y, snapX, snapY, stepX, stepY);
    if (active()) end();
    return accepted;
  }

  /** Finish the last accepted preview before an explicit local history/discrete command. */
  void end() {
    if (!active()) return;
    if (session.curve() != lastCurve
        || session.interactionVersion() != lastVersion
        || !view.bounds().equals(lastView)) {
      cancel();
      return;
    }
    if (panning()) view.endPan();
    else session.endGesture();
    reset();
  }

  /** Roll back only state still owned by this pointer; newer external edits always win. */
  void cancel() {
    if (!active()) return;
    if (session.curve() == lastCurve && editing() && session.interactionVersion() == lastVersion)
      session.cancelGesture();
    if (panning()) {
      if (view.bounds().equals(lastView)) view.cancelPan();
      else view.endPan();
    }
    reset();
  }

  private void reset() {
    mode = null;
    baseline = null;
    lastCurve = null;
    lastView = null;
    selected = Set.of();
    tangent = null;
  }
}
