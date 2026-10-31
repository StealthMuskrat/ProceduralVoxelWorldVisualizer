package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import java.util.*;
import java.util.function.Consumer;

/** Live theme preview with an independently readable inspector and explicit persistence. */
public final class ThemePanel {
  final FloatingWindowLayout windowLayout = new FloatingWindowLayout(980, 710);
  private final UserConfig user;
  final PreferenceActionScope actionScope = new PreferenceActionScope();
  final SettingsPageChrome layout = new SettingsPageChrome(5);
  private ThemeLayout.Panes panes;
  private ThemeLayout.Channels channelLayout;
  private DialogBounds.Rect paletteBounds, colorRowsBounds, contrastBounds;
  private boolean showContrast, revealPane;
  private final Map<String, Integer> channelIds = new LinkedHashMap<>();
  private final Map<String, DialogBounds.Rect> controlBounds = new LinkedHashMap<>();
  private final Map<String, Float> rowSteps = new LinkedHashMap<>(),
      rowStrides = new LinkedHashMap<>();
  private final Consumer<String> errors;
  private ThemeEditSession session;
  private ThemePreview preview;
  private ThemeFeedback feedback;
  private final ThemeRows rows = new ThemeRows();
  private final ThemeColorIssues colorIssues = new ThemeColorIssues();
  private final UiFields fields = new UiFields();
  private final ImString filter = new ImString(256);
  private String group = "All", message = "";
  private boolean smokeDrawn,
      smokeGuarded,
      smoke,
      probing,
      applyEnabled,
      resumeRequested,
      probeGuarded;
  private int submittedRows;
  private final Map<String, float[]> controls = new HashMap<>();
  private Map<Integer, ThemePalette.Rgba> safe;

  public ThemePanel(UserConfig user) {
    this(user, ignored -> {});
  }

  public ThemePanel(UserConfig user, Consumer<String> errors) {
    this.user = Objects.requireNonNull(user);
    this.errors = Objects.requireNonNull(errors);
  }

  private void ensure() {
    if (session != null) return;
    session = new ThemeEditSession(user);
    actionScope.resetDraft();
    safe = ThemeStyle.resolve(ThemePalette.starting("Dark"));
    feedback = new ThemeFeedback(ThemeStyle::resolve);
    preview =
        new ThemePreview(
            user,
            session,
            new ThemePreview.Style() {
              public Object owner() {
                return ThemeStyle.owner();
              }

              public Object apply(ThemePalette palette) {
                ThemeStyle.apply(palette);
                return ThemeStyle.owner();
              }
            });
  }

  public void draw(ImBoolean open) {
    if (probing) {
      controls.clear();
      channelIds.clear();
      controlBounds.clear();
      rowSteps.clear();
      rowStrides.clear();
    }
    if (!open.get()) {
      close();
      return;
    }
    ensure();
    preview.refresh();
    long request = actionScope.synchronize(ThemeActionValues.from(user, preview.paused()));
    // Push before Begin so even authored transparent/identical text remains recoverable.
    safe.forEach((key, c) -> ImGui.pushStyleColor(key, c.r(), c.g(), c.b(), c.a()));
    windowLayout.prepare();
    boolean visible = ImGui.begin("Themes", open);
    UiScale.window(user, "Themes");
    windowLayout.observe();
    try {
      if (visible && open.get()) {
        smokeDrawn = true;
        if (smoke || probing) {
          var colors = ThemeStyle.snapshot();
          smokeGuarded =
              ThemeContrast.ratio(colors.get(ImGuiCol.Text), colors.get(ImGuiCol.WindowBg)) >= 4.5;
          probeGuarded = smokeGuarded;
        }
        feedback.update(session.draft());
        paletteBounds = colorRowsBounds = contrastBounds = null;
        submittedRows = 0;
        layout.begin("theme-workspace-body");
        heading();
        panes =
            ThemeLayout.panes(
                ImGui.getContentRegionAvailX(),
                ImGui.getContentRegionAvailY(),
                layout.body().height(),
                ImGui.calcTextSize("0000000000000000000000000000").x,
                ImGui.getFrameHeightWithSpacing(),
                ImGui.getStyle().getItemSpacingX());
        if (panes.columns() || !showContrast) paletteRows();
        feedback.update(session.draft());
        if (panes.columns()) ImGui.sameLine();
        if (panes.columns() || showContrast) contrast();
        if (revealPane) {
          ImGui.setScrollHereY(.5f);
          revealPane = false;
        }
        layout.end();
        actions(request);
        if (smoke && feedback.checks().stream().noneMatch(ThemeContrast.Check::attention))
          throw new AssertionError("Low-contrast draft was not detected");
      } else actionScope.resetDraft();
    } finally {
      ImGui.end();
      ImGui.popStyleColor(safe.size());
    }
    if (open.get()) {
      if (resumeRequested) {
        resumeRequested = false;
        act(
            () -> {
              preview.resume();
              actionScope.resetDraft();
            });
      }
      preview.refresh();
    } else close();
  }

