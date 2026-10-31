package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred exact displayed-entry/result footer decisions; no OS clipboard or renderer is used. */
public final class DiagnosticDecisionProbe {
  private static EditorState state;
  private static DiagnosticsPanel panel;
  private static final ImBoolean open = new ImBoolean(true), hidden = new ImBoolean(false);
  private static String form = "Logs";
  private static int width = 1200, height = 900, opened;
  private static float global = 1, multiplier = 1;
  private static boolean collapsed, failCopy, failNavigate, failLogs;
  private static final List<String> copied = new ArrayList<>();
  private static final List<EditorEvents.Target> navigated = new ArrayList<>();
  private static EditorEvents.Entry alpha, beta;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "existing";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        var projectFile = out.resolve("protected-project.json");
        state.document.save(projectFile);
        require(state.document.open(projectFile).isEmpty(), "Canonical fixture reopen failed");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        alpha =
            state.events.publish(
                "ALPHA",
                Diagnostic.Severity.ERROR,
                EditorEvents.Subsystem.PROJECT,
                "alpha ### retained details\n" + "detail ".repeat(100),
                new EditorEvents.Target(
                    state.project().project.uuid, state.selected, null, null, "/noise2D/0", false),
                true);
        beta =
            state.events.publish(
                "BETA",
                Diagnostic.Severity.ERROR,
                EditorEvents.Subsystem.PROJECT,
                "beta settings",
                new EditorEvents.Target(
                    state.project().project.uuid, null, null, null, "/project/seed", true),
                true);
        var shared = Diagnostic.error("SAME_ROW", "/project/seed", "same object at two rows");
        var other = Diagnostic.error("OTHER_ROW", "/project/maxY", "another exact target");
        state.issues = new ArrayList<>(List.of(shared, shared, other));
        state.validationScope = "Rejected diagnostic fixture";
        panel =
            new DiagnosticsPanel(
                state,
                target -> {
                  if (failNavigate) throw new IllegalStateException("navigation boundary");
                  navigated.add(target);
                },
                text -> {
                  if (failCopy) throw new IllegalStateException("copy boundary");
                  copied.add(text);
                });
        panel.probeControls();
        panel.reveal(alpha.id());
        panel.probeSelectIssue(0);
        var cases = new ArrayList<Map<String, Object>>();
        for (var mode : List.of("Logs", "Notifications", "Validation")) {
          form = mode;
          capture(cases);
        }
        normal();
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        for (var mode : List.of("Logs", "Notifications", "Validation")) {
          form = mode;
          panel.reveal(alpha.id());
          if (mode.equals("Validation")) panel.probeSelectIssue(0);
          frames(4);
          for (var key : keys()) {
            if (key.equals("show")) {
              panel.probeFilter("unmatched");
              frames(3);
            }
            var events = state.events.view();
            var issues = state.issues;
            String scope = state.validationScope;
            releaseWithEscape(key);
            require(
                state.events.view() == events
                    && state.issues == issues
                    && state.validationScope.equals(scope)
                    && copied.isEmpty()
                    && navigated.isEmpty()
                    && opened == 0,
                "Escape authorized diagnostic action");
            if (key.equals("show")) {
              require(panel.probeFilter().equals("unmatched"), "Escape changed log filters");
              panel.probeFilter("");
            }
          }
        }
        // Selection changes cannot transfer an armed action to another displayed event.
        for (var mode : List.of("Logs", "Notifications")) {
          form = mode;
          for (var key :
              mode.equals("Logs")
                  ? List.of("copy", "navigate", "show")
                  : List.of("logs", "copy", "navigate", "read", "acknowledge")) {
            panel.reveal(alpha.id());
            frames(4);
            int old = chrome().actionIds().get(key);
            press(control(key));
            panel.reveal(beta.id());
            frame();
            release();
            frames(3);
            require(
                old != chrome().actionIds().get(key)
                    && copied.isEmpty()
                    && navigated.isEmpty()
                    && opened == 0
                    && !state.events.get(beta.id()).orElseThrow().read()
                    && state.events.get(beta.id()).orElseThrow().actionRequired(),
                "Held event action affected successor selection");
          }
        }
        // Selected-entry actions survive unrelated publications; bulk acknowledgement does not
        // adopt them.
        form = "Logs";
        panel.reveal(alpha.id());
        frames(4);
        var frozen = state.events.get(alpha.id()).orElseThrow();
        int oldCopy = chrome().actionIds().get("copy");
        press(control("copy"));
        publish("UNRELATED_X");
        frame();
        release();
        frames(3);
        require(
            copied.equals(List.of(frozen.details())) && oldCopy == chrome().actionIds().get("copy"),
            "Unrelated event retargeted selected Copy");
        form = "Notifications";
        panel.reveal(alpha.id());
        frames(4);
        press(control("all-read"));
        var newer = publish("UNRELATED_Y");
        frame();
        release();
        frames(3);
        require(
            !state.events.get(alpha.id()).orElseThrow().read()
                && !state.events.get(beta.id()).orElseThrow().read()
                && !state.events.get(newer.id()).orElseThrow().read(),
            "Held Mark All Read adopted a newer view");
        oldCopy = chrome().actionIds().get("copy");
        press(control("copy"));
        publish("UNRELATED_Z");
        frame();
        release();
        frames(3);
        require(
            copied.size() == 2
                && copied.getLast().equals(frozen.details())
                && oldCopy == chrome().actionIds().get("copy"),
            "Bulk view change invalidated independent selected entry");
        click("all-read");
        require(
            state.events.counts().unread() == 0 && state.events.counts().actionRequired() == 2,
            "Explicit Mark All Read resolved required actions");
        press(control("read"));
        repeatAlpha();
        frame();
        release();
        frames(3);
        require(
            !state.events.get(alpha.id()).orElseThrow().read(),
            "Held Mark Read accepted repeated occurrence");
        press(control("acknowledge"));
        repeatAlpha();
        frame();
        release();
        frames(3);
        require(
            state.events.get(alpha.id()).orElseThrow().actionRequired(),
            "Held Resolve accepted repeated occurrence");
        click("read");
        click("acknowledge");
        click("acknowledge");
        require(
            state.events.get(alpha.id()).orElseThrow().read()
                && !state.events.get(alpha.id()).orElseThrow().actionRequired()
                && state.events.get(alpha.id()).orElseThrow().dismissed()
                && state.events.get(alpha.id()).orElseThrow().count() == 3,
            "Explicit occurrence acknowledgement/resolve/dismiss failed");
        // Equal object payloads at different validation indices still have different action owners.
        form = "Validation";
        panel.probeSelectIssue(0);
        frames(4);
        int old = chrome().actionIds().get("copy");
        press(control("copy"));
        panel.probeSelectIssue(1);
        frame();
        release();
        frames(3);
        require(
            old != chrome().actionIds().get("copy")
                && copied.size() == 2
                && panel.selectedIssue() == 1,
            "Held Copy crossed identical diagnostic rows");
        press(control("validate"));
        state.issues = new ArrayList<>(state.issues);
        var replacement = state.issues;
        frame();
        release();
        frames(3);
        require(
            state.issues == replacement
                && state.validationScope.equals("Rejected diagnostic fixture")
                && panel.selectedIssue() == -1,
            "Held Validate adopted replacement result publication");
        panel.probeSelectIssue(0);
        frames(3);
        press(control("navigate"));
        Collections.reverse(state.issues);
        frame();
        release();
        frames(3);
        require(
            navigated.isEmpty() && panel.selectedIssue() == -1,
            "Held validation navigation replayed a reordered index");
        panel.probeRevealResults();
        frames(5);
        click("issue:0");
        click("copy");
        click("navigate");
        require(
            copied.size() == 3
                && copied.getLast().contains("/project/maxY")
                && navigated.size() == 1
                && navigated.getFirst().settings()
                && navigated.getFirst().pointer().equals("/project/maxY"),
            "Explicit validation selection routed wrong target");
        click("validate");
        require(
            state.issues.isEmpty()
                && state.validationScope.equals("Committed project")
                && panel.selectedIssue() == -1,
            "Explicit committed validation did not replace candidate results");
        // Injected external-boundary failures are logged once without escaping native window
        // scopes.
        form = "Logs";
        panel.reveal(alpha.id());
        frames(4);
        var retryIds = chrome().actionIds();
        failCopy = true;
        click("copy");
        failCopy = false;
        require(
            copied.size() == 3
                && retryIds.equals(chrome().actionIds())
                && code("DIAGNOSTIC_COPY_FAILED"),
            "Copy failure lost retry context");
        click("copy");
        require(copied.size() == 4, "Copy retry failed");
        failNavigate = true;
        click("navigate");
        failNavigate = false;
        require(
            navigated.size() == 1 && code("DIAGNOSTIC_NAVIGATION_FAILED"),
            "Navigation failure escaped recovery");
        click("navigate");
        require(navigated.size() == 2, "Navigation retry failed");
        form = "Notifications";
        frames(4);
        failLogs = true;
        click("logs");
        failLogs = false;
        require(
            opened == 0 && code("DIAGNOSTIC_LOGS_FAILED"), "Open Logs failure escaped recovery");
        click("logs");
        require(opened == 1, "Open Logs retry failed");
        for (var mode : List.of("Logs", "Notifications", "Validation")) {
          form = mode;
          if (mode.equals("Validation")) {
            state.issues = new ArrayList<>(List.of(shared, shared, other));
            state.validationScope = "Rejected diagnostic fixture";
            panel.probeSelectIssue(0);
          }
          frames(4);
          int copies = copied.size();
          press(control("copy"));
          open.set(false);
          frame();
          open.set(true);
          frame();
          release();
          frames(3);
          require(copied.size() == copies, "Hidden diagnostic presentation retained armed Copy");
          press(control("copy"));
          collapsed = true;
          frame();
          collapsed = false;
          frame();
          release();
          frames(3);
          require(copied.size() == copies, "Collapsed diagnostic presentation retained armed Copy");
        }
        // A same-UUID file reopen preserves history entries but retires an old navigation gesture.
        form = "Logs";
        panel.reveal(alpha.id());
        frames(4);
        press(control("navigate"));
        require(state.document.open(projectFile).isEmpty(), "Same-UUID reopen fixture failed");
        frame();
        release();
        frames(3);
        require(navigated.size() == 2, "Held event navigation crossed document lifetime");
        form = "Validation";
        frames(4);
        click("validate");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Diagnostic decisions changed project/preferences/private/runtime ownership");
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("diagnostics-view.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        Files.writeString(
            out.resolve("diagnostic-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("escapePreserved", true),
                    Map.entry("heldSelectionsBlocked", true),
                    Map.entry("unrelatedPublicationsIndependent", true),
                    Map.entry("heldBulkViewBlocked", true),
                    Map.entry("heldOccurrencesBlocked", true),
                    Map.entry("validationOwnersScoped", true),
                    Map.entry("callbackFailuresContained", true),
                    Map.entry("hiddenContextsReleased", true),
                    Map.entry("documentLifetimeScoped", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("copied", copied),
                    Map.entry("navigated", navigated),
                    Map.entry("openedLogs", opened),
                    Map.entry("alpha", alpha.id()),
                    Map.entry("beta", beta.id()),
                    Map.entry("physicalClipboardVerified", false),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "DIAGNOSTIC_DECISIONS_NATIVE_PASS exact entry/view/publication owners / Escape /"
                + " callbacks / acknowledgement / document lifetime");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static boolean code(String value) {
    return state.events.snapshot().stream()
        .anyMatch(e -> e.code().equals(value) && e.actionRequired());
  }

  private static EditorEvents.Entry publish(String code) {
    return state.events.publish(
        code,
        Diagnostic.Severity.INFORMATION,
        EditorEvents.Subsystem.APPLICATION,
        "unrelated publication " + code,
        null,
        false);
  }

  private static void repeatAlpha() {
    state.events.publish(
        alpha.code(), alpha.severity(), alpha.subsystem(), alpha.message(), alpha.target(), true);
  }

  private static List<String> keys() {
    return switch (form) {
      case "Logs" -> List.of("copy", "navigate", "show");
      case "Notifications" ->
          List.of("all-read", "logs", "copy", "navigate", "read", "acknowledge");
      default -> List.of("validate", "copy", "navigate");
    };
  }

  private static SettingsPageChrome chrome() {
    return switch (form) {
      case "Logs" -> panel.logsChrome;
      case "Notifications" -> panel.noticesChrome;
      default -> panel.validationChrome;
    };
  }

  private static FloatingWindowLayout layout() {
    return switch (form) {
      case "Logs" -> panel.logsLayout;
      case "Notifications" -> panel.noticesLayout;
      default -> panel.validationLayout;
    };
  }

  private static float[] control(String key) {
    var at = panel.controlAt(key);
    require(at != null, "Missing diagnostic control " + key);
    return at;
  }

  private static void capture(List<Map<String, Object>> cases) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      if (ids == null) ids = chrome().actionIds();
      require(ids.equals(chrome().actionIds()), "Reflow changed diagnostic footer owner");
      cases.add(
          Map.ofEntries(
              Map.entry("form", form),
              Map.entry("scenario", i),
              Map.entry("globalScale", global),
              Map.entry("windowScale", multiplier),
              Map.entry("window", layout().bounds()),
              Map.entry("body", chrome().body()),
              Map.entry("actions", chrome().actions()),
              Map.entry("ids", chrome().actionIds())));
    }
  }

