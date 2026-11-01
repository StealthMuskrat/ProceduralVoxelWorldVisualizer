package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Deferred real review lifetime, disabled Reload and publication reentry without GLFW or GL. */
public final class ResourceReviewLifetimeProbe {
  private static EditorState state;
  private static ResourcePanel resources;
  private static ResourceImportPanel imports;
  private static ResourcePackageDialog packages;
  private static ProjectData incoming;
  private static String form = "import";
  private static boolean publishingImport, rejectImport, inFrame, nestedSeen;
  private static String validationMode = "";
  private static int root, eligibilityObservations, publicationObservations, validationObservations;
  private static final List<String> deliveryRoutes = new ArrayList<>();
  private static int width = 1200, height = 900, validations, writes, replacements;
  private static float global = 1;
  private static final List<Map<String, Object>> cases = new ArrayList<>(),
      phases = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(TraceWorkflowProbe::fixture), "Fixture rejected");
        state.document.save(out.resolve("source-project.json"));
        state.user.save(out.resolve("preferences-before.json"));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        incoming = new ProjectData();
        var added = new Noise();
        added.id = "added";
        added.algorithm.key = "CONSTANT";
        added.algorithm.parameters.clear();
        added.algorithm.parameters.put("value", .75);
        incoming.noise2D.add(added);
        new ProjectIO().save(out.resolve("incoming.json"), incoming);
        resources = new ResourcePanel(state, new PropertyPanel(state));
        imports =
            new ResourceImportPanel(
                state, ResourceReviewLifetimeProbe::gate, ResourceReviewLifetimeProbe::validation);
        var files =
            new ResourcePackageFiles(
                new ResourcePackageFiles.Storage() {
                  public void write(Path temporary, byte[] bytes) throws IOException {
                    observePublication();
                    writes++;
                    var edit = resources.exportView().session();
                    var selection = Set.copyOf(resources.exportSelection);
                    require(
                        edit != null
                            && edit.busy()
                            && resources.blocksProjectTransition()
                            && !edit.canExport()
                            && !edit.canReload(),
                        "Export publication lost its fence");
                    packages.cancel();
                    resources.reset();
                    resources.cancelExport();
                    resources.beginExport();
                    resources.exportView().reset();
                    require(
                        packages.pending()
                            && resources.exportView().session() == edit
                            && selection.equals(resources.exportSelection)
                            && !packages.open("Import"),
                        "Publication cleanup replaced the active workspace");
                    try {
                      edit.write(
                          out.resolve("nested-must-not-write.pvresources.json"),
                          new ResourcePackageFiles());
                      throw new AssertionError("Nested export accepted");
                    } catch (IllegalStateException expected) {
                    }
                    Files.write(temporary, bytes);
                  }

                  public void replace(Path temporary, Path target) throws IOException {
                    observePublication();
                    replacements++;
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                  }
                });
        packages =
            new ResourcePackageDialog(
                state, resources, imports, ResourceReviewLifetimeProbe::gate, files);
        state.document.setValidator(ResourceReviewLifetimeProbe::validate);

        importDelivery();
        require(imports.open(incoming), "Import did not open");
        frames(5);
        var oldImport = imports.session();
        var importPlan = oldImport.preview();
        require(oldImport.canApply(), "Visible import was not reviewed");
        capture("import-current");
        phase("import-current", true, true);
        var token = state.document.sessionToken();
        long revision = state.revision;
        require(
            state.document.open(out.resolve("source-project.json")).isEmpty(),
            "Same-UUID reopen failed");
        frames(3);
        require(
            token != state.document.sessionToken()
                && state.revision == revision
                && !oldImport.current()
                && !oldImport.canReload()
                && !oldImport.canApply(),
            "Reopened document retained import authority");
        capture("import-reopened");
        click(imports.control("reload"));
        require(
            oldImport.preview() == importPlan
                && oldImport.apply() == ImportEditSession.Result.STALE
                && !oldImport.canReload(),
            "Disabled Reload revived an earlier import");
        phase("import-reopened", false, false);
        click(imports.control("cancel"));
        require(imports.open(incoming), "Fresh import did not open");
        frames(5);
        var freshImport = imports.session();
        var target =
            freshImport.preview().rows().stream()
                .filter(row -> row.id().equals("added"))
                .findFirst()
                .orElseThrow()
                .targetUuid();
        revision = state.revision;
        publishingImport = true;
        click(imports.control("apply"));
        publishingImport = false;
        require(
            !imports.pending()
                && freshImport.closed()
                && !freshImport.busy()
                && validations == 2
                && state.revision == revision + 1,
            "Fresh import did not publish exactly once");
        new ProjectIO().save(out.resolve("imported-project.json"), state.project());
        phase("import-committed", false, false);
        state.document.undo();
        state.revision++;
        new ProjectIO().save(out.resolve("import-undo.json"), state.project());
        require(
            read(out, "source-project.json").equals(read(out, "import-undo.json")),
            "One Undo did not restore source");
        phase("import-undone", false, false);

        form = "export";
        packageDelivery(out);
        require(packages.open("Export"), "Export did not open");
        frames(5);
        click(packages.control("included"));
        var oldExport = resources.exportView().session();
        var exportPlan = oldExport.preview();
        require(oldExport.canExport(), "Visible export was not reviewed");
        capture("export-current");
        phase("export-current", true, true);
        token = state.document.sessionToken();
        revision = state.revision;
        require(
            state.document.open(out.resolve("source-project.json")).isEmpty(),
            "Same-UUID export reopen failed");
        frames(3);
        require(
            token != state.document.sessionToken()
                && state.revision == revision
                && !oldExport.current()
                && !oldExport.canReload()
                && !oldExport.canExport(),
            "Reopened document retained export authority");
        capture("export-reopened");
        click(packages.control("reload"));
        var forbidden = out.resolve("stale-must-not-write.pvresources.json");
        try {
          resources.writeExport(forbidden, files);
          throw new AssertionError("Stale export accepted");
        } catch (IllegalStateException expected) {
        }
        require(
            oldExport.preview() == exportPlan
                && !oldExport.canReload()
                && !Files.exists(forbidden)
                && writes == 0,
            "Disabled Reload revived an earlier export");
        phase("export-reopened", false, false);
        click(packages.control("cancel"));
        require(packages.open("Export"), "Fresh export did not open");
        frames(5);
        click(packages.control("included"));
        click(packages.control("location"));
        path(out.resolve("selection.pvresources.json"));
        var freshExport = resources.exportView().session();
        click(packages.control("submit"));
        require(
            !packages.pending()
                && freshExport.closed()
                && !freshExport.busy()
                && writes == 1
                && replacements == 1
                && resources.exportView().session() == null
                && resources.exportSelection.isEmpty(),
            "Fresh export did not complete exactly once");
        phase("export-written", false, false);
        require(
            state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Review lifetime changed private input");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        require(
            read(out, "source-project.json").equals(read(out, "after.json"))
                && read(out, "preferences-before.json").equals(read(out, "preferences.json")),
            "Review lifetime changed final project/preferences");
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("fontSource", "synthetic-global-window-fixture");
        report.put("cases", cases);
        report.put("phases", phases);
        report.put("copiedTarget", target);
        report.put("incoming", added.uuid);
        report.put("importValidations", validations);
        report.put("exportWrites", writes);
        report.put("exportReplacements", replacements);
        report.put("physicalDpiVerified", false);
        report.put("deliveryRoutes", deliveryRoutes);
        report.put("eligibilityCallbacksOutsidePopup", eligibilityObservations);
        report.put("publicationCallbacksAfterPopup", publicationObservations);
        report.put("validationCallbacksAfterPopup", validationObservations);
        Files.writeString(
            out.resolve("resource-review-lifetime-native.json"),
            ProjectIO.JSON.writeValueAsString(report));
        System.out.println(
            "RESOURCE_REVIEW_LIFETIME_NATIVE_PASS identical reopen retires reviews / disabled"
                + " Reload / explicit fresh review / busy publication / one import Undo / one"
                + " export");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static boolean gate() {
    if (inFrame) {
      observe();
      eligibilityObservations++;
    }
    return false;
  }

  private static void observe() {
    require(
        inFrame && root == ImGui.getID("review-root"),
        "Resource callback delivered inside a popup or child");
  }

  private static void observePublication() {
    observe();
    publicationObservations++;
  }

  private static void validation() {
    observe();
    validationObservations++;
    if (validationMode.equals("nested")) {
      imports.cancel();
      ImGui.openPopup("Resource validation destination");
    } else {
      imports.cancel();
      require(imports.open(incoming), "Validation did not open its successor");
      if (validationMode.equals("throw"))
        throw new IllegalStateException("validation callback boundary");
    }
  }

  private static void importDelivery() {
    var before = ProjectIO.JSON.valueToTree(state.project());
    long revision = state.revision;
    require(imports.open(incoming), "Held Import did not open");
    frames(4);
    var oldIds = imports.chrome.actionIds();
    press(imports.control("apply"));
    imports.cancel();
    require(imports.open(incoming), "Held Import successor did not open");
    frame();
    release();
    require(
        imports.pending()
            && !oldIds.equals(imports.chrome.actionIds())
            && state.revision == revision,
        "Held Import activated its successor");
    click(imports.control("cancel"));
    deliveryRoutes.add("import-held");
    require(imports.open(incoming), "Escape Import did not open");
    frames(4);
    press(imports.control("apply"));
    escapeRelease();
    require(!imports.pending() && state.revision == revision, "Escape did not supersede Import");
    deliveryRoutes.add("import-escape");
    require(imports.open(incoming), "Rejected Import did not open");
    frames(4);
    rejectImport = publishingImport = true;
    click(imports.control("apply"));
    rejectImport = publishingImport = false;
    require(
        imports.pending() && imports.session().issueCount() > 0 && state.revision == revision,
        "Rejected import published");
    for (var mode : List.of("successor", "throw", "nested")) {
      if (imports.session().issueCount() == 0) {
        state.document.setValidator(
            p -> List.of(Diagnostic.error("FIXTURE_REJECT", "/fixture", "Deferred rejection")));
        imports.session().markShown(imports.session().preview());
        require(
            imports.session().apply() == ImportEditSession.Result.REJECTED,
            "Validation fixture did not reject");
        state.document.setValidator(ResourceReviewLifetimeProbe::validate);
      }
      validationMode = mode;
      frames(3);
      click(imports.control("validation"));
      if (mode.equals("nested")) {
        require(!imports.pending() && nestedSeen, "Old import closed the destination popup");
      } else {
        require(
            imports.pending()
                && imports.message().isEmpty()
                && imports.section().equals("Resources"),
            "Old validation completion annotated its successor");
      }
      deliveryRoutes.add("validation-" + mode);
    }
    frames(3);
    require(
        before.equals(ProjectIO.JSON.valueToTree(state.project())) && state.revision == revision,
        "Import delivery changed committed resources");
  }

  private static List<Diagnostic> validate(ProjectData project) {
    if (publishingImport) {
      observePublication();
      validations++;
      var edit = imports.session();
      require(
          edit != null && edit.busy() && !edit.current() && !edit.canReload(),
          "Import publication lost busy state");
      require(edit.apply() == ImportEditSession.Result.BUSY, "Nested import accepted");
      imports.cancel();
      require(
          imports.session() == edit && imports.pending() && !imports.open(incoming),
          "Busy import was cancelled or replaced");
    }
    return rejectImport
        ? List.of(Diagnostic.error("FIXTURE_REJECT", "/fixture", "Deferred rejection"))
        : NoiseCompiler.validate(project);
  }

  private static void packageDelivery(Path out) {
    for (boolean escape : new boolean[] {false, true}) {
      require(packages.open("Export"), "Delivery Export did not open");
      frames(4);
      click(packages.control("included"));
      click(packages.control("location"));
      var forbidden =
          out.resolve(
              escape
                  ? "escape-must-not-write.pvresources.json"
                  : "held-must-not-write.pvresources.json");
      path(forbidden);
      var oldIds = packages.chrome().actionIds();
      press(packages.control("submit"));
      if (escape) {
        escapeRelease();
        require(!packages.pending(), "Escape did not cancel Export");
      } else {
        packages.cancel();
        require(packages.open("Export"), "Held export successor did not open");
        frame();
        release();
        require(
            packages.pending()
                && !oldIds.equals(packages.chrome().actionIds())
                && !resources.canExport(),
            "Held Export activated/reviewed its successor");
        click(packages.control("cancel"));
      }
      require(writes == 0 && !Files.exists(forbidden), "Cancelled export reached storage");
      deliveryRoutes.add(escape ? "export-escape" : "export-held");
    }
  }

  private static void press(float[] at) {
    require(at != null, "Missing held action");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
    frames(2);
  }

  private static void escapeRelease() {
    var io = ImGui.getIO();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(2);
  }

  private static void phase(String name, boolean current, boolean reload) {
    phases.add(
        Map.of(
            "phase",
            name,
            "current",
            current,
            "canReload",
            reload,
            "noiseCount",
            state.project().noise2D.size()));
  }

  private static SettingsPageChrome chrome() {
    return form.equals("import") ? imports.chrome : packages.chrome();
  }

  private static DialogLayout layout() {
    return form.equals("import") ? imports.layout : packages.layout;
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Resource review lifetime caller");
    UiScale.window(state.user, "Resources");
    root = ImGui.getID("review-root");
    inFrame = true;
    try {
      if (form.equals("import")) {
        imports.draw();
        imports.draw();
      } else {
        packages.draw();
        packages.draw();
      }
      require(root == ImGui.getID("review-root"), "Review draw leaked native scopes");
      if (ImGui.beginPopup("Resource validation destination")) {
        try {
          nestedSeen = true;
          ImGui.textUnformatted("Validation destination");
          ImGui.closeCurrentPopup();
        } finally {
          ImGui.endPopup();
        }
      }
    } finally {
      inFrame = false;
    }
    ImGui.end();
    ImGui.render();
  }

  private static void capture(String name) {
    var old = state.user.appearance.windowScales.get("Resources");
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      global = globals[i];
      state.user.appearance.windowScales.put("Resources", scales[i]);
      frames(6);
      if (ids == null) ids = chrome().actionIds();
      require(ids.equals(chrome().actionIds()), "Reflow replaced review action IDs");
      cases.add(
          Map.of(
              "phase",
              name,
              "scenario",
              i,
              "globalScale",
              global,
              "windowScale",
              scales[i],
              "window",
              layout().bounds(),
              "body",
              chrome().body(),
              "actions",
              chrome().actions(),
              "ids",
              chrome().actionIds()));
    }
    width = 1200;
    height = 900;
    global = 1;
    if (old == null) state.user.appearance.windowScales.remove("Resources");
    else state.user.appearance.windowScales.put("Resources", old);
    frames(3);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void click(float[] at) {
    require(at != null, "Missing review action");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    frames(2);
  }

  private static void path(Path target) {
    click(packages.control("Path"));
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(target.toString());
    frames(3);
    require(packages.pathText().equals(target.toString()), "Typed package path differs");
  }

  private static com.fasterxml.jackson.databind.JsonNode read(Path out, String name)
      throws Exception {
    return ProjectIO.JSON.readTree(out.resolve(name).toFile());
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
