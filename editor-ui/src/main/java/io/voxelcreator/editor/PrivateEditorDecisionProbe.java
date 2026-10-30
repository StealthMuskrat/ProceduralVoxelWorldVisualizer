package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred native private editor footer identities, closure and explicit transaction handoffs. */
public final class PrivateEditorDecisionProbe {
  private static EditorState state;
  private static ExpressionEditSession expressionSession;
  private static ExpressionEditDialog expression;
  private static MembershipDialog membership;
  private static CurveDialog curve;
  private static String form = "Expression";
  private static int width = 1200, height = 900, callbacks;
  private static boolean done, failRefresh;
  private static Path out;

  public static void main(String[] args) throws Exception {
    out = Path.of(args[0]).toAbsolutePath();
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
            state.change(PrivateEditorDecisionProbe::fixture), "Private editor fixture rejected");
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var engine = state.engine;
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        for (String mode : List.of("Expression", "Membership", "Curve")) {
          form = mode;
          select(true);
          open();
          var owner = state.draft;
          var privateValues = ProjectIO.JSON.valueToTree(owner);
          String scope = scope();
          Map<String, Integer> ids = null;
          for (int i = 0; i < sizes.length; i++) {
            dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
            frames(8);
            if (ids == null) ids = chrome().actionIds();
            else
              require(
                  ids.equals(chrome().actionIds()),
                  "Same private request changed native footer IDs during reflow");
            require(scope.equals(scope()), "Reflow replaced private request scope");
            cases.add(receipt(i, globals[i], scales[i]));
          }
          click(control("cancel"));
          require(
              done
                  && state.draft == owner
                  && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft)),
              "Private Cancel changed parent input");
        }
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && callbacks == 0,
            "Layout changed committed history or staged input");
        dimensions(1200, 900, 1, 1);
        for (String mode : List.of("Expression", "Membership", "Curve")) {
          form = mode;
          select(true);
          open();
          frames(6);
          var parent = ProjectIO.JSON.valueToTree(state.draft);
          String old = scope();
          var oldIds = chrome().actionIds();
          press(control(primary()));
          cancelModel();
          open();
          frame();
          release();
          frames(4);
          require(
              !done
                  && !old.equals(scope())
                  && !oldIds.get(primary()).equals(chrome().actionIds().get(primary()))
                  && parent.equals(ProjectIO.JSON.valueToTree(state.draft))
                  && before.equals(ProjectIO.JSON.valueToTree(state.project())),
              "Held predecessor published into a successor");
          click(control("cancel"));
          open();
          frames(5);
          releaseWithEscape(control(primary()));
          require(
              (form.equals("Curve") ? !done : done)
                  && parent.equals(ProjectIO.JSON.valueToTree(state.draft))
                  && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                  && callbacks == 0,
              "Escape permitted private staging or changed Curve escape policy");
          if (form.equals("Curve")) click(control("cancel"));
        }
        // Cancelled modal owners close instead of drawing disabled, obsolete workspaces.
        for (String mode : List.of("Expression", "Membership")) {
          form = mode;
          select(true);
          open();
          frames(5);
          cancelModel();
          frames(3);
          require(done, "Externally cancelled session kept its modal");
          open();
          cancelModel();
          frames(2);
          require(done, "Cancel before first draw opened a modal");
        }
        form = "Expression";
        select(false);
        save("before");
        open();
        failRefresh = true;
        frames(5);
        click(control("stage"));
        frames(4);
        require(
            done
                && callbacks == 1
                && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && ((Noise) state.draft)
                    .processing
                    .getFirst()
                    .parameters
                    .get("expression")
                    .asText()
                    .equals("x+2"),
            "Expression stage did not stay private/consumed");
        require(state.applyDraft(), "Expression parent Apply failed");
        recordStep("expression", before);
        var expressionAfter = ProjectIO.JSON.valueToTree(state.project());
        form = "Curve";
        select(false);
        open();
        failRefresh = true;
        frames(5);
        click(control("apply"));
        frames(4);
        require(
            done
                && callbacks == 2
                && expressionAfter.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Curve stage did not stay private/consumed");
        require(state.applyDraft(), "Curve parent Apply failed");
        recordStep("curve", expressionAfter);
        var curveAfter = ProjectIO.JSON.valueToTree(state.project());
        form = "Membership";
        select(true);
        open();
        frames(5);
        click(control("apply"));
        require(
            done
                && membership.session().closed()
                && ((Membership) state.draft).id.equals("private-membership")
                && state.project().voxelSets.getFirst().id.equals("catalog"),
            "Membership did not preserve its separate private ID");
        recordStep("membership", curveAfter);
        require(
            callbacks == 2
                && state.events.snapshot().stream()
                        .filter(
                            e ->
                                Set.of(
                                        "EXPRESSION_EDIT_REFRESH_FAILED",
                                        "CURVE_EDIT_REFRESH_FAILED")
                                    .contains(e.code()))
                        .mapToLong(EditorEvents.Entry::count)
                        .sum()
                    == 2,
            "Accepted callbacks were repeated");
        require(
            state.engine == engine && state.engine.profiler().current().isEmpty(),
            "Private decisions replaced runtime ownership");
        Files.writeString(
            out.resolve("private-editor-decision-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("heldRequestsScoped", true),
                    Map.entry("sameRequestReflowStable", true),
                    Map.entry("escapePreventsPublication", true),
                    Map.entry("curveEscapeRetainsEditor", true),
                    Map.entry("cancelledSessionsClose", true),
                    Map.entry("privateStageThenParentApply", true),
                    Map.entry("membershipOnlyCommit", true),
                    Map.entry("oneUndoRedoPerEdit", true),
                    Map.entry("callbacks", callbacks),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "PRIVATE_EDITOR_DECISION_NATIVE_PASS scoped persistent footers / closed owners / Escape"
                + " / explicit staging and members-only replay");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    var formula = noise("formula");
    var expression = ProcessingRegistry.create("EXPRESSION", "formula");
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x+1"));
    formula.processing.add(expression);
    formula.outputBinding = Binding.earlier(expression.uuid);
    p.noise2D.add(formula);
    var shaped = noise("shaped");
    var item = ProcessingRegistry.create("CURVE", "curve");
    item.inputs.put("a", Binding.constant(.5));
    var c = new Curve();
    c.points.add(1, new Point(.5, .25));
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(c));
    shaped.processing.add(item);
    shaped.outputBinding = Binding.earlier(item.uuid);
    p.noise2D.add(shaped);
    for (int i = 0; i < 2; i++) {
      var type = new VoxelType();
      type.id = "type-" + i;
      p.voxelTypes.add(type);
    }
    var members = new Membership();
    members.id = "catalog";
    members.voxelTypeUuids.add(p.voxelTypes.getFirst().uuid);
    p.voxelSets.add(members);
  }

  private static Noise noise(String id) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", .125);
    return n;
  }

  private static void select(boolean privateInput) {
    if (form.equals("Membership")) {
      state.select("set", state.project().voxelSets.getFirst().uuid);
      if (privateInput) ((Membership) state.draft).id = "private-membership";
    } else {
      int i = form.equals("Expression") ? 0 : 1;
      state.select("noise2D", state.project().noise2D.get(i).uuid);
      if (privateInput) {
        ((Noise) state.draft).id = "private-" + form;
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      }
    }
  }

  private static void open() {
    done = false;
    failRefresh = false;
    if (form.equals("Expression")) {
      var n = (Noise) state.draft;
      expressionSession = new ExpressionEditSession(state, n, n.processing.getFirst());
      expressionSession.edit("x+2");
      expression =
          new ExpressionEditDialog(state, expressionSession, PrivateEditorDecisionProbe::refreshed);
    } else if (form.equals("Membership")) {
      membership = new MembershipDialog(state, (Membership) state.draft);
      membership.session().set(state.project().voxelTypes.getLast().uuid, true);
    } else {
      var n = (Noise) state.draft;
      var item = n.processing.getFirst();
      var c = ProjectIO.JSON.convertValue(item.parameters.get("curve"), Curve.class);
      curve =
          new CurveDialog(
              c, state, new CurveDraftEdit(state, n, item), PrivateEditorDecisionProbe::refreshed);
      curve.probeEnable();
      curve.selectPoint(c.points.get(1).uuid);
      curve.shortcut("CurveDelete");
    }
  }

  private static void refreshed() {
    callbacks++;
    if (failRefresh) throw new IllegalStateException("Injected private refresh failure");
  }

  private static void cancelModel() {
    if (form.equals("Expression")) expressionSession.cancel();
    else if (form.equals("Membership")) membership.session().cancel();
    else curve.cancel();
  }

  private static SettingsPageChrome chrome() {
    return form.equals("Expression")
        ? expression.chrome
        : form.equals("Membership") ? membership.chrome : curve.chrome;
  }

  private static String scope() {
    return form.equals("Expression")
        ? expression.actionScope()
        : form.equals("Membership") ? membership.actionScope() : curve.actionScope();
  }

  private static String primary() {
    return form.equals("Expression") ? "stage" : "apply";
  }

  private static float[] control(String key) {
    return form.equals("Expression")
        ? expression.control(key)
        : form.equals("Membership") ? membership.control(key) : curve.probeControl(key);
  }

  private static Map<String, Object> receipt(int scenario, float global, float scale) {
    var modal =
        form.equals("Expression")
            ? expression.layout.bounds()
            : form.equals("Membership") ? membership.layout.bounds() : curve.layout.bounds();
    return Map.ofEntries(
        Map.entry("scenario", scenario),
        Map.entry("form", form),
        Map.entry("globalScale", global),
        Map.entry("windowScale", scale),
        Map.entry("work", new DialogBounds.Rect(0, 0, width, height)),
        Map.entry("modal", modal),
        Map.entry("body", chrome().body()),
        Map.entry("actions", chrome().actions()),
        Map.entry("actionIds", chrome().actionIds()),
        Map.entry("request", scope()));
  }

  private static void save(String name) throws Exception {
    new ProjectIO().save(out.resolve(name + ".json"), state.project());
  }

  private static void recordStep(String name, JsonNode before) throws Exception {
    var after = ProjectIO.JSON.valueToTree(state.project());
    save(name);
    state.document.undo();
    state.revision++;
    require(before.equals(ProjectIO.JSON.valueToTree(state.project())), "One Undo failed: " + name);
    save(name + "-undone");
    state.document.redo();
    state.revision++;
    require(after.equals(ProjectIO.JSON.valueToTree(state.project())), "One Redo failed: " + name);
    save(name + "-redone");
    state.refreshDraft();
  }

  private static void dimensions(int w, int h, float global, float scale) {
    width = w;
    height = h;
    state.user.appearance.globalScale = global;
    state.user.appearance.windowScales.put("Properties", scale);
    state.user.appearance.windowScales.put("Curve Editor", scale);
  }

  private static void press(float[] at) {
    require(at != null, "Missing private editor action");
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
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Private editor fixture", ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, "Properties");
    DialogLayout.observe(state, "Properties");
    if (!form.equals("Curve")) {
      if (form.equals("Expression")) {
        if (expression.draw()) done = true;
      } else if (membership.draw()) done = true;
    }
    ImGui.end();
    if (form.equals("Curve")) {
      if (curve.draw()) done = true;
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