  private void heading() {
    ImGui.textWrapped(
        "Live preview is private. Save Theme persists only palette values; Revert or closing"
            + " restores the active theme. This inspector keeps its own readable palette.");
    ImGui.textWrapped("Starting appearance");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice(
        "##theme-starting-appearance",
        session.draft().base(),
        List.of("Dark", "Light"),
        v -> {
          session.edit(ThemePalette.starting(v));
          colorIssues.clearAll();
          message = "Starting appearance selected; overrides cleared in preview";
        });
    feedback.update(session.draft());
    if (preview.paused())
      ImGui.textWrapped(
          session.current(user)
              ? "Preview paused after another appearance took control. Resume keeps this private"
                  + " palette."
              : "Active theme changed. This private palette is retained; Revert Theme reloads the"
                  + " active values.");
    ImGui.textWrapped(
        session.changed() ? "Unsaved theme preview" : "Theme matches opening preferences");
    long attention = feedback.checks().stream().filter(ThemeContrast.Check::attention).count();
    ImGui.textWrapped("Contrast: " + attention + " need attention");
    colorIssues
        .problems()
        .forEach(
            (key, axes) -> {
              String name =
                  key.startsWith("status:")
                      ? key.substring(7) + " status"
                      : ThemeRegistry.COLORS.get(Integer.parseInt(key.substring(6))).name();
              String letters =
                  axes.stream()
                      .sorted()
                      .map(axis -> "RGBA".substring(axis, axis + 1))
                      .collect(java.util.stream.Collectors.joining(", "));
              ImGui.textWrapped("! " + name + ": correct " + letters + " or use starting color.");
            });
    if (!message.isEmpty()) ImGui.textWrapped(message);
  }

  private void actions(long request) {
    applyEnabled = session.current(user) && !preview.paused() && !colorIssues.any();
    boolean resumeEnabled = preview.paused() && session.current(user);
    ImGui.pushID("theme-request:" + request);
    ImGui.beginDisabled(!applyEnabled);
    boolean save = layout.identifiedAction("save", "Save Theme");
    control("save");
    ImGui.endDisabled();
    boolean revert = layout.identifiedAction("revert", "Revert Theme");
    control("revert");
    boolean reset = layout.identifiedAction("reset", "Reset to Default");
    control("reset");
    ImGui.beginDisabled(!resumeEnabled);
    boolean resume = layout.identifiedAction("resume", "Resume Preview");
    control("resume");
    ImGui.endDisabled();
    boolean view = layout.identifiedAction("view", "Palette / Contrast");
    control("view");
    if (ImGui.isItemHovered())
      UiScale.tooltip(
          panes.columns()
              ? "Show the palette and contrast panels"
              : showContrast ? "Show palette" : "Show contrast");
    ImGui.popID();
    if (!actionScope.current(ThemeActionValues.from(user, preview.paused()))
        || ImGui.isKeyPressed(ImGuiKey.Escape, false)) return;
    if (revert)
      act(
          () -> {
            session.revert(user);
            actionScope.resetDraft();
            colorIssues.clearAll();
            resumeRequested = true;
            message = "Theme reverted";
          });
    else if (reset) {
      session.edit(ThemePalette.starting("Dark"));
      actionScope.resetDraft();
      colorIssues.clearAll();
      message = "Default Dark staged for Save Theme";
    } else if (save && applyEnabled)
      act(
          () -> {
            session.apply(
                user, UserConfig.directory().resolve("user-config.json"), feedback.palette());
            actionScope.resetDraft();
            message = "Theme saved";
          });
    else if (resume && resumeEnabled) {
      resumeRequested = true;
      message = "Private preview resumed";
    } else if (view) {
      if (!panes.columns()) showContrast = !showContrast;
      revealPane = true;
    }
  }

