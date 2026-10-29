package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.CurveModes;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Private edit session. Apply validates once into the parent draft; Cancel discards local history.
 */
public final class CurveDialog {
  private final CurveEditSession session;
  private final CurveApplySession publication;
  private final EditorState ownerState;
  private final CurveDraftEdit owner;
  private final UiFields fields = new UiFields();
  final FloatingWindowLayout layout = new FloatingWindowLayout(840, 760);
  private DialogBounds.Rect bodyBounds;
  private float bodyScroll;

  DialogBounds.Rect probeBodyBounds() {
    return bodyBounds;
  }

  float probeBodyScroll() {
    return bodyScroll;
  }

  private final ImBoolean open = new ImBoolean(true);
  private final Set<UUID> selected = new LinkedHashSet<>();
  private boolean grid = true, snapX = false, snapY = false;
  private double stepX = .05, stepY = .05;
  private final CurveView view = new CurveView();
  private final CurvePointerGesture pointer;
  private final CurveControlFocus controlFocus = new CurveControlFocus();
  private CurveControlFocus.Destination focusDestination;

  boolean focusControl(UUID point, String field) {
    if (point != null && !selectPoint(point)) return false;
    controlFocus.request(point, field);
    return true;
  }

  String probeFocusedControl() {
    return controlFocus.acknowledged();
  }

  private void focusField(String key, Runnable draw) {
    boolean submitted = controlFocus.submit(key, focusDestination);
    if (submitted) ImGui.setKeyboardFocusHere();
    draw.run();
    if (controlFocus.matches(key)) {
      ImGui.setScrollHereY(.25f);
      if (ImGui.isItemActive() || ImGui.isItemFocused()) controlFocus.acknowledge(key);
      else if (!submitted && ImGui.isMouseClicked(0) || ImGui.isKeyPressed(ImGuiKey.Escape, false))
        controlFocus.cancel();
    }
  }

  CurveView.Bounds probeView() {
    return view.bounds();
  }

  void probeCenterView(double x, double y) {
    if (!view.centerAt(x, y)) throw new IllegalArgumentException("Probe view is not representable");
  }

  double probeZoom() {
    return view.zoom();
  }

  private boolean focused;
  private int previewFailures;
  private String previewFailure = "";

  int probePreviewFailures() {
    return previewFailures;
  }

  String probePreviewFailure() {
    return previewFailure;
  }

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

  String probeError() {
    return session.error();
  }

  private String fieldLabel(String label) {
    float available = Math.max(1, ImGui.getContentRegionAvailX());
    float text = ImGui.calcTextSize(label).x + 24;
    if (available < text + 160) {
      ImGui.textWrapped(label);
      ImGui.setNextItemWidth(available);
      return "###curve-" + label;
    }
    ImGui.setNextItemWidth(available - text);
    return label + "###curve-" + label;
  }

  private void toggleSameLine(String label) {
    ImGui.sameLine();
    if (ImGui.getContentRegionAvailX() < ImGui.calcTextSize(label).x + ImGui.getFrameHeight() + 8)
      ImGui.newLine();
  }

  private void number(String label, double value, DoubleConsumer set) {
    fields.number(fieldLabel(label), value, set);
  }

  private void modeChoice(String label, String value, List<String> options, Consumer<String> set) {
    if (ImGui.beginCombo(fieldLabel(label), value)) {
      UiScale.window(ownerState == null ? null : ownerState.user, "Curve Editor");
      for (String option : options) {
        if (ImGui.selectable(option, option.equals(value))) set.accept(option);
        controlRect(label + "/" + option);
      }
      ImGui.endCombo();
    }
    controlRect(label);
    fields.tooltip(CurveModes.help(value));
  }

  private void stopDrag() {
    pointer.end();
  }

  public CurveDialog(Curve curve, Consumer<Curve> apply) {
    this(curve, apply, () -> {}, null, null);
  }

