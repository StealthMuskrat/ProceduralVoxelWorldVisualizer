package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import java.util.*;
import java.util.function.Consumer;

/** Live theme preview with an independently readable inspector and explicit persistence. */
public final class ThemePanel {
  private final UserConfig user;
  private final Consumer<String> errors;
  private ThemeEditSession session;
  private ThemePreview preview;
  private ThemeFeedback feedback;
  private final ThemeRows rows = new ThemeRows();
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
    if (!open.get()) {
      close();
      return;
    }
    ensure();
    preview.refresh();
    // Push before Begin so even authored transparent/identical text remains recoverable.
    safe.forEach((key, c) -> ImGui.pushStyleColor(key, c.r(), c.g(), c.b(), c.a()));
    ImGui.setNextWindowSize(980, 710, ImGuiCond.FirstUseEver);
    boolean visible = ImGui.begin("Themes", open);
    UiScale.window(user, "Themes");
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
        toolbar();
        feedback.update(session.draft());
        ImGui.separator();
        paletteRows();
        feedback.update(session.draft());
        ImGui.sameLine();
        contrast();
        if (smoke && feedback.checks().stream().noneMatch(ThemeContrast.Check::attention))
          throw new AssertionError("Low-contrast draft was not detected");
      }
    } finally {
      ImGui.end();
      ImGui.popStyleColor(safe.size());
    }
    if (open.get()) {
      if (resumeRequested) {
        resumeRequested = false;
        act(preview::resume);
      }
      preview.refresh();
    } else close();
  }

  private void toolbar() {
    ImGui.textWrapped(
        "Live preview is private. Save Theme persists only palette values; Revert or closing"
            + " restores the active theme. This inspector keeps its own readable palette.");
    fields.choice(
        "Starting appearance",
        session.draft().base(),
        List.of("Dark", "Light"),
        v -> {
          session.edit(ThemePalette.starting(v));
          message = "Starting appearance selected; overrides cleared in preview";
        });
    feedback.update(session.draft());
    applyEnabled = session.current(user) && !preview.paused();
    ImGui.beginDisabled(!applyEnabled);
    if (ImGui.button("Save Theme"))
      act(
          () -> {
            session.apply(
                user, UserConfig.directory().resolve("user-config.json"), feedback.palette());
            message = "Theme saved";
          });
    control("save");
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Revert Theme"))
      act(
          () -> {
            session.revert(user);
            resumeRequested = true;
            message = "Theme reverted";
          });
    control("revert");
    ImGui.sameLine();
    if (ImGui.button("Reset to Default")) {
      session.edit(ThemePalette.starting("Dark"));
      message = "Default Dark staged for Save Theme";
    }
    control("reset");
    if (preview.paused()) {
      ImGui.textWrapped(
          session.current(user)
              ? "Preview paused after another appearance took control. Resume keeps this private"
                  + " palette."
              : "Active theme changed. This private palette is retained; Revert Theme reloads the"
                  + " active values.");
      ImGui.beginDisabled(!session.current(user));
      if (ImGui.button("Resume Preview")) {
        resumeRequested = true;
        message = "Private preview resumed";
      }
      control("resume");
      ImGui.endDisabled();
    }
    ImGui.text(
        (session.changed() ? "Unsaved theme preview" : "Theme matches opening preferences")
            + (message.isEmpty() ? "" : " / " + message));
  }

  private void paletteRows() {
    submittedRows = 0;
    ImGui.beginChild("theme-palette", ImGui.getContentRegionAvailX() * .51f, 0, true);
    ImGui.inputTextWithHint("##theme-filter", "Filter palette names...", filter);
    control("filter");
    fields.choice("Palette group", group, ThemeRows.GROUPS, v -> group = v);
    var visible = rows.get(group, filter.get());
    ImGui.beginChild("theme-palette-rows", 0, 0, false, ImGuiWindowFlags.HorizontalScrollbar);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(visible.size(), 2 * ImGui.getFrameHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = visible.get(i);
          submittedRows++;
          ImGui.pushID(row.key());
          var color =
              row.role() == null
                  ? feedback.colors().get(row.color())
                  : session.draft().status(row.role());
          float[] components = color.array();
          ImGui.setNextItemWidth(240);
          if (ImGui.colorEdit4(row.label(), components)) {
            var value = ThemePalette.Rgba.of(components);
            session.edit(
                row.role() == null
                    ? session.draft().color(row.color(), value)
                    : session.draft().status(row.role(), value));
            feedback.update(session.draft());
          }
          control(row.key());
          if (ImGui.isItemHovered()) ImGui.setTooltip(row.group() + " / " + row.label());
          if (ImGui.button("Use starting color")) {
            session.edit(
                row.role() == null
                    ? session.draft().color(row.color(), null)
                    : session.draft().status(row.role(), null));
            feedback.update(session.draft());
          }
          control("default:" + row.key());
          ImGui.popID();
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
    ImGui.beginChild("theme-contrast", 0, 0, true);
    long attention = checks.stream().filter(ThemeContrast.Check::attention).count();
    ImGui.text("Contrast feedback / " + attention + " need attention");
    ImGui.textWrapped(
        "Targets: text 4.5:1; selected control boundaries 3:1. Alpha is composited over the window"
            + " and an opaque black (Dark) or white (Light) backdrop. Other surfaces and display"
            + " conditions can differ.");
    for (var check : checks)
      ImGui.text(
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
    ImGui.text("Preview with authored window/text colors:");
    var bg = resolved.get(ImGuiCol.WindowBg);
    var fg = resolved.get(ImGuiCol.Text);
    ImGui.pushStyleColor(ImGuiCol.ChildBg, bg.r(), bg.g(), bg.b(), bg.a());
    ImGui.pushStyleColor(ImGuiCol.Text, fg.r(), fg.g(), fg.b(), fg.a());
    ImGui.beginChild("theme-samples", 0, 130, true);
    ImGui.text("Primary text / selected palette");
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
    session = null;
    preview = null;
    feedback = null;
    safe = null;
    message = "";
    resumeRequested = false;
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
    if (probing)
      controls.put(
          name,
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          });
  }

  float[] probeControl(String name) {
    return controls.get(name);
  }

  ThemePalette probeDraft() {
    return session == null ? null : session.draft();
  }

  void probeEdit(ThemePalette value) {
    ensure();
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