  private void paletteRows() {
    submittedRows = 0;
    UiScale.beginChild("theme-palette", panes.paletteWidth(), panes.height(), true);
    paletteBounds = windowBounds();
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    ImGui.inputTextWithHint("##theme-filter", "Filter palette names...", filter);
    control("filter");
    ImGui.textWrapped("Palette group");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice("##theme-palette-group", group, ThemeRows.GROUPS, v -> group = v);
    var visible = rows.get(group, filter.get());
    UiScale.beginChild(
        "theme-palette-rows",
        0,
        ThemeLayout.viewport(
            ImGui.getContentRegionAvailY(),
            paletteBounds.height(),
            ImGui.getFrameHeightWithSpacing()),
        false);
    colorRowsBounds = windowBounds();
    channelLayout =
        ThemeLayout.channels(
            ImGui.getContentRegionAvailX(),
            ImGui.calcTextSize("0.000").x + 2 * ImGui.getStyle().getFramePaddingX(),
            Math.max(ImGui.calcTextSize("R").x, ImGui.calcTextSize("A").x),
            ImGui.getStyle().getItemSpacingX(),
            ImGui.getTextLineHeightWithSpacing(),
            ImGui.getFrameHeightWithSpacing());
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(visible.size(), channelLayout.rowHeight());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = visible.get(i);
          submittedRows++;
          float top = ImGui.getCursorPosY();
          ImGui.pushID(row.key());
          var color =
              row.role() == null
                  ? feedback.colors().get(row.color())
                  : session.draft().status(row.role());
          float[] components = color.array();
          ImGui.setNextItemWidth(ImGui.getFrameHeight());
          ImGui.beginDisabled(colorIssues.blocked(row.key()));
          boolean changed =
              UiScale.colorEdit4(
                  "##theme-swatch",
                  components,
                  ImGuiColorEditFlags.NoInputs | ImGuiColorEditFlags.NoLabel);
          control(row.key());
          ImGui.endDisabled();
          int edited = 0;
          if (changed) {
            float[] before = color.array();
            for (int axis = 0; axis < 4; axis++)
              if (Float.floatToIntBits(before[axis]) != Float.floatToIntBits(components[axis]))
                edited |= 1 << axis;
          }
          ImGui.sameLine();
          ImGui.text(StatusBarText.clip(row.label(), 160));
          if (ImGui.isItemHovered()) UiScale.tooltip(row.group() + " / " + row.label());
          String[] axes = {"R", "G", "B", "A"};
          for (int axis = 0; axis < 4; axis++) {
            if (axis % channelLayout.columns() != 0) ImGui.sameLine();
            ImGui.beginGroup();
            ImGui.text(axes[axis]);
            if (!channelLayout.stackedLabels()) ImGui.sameLine();
            ImGui.setNextItemWidth(channelLayout.fieldWidth());
            String id = "##theme-channel-" + axes[axis];
            if (probing) channelIds.put(row.key() + ":" + axes[axis], ImGui.getID(id));
            float[] value = {components[axis]};
            ImGui.beginDisabled(
                colorIssues.blocked(row.key()) && !colorIssues.bad(row.key(), axis));
            if (ImGui.sliderFloat(
                id,
                value,
                0,
                1,
                "%.3f",
                ImGuiSliderFlags.AlwaysClamp | ImGuiSliderFlags.NoRoundToFormat)) {
              components[axis] = value[0];
              changed = true;
              edited |= 1 << axis;
            }
            control("channel:" + row.key() + ":" + axes[axis]);
            ImGui.endDisabled();
            ImGui.endGroup();
          }
          if (changed && colorIssues.observe(row.key(), components, edited))
            act(
                () -> {
                  var value = ThemePalette.Rgba.of(components);
                  session.edit(
                      row.role() == null
                          ? session.draft().color(row.color(), value)
                          : session.draft().status(row.role(), value));
                  feedback.update(session.draft());
                });
          if (DialogLayout.button("Use starting color")) {
            colorIssues.clear(row.key());
            session.edit(
                row.role() == null
                    ? session.draft().color(row.color(), null)
                    : session.draft().status(row.role(), null));
            feedback.update(session.draft());
          }
          control("default:" + row.key());
          ImGui.popID();
          if (probing) rowSteps.put(row.key(), ImGui.getCursorPosY() - top);
          ImGui.setCursorPosY(top + channelLayout.rowHeight() - ImGui.getStyle().getItemSpacingY());
          ImGui.dummy(0, 0);
          if (probing) rowStrides.put(row.key(), ImGui.getCursorPosY() - top);
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    ImGui.endChild();
  }

