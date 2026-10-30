package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production hierarchical choices/Included disclosure and exact operation-only export. */
public final class ExportHierarchyProbe {
  private static EditorState state;
  private static ResourcePanel resources;
  private static ResourcePackageDialog dialog;
  private static ResourceImportPanel imports;
  private static int width = 1200, height = 900;
  private static UUID chosen, signal, fields, nested, worldFolder, deepWorld, carrier;

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
        require(state.change(ExportHierarchyProbe::fixture), "Hierarchy fixture rejected");
        state.select("noise2D", signal);
        var draft = (Noise) state.draft;
        draft.id = "private-signal";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        resources = new ResourcePanel(state, new PropertyPanel(state));
        imports = new ResourceImportPanel(state, () -> false, () -> {});
        dialog = new ResourcePackageDialog(state, resources, imports, () -> false);
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        var cases = new ArrayList<Map<String, Object>>();
        Integer id = null;
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = globals[i];
          state.user.appearance.windowScales.put("Resources", scales[i]);
          require(dialog.open("Export"), "Hierarchy Export did not open");
          // Case setup selects the operation; native checkbox/folder actions are exercised below.
          resources.exportSelection.clear();
          resources.exportSelection.add(chosen);
          frames(7);
          var view = resources.exportView();
          click(view.probeControl("filter"));
          type("chosen");
          wheel(-100);
          require(
              view.rowIds().containsKey(chosen) && view.treeDepths().get(chosen) == 3,
              "Search failed to reveal deep operation");
          Integer actual = view.rowIds().get(chosen);
          require(id == null || id.equals(actual), "Operation checkbox ID changed on reflow");
          id = actual;
          long queries = view.queries(), builds = view.treeBuilds(), prepared = view.preparations();
          frames(4);
          require(
              queries == view.queries()
                  && builds == view.treeBuilds()
                  && prepared == view.preparations(),
              "Idle hierarchy rebuilt its catalog/query/closure");
          cases.add(receipt(i, globals[i], scales[i]));
          click(dialog.control("included"));
          require(resources.canExport(), "Included hierarchy did not acknowledge its preview");
          cases.add(receipt(i, globals[i], scales[i]));
          click(dialog.control("cancel"));
          require(!dialog.pending(), "Hierarchy Cancel retained the file action");
        }
        width = 1200;
        height = 900;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.put("Resources", 1f);
        require(dialog.open("Export"), "Interactive hierarchy open");
        frames(6);
        var view = resources.exportView();
        // Deselect the opening signal and select the operation using actual checkboxes.
        click(view.probeControl("filter"));
        type("signal");
        click(view.probeControl(signal.toString()));
        require(resources.exportSelection.isEmpty(), "Signal deselection failed");
        click(view.probeControl("filter"));
        type("chosen");
        click(view.probeControl(chosen.toString()));
        require(
            resources.exportSelection.equals(Set.of(chosen)),
            "Operation checkbox selected unrelated rows");
        var preview = view.session().preview();
        long prepared = view.preparations();
        click(view.probeControl("selected-only"));
        require(
            view.treeDepths().keySet().equals(Set.of(worldFolder, deepWorld, carrier, chosen)),
            "Selected-only lost ancestor context");
        click(view.probeControl("selected-only"));
        click(view.probeControl("filter"));
        erase();
        require(
            !view.rowIds().containsKey(chosen), "Search clearing overwrote stored collapse state");
        click(view.probeControl("expand:" + worldFolder));
        click(view.probeControl("expand:" + deepWorld));
        require(
            view.rowIds().containsKey(chosen), "World disclosure failed to reveal layer/operation");
        click(view.probeControl("expand:" + carrier));
        require(!view.rowIds().containsKey(chosen), "Layer collapse retained its operation row");
        click(view.probeControl("expand:" + carrier));
        require(
            resources.exportSelection.equals(Set.of(chosen))
                && view.session().preview() == preview
                && prepared == view.preparations(),
            "Disclosure changed selection or prepared payload");
        click(view.probeControl("expand:" + fields));
        click(view.probeControl("expand:" + nested));
        require(view.rowIds().containsKey(signal), "Noise folder disclosure failed");
        click(view.probeControl(fields.toString()));
        require(
            resources.exportSelection.equals(Set.of(chosen, fields))
                && view.session().preview().resources().noise2D.size() == 502,
            "Folder checkbox did not include its full subtree");
        click(view.probeControl(fields.toString()));
        require(
            resources.exportSelection.equals(Set.of(chosen))
                && view.session().preview().resources().noise2D.size() == 1,
            "Deselecting folder removed unrelated explicit operation or kept siblings");
        click(dialog.control("included"));
        click(view.probeControl("included-expand:" + fields));
        click(view.probeControl("included-expand:" + nested));
        click(view.probeControl("included-expand:" + worldFolder));
        click(view.probeControl("included-expand:" + deepWorld));
        require(
            view.includedRows() == 8 && resources.canExport(),
            "Included tree lost exact context or dependency resources");
        var exported = view.session().preview();
        prepared = view.preparations();
        click(view.probeControl("included-expand:" + carrier));
        click(view.probeControl("included-expand:" + carrier));
        require(
            exported == view.session().preview()
                && prepared == view.preparations()
                && resources.canExport(),
            "Included disclosure invalidated an unchanged review");
        click(dialog.control("location"));
        click(dialog.control("Path"));
        type(out.resolve("hierarchy.pvresources.json").toString());
        click(dialog.control("submit"));
        require(
            !dialog.pending() && resources.exportSelection.isEmpty(),
            "Hierarchy export did not consume its session");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.engine == engine
                && state.draft == draft
                && draft.id.equals("private-signal")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Hierarchy presentation/export changed authored/private state");
        new ProjectIO().save(out.resolve("source-project.json"), state.project());
        Files.writeString(
            out.resolve("export-hierarchy-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("stableOperationId", id),
                    Map.entry("searchContext", true),
                    Map.entry("selectedOnlyContext", true),
                    Map.entry("expansionRestored", true),
                    Map.entry("disclosureOnly", true),
                    Map.entry("folderSubtreeSelection", true),
                    Map.entry("exactIncludedContext", true),
                    Map.entry("operationOnlyPackage", true),
                    Map.entry("privateStatePreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "EXPORT_HIERARCHY_NATIVE_PASS authored hierarchy / context search / private subtree"
                + " choices / carrier package / view-only disclosure");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Noise noise(String id, UUID parent, double value) {
    var n = new Noise();
    n.id = id;
    n.parentFolderUuid = parent;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private static Folder folder(String id, String root, UUID parent) {
    var f = new Folder();
    f.id = id;
    f.rootKind = root;
    f.parentFolderUuid = parent;
    return f;
  }

  private static void fixture(ProjectData p) {
    p.project.minY = -8;
    p.project.maxY = 15;
    var a = folder("fields", "noise2D", null);
    var b = folder("nested", "noise2D", a.uuid);
    var c = folder("world-folder", "world", null);
    var d = folder("deep-world", "world", c.uuid);
    fields = a.uuid;
    nested = b.uuid;
    worldFolder = c.uuid;
    deepWorld = d.uuid;
    p.folders.addAll(List.of(a, b, c, d));
    var n = noise("signal", b.uuid, .625);
    signal = n.uuid;
    var unused = noise("unrelated-source", a.uuid, .125);
    p.noise2D.addAll(List.of(n, unused));
    a.childOrder.addAll(List.of(b.uuid, unused.uuid));
    b.childOrder.add(n.uuid);
    for (int i = 0; i < 500; i++) p.noise2D.add(noise("bulk-" + i, a.uuid, .25));
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var other = new Layer();
    other.id = "unrelated";
    other.parentFolderUuid = c.uuid;
    other.defaults.action = "CLEAR";
    other.defaults.threshold = .5;
    var clear = new Operation();
    clear.id = "clear";
    clear.noiseGeneratorUuid = unused.uuid;
    clear.interpretation = "DENSITY";
    other.operations.add(clear);
    var layer = new Layer();
    layer.id = "carrier";
    carrier = layer.uuid;
    layer.parentFolderUuid = d.uuid;
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    layer.defaults.threshold = .5;
    var op = new Operation();
    op.id = "chosen";
    chosen = op.uuid;
    op.noiseGeneratorUuid = n.uuid;
    op.interpretation = "DENSITY";
    var sibling = new Operation();
    sibling.id = "sibling";
    sibling.noiseGeneratorUuid = unused.uuid;
    sibling.interpretation = "DENSITY";
    layer.operations.addAll(List.of(op, sibling));
    p.worldGenerator.layers.addAll(List.of(other, layer));
    c.childOrder.addAll(List.of(d.uuid, other.uuid));
    d.childOrder.add(layer.uuid);
    p.project.rootOrder.put("noise2D", new ArrayList<>(List.of(a.uuid)));
    p.project.rootOrder.put("world", new ArrayList<>(List.of(c.uuid)));
  }

  private static Map<String, Object> receipt(int scenario, float global, float scale) {
    var view = resources.exportView();
    var row = new LinkedHashMap<String, Object>();
    var modal = dialog.layout.bounds();
    var chrome = dialog.chrome();
    require(
        DialogBounds.contains(dialog.layout.workArea().bounds(), modal)
            && DialogBounds.contains(modal, chrome.body()),
        "Hierarchy modal/body outside work");
    for (var box : chrome.actions().values())
      require(DialogBounds.contains(modal, box), "Hierarchy footer outside modal");
    row.put("scenario", scenario);
    row.put("form", dialog.page());
    row.put("globalScale", global);
    row.put("windowScale", scale);
    row.put("work", dialog.layout.workArea().bounds());
    row.put("modal", modal);
    row.put("body", chrome.body());
    row.put("actions", chrome.actions());
    row.put("list", view.listBounds());
    row.put("stride", view.rowStride());
    row.put("metrics", view.rowMetrics());
    row.put("ids", view.rowIds());
    row.put("depths", view.treeDepths());
    row.put("rows", dialog.page().equals("Choices") ? view.submitted() : view.includedRows());
    return row;
  }

  private static void wheel(float value) {
    var rect = resources.exportView().listBounds();
    var io = ImGui.getIO();
    io.addMousePosEvent(rect.x() + rect.width() / 2, rect.y() + rect.height() / 2);
    frame();
    io.addMouseWheelEvent(0, value);
    frames(6);
  }

  private static void click(float[] at) {
    require(at != null, "Missing hierarchy control");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(4);
  }

  private static void erase() {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addKeyEvent(ImGuiKey.Backspace, true);
    frame();
    io.addKeyEvent(ImGuiKey.Backspace, false);
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
    ImGui.begin("Export hierarchy fixture", ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, "Resources");
    DialogLayout.observe(state, "Resources");
    dialog.draw();
    imports.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
