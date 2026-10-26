package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Private edit session. Apply validates once into the parent draft; Cancel discards local history.
 */
public final class CurveDialog {
  private final CurveEditSession session;
  private final Consumer<Curve> apply;
  private final UiFields fields = new UiFields();
  private final ImBoolean open = new ImBoolean(true);
  private final Set<UUID> selected = new LinkedHashSet<>();
  private boolean grid = true, snapX = false, snapY = false, dragging = false;
  private double stepX = .05, stepY = .05, dragX, dragY;
  private final CurveView view = new CurveView();
  private boolean panning;
  private double panMouseX, panMouseY;

  CurveView.Bounds probeView() {
    return view.bounds();
  }

  double probeZoom() {
    return view.zoom();
  }

  private boolean focused;
  private UUID tangentPoint;
  private int tangentSide;
  private double tangentX;
  private String applyError = "";
  private boolean probing;

  void probeEnable() {
    probing = true;
  }

  private final Map<String, float[]> controls = new HashMap<>();
  private final Map<UUID, float[]> pointCenters = new HashMap<>();
  private final Map<String, float[]> handleCenters = new HashMap<>();

  float[] probeHandle(UUID id, int side) {
    return handleCenters.get(id + ":" + side);
  }

  float[] probeControl(String key) {
    return controls.get(key);
  }

  float[] probePoint(UUID id) {
    return pointCenters.get(id);
  }

  Curve probeCurve() {
    return io.voxelcreator.model.ProjectIO.JSON.convertValue(
        io.voxelcreator.model.ProjectIO.JSON.valueToTree(session.curve()), Curve.class);
  }

  boolean probeCanUndo() {
    return session.canUndo();
  }