  private void contrast() {
    var resolved = feedback.colors();
    var checks = feedback.checks();
    UiScale.beginChild("theme-contrast", panes.contrastWidth(), panes.height(), true);
    contrastBounds = windowBounds();
    long attention = checks.stream().filter(ThemeContrast.Check::attention).count();
    ImGui.textWrapped("Contrast feedback / " + attention + " need attention");
    ImGui.textWrapped(
        "Targets: text 4.5:1; selected control boundaries 3:1. Alpha is composited over the window"
            + " and an opaque black (Dark) or white (Light) backdrop. Other surfaces and display"
            + " conditions can differ.");
    for (var check : checks)
      ImGui.textWrapped(
          String.format(
              Locale.ROOT,
              "%s %.2f:1 / %.1f  %s",
              check.attention() ? "!" : "OK",
              check.ratio(),
              check.target(),
              check.label()));
    ImGui.separator();
    ImGui.textWrapped(
        "Feedback does not alter or block your palette. Restore individual starting colors, switch"
            + " appearance, Revert, or reset at any time.");
    ImGui.textWrapped("Preview with authored window/text colors:");
    var bg = resolved.get(ImGuiCol.WindowBg);
    var fg = resolved.get(ImGuiCol.Text);
    ImGui.pushStyleColor(ImGuiCol.ChildBg, bg.r(), bg.g(), bg.b(), bg.a());
    ImGui.pushStyleColor(ImGuiCol.Text, fg.r(), fg.g(), fg.b(), fg.a());
    UiScale.beginChild(
        "theme-samples",
        0,
        Math.max(
            1, Math.min(6 * ImGui.getTextLineHeightWithSpacing(), .5f * contrastBounds.height())),
        true);
    ImGui.textWrapped("Primary text / selected palette");
    for (String role : ThemePalette.ROLES) {
      var c = session.draft().status(role);
      ImGui.textColored(c.r(), c.g(), c.b(), c.a(), role + ": example status message");
    }
    ImGui.endChild();
    ImGui.popStyleColor(2);
    ImGui.endChild();
  }

  private void act(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException error) {
      String detail = error.getMessage();
      if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
      if (error.getCause() != null) {
        String cause = error.getCause().getMessage();
        if (cause == null || cause.isBlank()) cause = error.getCause().getClass().getSimpleName();
        detail += ": " + cause;
      }
      message = "! " + (detail.length() > 1024 ? detail.substring(0, 1023) + "…" : detail);
      errors.accept(message);
    }
  }

  public void close() {
    if (session == null) return;
    preview.close();
    actionScope.resetDraft();
    session = null;
    preview = null;
    feedback = null;
    safe = null;
    message = "";
    resumeRequested = false;
    colorIssues.clearAll();
  }

  void smokeDraft() {
    ensure();
    smoke = true;
    smokeDrawn = smokeGuarded = false;
    session.edit(
        session
            .draft()
            .color(ImGuiCol.Text, new ThemePalette.Rgba(.2f, .2f, .2f, 1))
            .color(ImGuiCol.WindowBg, new ThemePalette.Rgba(.2f, .2f, .2f, 1)));
    preview.refresh();
  }

  void assertSmokeDrawn() {
    if (!smokeDrawn
        || !smokeGuarded
        || !session.changed()
        || ThemePalette.from(user.appearance).equals(session.draft()))
      throw new AssertionError("Theme preview did not remain readable and private");
    smoke = false;
    close();
  }

  void probeControls() {
    probing = true;
  }

  private void control(String name) {
    if (probing) {
      controlBounds.put(name, PopupLayout.itemBounds());
      controls.put(
          name,
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          });
    }
  }

  private static DialogBounds.Rect windowBounds() {
    return new DialogBounds.Rect(
        ImGui.getWindowPosX(),
        ImGui.getWindowPosY(),
        ImGui.getWindowSizeX(),
        ImGui.getWindowSizeY());
  }

  ThemeLayout.Panes panes() {
    return panes;
  }

  ThemeLayout.Channels channelLayout() {
    return channelLayout;
  }

  DialogBounds.Rect paletteBounds() {
    return paletteBounds;
  }

  DialogBounds.Rect contrastBounds() {
    return contrastBounds;
  }

  DialogBounds.Rect colorRowsBounds() {
    return colorRowsBounds;
  }

  Map<String, Integer> channelIds() {
    return Map.copyOf(channelIds);
  }

  Map<String, DialogBounds.Rect> controlBounds() {
    return Map.copyOf(controlBounds);
  }

  Map<String, Float> rowSteps() {
    return Map.copyOf(rowSteps);
  }

  Map<String, Float> rowStrides() {
    return Map.copyOf(rowStrides);
  }

  boolean showingContrast() {
    return showContrast;
  }

  float[] probeControl(String name) {
    return controls.get(name);
  }

  ThemePalette probeDraft() {
    return session == null ? null : session.draft();
  }

  void probeEdit(ThemePalette value) {
    ensure();
    colorIssues.clearAll();
    session.edit(value);
  }

  boolean probePaused() {
    return preview != null && preview.paused();
  }

  boolean probeApplyEnabled() {
    return applyEnabled;
  }

  int submittedRows() {
    return submittedRows;
  }

  long evaluations() {
    return feedback == null ? 0 : feedback.evaluations();
  }

  long queries() {
    return rows.queries();
  }

  boolean probeGuarded() {
    return probeGuarded;
  }

  Map<Integer, ThemePalette.Rgba> probeColors() {
    return feedback.colors();
  }

  List<ThemeContrast.Check> probeChecks() {
    return feedback.checks();
  }
}
