package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import java.util.*;

/** Live theme preview with an independently readable inspector and explicit persistence. */
public final class ThemePanel {
  private final UserConfig user;
  private ThemeEditSession session;
  private final UiFields fields = new UiFields();
  private final ImString filter = new ImString(256);
  private String group = "All", message = "";
  private boolean smokeDrawn, smokeGuarded, smoke;
  private Map<Integer, ThemePalette.Rgba> safe;

  public ThemePanel(UserConfig user) {
    this.user = user;
  }

  private void ensure() {
    if (session == null) {
      session = new ThemeEditSession(user);
      safe = ThemeStyle.resolve(ThemePalette.starting("Dark"));
      ThemeStyle.apply(session.draft());
    }
  }

  public void draw(ImBoolean open) {
    if (!open.get()) {
      close();
      return;
    }
    ensure();
    var resolved = ThemeStyle.resolve(session.draft());
    var checks = ThemeContrast.audit(resolved, session.draft());
    // Every editor color is guarded, so even transparent/identical foregrounds stay recoverable.
    safe.forEach((key, c) -> ImGui.pushStyleColor(key, c.r(), c.g(), c.b(), c.a()));
    ImGui.setNextWindowSize(980, 710, ImGuiCond.FirstUseEver);
    boolean visible = ImGui.begin("Themes", open);
    if (visible) {
      smokeDrawn = true;
      if (smoke) {
        var colors = ThemeStyle.snapshot();
        smokeGuarded =
            ThemeContrast.ratio(colors.get(ImGuiCol.Text), colors.get(ImGuiCol.WindowBg)) >= 4.5;
      }
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
      if (ImGui.button("Save Theme")) {
        try {
          session.apply(user, UserConfig.directory().resolve("user-config.json"));
          message = "Theme saved";
        } catch (RuntimeException e) {
          message = "! " + e.getMessage();
        }
      }
      ImGui.sameLine();
      if (ImGui.button("Revert Theme")) {
        session.revert(user);
        message = "Theme reverted";
      }
      ImGui.sameLine();
      if (ImGui.button("Reset to Default")) {
        session.edit(ThemePalette.starting("Dark"));
        message = "Default Dark staged for Save Theme";
      }
      ImGui.text(
          (session.changed() ? "Unsaved theme preview" : "Theme matches active preferences")
              + (message.isEmpty() ? "" : " / " + message));
      ImGui.separator();
      ImGui.beginChild("theme-palette", ImGui.getContentRegionAvailX() * .51f, 0, true);
      ImGui.inputTextWithHint("##theme-filter", "Filter palette names...", filter);
      var groups = new ArrayList<String>();
      groups.add("All");
      ThemeRegistry.COLORS.stream().map(ThemeRegistry.Color::group).distinct().forEach(groups::add);
      groups.add("Status messages");
      fields.choice("Palette group", group, groups, v -> group = v);
      String query = filter.get().strip().toLowerCase(Locale.ROOT);
      if (group.equals("All") || group.equals("Status messages")) {
        ImGui.text("Status messages (also identified by text)");
        for (String role : ThemePalette.ROLES) {
          if (!role.toLowerCase(Locale.ROOT).contains(query)) continue;
          float[] c = session.draft().status(role).array();
          ImGui.pushID("status-" + role);
          ImGui.setNextItemWidth(240);
          if (ImGui.colorEdit4(role, c))
            session.edit(session.draft().status(role, ThemePalette.Rgba.of(c)));
          if (ImGui.smallButton("Use starting color"))
            session.edit(session.draft().status(role, null));
          ImGui.popID();
        }
        ImGui.separator();
      }
      var rows =
          ThemeRegistry.COLORS.stream()
              .filter(
                  c ->
                      (group.equals("All") || group.equals(c.group()))
                          && (c.name() + " " + c.group()).toLowerCase(Locale.ROOT).contains(query))
              .toList();
      for (var row : rows) {
        ImGui.pushID(row.key());
        float[] c = resolved.get(row.key()).array();
        ImGui.setNextItemWidth(240);
        if (ImGui.colorEdit4(row.name(), c))
          session.edit(session.draft().color(row.key(), ThemePalette.Rgba.of(c)));
        if (ImGui.isItemHovered())
          ImGui.setTooltip(row.group() + " / fixed color key " + row.key());
        if (ImGui.smallButton("Use starting color"))
          session.edit(session.draft().color(row.key(), null));
        ImGui.popID();
      }
      ImGui.endChild();
      ImGui.sameLine();
      ImGui.beginChild("theme-contrast", 0, 0, true);
      long attention = checks.stream().filter(ThemeContrast.Check::attention).count();
      ImGui.text("Contrast feedback / " + attention + " need attention");
      ImGui.textWrapped(
          "Targets: text 4.5:1; selected control boundaries 3:1. Alpha is composited over the"
              + " window and an opaque black (Dark) or white (Light) backdrop. Other surfaces and"
              + " display conditions can differ.");
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
          "Feedback does not alter or block your palette. Restore individual starting colors,"
              + " switch appearance, Revert, or reset at any time.");
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
    ImGui.end();
    ImGui.popStyleColor(safe.size());
    if (open.get()) ThemeStyle.apply(session.draft());
    else close();
    if (smoke && checks.stream().noneMatch(ThemeContrast.Check::attention))
      throw new AssertionError("Low-contrast draft was not detected");
  }

  public void close() {
    if (session == null) return;
    ThemeStyle.apply(ThemePalette.from(user.appearance));
    session = null;
    message = "";
  }

  void smokeDraft() {
    ensure();
    smoke = true;
    smokeDrawn = false;
    session.edit(
        session
            .draft()
            .color(ImGuiCol.Text, new ThemePalette.Rgba(.2f, .2f, .2f, 1))
            .color(ImGuiCol.WindowBg, new ThemePalette.Rgba(.2f, .2f, .2f, 1)));
    ThemeStyle.apply(session.draft());
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
}
