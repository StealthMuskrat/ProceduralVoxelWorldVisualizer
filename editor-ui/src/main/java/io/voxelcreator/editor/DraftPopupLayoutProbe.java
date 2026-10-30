package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Deferred real picker/navigation input workflow, without GLFW, OpenGL or physical DPI claims. */
public final class DraftPopupLayoutProbe {
  private static EditorState state;
  private static PropertyPanel properties;
  private static GuardedNavigation source;
  private static DraftChoicePicker picker;
  private static List<DraftChoicePicker.Choice> choices;
  private static int width, height, selections;
  private static float windowScale;
  private static String mode = "picker", selected;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  for (String id : new String[] {"origin", "destination"}) {
                    var n = new Noise();
                    n.id = id;
                    n.algorithm.key = "CONSTANT";
                    n.algorithm.parameters.clear();
                    n.algorithm.parameters.put("value", .125);
                    p.noise2D.add(n);
                  }
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = " invalid ";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        properties = new PropertyPanel(state);
        source = new GuardedNavigation(state, properties);
        picker = new DraftChoicePicker();
        var rows = new ArrayList<DraftChoicePicker.Choice>();
        for (int i = 0; i < 96; i++)
          rows.add(
              new DraftChoicePicker.Choice(
                  String.format(Locale.ROOT, "key-%03d", i),
                  "Long local choice label " + i,
                  "Scalar / deliberately long category description"));
        choices = List.copyOf(rows);
        var cases = new ArrayList<Map<String, Object>>();
        for (int scenario = 0; scenario < 4; scenario++) {
          width = scenario == 3 ? 720 : 360;
          height = scenario == 3 ? 480 : 240;
          windowScale = scenario == 3 ? 2.5f : 1;
          state.user.appearance.globalScale = new float[] {.5f, 1, 2.5f, 2.5f}[scenario];
          if (scenario == 0) {
            mode = "source";
            var destination = state.project().noise2D.getLast().uuid;
            require(
                source.request(
                        new EditorEvents.Target(
                            state.project().project.uuid, destination, null, null, "", false))
                    == SearchNavigation.Result.NEEDS_DECISION,
                "No queued source guard");
            require(
                source.decide(SearchNavigation.Decision.CANCEL)
                    == SearchNavigation.Result.CANCELLED,
                "Queued Cancel failed");
            frames(3);
            require(
                source.dialog.layout.bounds() == null,
                "Cancelled request opened an unowned modal on a later draw");
          }
          exercisePicker(cases, scenario);
          exerciseGuard(cases, scenario, "reference");
          exerciseGuard(cases, scenario, "source");
        }
        var following = closedDialogPreservesNextWindow();
        require(selections == 4, "Choice callback repeated");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.draft == draft
                && draft.id.equals(" invalid ")
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Layout/input changed private or committed authoring state");
        Files.writeString(
            out.resolve("draft-popup-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "cases",
                    cases,
                    "selections",
                    selections,
                    "followingWindow",
                    following,
                    "projectPreserved",
                    true,
                    "cancelledBeforeDrawPreserved",
                    true,
                    "physicalDetachedVerified",
                    false,
                    "physicalDpiVerified",
                    false,
                    "gpuFontTextureVerified",
                    false)));
        System.out.println(
            "DRAFT_POPUP_LAYOUT_NATIVE_PASS local key selection, bounded decision footers, failed"
                + " Apply feedback, Escape and closed-modal NextWindow preservation");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void exercisePicker(List<Map<String, Object>> cases, int scenario) {
    mode = "picker";
    frames(3);
    picker.probeOpen();
    frames(4);
    type("");
    frames(3);
    var layout = picker.layout;
    var receipt = receipt("picker", scenario, layout.bounds());
    require(DialogBounds.contains(layout.bounds(), layout.queryBounds()), "Query outside popup");
    require(DialogBounds.contains(layout.bounds(), layout.listBounds()), "List outside popup");
    require(picker.submitted() > 0 && picker.submitted() < 96, "Lost row clipping");
    receipt.put("query", layout.queryBounds());
    receipt.put("list", layout.listBounds());
    receipt.put("submitted", picker.submitted());
    type("key-095");
    frames(3);
    require(picker.submitted() == 1, "Local key search did not isolate the last choice");
    click(picker.option("key-095"));
    require(
        "key-095".equals(selected) && layout.bounds() == null, "Wrong choice or popup still open");
    picker.probeOpen();
    frames(3);
    require(picker.submitted() == 1, "Reopen lost private query");
    type("no-such-local-choice");
    frames(2);
    require(picker.submitted() == 0, "Empty query retained stale rows");
    int before = selections;
    escape();
    require(
        layout.bounds() == null && selections == before && "key-095".equals(selected),
        "Escape invoked a change callback");
    cases.add(receipt);
  }

  private static void exerciseGuard(List<Map<String, Object>> cases, int scenario, String kind) {
    mode = kind;
    frames(3);
    var destination = state.project().noise2D.getLast().uuid;
    if (kind.equals("reference")) properties.probeOpenReference(destination);
    else
      require(
          source.request(
                  new EditorEvents.Target(
                      state.project().project.uuid, destination, null, null, "", false))
              == SearchNavigation.Result.NEEDS_DECISION,
          "No source guard");
    frames(4);
    var dialog = kind.equals("reference") ? properties.referenceDialog : source.dialog;
    var receipt = receipt(kind, scenario, dialog.layout.bounds());
    var actions = new LinkedHashMap<String, DialogBounds.Rect>();
    dialog.buttons().forEach((key, bounds) -> actions.put(key.name(), bounds));
    require(actions.size() == 3, "Missing decision action");
    for (var action : actions.values())
      require(DialogBounds.contains(dialog.layout.bounds(), action), "Action outside modal");
    receipt.put("actions", actions);
    click(dialog.button("APPLY"));
    frames(3);
    require(
        dialog.layout.bounds() != null && !dialog.message().isEmpty(),
        "Rejected Apply lost modal or persistent feedback");
    // Let the error survive several redraws; Escape must preserve the exact private draft.
    frames(4);
    require(!dialog.message().isEmpty(), "Feedback disappeared on the next frame");
    escape();
    require(dialog.layout.bounds() == null && !source.pending(), "Escape left pending navigation");
    receipt.put("failedApplyRetained", true);
    receipt.put("escapePreserved", true);
    cases.add(receipt);
  }

  private static Map<String, Object> receipt(String kind, int scenario, DialogBounds.Rect bounds) {
    var work = new DialogBounds.Rect(0, 0, width, height);
    require(
        bounds != null && DialogBounds.contains(DialogBounds.inset(work), bounds),
        "Popup outside work area: " + kind + "/" + scenario);
    var result = new LinkedHashMap<String, Object>();
    result.put("kind", kind);
    result.put("scenario", scenario);
    result.put("bounds", bounds);
    result.put("workArea", work);
    result.put("globalScale", state.user.appearance.globalScale);
    result.put("windowScale", windowScale);
    return result;
  }

  private static DialogBounds.Rect closedDialogPreservesNextWindow() {
    var io = ImGui.getIO();
    io.setDisplaySize(720, 480);
    io.setFontGlobalScale(1);
    ImGui.newFrame();
    ImGui.setNextWindowPos(31, 37, ImGuiCond.Always);
    ImGui.setNextWindowSize(211, 133, ImGuiCond.Always);
    require(
        !new DialogLayout(state, "Properties", 600, 360).begin("Never opened modal"),
        "Closed modal began");
    ImGui.begin("Unrelated following window", ImGuiWindowFlags.NoSavedSettings);
    var result =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    ImGui.end();
    ImGui.render();
    require(
        result.equals(new DialogBounds.Rect(31, 37, 211, 133)),
        "Closed modal changed unrelated NextWindow data");
    return result;
  }

  private static void type(String text) {
    var query = picker.layout.queryBounds();
    click(new float[] {query.x() + query.width() / 2, query.y() + query.height() / 2});
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    if (text.isEmpty()) {
      io.addKeyEvent(ImGuiKey.Backspace, true);
      frame();
      io.addKeyEvent(ImGuiKey.Backspace, false);
    } else io.addInputCharactersUTF8(text);
    frames(2);
  }

  private static void escape() {
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
    frames(3);
  }

  private static void click(float[] point) {
    require(point != null, "Missing input target");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin(
        "Draft popup fixture host", ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoTitleBar);
    ImGui.setWindowFontScale(windowScale);
    if (mode.equals("picker"))
      picker.draw(
          "Local input",
          selected,
          choices,
          null,
          value -> {
            selected = value;
            selections++;
          });
    else {
      ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      properties.draw();
      if (mode.equals("source")) source.draw();
    }
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