  CurveDialog(Curve curve, EditorState state, CurveDraftEdit owner, Runnable accepted) {
    this(curve, value -> owner.stage(state, value), accepted, state, owner);
  }

  private CurveDialog(
      Curve curve,
      Consumer<Curve> stage,
      Runnable accepted,
      EditorState state,
      CurveDraftEdit owner) {
    session = new CurveEditSession(curve);
    pointer = new CurvePointerGesture(session, view);
    view.synchronize(curve.min, curve.max, curve.displayMin, curve.displayMax);
    publication = new CurveApplySession(stage, accepted);
    ownerState = state;
    this.owner = owner;
  }

  public boolean focused() {
    return open.get() && focused;
  }

  public void shortcut(String action) {
    if (!open.get()) return;
    controlFocus.cancel();
    if (Set.of("CurveDelete", "CurveUndo", "CurveRedo").contains(action)) stopDrag();
    switch (action) {
      case "CurveDelete" -> deleteSelected();
      case "CurveUndo" -> session.undo();
      case "CurveRedo" -> session.redo();
      default -> {}
    }
  }

  public boolean selectPoint(UUID id) {
    if (!open.get()) return false;
    stopDrag();
    controlFocus.cancel();
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
    if (!open.get()) return;
    stopDrag();
    controlFocus.cancel();
    selected.clear();
    session.curve().points.forEach(p -> selected.add(p.uuid));
  }

  public void cancel() {
    publication.cancel();
    if (owner != null) owner.cancel();
    controlFocus.cancel();
    pointer.cancel();
    session.cancelGesture();
    view.cancelPan();
    focused = false;
    open.set(false);
  }

