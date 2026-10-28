package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production import workspace: real filter/combo/modal events and saved-model replay. */
public final class ImportWorkspaceProbe {
  private static ResourceImportPanel panel;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1400, 1000);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var state = new EditorState(new UserConfig(), false)) {
        require(
            state.change(
                p -> {
                  p.project.seed = 41;
                  p.noise2D.add(noise("editing", .125));
                  p.noise2D.add(noise("shared", .25));
                  p.noise2D.add(noise("conflict", .5));
                }),
            "Destination fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = state.draft;
        UUID selected = state.selected, existingShared = state.project().noise2D.get(1).uuid;
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var user = ProjectIO.JSON.valueToTree(state.user);
        var original = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var incoming = incoming();
        UUID shared = incoming.noise2D.get(0).uuid,
            conflict = incoming.noise2D.get(1).uuid,
            retained = incoming.noise2D.get(2).uuid,
            discarded = incoming.noise2D.get(3).uuid;
        Files.writeString(
            out.resolve("import-package.json"),
            ProjectIO.JSON.writeValueAsString(Map.of("packageVersion", 1, "resources", incoming)));
        var blocked = new AtomicBoolean(true);
        int[] validation = {0};
        panel = new ResourceImportPanel(state, blocked::get, () -> validation[0]++);
        require(!panel.open(incoming), "Blocked import entry");
        blocked.set(false);
        require(panel.open(incoming), "Import did not open");
        skipBulk(incoming);
        frames(4);
        int top = panel.submitted();
        var topIds = panel.visibleIds();
        long queries = panel.queries();
        var session = panel.session();
        var firstPlan = session.preview();
        require(
            !panel.open(new ProjectData()) && panel.session() == session,
            "Second package replaced workspace");
        panel.probeScroll(15000);
        frames(4);
        int scrolled = panel.submitted();
        require(
            top > 0
                && top <= 24
                && scrolled > 0
                && scrolled <= 24
                && !topIds.equals(panel.visibleIds())
                && queries == panel.queries()
                && firstPlan == session.preview(),
            "Scroll rebuilt preview/query or submitted unbounded rows");
        text("shared");
        choose(shared, ResourceImport.Choice.REUSE);
        require(
            row(session.preview(), shared).targetUuid().equals(existingShared),
            "Compatible reuse target");
        text("discarded");
        choose(discarded, ResourceImport.Choice.SKIP);
        text("conflict");
        click(panel.rowControl(conflict));
        require(
            panel.control(conflict + ":COPY") != null && panel.control(conflict + ":REUSE") == null,
            "Incompatible reuse was offered");
        click(panel.control(conflict + ":COPY"));
        escape();
        require(
            !panel.pending()
                && state.draft == draft
                && state.seedInputs.text("seedOffset").equals("-")
                && original.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && history == state.document.historyBytes(),
            "Cancel changed destination/private draft");
        require(panel.open(incoming), "Import reopen failed");
        skipBulk(incoming);
        frames(4);
        text("shared");
        choose(shared, ResourceImport.Choice.REUSE);
        text("discarded");
        choose(discarded, ResourceImport.Choice.SKIP);
        session = panel.session();
        var oldPreview = session.preview();
        UUID oldCopy = row(oldPreview, retained).targetUuid();
        state.change(p -> p.project.seed = 42);
        frames(4);
        require(
            !session.current() && !session.canApply() && oldPreview == session.preview(),
            "Changed destination silently refreshed");
        click("apply");
        require(panel.pending() && state.project().noise2D.size() == 3, "Stale preview applied");
        click("reload");
        var preview = session.preview();
        UUID newCopy = row(preview, retained).targetUuid();
        require(
            !oldCopy.equals(newCopy)
                && session.choice(shared) == ResourceImport.Choice.REUSE
                && session.choice(discarded) == ResourceImport.Choice.SKIP
                && session.canApply(),
            "Explicit reload lost choices or review");
        state.document.save(out.resolve("import-before.json"));
        var beforeImport = ProjectIO.JSON.valueToTree(state.project());
        revision = state.revision;
        history = state.document.historyBytes();
        state.document.setValidator(
            p ->
                p.noise2D.size() > 3
                    ? List.of(
                        Diagnostic.error("IMPORT_FIXTURE", "/noise2D", "Injected import rejection"))
                    : NoiseCompiler.validate(p));
        click("apply");
        require(
            panel.pending()
                && session.preview() == preview
                && session.issueCount() == 1
                && state.draft == draft
                && state.seedInputs.text("seedOffset").equals("-")
                && beforeImport.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Rejected import lost ownership");
        click("validation");
        require(validation[0] == 1, "Validation action missing");
        state.document.setValidator(NoiseCompiler::validate);
        click("apply");
        require(
            !panel.pending()
                && state.project().noise2D.size() == 5
                && state.draft == draft
                && selected.equals(state.selected)
                && state.multiSelection.equals(Set.of(selected))
                && state.seedInputs.text("seedOffset").equals("-")
                && state.revision == revision + 1,
            "Successful import refreshed/applied private Properties");
        var after = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(
            beforeImport.equals(ProjectIO.JSON.valueToTree(state.project())), "One import Undo");
        state.document.redo();
        state.revision++;
        require(
            after.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "One import Redo or preferences");
        state.document.save(out.resolve("import-after.json"));
        Files.writeString(
            out.resolve("import-targets.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "shared",
                    row(preview, shared).targetUuid(),
                    "conflict",
                    row(preview, conflict).targetUuid(),
                    "retained",
                    newCopy,
                    "beforeReloadRetained",
                    oldCopy,
                    "existingShared",
                    existingShared)));
        Files.writeString(
            out.resolve("import-workspace-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "incomingRows",
                    1004,
                    "topRows",
                    top,
                    "scrolledRows",
                    scrolled,
                    "cancelRetained",
                    true,
                    "staleReload",
                    true,
                    "privateDraftRetained",
                    true,
                    "failedApplyRetained",
                    true,
                    "oneUndoRedo",
                    true,
                    "queriesReused",
                    true)));
        System.out.println(
            "IMPORT_WORKSPACE_NATIVE_PASS 1004 clipped rows / native choices / explicit reload /"
                + " validation retry / private draft / one Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static ProjectData incoming() {
    var p = new ProjectData();
    p.noise2D.add(noise("shared", .25));
    p.noise2D.add(noise("conflict", .9));
    p.noise2D.add(noise("retained", .75));
    p.noise2D.add(noise("discarded", .875));
    for (int i = 0; i < 1000; i++)
      p.noise2D.add(noise(String.format(Locale.ROOT, "bulk-%04d", i), .125));
    return p;
  }

  private static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private static void skipBulk(ProjectData incoming) {
    incoming.noise2D.stream()
        .filter(n -> n.id.startsWith("bulk-"))
        .forEach(n -> panel.session().choose(n.uuid, ResourceImport.Choice.SKIP));
  }

  private static ResourceImport.Row row(ResourceImport.Plan plan, UUID id) {
    return plan.rows().stream().filter(r -> r.uuid().equals(id)).findFirst().orElseThrow();
  }

  private static void choose(UUID id, ResourceImport.Choice choice) {
    click(panel.rowControl(id));
    click(panel.control(id + ":" + choice));
  }

  private static void text(String text) {
    click("filter");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(4);
  }

  private static void click(String key) {
    click(panel.control(key));
  }

  private static void click(float[] at) {
    require(at != null, "Import control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(4);
  }

  private static void escape() {
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
