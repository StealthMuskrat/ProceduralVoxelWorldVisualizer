package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production file/history geometry, native decision arbitration and path recovery. */
public final class ProjectDecisionLayoutProbe {
  private static EditorState state;
  private static ProjectFileDialog files;
  private static ProjectHistoryPanel history;
  private static final AtomicBoolean blocked = new AtomicBoolean();
  private static int width = 1200, height = 900, replacements, exits;
  private static String form = "DRAFT";
  private static UUID noiseId;

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
                  p.project.seed = 5;
                  var n = noise("field", .125);
                  noiseId = n.uuid;
                  p.noise2D.add(n);
                }),
            "Project decision fixture rejected");
        require(state.change(p -> p.project.seed = 6), "History fixture rejected");
        files = new ProjectFileDialog(state, () -> replacements++, () -> exits++, blocked::get);
        history = new ProjectHistoryPanel(state, blocked::get);
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = globals[i];
          state.user.appearance.windowScales.put("Properties", scales[i]);
          for (String name : List.of("DRAFT", "GUARD", "SAVE", "OPEN", "UNDO", "REDO")) {
            form = name;
            if (name.equals("REDO")) {
              state.document.undo();
              state.revision++;
            }
            prepare(name, true);
            var source = ProjectIO.JSON.valueToTree(state.project());
            var draft = state.draft;
            long revision = state.revision, bytes = state.document.historyBytes();
            boolean dirty = state.document.dirty();
            frames(8);
            cases.add(receipt(i, globals[i], scales[i]));
            click(cancelControl());
            require(
                !history.session.pending()
                    && files.workflow.phase() == ProjectFileWorkflow.Phase.IDLE
                    && source.equals(ProjectIO.JSON.valueToTree(state.project()))
                    && state.draft == draft
                    && state.revision == revision
                    && bytes == state.document.historyBytes()
                    && dirty == state.document.dirty(),
                "Layout Cancel changed project/private/history");
            if (name.equals("REDO")) {
              state.document.redo();
              state.revision++;
            }
          }
        }
        width = 1200;
        height = 900;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.put("Properties", 1f);
        // Every mutating decision is canceled before its release can act.
        for (String name : List.of("DRAFT", "GUARD", "SAVE", "OPEN", "UNDO", "REDO")) {
          form = name;
          if (name.equals("REDO")) {
            state.document.undo();
            state.revision++;
          }
          prepare(name, false);
          frames(6);
          if (name.equals("SAVE")) {
            click(files.probeControl("Path"));
            type(out.resolve("cancelled-save.json").toString());
          }
          if (name.equals("OPEN")) {
            var incoming = new ProjectData();
            incoming.noise2D.add(noise("incoming", .75));
            new ProjectIO().save(out.resolve("incoming.json"), incoming);
            click(files.probeControl("Path"));
            type(out.resolve("incoming.json").toString());
          }
          var source = ProjectIO.JSON.valueToTree(state.project());
          var draft = state.draft;
          long revision = state.revision, bytes = state.document.historyBytes();
          releaseWithEscape(primaryControl());
          require(
              files.workflow.phase() == ProjectFileWorkflow.Phase.IDLE
                  && !history.session.pending()
                  && source.equals(ProjectIO.JSON.valueToTree(state.project()))
                  && state.draft == draft
                  && state.revision == revision
                  && bytes == state.document.historyBytes()
                  && replacements == 0
                  && exits == 0,
              "Escape lost a decision frame");
          if (name.equals("REDO")) {
            state.document.redo();
            state.revision++;
          }
        }
        require(!Files.exists(out.resolve("cancelled-save.json")), "Canceled Save wrote a file");
        form = "GUARD";
        prepare("GUARD", false);
        frames(5);
        releaseWithEscape(files.probeControl("Don't Save"));
        require(replacements == 0 && exits == 0, "Escape lost Don't Save decision");
        form = "DRAFT";
        prepare("DRAFT", false);
        frames(5);
        releaseWithEscape(files.probeControl("Discard Draft"));
        require(replacements == 0 && exits == 0, "Escape lost Discard Draft decision");

        // A held predecessor must not activate a same-phase reopened request.
        form = "SAVE";
        prepare("SAVE", false);
        frames(5);
        click(files.probeControl("Path"));
        type(out.resolve("held-predecessor.json").toString());
        int oldId = files.pathId();
        long oldRequest = files.workflow.requestVersion();
        press(files.probeControl("Save"));
        files.workflow.cancel();
        files.workflow.save(true);
        frame();
        require(
            files.pathId() != oldId
                && files.workflow.requestVersion() != oldRequest
                && files.probePath().equals("project.pvproj.json"),
            "Same-phase reopen retained old path/widget identity");
        release();
        frames(4);
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.SAVE
                && !Files.exists(out.resolve("held-predecessor.json")),
            "Held Save activated a new request");
        click(files.probeControl("Cancel"));
        form = "UNDO";
        prepare("UNDO", false);
        frames(5);
        var heldSource = ProjectIO.JSON.valueToTree(state.project());
        var heldDraft = state.draft;
        press(history.control("DISCARD"));
        history.session.clear();
        history.request(ProjectHistorySession.Direction.UNDO);
        frame();
        release();
        frames(4);
        require(
            history.session.pending()
                && heldSource.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == heldDraft,
            "Held history decision consumed a successor");
        click(history.control("CANCEL"));

        // Rejected paths retain raw input and return keyboard focus for a click-free correction.
        form = "SAVE";
        prepare("SAVE", false);
        frames(5);
        var beforeSave = ProjectIO.JSON.valueToTree(state.project());
        var privateDraft = state.draft;
        long saveRevision = state.revision, saveHistory = state.document.historyBytes();
        var directory = out.resolve("cannot-replace-directory");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("sentinel"), "retained");
        click(files.probeControl("Path"));
        type(directory.toString());
        click(files.probeControl("Save"));
        frames(5);
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.SAVE
                && !files.workflow.error().isEmpty()
                && files.probePath().equals(directory.toString())
                && !files.focusPending()
                && state.draft == privateDraft
                && beforeSave.equals(ProjectIO.JSON.valueToTree(state.project()))
                && Files.readString(directory.resolve("sentinel")).equals("retained"),
            "Failed Save lost path/private input or destination");
        type(out.resolve("saved.pvproj.json").toString());
        require(
            files.probePath().equals(out.resolve("saved.pvproj.json").toString()),
            "Failed Save did not focus its path");
        click(files.probeControl("Save"));
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.IDLE
                && state.draft == privateDraft
                && state.revision == saveRevision
                && saveHistory == state.document.historyBytes()
                && !state.document.dirty()
                && beforeSave.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Save retry applied private Properties or changed history");
        byte[] savedBytes = Files.readAllBytes(out.resolve("saved.pvproj.json"));
        // Save from the dirty guard must also lose to Escape even with a known project path.
        require(state.change(p -> p.project.seed++), "Known-path guard fixture rejected");
        form = "GUARD";
        prepare("GUARD", false);
        frames(5);
        releaseWithEscape(files.probeControl("Save"));
        require(
            Arrays.equals(savedBytes, Files.readAllBytes(out.resolve("saved.pvproj.json")))
                && state.document.dirty(),
            "Guard Save beat Escape");
        form = "SAVE";
        prepare("SAVE", false);
        frames(5);
        click(files.probeControl("Path"));
        type(out.resolve("stale-save.json").toString());
        require(state.change(p -> p.project.seed++), "Stale path owner fixture rejected");
        click(files.probeControl("Save"));
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.IDLE
                && !Files.exists(out.resolve("stale-save.json")),
            "Stale path request wrote a file");
        form = "OPEN";
        prepare("OPEN", false);
        frames(5);
        var beforeOpen = ProjectIO.JSON.valueToTree(state.project());
        privateDraft = state.draft;
        click(files.probeControl("Path"));
        type(out.resolve("missing-project.json").toString());
        click(files.probeControl("Open"));
        frames(5);
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.OPEN
                && !files.workflow.error().isEmpty()
                && state.draft == privateDraft
                && beforeOpen.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Failed Open replaced the current owner");
        type(out.resolve("incoming.json").toString());
        click(files.probeControl("Open"));
        require(
            replacements == 1
                && exits == 0
                && files.workflow.phase() == ProjectFileWorkflow.Phase.IDLE
                && state.draft == null
                && state.selected == null
                && state.multiSelection.isEmpty()
                && !state.seedInputs.pending(state)
                && state.project().noise2D.getFirst().id.equals("incoming"),
            "Open retry failed its transactional handoff");
        new ProjectIO().save(out.resolve("opened-project.json"), state.project());
        Files.writeString(
            out.resolve("project-decision-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("cancelBeforeMutation", true),
                    Map.entry("samePhaseReopenScoped", true),
                    Map.entry("heldHistoryScoped", true),
                    Map.entry("failedSaveRetained", true),
                    Map.entry("pathFocusRecovery", true),
                    Map.entry("committedOnlySave", true),
                    Map.entry("knownPathGuardCancelled", true),
                    Map.entry("staleSaveBlocked", true),
                    Map.entry("failedOpenRetained", true),
                    Map.entry("openRetryHandoff", true),
                    Map.entry("replacements", replacements),
                    Map.entry("exits", exits),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "PROJECT_DECISION_LAYOUT_NATIVE_PASS persistent file/history actions / Cancel priority"
                + " / request scope / path retry / committed-only save and owned open");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private static void prepare(String name, boolean raw) {
    state.select("noise2D", noiseId);
    if (!name.equals("GUARD")) {
      ((Noise) state.draft).id = "private";
      ((Noise) state.draft).seedOffset = 17;
      if (raw) state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    }
    switch (name) {
      case "DRAFT" -> files.workflow.request(ProjectFileWorkflow.Intent.OPEN);
      case "GUARD" -> files.workflow.request(ProjectFileWorkflow.Intent.OPEN);
      case "SAVE" -> files.workflow.save(true);
      case "OPEN" -> {
        files.workflow.request(ProjectFileWorkflow.Intent.OPEN);
        if (files.workflow.phase() == ProjectFileWorkflow.Phase.DRAFT)
          files.workflow.decideDraft(ProjectFileWorkflow.DraftDecision.DISCARD);
        if (files.workflow.phase() == ProjectFileWorkflow.Phase.GUARD)
          files.workflow.decide(ProjectFileWorkflow.Decision.DISCARD);
      }
      case "UNDO" -> history.request(ProjectHistorySession.Direction.UNDO);
      case "REDO" -> history.request(ProjectHistorySession.Direction.REDO);
      default -> throw new IllegalArgumentException(name);
    }
  }

  private static boolean isHistory() {
    return form.equals("UNDO") || form.equals("REDO");
  }

  private static float[] cancelControl() {
    return isHistory() ? history.control("CANCEL") : files.probeControl("Cancel");
  }

  private static float[] primaryControl() {
    return isHistory()
        ? history.control("DISCARD")
        : files.probeControl(
            switch (form) {
              case "DRAFT" -> "Apply Draft";
              case "GUARD", "SAVE" -> "Save";
              default -> "Open";
            });
  }

  private static Map<String, Object> receipt(int scenario, float global, float scale) {
    var layout = isHistory() ? history.layout : files.layout;
    var chrome = isHistory() ? history.chrome : files.chrome();
    var modal = layout.bounds();
    require(
        DialogBounds.contains(layout.workArea().bounds(), modal)
            && DialogBounds.contains(modal, chrome.body()),
        "Project decision modal/body outside work");
    for (var box : chrome.actions().values())
      require(DialogBounds.contains(modal, box), "Project decision action outside modal");
    var row = new LinkedHashMap<String, Object>();
    row.put("scenario", scenario);
    row.put("form", form);
    row.put("globalScale", global);
    row.put("windowScale", scale);
    row.put("work", layout.workArea().bounds());
    row.put("modal", modal);
    row.put("body", chrome.body());
    row.put("actions", chrome.actions());
    row.put(
        "request",
        isHistory() ? history.session.requestVersion() : files.workflow.requestVersion());
    row.put("pathId", isHistory() ? 0 : files.pathId());
    return row;
  }

  private static void press(float[] at) {
    require(at != null, "Missing project decision control");
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

  private static void click(float[] at) {
    press(at);
    release();
    frames(4);
  }

  private static void releaseWithEscape(float[] at) {
    press(at);
    var io = ImGui.getIO();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void type(String value) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frames(4);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Project decision layout fixture", ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, "Properties");
    DialogLayout.observe(state, "Properties");
    if (isHistory()) history.draw();
    else files.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
