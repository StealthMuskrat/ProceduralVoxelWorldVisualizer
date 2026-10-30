package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deferred production package modal: native pages/path/decisions with isolated real package I/O.
 */
public final class PackageLayoutProbe {
  private static EditorState state;
  private static ResourcePanel resources;
  private static ResourceImportPanel imports;
  private static ResourcePackageDialog dialog;
  private static final AtomicBoolean blocked = new AtomicBoolean(), failStage = new AtomicBoolean();
  private static int width = 1200, height = 900, stageAttempts;

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
        require(state.change(PackageLayoutProbe::fixture), "Package fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private-field";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var properties = new PropertyPanel(state);
        resources = new ResourcePanel(state, properties);
        imports = new ResourceImportPanel(state, blocked::get, () -> {});
        var files =
            new ResourcePackageFiles(
                new ResourcePackageFiles.Storage() {
                  public void write(Path temporary, byte[] content) throws IOException {
                    stageAttempts++;
                    if (failStage.getAndSet(false)) {
                      Files.write(temporary, Arrays.copyOf(content, Math.min(17, content.length)));
                      throw new IOException("Injected partial staging failure");
                    }
                    try (var channel =
                        FileChannel.open(
                            temporary,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                      var bytes = ByteBuffer.wrap(content);
                      while (bytes.hasRemaining()) channel.write(bytes);
                      channel.force(true);
                    }
                  }

                  public void replace(Path temporary, Path destination) throws IOException {
                    Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                  }
                });
        dialog = new ResourcePackageDialog(state, resources, imports, blocked::get, files);
        var baseline = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var engine = state.engine;
        boolean dirty = state.document.dirty();
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        Integer rowId = null;
        for (int i = 0; i < sizes.length; i++) {
          dimensions(sizes[i], globals[i], scales[i]);
          openExport();
          var view = resources.exportView();
          require(!resources.canExport(), "Choices alone acknowledged the export closure");
          Integer id = view.rowIds().get(state.project().voxelSets.getFirst().uuid);
          require(
              id != null && (rowId == null || rowId.equals(id)),
              "Export row identity changed across reflow");
          rowId = id;
          long preparations = view.preparations(), queries = view.queries();
          frames(4);
          require(
              preparations == view.preparations() && queries == view.queries(),
              "Idle export rebuilt its query/closure");
          cases.add(receipt(i, globals[i], scales[i]));
          click(dialog.control("included"));
          require(resources.canExport(), "Included did not acknowledge its exact preview");
          cases.add(receipt(i, globals[i], scales[i]));
          click(dialog.control("location"));
          cases.add(receipt(i, globals[i], scales[i]));
          click(dialog.control("cancel"));
          require(
              !dialog.pending() && resources.exportSelection.isEmpty(),
              "Export Cancel retained selection");
          require(dialog.open("Import"), "Import path did not open");
          frames(7);
          cases.add(receipt(i, globals[i], scales[i]));
          click(dialog.control("cancel"));
        }
        require(
            baseline.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.engine == engine
                && state.draft == draft
                && draft.id.equals("private-field")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Package reflow changed project/private/runtime ownership");
        dimensions(new int[] {1200, 900}, 1, 1);
        var active = out.resolve("active-project.pvproj.json");
        state.document.save(active);
        byte[] protectedBytes = Files.readAllBytes(active);
        openExport();
        click(dialog.control("included"));
        click(dialog.control("location"));
        var session = resources.exportView().session();
        var preview = session.preview();
        var pathBefore = dialog.pathText();
        require(
            !dialog.open("Import")
                && dialog.action().equals("Export")
                && dialog.pathText().equals(pathBefore),
            "Pending package was retargeted");
        setPath(active.toString());
        click(dialog.control("submit"));
        require(
            dialog.pending()
                && dialog.page().equals("Path")
                && !dialog.error().isEmpty()
                && Arrays.equals(protectedBytes, Files.readAllBytes(active))
                && stageAttempts == 0
                && session.preview() == preview
                && resources.canExport(),
            "Protected project path changed source/preview");
        var target = out.resolve("selection.pvresources.json");
        Files.writeString(target, "previous package");
        setPath(target.toString());
        failStage.set(true);
        click(dialog.control("submit"));
        require(
            dialog.pending()
                && dialog.pathText().equals(target.toString())
                && session.preview() == preview
                && resources.canExport()
                && Files.readString(target).equals("previous package")
                && stageAttempts == 1,
            "Failed staging consumed the reviewed preview/path or replaced the target");
        try (var entries = Files.list(out)) {
          require(
              entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
              "Failed staging leaked a temporary file");
        }
        click(dialog.control("submit"));
        require(
            !dialog.pending() && session.closed() && !resources.canExport() && stageAttempts == 2,
            "Successful retry was not consumed exactly once");
        new ResourcePackageFiles().read(target);
        require(
            Arrays.equals(protectedBytes, Files.readAllBytes(active))
                && state.draft == draft
                && state.seedInputs.text("seedOffset").equals("-"),
            "Retry touched active project/private input");
        openExport();
        click(dialog.control("included"));
        click(dialog.control("location"));
        var cancelled = out.resolve("cancelled.pvresources.json");
        setPath(cancelled.toString());
        releaseWithEscape(dialog.control("submit"));
        require(
            !dialog.pending() && !Files.exists(cancelled) && stageAttempts == 2,
            "Same-frame Escape wrote an export");

        openExport();
        click(dialog.control("included"));
        resources.exportView().reset();
        frames(4);
        click(dialog.control("submit"));
        require(
            dialog.pending() && resources.exportView().session() == null && stageAttempts == 2,
            "An externally reset workspace was implicitly reopened or written");
        click(dialog.control("cancel"));

        openExport();
        click(dialog.control("included"));
        var view = resources.exportView();
        var old = view.session().preview();
        long prepared = view.preparations();
        require(state.change(p -> p.project.seed++), "Stale-source edit rejected");
        frames(4);
        require(
            !resources.canExport()
                && view.session().preview() == old
                && view.preparations() == prepared,
            "Stale export silently rebuilt");
        click(dialog.control("submit"));
        require(stageAttempts == 2, "Disabled stale Export performed I/O");
        clickOnce(dialog.control("reload"));
        require(!resources.canExport(), "Reload exported in its decision frame");
        frames(5);
        require(
            resources.canExport()
                && view.session().preview() != old
                && view.preparations() == prepared + 1,
            "Reload successor was not reviewed");
        require(state.change(p -> p.voxelSets.clear()), "Missing-selection fixture rejected");
        frames(3);
        click(dialog.control("reload"));
        require(
            view.unavailable() == 1 && !resources.canExport(),
            "Reload silently dropped a missing UUID");
        click(dialog.control("remove-unavailable"));
        require(
            view.unavailable() == 0
                && resources.exportSelection.equals(Set.of(state.project().noise2D.getFirst().uuid))
                && resources.canExport()
                && stageAttempts == 2,
            "Remove Missing substituted selections or wrote a package");
        click(dialog.control("cancel"));

        // Import must hand off only a validated package, with no project replacement/commit.
        var importBase = ProjectIO.JSON.valueToTree(state.project());
        long importRevision = state.revision, importHistory = state.document.historyBytes();
        blocked.set(true);
        require(!dialog.open("Import"), "Blocked package request opened");
        blocked.set(false);
        require(dialog.open("Import"), "Import retry did not open");
        frames(5);
        var malformed = out.resolve("malformed.pvresources.json");
        Files.writeString(malformed, "{\"packageVersion\":2}");
        setPath(malformed.toString());
        click(dialog.control("submit"));
        require(
            dialog.pending() && !imports.pending() && !dialog.error().isEmpty(),
            "Malformed package opened an import workspace");
        setPath(target.toString());
        releaseWithEscape(dialog.control("submit"));
        require(!dialog.pending() && !imports.pending(), "Same-frame Escape opened Import review");
        require(dialog.open("Import"), "Valid package handoff did not open");
        frames(5);
        setPath(target.toString());
        click(dialog.control("submit"));
        require(
            !dialog.pending()
                && imports.pending()
                && importBase.equals(ProjectIO.JSON.valueToTree(state.project()))
                && importRevision == state.revision
                && importHistory == state.document.historyBytes()
                && state.draft == draft,
            "Package handoff replaced or committed the project/private draft");
        imports.cancel();
        frames(2);
        require(dialog.open("Export"), "Foreign-source fixture did not open");
        frames(4);
        state.newSession();
        frames(3);
        click(dialog.control("reload"));
        require(
            dialog.pending() && !resources.exportView().canReload() && !resources.canExport(),
            "Foreign project reused the export source");
        click(dialog.control("cancel"));
        Files.writeString(
            out.resolve("package-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("exactIncludedReview", true),
                    Map.entry("cachedQueriesAndClosure", true),
                    Map.entry("pendingRetargetBlocked", true),
                    Map.entry("externalResetBlocked", true),
                    Map.entry("activeProjectProtected", true),
                    Map.entry("failedStageRetained", true),
                    Map.entry("successfulRetryConsumed", true),
                    Map.entry("cancelBeforeIo", true),
                    Map.entry("reloadReviewRequired", true),
                    Map.entry("explicitMissingRemoval", true),
                    Map.entry("foreignReloadBlocked", true),
                    Map.entry("malformedImportRetained", true),
                    Map.entry("importHandoffOnly", true),
                    Map.entry("privateStatePreserved", true),
                    Map.entry("stageAttempts", stageAttempts),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "PACKAGE_LAYOUT_NATIVE_PASS persistent path/review actions / exact owned preview /"
                + " protected and failed I/O / Cancel / handoff");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    for (int i = 0; i < 2000; i++) {
      var t = new VoxelType();
      t.id = String.format(Locale.ROOT, "type-%05d", i);
      p.voxelTypes.add(t);
    }
    var set = new Membership();
    set.id = "catalog";
    set.voxelTypeUuids.addAll(List.of(p.voxelTypes.getFirst().uuid, p.voxelTypes.getLast().uuid));
    p.voxelSets.add(set);
    for (int dimension : List.of(2, 3)) {
      var n = new Noise();
      n.id = "shared-field";
      n.dimension = dimension;
      n.algorithm.key = "CONSTANT";
      n.algorithm.parameters.clear();
      n.algorithm.parameters.put("value", dimension == 2 ? .625 : .25);
      (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    }
  }

  private static void openExport() {
    require(dialog.open("Export"), "Export did not open");
    frames(7);
    var view = resources.exportView();
    click(view.probeControl("filter"));
    type("catalog");
    click(view.probeControl(state.project().voxelSets.getFirst().uuid.toString()));
    frames(3);
  }

  private static void dimensions(int[] size, float global, float scale) {
    width = size[0];
    height = size[1];
    state.user.appearance.globalScale = global;
    state.user.appearance.windowScales.put("Resources", scale);
  }

  private static void setPath(String text) {
    click(dialog.control("Path"));
    type(text);
  }

  private static Map<String, Object> receipt(int scenario, float global, float scale) {
    var modal = dialog.layout.bounds();
    var chrome = dialog.chrome();
    require(
        DialogBounds.contains(dialog.layout.workArea().bounds(), modal)
            && DialogBounds.contains(modal, chrome.body()),
        "Package modal/body outside work area");
    for (var box : chrome.actions().values())
      require(DialogBounds.contains(modal, box), "Package action outside modal");
    var row = new LinkedHashMap<String, Object>();
    row.put("scenario", scenario);
    row.put("form", dialog.action() + "-" + dialog.page());
    row.put("work", dialog.layout.workArea().bounds());
    row.put("modal", modal);
    row.put("body", chrome.body());
    row.put("actions", chrome.actions());
    row.put("globalScale", global);
    row.put("windowScale", scale);
    var view = resources.exportView();
    row.put("choices", dialog.page().equals("Choices") ? view.submitted() : 0);
    row.put("included", dialog.page().equals("Included") ? view.includedRows() : 0);
    row.put("ids", dialog.page().equals("Choices") ? view.rowIds() : Map.of());
    row.put(
        "list", Set.of("Choices", "Included").contains(dialog.page()) ? view.listBounds() : null);
    row.put("stride", view.rowStride());
    row.put("metrics", view.rowMetrics());
    return row;
  }

  private static void click(float[] at) {
    clickOnce(at);
    frames(4);
  }

  private static void clickOnce(float[] at) {
    require(at != null, "Missing package native control");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void releaseWithEscape(float[] at) {
    require(at != null, "Missing package submit");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
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
    frames(4);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Package layout fixture", ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, "Resources");
    DialogLayout.observe(state, "Resources");
    dialog.draw();
    imports.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