  private void controlRect(String key) {
    if (!probing) return;
    controls.put(
        key,
        new float[] {
          (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
          (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
        });
  }

  private void stopDrag() {
    dragging = false;
    tangentPoint = null;
    panning = false;
    view.endPan();
  }

  public CurveDialog(Curve curve, Consumer<Curve> apply) {
    session = new CurveEditSession(curve);
    view.synchronize(curve.min, curve.max, curve.displayMin, curve.displayMax);
    this.apply = apply;
  }

  public boolean focused() {
    return open.get() && focused;
  }

  public void shortcut(String action) {
    if (Set.of("CurveDelete", "CurveUndo", "CurveRedo").contains(action)) stopDrag();
    switch (action) {
      case "CurveDelete" -> deleteSelected();
      case "CurveUndo" -> session.undo();
      case "CurveRedo" -> session.redo();
      default -> {}
    }
  }

  public boolean selectPoint(UUID id) {
    var point = session.curve().points.stream().filter(p -> p.uuid.equals(id)).findFirst();
    if (point.isEmpty()) return false;
    selected.clear();
    selected.add(id);
    var c = session.curve();
    view.synchronize(c.min, c.max, c.displayMin, c.displayMax);
    view.centerAt(point.get().x, point.get().y);
    return true;
  }

  Set<UUID> selectedPoints() {
    return Set.copyOf(selected);
  }

  public void selectAll() {
    selected.clear();
    session.curve().points.forEach(p -> selected.add(p.uuid));
  }

  public void cancel() {
    session.cancelGesture();
    view.cancelPan();
    stopDrag();
    open.set(false);
  }

  public boolean draw() {
    ImGui.setNextWindowSize(840, 760, ImGuiCond.FirstUseEver);
    ImGui.begin("Curve Editor", open);
    focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
    Curve curve = session.curve();
    fields.number(
        "Domain minimum",
        curve.min,
        v ->
            session.edit(
                c -> {
                  c.min = v;
                  c.points.getFirst().x = v;
                }));
    fields.number(
        "Domain maximum",
        curve.max,
        v ->
            session.edit(
                c -> {
                  c.max = v;
                  c.points.getLast().x = v;
                }));
    fields.number("Display minimum", curve.displayMin, v -> session.edit(c -> c.displayMin = v));
    fields.number("Display maximum", curve.displayMax, v -> session.edit(c -> c.displayMax = v));
    fields.choice(
        "Outside domain",
        curve.outsideDomain,
        List.of("CLAMP", "EXTEND", "REPEAT", "MIRROR"),
        v -> session.edit(c -> c.outsideDomain = v));
    fields.bool("Grid", grid, v -> grid = v);
    ImGui.sameLine();
    fields.bool("Snap X", snapX, v -> snapX = v);
    ImGui.sameLine();
    fields.bool("Snap Y", snapY, v -> snapY = v);
    controlRect("snapY");
    fields.number(
        "X increment",
        stepX,
        v -> {
          if (v > 0 && Double.isFinite(v)) stepX = v;
        });
    fields.number(
        "Y increment",
        stepY,
        v -> {
          if (v > 0 && Double.isFinite(v)) stepY = v;
        });
    if (ImGui.button("Undo") && session.canUndo()) {
      stopDrag();
      session.undo();
    }
    controlRect("undo");
    ImGui.sameLine();
    if (ImGui.button("Redo") && session.canRedo()) {
      stopDrag();
      session.redo();
    }
    controlRect("redo");
    ImGui.sameLine();
    if (ImGui.button("Fit")) {
      stopDrag();
      session.endGesture();
      view.fit();
    }
    controlRect("fit");
    curve = session.curve();
    var compiled = new ScalarCurve(curve);
    float px = ImGui.getCursorScreenPosX(),
        py = ImGui.getCursorScreenPosY(),
        w = Math.max(150, ImGui.getContentRegionAvailX()),
        h = Math.max(170, ImGui.getContentRegionAvailY() - 240);
    ImGui.invisibleButton(
        "curve-plot",
        w,
        h,
        ImGuiButtonFlags.MouseButtonLeft
            | ImGuiButtonFlags.MouseButtonMiddle
            | ImGuiButtonFlags.MouseButtonRight);
    if (probing) controls.put("plot", new float[] {px, py, w, h});
    pointCenters.clear();
    handleCenters.clear();
    boolean hovered = ImGui.isItemHovered();
    var io = ImGui.getIO();
    view.synchronize(curve.min, curve.max, curve.displayMin, curve.displayMax);
    if (hovered && !dragging && ImGui.isMouseClicked(2)) {
      panning = true;
      panMouseX = io.getMousePosX();
      panMouseY = io.getMousePosY();
      view.beginPan();
    }
    if (panning) {
      if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
        view.cancelPan();
        panning = false;
      } else {
        view.pan((panMouseX - io.getMousePosX()) / w, (io.getMousePosY() - panMouseY) / h);
        panMouseX = io.getMousePosX();
        panMouseY = io.getMousePosY();
        if (!ImGui.isMouseDown(2)) {
          view.endPan();
          panning = false;
        }
      }
    }
    if (hovered && !dragging && !panning && io.getMouseWheel() != 0)
      view.wheel(
          (io.getMousePosX() - px) / w, (py + h - io.getMousePosY()) / h, io.getMouseWheel());
    var bounds = view.bounds();
    double lo = bounds.minX(), hi = bounds.maxX(), ylo = bounds.minY(), yhi = bounds.maxY();
    double mx = lo + (io.getMousePosX() - px) / w * (hi - lo),
        my = yhi - (io.getMousePosY() - py) / h * (yhi - ylo);
    var draw = ImGui.getWindowDrawList();
    draw.addRectFilled(px, py, px + w, py + h, ImGui.getColorU32(.055f, .07f, .09f, 1));
    draw.pushClipRect(px, py, px + w, py + h, true);
    if (grid)
      for (int i = 0; i <= 10; i++) {
        float sx = px + i * w / 10, sy = py + i * h / 10;
        draw.addLine(sx, py, sx, py + h, 0x333b4c5e);
        draw.addLine(px, sy, px + w, sy, 0x333b4c5e);
      }
    if (lo <= 0 && hi >= 0)
      draw.addLine(sx(0, px, w, lo, hi), py, sx(0, px, w, lo, hi), py + h, 0x88708090);
    if (ylo <= 0 && yhi >= 0)
      draw.addLine(px, sy(0, py, h, ylo, yhi), px + w, sy(0, py, h, ylo, yhi), 0x88708090);
    float lastX = px, lastY = sy(compiled.sample(lo), py, h, ylo, yhi);
    for (int i = 1; i <= 512; i++) {
      double x = lo + (hi - lo) * i / 512;
      float nx = px + w * i / 512, ny = sy(compiled.sample(x), py, h, ylo, yhi);
      draw.addLine(lastX, lastY, nx, ny, 0xffbce37b, 2);
      lastX = nx;
      lastY = ny;
    }
    Point hit = null;
    UUID handleHit = null;
    int sideHit = 0;
    double handleX = 0, best = 10;
    for (int i = 0; i < curve.points.size(); i++) {
      Point point = curve.points.get(i);
      float x = sx(point.x, px, w, lo, hi), y = sy(point.y, py, h, ylo, yhi);
      if (probing) pointCenters.put(point.uuid, new float[] {x, y});
      if (selected.contains(point.uuid))
        for (int side : List.of(-1, 1)) {
          boolean visible =
              side < 0
                  ? i > 0 && curve.points.get(i - 1).segmentInterpolation.equals("BEZIER")
                  : i < curve.points.size() - 1 && point.segmentInterpolation.equals("BEZIER");
          if (!visible) continue;
          double
              offset =
                  (side < 0
                          ? point.x - curve.points.get(i - 1).x
                          : curve.points.get(i + 1).x - point.x)
                      / 3
                      * side,
              slope = side < 0 ? compiled.incomingSlope(i) : compiled.outgoingSlope(i);
          float hx = sx(point.x + offset, px, w, lo, hi),
              hy = sy(point.y + offset * slope, py, h, ylo, yhi);
          if (probing) handleCenters.put(point.uuid + ":" + side, new float[] {hx, hy});
          draw.addLine(x, y, hx, hy, 0xffc8a374);
          draw.addCircleFilled(
              hx, hy, 4, point.handleMode.equals("AUTO") ? 0xff777777 : 0xffe9bd8d);
          double distance = Math.hypot(hx - io.getMousePosX(), hy - io.getMousePosY());
          if (distance < best) {
            best = distance;
            handleHit = point.uuid;
            sideHit = side;
            handleX = offset;
            hit = null;
          }
        }
      draw.addCircleFilled(
          x,
          y,
          selected.contains(point.uuid) ? 6 : 4,
          selected.contains(point.uuid) ? 0xff78b7ff : 0xffeeeeee);
      double distance = Math.hypot(x - io.getMousePosX(), y - io.getMousePosY());
      if (distance < best) {
        best = distance;
        hit = point;
        handleHit = null;
      }
    }
    draw.popClipRect();
    if (hovered && !panning) {
      if (ImGui.isMouseClicked(0)) {
        if (handleHit != null) {
          tangentPoint = handleHit;
          tangentSide = sideHit;
          tangentX = handleX;
          dragY = my;
          session.beginGesture();
          dragging = true;
        } else if (hit != null) {
          if (io.getKeyShift()) {
            if (!selected.add(hit.uuid)) selected.remove(hit.uuid);
          } else if (!selected.contains(hit.uuid)) {
            selected.clear();
            selected.add(hit.uuid);
          }
          dragX = mx;
          dragY = my;
          tangentPoint = null;
          session.beginGesture();
          dragging = !selected.isEmpty();
        } else if (!io.getKeyShift()) selected.clear();
      }
      if (ImGui.isMouseDoubleClicked(0)
          && hit == null
          && handleHit == null
          && mx > curve.min
          && mx < curve.max
          && Math.abs(sy(compiled.sample(mx), py, h, ylo, yhi) - io.getMousePosY()) < 10) {
        UUID id =
            session.insert(mx, snapX && !io.getKeyAlt(), snapY && !io.getKeyAlt(), stepX, stepY);
        if (id != null) {
          selected.clear();
          selected.add(id);
        }
      }
    }
    if (dragging && ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      session.cancelGesture();
      stopDrag();
    }
    if (dragging && ImGui.isMouseDown(0)) {
      if (tangentPoint != null) {
        Point p = CurveEditSession.point(session.curve(), tangentPoint);
        session.moveTangent(p.uuid, tangentSide, my - dragY, tangentX);
      } else
        session.move(
            selected,
            mx - dragX,
            my - dragY,
            snapX && !io.getKeyAlt(),
            snapY && !io.getKeyAlt(),
            stepX,
            stepY);
    }
    if (ImGui.isMouseReleased(0) && dragging) {
      dragging = false;
      tangentPoint = null;
      session.endGesture();
    }
    if (ImGui.beginPopupContextItem("curve-actions")) {
      if (ImGui.menuItem("Select All")) {
        selected.clear();
        session.curve().points.forEach(p -> selected.add(p.uuid));
      }
      if (ImGui.menuItem("Clear Selection")) selected.clear();
      if (ImGui.menuItem("Delete Selected Interior Points")) deleteSelected();
      if (ImGui.menuItem("Fit View")) {
        stopDrag();
        session.endGesture();
        view.fit();
      }
      ImGui.endPopup();
    }
    ImGui.textDisabled(
        String.format(
            Locale.ROOT,
            "Input %.5f  Output %.5f  |  %d selected; Shift+click selects; Escape cancels drag",
            mx,
            my,
            selected.size()));
    curve = session.curve();
    var selection = curve.points.stream().filter(p -> selected.contains(p.uuid)).toList();
    if (selection.size() == 1) {
      Point point = selection.getFirst();
      UUID id = point.uuid;
      boolean endpoint = point == curve.points.getFirst() || point == curve.points.getLast();
      if (!endpoint)
        fields.number("X", point.x, v -> session.edit(c -> CurveEditSession.point(c, id).x = v));
      else
        ImGui.textDisabled(String.format(Locale.ROOT, "Endpoint X %.5f (domain locked)", point.x));
      fields.number("Y", point.y, v -> session.edit(c -> CurveEditSession.point(c, id).y = v));
      fields.choice(
          "Next segment",
          point.segmentInterpolation,
          List.of("STEP", "LINEAR", "SMOOTH", "BEZIER"),
          v -> session.edit(c -> CurveEditSession.point(c, id).segmentInterpolation = v));
      int index = curve.points.indexOf(point);
      if (point.segmentInterpolation.equals("BEZIER")
          || index > 0 && curve.points.get(index - 1).segmentInterpolation.equals("BEZIER")) {
        fields.choice(
            "Handle mode",
            point.handleMode,
            List.of("AUTO", "ALIGNED", "FREE"),
            v ->
                session.edit(
                    c -> {
                      Point p = CurveEditSession.point(c, id);
                      var definition = new ScalarCurve(c);
                      int pointIndex = c.points.indexOf(p);
                      double incoming = definition.incomingSlope(pointIndex),
                          outgoing = definition.outgoingSlope(pointIndex);
                      p.handleMode = v;
                      if (!v.equals("AUTO")) {
                        p.inTangent = incoming;
                        p.outTangent = outgoing;
                      }
                      if (v.equals("ALIGNED")) p.inTangent = p.outTangent;
                    }));
        if (!point.handleMode.equals("AUTO")) {
          fields.number(
              "Incoming slope",
              point.inTangent,
              v ->
                  session.edit(
                      c -> {
                        Point p = CurveEditSession.point(c, id);
                        p.inTangent = v;
                        if (p.handleMode.equals("ALIGNED")) p.outTangent = v;
                      }));
          fields.number(
              "Outgoing slope",
              point.outTangent,
              v ->
                  session.edit(
                      c -> {
                        Point p = CurveEditSession.point(c, id);
                        p.outTangent = v;
                        if (p.handleMode.equals("ALIGNED")) p.inTangent = v;
                      }));
        } else
          ImGui.textDisabled(
              "Auto slopes follow the monotone curve. Choose Aligned/Free to drag handles.");
      }
    } else if (selection.size() > 1)
      ImGui.textWrapped(
          "Drag any selected point to move the group. Endpoint X values stay locked.");
    if (ImGui.button("Delete Selected Interior Points")) deleteSelected();
    controlRect("delete");
    if (!session.error().isEmpty()) ImGui.textWrapped("! " + session.error());
    if (ImGui.button("Apply")) {
      session.endGesture();
      stopDrag();
      try {
        apply.accept(session.curve());
        open.set(false);
      } catch (RuntimeException invalid) {
        applyError = Objects.requireNonNullElse(invalid.getMessage(), "Curve Apply failed");
      }
    }
    controlRect("apply");
    ImGui.sameLine();
    if (ImGui.button("Cancel")) cancel();
    controlRect("cancel");
    if (!applyError.isEmpty()) ImGui.textWrapped("! " + applyError);
    ImGui.end();
    return !open.get();
  }

  private void deleteSelected() {
    stopDrag();
    session.endGesture();
    session.removeInterior(selected);
    selected.retainAll(session.curve().points.stream().map(p -> p.uuid).toList());
  }

  private static float sx(double x, float px, float w, double lo, double hi) {
    return (float) Math.max(-1e7, Math.min(1e7, px + (x - lo) / (hi - lo) * w));
  }

  private static float sy(double y, float py, float h, double lo, double hi) {
    return (float) Math.max(-1e7, Math.min(1e7, py + h - (y - lo) / (hi - lo) * h));
  }
}
