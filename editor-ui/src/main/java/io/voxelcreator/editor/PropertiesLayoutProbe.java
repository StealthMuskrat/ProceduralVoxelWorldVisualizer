package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred production Properties footer geometry, guarded actions and child-body focus recovery.
 */
public final class PropertiesLayoutProbe {
  private static EditorState state;
  private static PropertyPanel panel;
  private static int width = 1200, height = 900;
  private static UUID a, b, layer, operation, type, membership, folder;
  private static boolean focus;

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
        require(state.change(PropertiesLayoutProbe::fixture), "Properties fixture rejected");
        a = state.project().noise2D.getFirst().uuid;
        b = state.project().noise2D.getLast().uuid;
        layer = state.project().worldGenerator.layers.getFirst().uuid;
        operation = state.project().worldGenerator.layers.getFirst().operations.getFirst().uuid;
        type = state.project().voxelTypes.getFirst().uuid;
        membership = state.project().voxelSets.getFirst().uuid;
        folder = state.project().folders.getFirst().uuid;
        panel = new PropertyPanel(state);
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var engine = state.engine;
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        for (String form :
            List.of(
                "Noise", "Layer", "Placement", "Voxel", "Membership", "Folder", "Multi", "Empty")) {
          select(form);
          var draft = state.draft;
          var privateValues = ProjectIO.JSON.valueToTree(draft);
          Long version = null;
          Map<String, Integer> ids = null;
          for (int i = 0; i < sizes.length; i++) {
            dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
            frames(8);
            if (version == null) {
              version = panel.actionOwner.version();
              ids = panel.chrome.actionIds();
            }
            require(
                version == panel.actionOwner.version() && ids.equals(panel.chrome.actionIds()),
                "Reflow replaced a Properties action owner");
            var actions = panel.chrome.actions();
            panel.probeBodyScroll(1000000);
            frames(4);
            require(
                actions.equals(panel.chrome.actions()),
                "Body scrolling moved the Properties footer");
            require(
                panel.probeFooterEditable() == !Set.of("Multi", "Empty").contains(form),
                "Properties action eligibility mismatch");
            cases.add(receipt(form, i, globals[i], scales[i]));
            require(
                state.draft == draft
                    && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
                    && before.equals(ProjectIO.JSON.valueToTree(state.project())),
                "Properties layout changed private/committed input");
          }
        }
        require(
            revision == state.revision && history == state.document.historyBytes(),
            "Layout changed history");
        dimensions(1200, 900, 1, 1);
        for (String action : List.of("apply", "revert")) {
          state.select("noise2D", a);
          ((Noise) state.draft).algorithm.parameters.put("value", .625);
          frames(5);
          var privateValues = ProjectIO.JSON.valueToTree(state.draft);
          releaseWithEscape(panel.probeAction(action));
          require(
              privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
                  && before.equals(ProjectIO.JSON.valueToTree(state.project())),
              "Escape authorized a parent action");
          for (boolean sameUuid : new boolean[] {false, true}) {
            state.select("noise2D", a);
            ((Noise) state.draft).algorithm.parameters.put("value", .625);
            frames(5);
            long old = panel.actionOwner.version();
            var oldIds = panel.chrome.actionIds();
            press(panel.probeAction(action));
            if (sameUuid) state.refreshDraft();
            else state.select("noise2D", b);
            ((Noise) state.draft).algorithm.parameters.put("value", .875);
            var successor = state.draft;
            var values = ProjectIO.JSON.valueToTree(successor);
            frame();
            release();
            frames(4);
            require(
                panel.actionOwner.version() != old
                    && !oldIds.get(action).equals(panel.chrome.actionIds().get(action))
                    && state.draft == successor
                    && values.equals(ProjectIO.JSON.valueToTree(state.draft))
                    && before.equals(ProjectIO.JSON.valueToTree(state.project())),
                "Held Properties action affected a successor");
          }
        }
        state.select("noise2D", a);
        ((Noise) state.draft).algorithm.parameters.put("value", .625);
        frames(5);
        press(panel.probeAction("apply"));
        require(state.change(p -> p.project.seed++), "Revision fixture rejected");
        var changedProject = ProjectIO.JSON.valueToTree(state.project());
        var privateOwner = state.draft;
        frame();
        release();
        frames(3);
        require(
            state.draft == privateOwner
                && changedProject.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Held Apply crossed a committed revision");
        state.document.undo();
        state.revision++;
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Revision fixture Undo failed");
        state.select("noise2D", a);
        ((Noise) state.draft).algorithm.parameters.put("value", .625);
        frames(5);
        var privateValues = ProjectIO.JSON.valueToTree(state.draft);
        panel.probeOpenCurve();
        frames(5);
        require(
            panel.searchBlocked() && !panel.probeFooterEditable(),
            "Private curve did not block parent actions");
        click(panel.probeAction("apply"));
        click(panel.probeAction("revert"));
        panel.closeSearchCurve();
        frames(4);
        require(
            panel.probeFooterEditable()
                && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Blocked parent action changed the curve owner");
        select("Multi");
        frames(4);
        click(panel.probeAction("apply"));
        click(panel.probeAction("revert"));
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Multiple selection applied a hidden draft");

        // Rejected Apply retains exact raw seed input; the body owns subsequent focus/typing.
        state.select("noise2D", a);
        var draft = (Noise) state.draft;
        draft.algorithm.parameters.put("value", .75);
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        frames(5);
        new ProjectIO().save(out.resolve("properties-before.json"), state.project());
        click(panel.probeAction("apply"));
        require(
            state.draft == draft
                && state.seedInputs.text("seedOffset").equals("-")
                && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && !state.issues.isEmpty(),
            "Rejected footer Apply lost private input");
        panel.probeBodyScroll(1000000);
        frames(4);
        var footer = panel.chrome.actions();
        click(panel.probeAction("problems"));
        frames(4);
        require(
            panel.probeBodyScroll() < 1
                && footer.equals(panel.chrome.actions())
                && state.seedInputs.text("seedOffset").equals("-"),
            "Show Problems did not reveal retained feedback");
        require(
            panel.goToProblem(
                new EditorEvents.Target(
                    state.project().project.uuid, a, null, "seedOffset", "", false)),
            "Seed focus route rejected");
        focus = true;
        for (int i = 0; i < 40 && !"null:seedOffset".equals(panel.probeFocusedProperty()); i++)
          frame();
        require(
            "null:seedOffset".equals(panel.probeFocusedProperty()),
            "Child body did not acknowledge target field");
        frames(3);
        replace("17");
        require(
            draft.seedOffset == 17 && !state.seedInputs.pending(state),
            "Native seed repair failed");
        click(panel.probeAction("apply"));
        require(
            state.project().noise2D.getFirst().seedOffset == 17
                && state.project().noise2D.getFirst().algorithm.parameters.get("value") == .75,
            "Properties footer did not commit exact repaired input");
        var after = ProjectIO.JSON.valueToTree(state.project());
        new ProjectIO().save(out.resolve("properties-after.json"), state.project());
        state.document.undo();
        state.revision++;
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())), "One parent Undo failed");
        new ProjectIO().save(out.resolve("properties-undone.json"), state.project());
        state.document.redo();
        state.revision++;
        require(
            after.equals(ProjectIO.JSON.valueToTree(state.project())), "One parent Redo failed");
        new ProjectIO().save(out.resolve("properties-redone.json"), state.project());
        state.refreshDraft();
        frames(4);
        ((Noise) state.draft).algorithm.parameters.put("value", .875);
        state.seedInputs.edit(state, "seedOffset", "Seed", "+");
        var replaced = state.draft;
        frames(4);
        click(panel.probeAction("revert"));
        require(
            state.draft != replaced
                && !state.seedInputs.pending(state)
                && ((Noise) state.draft).seedOffset == 17
                && after.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Revert did not replace only private input");
        require(
            state.engine == engine && state.engine.profiler().current().isEmpty(),
            "Properties layout changed runtime ownership");
        Files.writeString(
            out.resolve("properties-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("footerPersistent", true),
                    Map.entry("sameOwnerReflowStable", true),
                    Map.entry("escapePreventsMutation", true),
                    Map.entry("heldSuccessorsBlocked", true),
                    Map.entry("revisionBlocked", true),
                    Map.entry("privateEditorBlocked", true),
                    Map.entry("multiSelectionReadOnly", true),
                    Map.entry("invalidSeedRetained", true),
                    Map.entry("problemRevealAndFocus", true),
                    Map.entry("oneUndoRedo", true),
                    Map.entry("revertPrivateOnly", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "PROPERTIES_LAYOUT_NATIVE_PASS persistent footer / scoped owners / child body focus /"
                + " explicit Apply-Revert / exact Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    var a = noise("a", .125);
    var b = noise("b", .25);
    var curve = ProcessingRegistry.create("CURVE", "curve");
    curve.parameters.put("curve", ProjectIO.JSON.valueToTree(new Curve()));
    a.processing.add(curve);
    p.noise2D.addAll(List.of(a, b));
    for (int i = 0; i < 24; i++) {
      var parameter = new Parameter();
      parameter.id = "parameter-" + i;
      parameter.value = ProjectIO.JSON.valueToTree((double) i);
      a.sharedParameters.add(parameter);
    }
    var l = new Layer();
    l.id = "layer";
    var op = new Operation();
    op.id = "placement";
    op.noiseGeneratorUuid = b.uuid;
    l.operations.add(op);
    p.worldGenerator.layers.add(l);
    var t = new VoxelType();
    t.id = "type";
    p.voxelTypes.add(t);
    var m = new Membership();
    m.id = "catalog";
    m.voxelTypeUuids.add(t.uuid);
    p.voxelSets.add(m);
    var f = new Folder();
    f.id = "folder";
    f.rootKind = "noise2D";
    p.folders.add(f);
  }

  private static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private static void select(String form) {
    switch (form) {
      case "Noise" -> state.select("noise2D", a);
      case "Layer" -> state.select("layer", layer);
      case "Placement" -> state.select("operation", operation);
      case "Voxel" -> state.select("voxel", type);
      case "Membership" -> state.select("set", membership);
      case "Folder" -> state.select("folder", folder);
      case "Multi" -> {
        state.select("noise2D", a);
        state.multiSelection.add(b);
      }
      case "Empty" -> {
        state.selected = null;
        state.draft = null;
        state.selectedKind = "";
        state.multiSelection.clear();
        state.seedInputs.synchronize(state);
      }
      default -> throw new IllegalArgumentException(form);
    }
    if (state.draft instanceof Identity r) r.id = "private-" + form;
    if (form.equals("Noise")) state.seedInputs.edit(state, "seedOffset", "Seed", "-");
  }

  private static Map<String, Object> receipt(String form, int scenario, float global, float scale) {
    return Map.ofEntries(
        Map.entry("form", form),
        Map.entry("scenario", scenario),
        Map.entry("globalScale", global),
        Map.entry("windowScale", scale),
        Map.entry("work", new DialogBounds.Rect(0, 0, width, height)),
        Map.entry("window", panel.windowLayout.bounds()),
        Map.entry("body", panel.chrome.body()),
        Map.entry("actions", panel.chrome.actions()),
        Map.entry("actionIds", panel.chrome.actionIds()),
        Map.entry("request", panel.actionOwner.version()),
        Map.entry("editable", panel.probeFooterEditable()));
  }

  private static void dimensions(int w, int h, float global, float scale) {
    width = w;
    height = h;
    state.user.appearance.globalScale = global;
    state.user.appearance.windowScales.put("Properties", scale);
  }

  private static void replace(String value) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void press(float[] at) {
    require(at != null, "Missing Properties action");
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

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    if (focus) {
      ImGui.setWindowFocus("Properties");
      focus = false;
    }
    ImGui.setNextWindowPos(8, 8, ImGuiCond.Always);
    ImGui.setNextWindowSize(width - 16, height - 16, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(false, ImGuiCond.Always);
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
