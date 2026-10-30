package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred production diagnostic geometry, exact selection and acknowledgement workflow. */
public final class DiagnosticsLayoutProbe {
  private static EditorState state;
  private static DiagnosticsPanel panel;
  private static final ImBoolean open = new ImBoolean(true);
  private static int width = 1400, height = 1200, logsOpened;
  private static String mode = "logs", copied = "";
  private static final List<EditorEvents.Target> navigated = new ArrayList<>();
  private static DialogBounds.Rect window;

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
                  n.id = "committed";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(state.project());
        var userBefore = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        var target =
            new EditorEvents.Target(
                state.project().project.uuid, state.selected, null, null, "/noise2D/0", false);
        EditorEvents.Entry selected = null;
        for (int i = 0; i < 200; i++)
          selected =
              state.events.publish(
                  "FIXTURE_" + i,
                  Diagnostic.Severity.ERROR,
                  EditorEvents.Subsystem.PROJECT,
                  "fixture-" + i + " ### snow 雪\n" + "full detail ".repeat(100),
                  target,
                  i == 199);
        var issueRows = new ArrayList<Diagnostic>();
        for (int i = 0; i < 200; i++)
          issueRows.add(
              Diagnostic.error(
                  "ISSUE_" + i,
                  i == 0 ? "/project/seed" : "/project/maxY",
                  "candidate detail " + i + " ###\n" + "long ".repeat(100)));
        state.issues = List.copyOf(issueRows);
        state.validationScope = "Rejected fixture";
        panel = new DiagnosticsPanel(state, navigated::add, text -> copied = text);
        panel.probeControls();
        show("logs");
        int unfilteredLogs = panel.submittedRows();
        float unfilteredHeight = panel.resultsBounds().height(),
            unfilteredStride = panel.rowStride();
        click("event:" + selected.id());
        require(
            panel.selectedEvent() == selected.id()
                && state.events.get(selected.id()).orElseThrow().read(),
            "Event row selection/read failed");
        click("filter");
        type("no-such-entry");
        require(
            panel.submittedRows() == 0 && panel.selectedEvent() == selected.id(),
            "Filtering silently changed selected event");
        click("show");
        frames(5);
        require(
            panel.selectedEvent() == selected.id() && panel.submittedRows() > 0,
            "Show Selected failed");
        click("filter");
        type("fixture-199");
        require(panel.submittedRows() == 1, "Actual log search failed");
        show("validation");
        click("issue:0");
        require(panel.selectedIssue() == 0, "Validation row selection failed");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1200}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 300}};
        float[] global = {1, .5f, 1, 2.5f, 2.5f, 1}, multiplier = {1, 1, 1, 1, 2.5f, 1};
        var selectedIds = new HashMap<String, Integer>();
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = global[i];
          for (String name : List.of("Logs", "Notifications", "Validation"))
            state.user.appearance.windowScales.put(name, multiplier[i]);
          for (String form : List.of("logs", "notices", "validation")) {
            show(form);
            var chrome = chrome();
            require(DialogBounds.contains(window, chrome.body()), "Diagnostic body outside client");
            for (var action : chrome.actions().values())
              require(DialogBounds.contains(window, action), "Diagnostic action outside client");
            String key = form.equals("validation") ? "0" : Long.toString(selected.id());
            Integer id = panel.rowIds().get(key);
            require(id != null, "Selected row not submitted after explicit results reveal");
            if (!selectedIds.containsKey(form)) selectedIds.put(form, id);
            require(
                selectedIds.get(form).equals(id), "Diagnostic row identity changed during reflow");
            require(
                panel.selectedEvent() == selected.id() && panel.selectedIssue() == 0,
                "Reflow replaced a selection");
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("scenario", i);
            receipt.put("form", form);
            receipt.put("window", window);
            receipt.put("body", chrome.body());
            receipt.put("actions", chrome.actions());
            receipt.put("panes", panel.panes());
            receipt.put("results", panel.resultsBounds());
            receipt.put("detail", panel.detailBounds());
            receipt.put("rows", panel.submittedRows());
            receipt.put("stride", panel.rowStride());
            receipt.put("selectedRowId", id);
            receipt.put("selectedEvent", selected.id());
            receipt.put("selectedIssue", panel.selectedIssue());
            receipt.put("globalScale", global[i]);
            receipt.put("windowScale", multiplier[i]);
            cases.add(receipt);
          }
        }
        width = 1400;
        height = 1200;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.clear();
        show("logs");
        long builds = panel.projectionBuilds(false);
        frames(5);
        require(panel.projectionBuilds(false) == builds, "Unchanged log projection rebuilt");
        click("copy");
        require(
            copied.equals(state.events.get(selected.id()).orElseThrow().details()),
            "Copy did not use selected exact event");
        click("navigate");
        require(
            navigated.size() == 1 && navigated.getFirst().equals(target),
            "Event source callback failed");
        show("notices");
        click("all-read");
        require(
            state.events.counts().unread() == 0 && state.events.counts().actionRequired() == 1,
            "Mark All Read resolved an action");
        click("acknowledge");
        require(
            !state.events.get(selected.id()).orElseThrow().actionRequired(),
            "Explicit resolve failed");
        click("acknowledge");
        require(
            state.events.get(selected.id()).orElseThrow().dismissed()
                && state.events.snapshot().size() == 200
                && state.events.search("", null, null, true).size() == 199,
            "Dismiss lost history");
        var repeat =
            state.events.publish(
                selected.code(),
                selected.severity(),
                selected.subsystem(),
                selected.message(),
                selected.target(),
                true);
        require(
            repeat.id() == selected.id()
                && repeat.count() == 2
                && !repeat.read()
                && !repeat.dismissed()
                && repeat.actionRequired(),
            "Repeat did not reopen same notification group");
        frames(4);
        click("logs");
        require(logsOpened == 1, "Selected log callback failed");
        show("logs");
        click("navigate");
        require(
            navigated.size() == 2 && state.events.get(selected.id()).orElseThrow().read(),
            "Repeated event source navigation/read failed");
        show("validation");
        click("copy");
        require(
            copied.contains("/project/seed") && copied.contains("ISSUE_0"),
            "Validation copy callback used another row");
        int previousIssueId = panel.rowIds().get("0");
        state.issues = new ArrayList<>(state.issues);
        frames(5);
        require(
            panel.selectedIssue() == -1 && !panel.rowIds().get("0").equals(previousIssueId),
            "Replaced validation results reused native selection identity");
        click("navigate");
        require(navigated.size() == 2, "Unselected replacement row navigated");
        click("issue:0");
        click("navigate");
        require(
            navigated.size() == 3
                && navigated.getLast().settings()
                && navigated.getLast().pointer().equals("/project/seed"),
            "Explicit replacement issue callback failed");
        click("validate");
        frames(4);
        require(
            state.issues.isEmpty()
                && state.validationScope.equals("Committed project")
                && panel.selectedIssue() == -1,
            "Actual committed-project validation did not replace synthetic results");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && userBefore.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.engine == engine
                && state.draft == draft
                && draft.id.equals("private")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Diagnostics changed project/history/private/runtime ownership");
        new ProjectIO().save(out.resolve("diagnostics-project.json"), state.project());
        Files.writeString(
            out.resolve("diagnostics-events.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        Files.writeString(
            out.resolve("diagnostics-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("unfilteredRows", unfilteredLogs),
                    Map.entry("unfilteredHeight", unfilteredHeight),
                    Map.entry("unfilteredStride", unfilteredStride),
                    Map.entry("selectedEvent", selected.id()),
                    Map.entry("filteredSelectionPreserved", true),
                    Map.entry("cachedProjection", true),
                    Map.entry("clipboardCallback", true),
                    Map.entry("sourceCallbacks", navigated.size()),
                    Map.entry("logsCallbacks", logsOpened),
                    Map.entry("readResolveDismissRepeat", true),
                    Map.entry("validationReplacementClearedSelection", true),
                    Map.entry("committedValidationReadOnly", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("physicalClipboardVerified", false),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "DIAGNOSTICS_LAYOUT_NATIVE_PASS measured panes / persistent recovery / exact selection"
                + " / acknowledgement / cached rows");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void show(String form) {
    mode = form;
    panel.probeRevealResults();
    frames(7);
  }

  private static SettingsPageChrome chrome() {
    return mode.equals("logs")
        ? panel.logsChrome
        : mode.equals("notices") ? panel.noticesChrome : panel.validationChrome;
  }

  private static void click(String key) {
    float[] at = panel.controlAt(key);
    require(at != null, "Missing " + mode + " control " + key);
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void type(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    switch (mode) {
      case "logs" -> {
        panel.drawLogs(open);
        window = panel.logsLayout.bounds();
      }
      case "notices" -> {
        panel.drawNotifications(open, () -> logsOpened++);
        window = panel.noticesLayout.bounds();
      }
      default -> {
        panel.drawValidation(open);
        window = panel.validationLayout.bounds();
      }
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
