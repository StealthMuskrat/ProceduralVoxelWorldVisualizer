package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.function.Consumer;

/** Isolated curve edit context; Apply validates one parent draft change, Cancel discards. */
public final class CurveDialog {
  private final Curve curve;
  private final Consumer<Curve> apply;
  private final UiFields f = new UiFields();
  private final ImBoolean open = new ImBoolean(true);
  private final Set<UUID> selected = new LinkedHashSet<>();
  private boolean grid = true, snapX = false, snapY = false;
  private double stepX = .05, stepY = .05, zoom = 1, panX = 0, panY = 0;
  private String error = "";
  private boolean dragging = false;
  private int tangentSide = 0;

  public CurveDialog(Curve c, Consumer<Curve> apply) {
    curve = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(c), Curve.class);
    this.apply = apply;
  }

  public boolean draw() {
    ImGui.setNextWindowSize(800, 650, ImGuiCond.FirstUseEver);
    ImGui.begin("Curve Editor", open);
    f.number(
        "Domain minimum",
        curve.min,
        v -> {
          curve.min = v;
          curve.points.getFirst().x = v;
        });
    f.number(
        "Domain maximum",
        curve.max,
        v -> {
          curve.max = v;
          curve.points.getLast().x = v;
        });
    f.choice(
        "Outside domain",
        curve.outsideDomain,
        List.of("CLAMP", "EXTEND", "REPEAT", "MIRROR"),
        v -> curve.outsideDomain = v);
    f.bool("Grid", grid, v -> grid = v);
    ImGui.sameLine();
    f.bool("Snap X", snapX, v -> snapX = v);
    ImGui.sameLine();
    f.bool("Snap Y", snapY, v -> snapY = v);
    f.number("X increment", stepX, v -> stepX = v);
    f.number("Y increment", stepY, v -> stepY = v);
    float px = ImGui.getCursorScreenPosX(),
        py = ImGui.getCursorScreenPosY(),
        w = Math.max(100, ImGui.getContentRegionAvailX()),
        h = Math.max(150, ImGui.getContentRegionAvailY() - 170);
    ImGui.invisibleButton(
        "curve-plot",
        w,
        h,
        ImGuiButtonFlags.MouseButtonLeft
            | ImGuiButtonFlags.MouseButtonMiddle
            | ImGuiButtonFlags.MouseButtonRight);
    boolean hovered = ImGui.isItemHovered();
    var draw = ImGui.getWindowDrawList();
    draw.addRectFilled(px, py, px + w, py + h, ImGui.getColorU32(.055f, .07f, .09f, 1));
    double range = Math.max(1e-9, curve.max - curve.min),
        lo = curve.min + panX - range * (1 / zoom - 1) / 2,
        hi = lo + range / zoom,
        ylo = -.2 + panY - (1 / zoom - 1) / 2,
        yhi = ylo + 1.4 / zoom;
    if (grid)
      for (int i = 0; i <= 10; i++) {
        float x = px + i * w / 10, y = py + i * h / 10;
        draw.addLine(x, py, x, py + h, 0x223b4c5e);
        draw.addLine(px, y, px + w, y, 0x223b4c5e);
      }
    try {
      var compiled = new ScalarCurve(curve);
      float lx = px, ly = screenY(compiled.sample(lo), py, h, ylo, yhi);
      for (int i = 1; i <= 256; i++) {
        double x = lo + (hi - lo) * i / 256;
        float sx = px + w * i / 256, sy = screenY(compiled.sample(x), py, h, ylo, yhi);
        draw.addLine(lx, ly, sx, sy, 0xffbce37b, 2);
        lx = sx;
        ly = sy;
      }
    } catch (Exception e) {
      error = e.getMessage();
    }
    double mx = lo + (ImGui.getIO().getMousePosX() - px) / w * (hi - lo),
        my = yhi - (ImGui.getIO().getMousePosY() - py) / h * (yhi - ylo);
    Point hit = null;
    double best = 100;
    for (var point : curve.points) {
      float sx = screenX(point.x, px, w, lo, hi), sy = screenY(point.y, py, h, ylo, yhi);
      draw.addCircleFilled(
          sx,
          sy,
          selected.contains(point.uuid) ? 6 : 4,
          selected.contains(point.uuid) ? 0xff78b7ff : 0xffeeeeee);
      double d = Math.hypot(sx - ImGui.getIO().getMousePosX(), sy - ImGui.getIO().getMousePosY());
      if (d < best) {
        best = d;
        hit = point;
      }
    }
    if (hovered) {
      if (ImGui.getIO().getMouseWheel() != 0)
        zoom = Math.max(.1, Math.min(100, zoom * Math.exp(ImGui.getIO().getMouseWheel() * .12)));
      if (ImGui.isMouseDragging(2)) {
        panX -= ImGui.getIO().getMouseDeltaX() / w * (hi - lo);
        panY += ImGui.getIO().getMouseDeltaY() / h * (yhi - ylo);
      }
      if (ImGui.isMouseClicked(0) && best < 10) {
        if (!ImGui.getIO().getKeyShift()) selected.clear();
        selected.add(hit.uuid);
        dragging = true;
        f.clear();
      } else if (ImGui.isMouseDoubleClicked(0) && mx > curve.min && mx < curve.max) {
        double x =
            snapX && !ImGui.getIO().getKeyAlt() && stepX > 0 ? Math.rint(mx / stepX) * stepX : mx;
        if (curve.points.stream().noneMatch(p -> Math.abs(p.x - x) < 1e-10)) {
          var point = new Point(x, my);
          curve.points.add(point);
          curve.points.sort(Comparator.comparingDouble(p -> p.x));
          selected.clear();
          selected.add(point.uuid);
        }
      }
      if (dragging && ImGui.isMouseDown(0)) {
        double dx = ImGui.getIO().getMouseDeltaX() / w * (hi - lo),
            dy = -ImGui.getIO().getMouseDeltaY() / h * (yhi - ylo);
        var before = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(curve), Curve.class);
        for (int i = 0; i < curve.points.size(); i++) {
          var point = curve.points.get(i);
          if (selected.contains(point.uuid)) {
            if (i > 0 && i < curve.points.size() - 1) point.x += dx;
            point.y += dy;
            if (!ImGui.getIO().getKeyAlt()) {
              if (snapX && stepX > 0 && i > 0 && i < curve.points.size() - 1)
                point.x = Math.rint(point.x / stepX) * stepX;
              if (snapY && stepY > 0) point.y = Math.rint(point.y / stepY) * stepY;
            }
          }
        }
        try {
          new ScalarCurve(curve);
          error = "";
        } catch (Exception e) {
          curve.points = before.points;
          error = e.getMessage();
        }
      }
    }
    if (ImGui.isMouseReleased(0)) dragging = false;
    var point = curve.points.stream().filter(p -> selected.contains(p.uuid)).findFirst();
    if (point.isPresent()) {
      var p = point.get();
      boolean endpoint = p == curve.points.getFirst() || p == curve.points.getLast();
      if (!endpoint) f.number("X", p.x, v -> p.x = v);
      else ImGui.text("Endpoint X: " + p.x + " (domain locked)");
      f.number("Y", p.y, v -> p.y = v);
      f.choice(
          "Next segment",
          p.segmentInterpolation,
          List.of("STEP", "LINEAR", "SMOOTH", "BEZIER"),
          v -> p.segmentInterpolation = v);
      if (p.segmentInterpolation.equals("BEZIER")) {
        f.choice(
            "Handle mode", p.handleMode, List.of("AUTO", "ALIGNED", "FREE"), v -> p.handleMode = v);
        f.number(
            "Incoming slope",
            p.inTangent,
            v -> {
              p.inTangent = v;
              if (p.handleMode.equals("ALIGNED")) p.outTangent = v;
            });
        f.number(
            "Outgoing slope",
            p.outTangent,
            v -> {
              p.outTangent = v;
              if (p.handleMode.equals("ALIGNED")) p.inTangent = v;
            });
      }
    }
    if (ImGui.button("Delete Selected Interior Points"))
      curve.points.removeIf(
          p ->
              p != curve.points.getFirst()
                  && p != curve.points.getLast()
                  && selected.contains(p.uuid));
    if (!error.isEmpty()) ImGui.textWrapped("! " + error);
    if (ImGui.button("Apply")) {
      try {
        new ScalarCurve(curve);
        apply.accept(curve);
        open.set(false);
      } catch (Exception e) {
        error = e.getMessage();
      }
    }
    ImGui.sameLine();
    if (ImGui.button("Cancel")) open.set(false);
    ImGui.end();
    return !open.get();
  }

  private static float screenX(double x, float px, float w, double lo, double hi) {
    return (float) (px + (x - lo) / (hi - lo) * w);
  }

  private static float screenY(double y, float py, float h, double lo, double hi) {
    return (float) (py + h - (y - lo) / (hi - lo) * h);
  }
}
