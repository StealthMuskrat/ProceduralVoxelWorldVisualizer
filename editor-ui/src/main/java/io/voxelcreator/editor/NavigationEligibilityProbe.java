package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred production Find/reference decision eligibility and private curve preservation. */
public final class NavigationEligibilityProbe {
  private static EditorState state;
  private static PropertyPanel properties;
  private static ResourceFindPanel find;
  private static Path out;
  private static String form;
  private static int width = 1200, height = 900;
  private static float global = 1;
  private static final List<Map<String, Object>> cases = new ArrayList<>(),
      routes = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  TraceWorkflowProbe.fixture(p);
                  var curve = ProcessingRegistry.create("CURVE", "private-curve", 2);
                  curve.parameters.put("curve", ProjectIO.JSON.valueToTree(new Curve()));
                  p.noise2D.getFirst().processing.add(curve);
                  var other = new Noise();
                  other.id = "destination";
                  other.algorithm.key = "CONSTANT";
                  other.algorithm.parameters.clear();
                  other.algorithm.parameters.put("value", .25);
                  p.noise2D.add(other);
                }),
            "Fixture rejected");
        state.document.save(out.resolve("source-project.json"));
        state.user.save(out.resolve("preferences-before.json"));
        for (var mode : List.of("find", "reference")) {
          prepare(mode, true);
          capture();
          var draft = state.draft;
          long revision = state.revision;
          click(guard().button("APPLY"));
          require(
              pending()
                  && !guard().message().isEmpty()
                  && state.draft == draft
                  && state.revision == revision,
              "Rejected Apply lost its request/private input");
          click(guard().button("CANCEL"));
          require(
              !pending() && state.draft == draft && "-".equals(state.seedInputs.text("seedOffset")),
              "Cancel changed rejected input");
          record(mode + "-rejected", 0, true, false);
        }
        for (var decision : SearchNavigation.Decision.values()) {
          prepare("find", true);
          var draft = (Noise) state.draft;
          var selected = state.selected;
          long revision = state.revision, history = state.document.historyBytes();
          var curve = draft.processing.getLast();
          require(
              properties.goToProblem(
                  new EditorEvents.Target(
                      state.project().project.uuid, draft.uuid, curve.uuid, "curve", "", false)),
              "Private curve could not open after Find request");
          var privateEditor = properties.probeCurveDialog();
          require(privateEditor != null, "Missing private editor");
          click(guard().button(decision.name()));
          require(
              !pending()
                  && state.draft == draft
                  && Objects.equals(state.selected, selected)
                  && state.revision == revision
                  && state.document.historyBytes() == history
                  && properties.probeCurveDialog() == privateEditor
                  && "-".equals(state.seedInputs.text("seedOffset")),
              "Find decision replaced or committed over the private editor");
          require(
              state.project().noise2D.getFirst().id.equals("field")
                  && draft.id.equals("private-field"),
              "Blocked decision published private values");
          record("find-blocked-" + decision.name().toLowerCase(Locale.ROOT), 0, true, true);
          properties.closeSearchCurve();
        }
        for (var mode : List.of("find", "reference"))
          for (var decision :
              List.of(SearchNavigation.Decision.APPLY, SearchNavigation.Decision.DISCARD)) {
            prepare(mode, false);
            long revision = state.revision;
            click(guard().button(decision.name()));
            boolean applied = decision == SearchNavigation.Decision.APPLY;
            require(
                !pending()
                    && Objects.equals(state.selected, state.project().noise2D.getLast().uuid)
                    && state.revision == revision + (applied ? 1 : 0),
                "Healthy navigation was blocked or applied twice");
            require(
                state.project().noise2D.getFirst().id.equals(applied ? "private-field" : "field"),
                "Wrong published source draft");
            record(
                mode + "-" + decision.name().toLowerCase(Locale.ROOT),
                state.revision - revision,
                false,
                false);
            if (applied) {
              save(mode + "-applied.json");
              try (var world = state.engine.openWorld(new CompiledProject(state.project()))) {
                write(mode + "-trace.json", world.trace(new BlockPos(2, 0, 3)));
              }
              state.document.undo();
              save(mode + "-undo.json");
              require(
                  read(mode + "-undo.json").equals(read("source-project.json")),
                  "One navigation Undo did not restore the complete source project");
            }
          }
        save("after.json");
        state.user.save(out.resolve("preferences.json"));
        require(
            read("source-project.json").equals(read("after.json"))
                && read("preferences-before.json").equals(read("preferences.json")),
            "Navigation changed final project/preferences");
        try (var world = state.engine.openWorld(new CompiledProject(state.project()))) {
          write("source-trace.json", world.trace(new BlockPos(2, 0, 3)));
        }
        write(
            "navigation-eligibility-native.json",
            Map.of(
                "formatVersion",
                1,
                "fontSource",
                "synthetic-global-window-fixture",
                "cases",
                cases,
                "routes",
                routes,
                "privateEditorPreserved",
                true,
                "oneUndoRestored",
                true,
                "physicalDpiVerified",
                false));
        System.out.println(
            "NAVIGATION_ELIGIBILITY_NATIVE_PASS Find/private editor exclusion / reference"
                + " self-pending eligibility / raw input preservation / healthy Apply-Discard / one"
                + " Undo");
      }
      BackgroundNavigationProbe.run(out.resolve("background-apply"));
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void prepare(String mode, boolean raw) throws Exception {
    form = mode;
    require(
        state.document.open(out.resolve("source-project.json")).isEmpty(), "Fixture reopen failed");
    state.selected = null;
    state.draft = null;
    state.multiSelection.clear();
    state.seedInputs.synchronize(state);
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private-field";
    if (raw) state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    properties = new PropertyPanel(state);
    find = new ResourceFindPanel(state, properties);
    if (mode.equals("find")) {
      var filter =
          new ResourceSearchIndex.Filter(
              "destination",
              "noise2D",
              "All",
              "noise2D",
              null,
              null,
              false,
              ResourceSearchIndex.Mode.ID);
      find.filter(filter);
      frames(5);
      var hit = new ResourceSearchIndex(state.project()).search(filter).getFirst();
      click(find.center(hit.key()));
    } else {
      properties.probeOpenReference(state.project().noise2D.getLast().uuid);
      frames(5);
    }
    require(pending() && guard().buttons().size() == 3, "Missing production navigation guard");
  }

  private static boolean pending() {
    return form.equals("find") ? find.navigationPending() : properties.searchBlocked();
  }

  private static NavigationGuardDialog guard() {
    return form.equals("find") ? find.guardDialog : properties.referenceDialog;
  }

  private static void record(String phase, long revision, boolean raw, boolean privateEditor) {
    routes.add(
        Map.of(
            "phase",
            phase,
            "revisionDelta",
            revision,
            "rawRetained",
            raw,
            "privateEditor",
            privateEditor,
            "pending",
            pending()));
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width + 32, height + 32, ImGuiCond.Always);
    ImGui.begin("Navigation Eligibility Host");
    UiScale.window(state.user, form.equals("find") ? "Find" : "Properties");
    int root = ImGui.getID("eligibility-root");
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    if (form.equals("find")) find.draw(new ImBoolean(true));
    else properties.draw();
    require(root == ImGui.getID("eligibility-root"), "Navigation left unbalanced native scopes");
    ImGui.end();
    ImGui.render();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void click(float[] at) {
    require(at != null, "Missing production action");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    frames(2);
  }

  private static void capture() {
    String owner = form.equals("find") ? "Find" : "Properties";
    var old = state.user.appearance.windowScales.get(owner);
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      global = globals[i];
      state.user.appearance.windowScales.put(owner, scales[i]);
      frames(6);
      if (ids == null) ids = guard().chrome.actionIds();
      require(ids.equals(guard().chrome.actionIds()), "Reflow changed pending decision ownership");
      cases.add(
          Map.of(
              "form",
              form,
              "scenario",
              i,
              "globalScale",
              global,
              "windowScale",
              scales[i],
              "window",
              guard().layout.bounds(),
              "body",
              guard().chrome.body(),
              "actions",
              guard().chrome.actions(),
              "ids",
              guard().chrome.actionIds()));
    }
    width = 1200;
    height = 900;
    global = 1;
    if (old == null) state.user.appearance.windowScales.remove(owner);
    else state.user.appearance.windowScales.put(owner, old);
    frames(3);
  }

  private static void save(String name) throws Exception {
    new ProjectIO().save(out.resolve(name), state.project());
  }

  private static com.fasterxml.jackson.databind.JsonNode read(String name) throws Exception {
    return ProjectIO.JSON.readTree(out.resolve(name).toFile());
  }

  private static void write(String name, Object value) throws Exception {
    Files.writeString(out.resolve(name), ProjectIO.JSON.writeValueAsString(value));
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