  private static void normal() {
    dimensions(1200, 900, 1, 1);
    frames(5);
  }

  private static void dimensions(int w, int h, float scale, float windowScale) {
    width = w;
    height = h;
    global = scale;
    multiplier = windowScale;
    for (var name : List.of("Logs", "Notifications", "Validation"))
      state.user.appearance.windowScales.put(name, multiplier);
  }

  private static void press(float[] at) {
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static void click(String key) {
    press(control(key));
    release();
    frames(4);
  }

  private static void releaseWithEscape(String key) {
    press(control(key));
    var io = ImGui.getIO();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    if (!form.equals("Logs")) panel.drawLogs(hidden);
    if (!form.equals("Notifications"))
      panel.drawNotifications(hidden, DiagnosticDecisionProbe::openLogs);
    if (!form.equals("Validation")) panel.drawValidation(hidden);
    if (open.get()) {
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
      ImGui.setNextWindowFocus();
    }
    switch (form) {
      case "Logs" -> panel.drawLogs(open);
      case "Notifications" -> panel.drawNotifications(open, DiagnosticDecisionProbe::openLogs);
      case "Validation" -> panel.drawValidation(open);
      default -> throw new AssertionError();
    }
    ImGui.render();
  }

  private static void openLogs() {
    if (failLogs) throw new IllegalStateException();
    opened++;
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