  public boolean draw() {
    if (!open.get() || publication.closed()) return true;
    layout.prepare();
    if (controlFocus.windowRequest()) ImGui.setNextWindowFocus();
    boolean visible = ImGui.begin("Curve Editor", open);
    UiScale.window(ownerState == null ? null : ownerState.user, "Curve Editor");
    layout.observe();
    if (!open.get()) {
      cancel();
      ImGui.end();
      return true;
    }
    if (!visible) {
      pointer.cancel();
      focused = false;
      ImGui.end();
      return false;
    }
    focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
    if (!focused || ImGui.getIO().getAppFocusLost() || ShortcutKeys.popup()) pointer.cancel();
    float bodyHeight =
        CurveLayout.body(ImGui.getContentRegionAvailY(), ImGui.getFrameHeightWithSpacing());
    ImGui.beginChild("curve-content", 0, bodyHeight, false, ImGuiWindowFlags.NoScrollWithMouse);
    ImGui.setWindowFontScale(1); // Inherit the root multiplier exactly once.
    bodyBounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    Curve curve = session.curve();
    final Curve initial = curve;
    focusDestination = controlFocus.destination(curve);
    if (focusDestination != null && !focusDestination.explanation().isEmpty())
      ImGui.textWrapped(focusDestination.explanation());
    focusField(
        "min",
        () ->
            number(
                "Domain minimum",
                initial.min,
                v ->
                    session.edit(
                        c -> {
                          c.min = v;
                          c.points.getFirst().x = v;
                        })));
    controlRect("domainMin");
    focusField(
        "max",
        () ->
            number(
                "Domain maximum",
                initial.max,
                v ->
                    session.edit(
                        c -> {
                          c.max = v;
                          c.points.getLast().x = v;
                        })));
    controlRect("domainMax");
    focusField(
        "displayMin",
        () ->
            number(
                "Display minimum", initial.displayMin, v -> session.edit(c -> c.displayMin = v)));
    controlRect("displayMin");
    focusField(
        "displayMax",
        () ->
            number(
                "Display maximum", initial.displayMax, v -> session.edit(c -> c.displayMax = v)));
    controlRect("displayMax");
    focusField(
        "outsideDomain",
        () ->
            modeChoice(
                "Outside domain",
                initial.outsideDomain,
                CurveModes.OUTSIDE,
                v -> session.edit(c -> c.outsideDomain = v)));
    fields.bool("Grid", grid, v -> grid = v);
    toggleSameLine("Snap X");
    fields.bool("Snap X", snapX, v -> snapX = v);
    toggleSameLine("Snap Y");
    fields.bool("Snap Y", snapY, v -> snapY = v);
    controlRect("snapY");
    number(
        "X increment",
        stepX,
        v -> {
          if (v > 0 && Double.isFinite(v)) stepX = v;
        });
    number(
        "Y increment",
        stepY,
        v -> {
          if (v > 0 && Double.isFinite(v)) stepY = v;
        });
    if (DialogLayout.button("Undo") && session.canUndo()) {
      controlFocus.cancel();
      stopDrag();
      session.undo();
    }
    controlRect("undo");
    DialogLayout.sameLine("Redo");
    if (DialogLayout.button("Redo") && session.canRedo()) {
      controlFocus.cancel();
      stopDrag();
      session.redo();
    }
    controlRect("redo");
    DialogLayout.sameLine("Fit");
    if (DialogLayout.button("Fit")) {
      stopDrag();
      session.endGesture();
      view.fit();
    }
    controlRect("fit");
    curve = session.curve();
    var compiled = new ScalarCurve(curve);
    var plotSize =
        CurveLayout.plot(
            ImGui.getContentRegionAvailX(), ImGui.getContentRegionAvailY(), bodyBounds.height());
    float px = ImGui.getCursorScreenPosX(),
        py = ImGui.getCursorScreenPosY(),
        w = plotSize.width(),
        h = plotSize.height();
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
    if (hovered && (ImGui.isMouseClicked(0) || ImGui.isMouseClicked(1) || ImGui.isMouseClicked(2)))
      controlFocus.cancel();
    var io = ImGui.getIO();
    view.synchronize(curve.min, curve.max, curve.displayMin, curve.displayMax);
    if (hovered
        && !io.getAppFocusLost()
        && !ShortcutKeys.popup()
        && (ImGui.isMouseClicked(0) || ImGui.isMouseClicked(2))) {
      ImGui.setWindowFocus("Curve Editor");
      focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
    }
    boolean eligible =
        focused
            && !io.getAppFocusLost()
            && !ShortcutKeys.popup()
            && io.getMousePosX() > -Float.MAX_VALUE
            && io.getMousePosY() > -Float.MAX_VALUE;
    var plot = new CurvePointerGesture.Plot(px, py, w, h, view.bounds());
    if (pointer.active()
        && (!eligible || !pointer.current(plot) || ImGui.isKeyPressed(ImGuiKey.Escape, false)))
      pointer.cancel();
    if (hovered
        && eligible
        && !pointer.active()
        && ImGui.isMouseClicked(2)
        && !ImGui.isKeyPressed(ImGuiKey.Escape, false)
        && !ImGui.isMouseDown(0)
        && !ImGui.isMouseDown(1)) pointer.beginPan(plot, io.getMousePosX(), io.getMousePosY());
    if (pointer.panning()) {
      if (ImGui.isMouseDown(2))
        pointer.move(plot, io.getMousePosX(), io.getMousePosY(), false, false, stepX, stepY);
      else if (ImGui.isMouseReleased(2))
        pointer.release(plot, io.getMousePosX(), io.getMousePosY(), false, false, stepX, stepY);
      else pointer.cancel();
    }
    if (hovered
        && eligible
        && !pointer.active()
        && !ImGui.isMouseDown(0)
        && !ImGui.isMouseDown(1)
        && !ImGui.isMouseDown(2)
        && io.getMouseWheel() != 0)
      view.wheel(
          (io.getMousePosX() - px) / w, (py + h - io.getMousePosY()) / h, io.getMouseWheel());
    plot = new CurvePointerGesture.Plot(px, py, w, h, view.bounds());
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
    var preview = CurvePreview.capture(compiled, lo, hi);
    previewFailures = preview.failures();
    previewFailure = preview.firstFailure() == null ? "" : preview.firstFailure().message();
    boolean previousValid = false;
    float lastX = 0, lastY = 0;
    for (int i = 0; i < preview.size(); i++) {
      if (!preview.valid(i)) {
        previousValid = false;
        continue;
      }
      float nx = sx(preview.input(i), px, w, lo, hi), ny = sy(preview.value(i), py, h, ylo, yhi);
      if (previousValid) draw.addLine(lastX, lastY, nx, ny, 0xffbce37b, 2);
      previousValid = true;
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
    if (hovered
        && eligible
        && !pointer.active()
        && !ImGui.isMouseDown(1)
        && !ImGui.isMouseDown(2)
        && !ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      if (ImGui.isMouseClicked(0)) {
        if (handleHit != null) {
          pointer.beginTangent(
              plot, io.getMousePosX(), io.getMousePosY(), handleHit, sideHit, handleX);
        } else if (hit != null) {
          if (io.getKeyShift()) {
            if (!selected.add(hit.uuid)) selected.remove(hit.uuid);
          } else if (!selected.contains(hit.uuid)) {
            selected.clear();
            selected.add(hit.uuid);
          }
          pointer.beginPoints(plot, io.getMousePosX(), io.getMousePosY(), selected);
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
    if (pointer.editing()) {
      if (ImGui.isMouseDown(0))
        pointer.move(
            plot,
            io.getMousePosX(),
            io.getMousePosY(),
            snapX && !io.getKeyAlt(),
            snapY && !io.getKeyAlt(),
            stepX,
            stepY);
      else if (ImGui.isMouseReleased(0))
        pointer.release(
            plot,
            io.getMousePosX(),
            io.getMousePosY(),
            snapX && !io.getKeyAlt(),
            snapY && !io.getKeyAlt(),
            stepX,
            stepY);
      else pointer.cancel();
    }
    if (ImGui.beginPopupContextItem("curve-actions")) {
      UiScale.window(ownerState == null ? null : ownerState.user, "Curve Editor");
      if (ImGui.menuItem("Select All")) selectAll();
      controlRect("menu/selectAll");
      if (ImGui.menuItem("Clear Selection")) selected.clear();
      controlRect("menu/clear");
      if (ImGui.menuItem("Delete Selected Interior Points")) deleteSelected();
      controlRect("menu/delete");
      if (ImGui.menuItem("Fit View")) {
        stopDrag();
        session.endGesture();
        view.fit();
      }
      controlRect("menu/fit");
      ImGui.endPopup();
    }
    ImGui.textWrapped(
        String.format(
            Locale.ROOT,
            "Input %.5f  Output %.5f  |  %d selected; Shift+click selects; Escape cancels drag",
            mx,
            my,
            selected.size()));
    if (preview.firstFailure() != null)
      ImGui.textWrapped(
          "! Curve preview: "
              + preview.failures()
              + " samples unavailable; first at "
              + preview.firstFailure().input()
              + ": "
              + preview.firstFailure().message()
              + ". Fit the view or adjust the curve. Failed samples are shown as gaps.");
    curve = session.curve();
    var selection = curve.points.stream().filter(p -> selected.contains(p.uuid)).toList();
    if (selection.size() == 1) {
      Point point = selection.getFirst();
      UUID id = point.uuid;
      boolean endpoint = point == curve.points.getFirst() || point == curve.points.getLast();
      if (!endpoint)
        focusField(
            "x",
            () ->
                number("X", point.x, v -> session.edit(c -> CurveEditSession.point(c, id).x = v)));
      else
        ImGui.textWrapped(String.format(Locale.ROOT, "Endpoint X %.5f (domain locked)", point.x));
      focusField(
          "y",
          () -> number("Y", point.y, v -> session.edit(c -> CurveEditSession.point(c, id).y = v)));
      controlRect("pointY");
      focusField(
          "segmentInterpolation",
          () ->
              modeChoice(
                  "Next segment",
                  point.segmentInterpolation,
                  CurveModes.INTERPOLATIONS,
                  v -> session.edit(c -> CurveEditSession.point(c, id).segmentInterpolation = v)));
      int index = curve.points.indexOf(point);
      if (point.segmentInterpolation.equals("BEZIER")
          || index > 0 && curve.points.get(index - 1).segmentInterpolation.equals("BEZIER")) {
        focusField(
            "handleMode",
            () ->
                modeChoice(
                    "Handle mode",
                    point.handleMode,
                    CurveModes.HANDLES,
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
                            })));
        if (!point.handleMode.equals("AUTO")) {
          focusField(
              "inTangent",
              () ->
                  number(
                      "Incoming slope",
                      point.inTangent,
                      v ->
                          session.edit(
                              c -> {
                                Point p = CurveEditSession.point(c, id);
                                p.inTangent = v;
                                if (p.handleMode.equals("ALIGNED")) p.outTangent = v;
                              })));
          focusField(
              "outTangent",
              () ->
                  number(
                      "Outgoing slope",
                      point.outTangent,
                      v ->
                          session.edit(
                              c -> {
                                Point p = CurveEditSession.point(c, id);
                                p.outTangent = v;
                                if (p.handleMode.equals("ALIGNED")) p.inTangent = v;
                              })));
        } else
          ImGui.textWrapped(
              "Auto slopes follow the monotone curve. Choose Aligned/Free to drag handles.");
      }
    } else if (selection.size() > 1)
      ImGui.textWrapped(
          "Drag any selected point to move the group. Endpoint X values stay locked.");
    if (DialogLayout.button("Delete Selected Interior Points")) deleteSelected();
    controlRect("delete");
    if (!session.error().isEmpty()) ImGui.textWrapped("! " + session.error());
    boolean current = owner == null || owner.current(ownerState);
    if (!current)
      ImGui.textWrapped(
          "The source or selection changed. Private curve edits are preserved; Cancel and reopen"
              + " the source before applying.");
    if (!publication.error().isEmpty()) ImGui.textWrapped("! " + publication.error());
    // The plot owns wheel zoom. Other body space scrolls the inspector without also zooming it.
    if (ImGui.isWindowHovered()
        && !hovered
        && !pointer.active()
        && !ShortcutKeys.popup()
        && !ImGui.isMouseDown(0)
        && !ImGui.isMouseDown(1)
        && !ImGui.isMouseDown(2)
        && io.getMouseWheel() != 0)
      ImGui.setScrollY(
          ImGui.getScrollY() - io.getMouseWheel() * ImGui.getTextLineHeightWithSpacing() * 3);
    bodyScroll = ImGui.getScrollY();
    ImGui.endChild();
    ImGui.beginDisabled(!current);
    if (DialogLayout.button("Apply")) {
      stopDrag();
      session.endGesture();
      if (publication.apply(session.curve())) {
        open.set(false);
        focused = false;
        var failure = publication.followUpFailure();
        if (failure != null && ownerState != null)
          ownerState.error(
              "CURVE_EDIT_REFRESH_FAILED",
              EditorEvents.Subsystem.APPLICATION,
              "Curve staged in Properties; UI refresh failed: "
                  + Objects.requireNonNullElse(failure.getMessage(), failure.toString()));
      }
    }
    controlRect("apply");
    ImGui.endDisabled();
    DialogLayout.sameLine("Cancel");
    if (DialogLayout.button("Cancel")) cancel();
    controlRect("cancel");
    ImGui.end();
    return !open.get();
  }

  private void deleteSelected() {
    controlFocus.cancel();
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
