package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production selection/edit/source guards using native decisions and isolated state. */
public final class ResourceDecisionLayoutProbe {
  private static EditorState state;
  private static ResourcePanel resources;
  private static PropertyPanel properties;
  private static GuardedNavigation source;
  private static int width = 1200, height = 900, callbacks;
  private static String form = "Selection";
  private static UUID a, b;
  private static final AtomicBoolean fresh = new AtomicBoolean(true);

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
                  p.noise2D.addAll(List.of(noise("a", .125), noise("b", .25), noise("c", .5)));
                }),
            "Resource decision fixture rejected");
        a = state.project().noise2D.get(0).uuid;
        b = state.project().noise2D.get(1).uuid;
        properties = new PropertyPanel(state);
        resources = new ResourcePanel(state, properties);
        source = new GuardedNavigation(state, properties);
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, bytes = state.document.historyBytes();
        var engine = state.engine;
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = globals[i];
          state.user.appearance.windowScales.put("Resources", scales[i]);
          state.user.appearance.windowScales.put("Properties", scales[i]);
          for (String mode : List.of("Selection", "Edit", "Source")) {
            form = mode;
            prepare(true);
            var draft = state.draft;
            frames(8);
            cases.add(receipt(i, globals[i], scales[i]));
            click(guard().button("APPLY"));
            require(
                pending()
                    && !guard().message().isEmpty()
                    && state.draft == draft
                    && state.seedInputs.text("seedOffset").equals("-"),
                "Rejected Apply lost its owned request/input");
            click(guard().button("CANCEL"));
            require(
                !pending()
                    && state.draft == draft
                    && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                    && revision == state.revision
                    && bytes == state.document.historyBytes(),
                "Guard Cancel changed committed/private state");
          }
        }
        width = 1200;
        height = 900;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.put("Resources", 1f);
        state.user.appearance.windowScales.put("Properties", 1f);
        for (String mode : List.of("Selection", "Edit", "Source"))
          for (String decision : List.of("APPLY", "DISCARD")) {
            form = mode;
            prepare(false);
            var draft = state.draft;
            frames(6);
            releaseWithEscape(guard().button(decision));
            require(
                !pending()
                    && state.draft == draft
                    && state.selected.equals(a)
                    && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                    && revision == state.revision
                    && bytes == state.document.historyBytes()
                    && callbacks == 0,
                "Escape lost a draft decision frame");
          }
        // A newly opened guard cannot inherit a held predecessor's native action.
        for (String mode : List.of("Selection", "Edit", "Source")) {
          form = mode;
          prepare(false);
          frames(5);
          long old = guard().requestVersion();
          press(guard().button("APPLY"));
          cancelModel();
          prepare(false);
          frame();
          release();
          frames(4);
          require(
              pending()
                  && guard().requestVersion() != old
                  && before.equals(ProjectIO.JSON.valueToTree(state.project())),
              "Held guard activated a successor");
          click(guard().button("CANCEL"));
        }
        form = "Source";
        prepare(false);
        frames(5);
        var sourceDraft = state.draft;
        fresh.set(false);
        click(guard().button("DISCARD"));
        require(
            !pending() && state.draft == sourceDraft && state.selected.equals(a),
            "Stale source link discarded current input");
        fresh.set(true);
        form = "Selection";
        prepare(false);
        frames(5);
        ((Noise) state.draft).seedOffset = 23;
        var changed = state.draft;
        click(guard().button("DISCARD"));
        require(
            !pending() && state.draft == changed && state.selected.equals(a),
            "Changed selection draft was discarded by a stale request");
        form = "Edit";
        prepare(false);
        frames(5);
        ((Noise) state.draft).seedOffset = 23;
        changed = state.draft;
        click(guard().button("APPLY"));
        require(
            !pending()
                && state.draft == changed
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Changed edit owner was applied");

        // A valid combined edit consumes its callback before a failing UI refresh.
        state.select("noise2D", a);
        ((Noise) state.draft).seedOffset = 17;
        form = "Edit";
        new ProjectIO().save(out.resolve("resource-before.json"), state.project());
        require(
            resources.probeRequestEdit(
                    "Rename b after applying a",
                    ResourceBrowserEdits.rename(b, "renamed-b"),
                    () -> {
                      callbacks++;
                      throw new IllegalStateException("Injected refresh failure");
                    })
                == ResourceEditSession.Result.NEEDS_DECISION,
            "Combined edit request rejected");
        frames(5);
        click(guard().button("APPLY"));
        require(
            !pending()
                && callbacks == 1
                && state.project().noise2D.getFirst().seedOffset == 17
                && state.project().noise2D.get(1).id.equals("renamed-b")
                && state.revision == revision + 1,
            "Combined edit/refresh consumption failed");
        frames(5);
        require(
            callbacks == 1
                && state.events.snapshot().stream()
                        .filter(e -> e.code().equals("RESOURCE_EDIT_REFRESH_FAILED"))
                        .mapToLong(EditorEvents.Entry::count)
                        .sum()
                    == 1,
            "Refresh callback/error was repeated");
        var after = ProjectIO.JSON.valueToTree(state.project());
        new ProjectIO().save(out.resolve("resource-after.json"), state.project());
        state.document.undo();
        state.revision++;
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())), "One combined Undo failed");
        new ProjectIO().save(out.resolve("resource-undone.json"), state.project());
        state.document.redo();
        state.revision++;
        require(
            after.equals(ProjectIO.JSON.valueToTree(state.project())), "One combined Redo failed");
        new ProjectIO().save(out.resolve("resource-redone.json"), state.project());
        require(
            state.engine == engine && state.engine.profiler().current().isEmpty(),
            "Guard presentation changed runtime ownership");
        Files.writeString(
            out.resolve("resource-decision-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("rejectedApplyRetained", true),
                    Map.entry("cancelBeforeMutation", true),
                    Map.entry("heldRequestScoped", true),
                    Map.entry("staleOwnersBlocked", true),
                    Map.entry("combinedEditOnce", true),
                    Map.entry("refreshFailureConsumed", true),
                    Map.entry("oneUndoRedo", true),
                    Map.entry("callbacks", callbacks),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "RESOURCE_DECISION_LAYOUT_NATIVE_PASS persistent selection/edit/source guards / Cancel"
                + " / stale owners / consumed refresh / one combined Undo-Redo");
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

  private static void prepare(boolean raw) {
    state.select("noise2D", a);
    ((Noise) state.draft).seedOffset = 17;
    if (raw) state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    if (form.equals("Selection")) resources.probeRequestSelection(b);
    else if (form.equals("Edit"))
      require(
          resources.probeRequestEdit(
                  "Rename b", ResourceBrowserEdits.rename(b, "renamed-b"), () -> callbacks++)
              == ResourceEditSession.Result.NEEDS_DECISION,
          "Resource edit did not guard private input");
    else
      require(
          source.request(
                  new EditorEvents.Target(state.project().project.uuid, b, null, null, "", false),
                  fresh::get)
              == SearchNavigation.Result.NEEDS_DECISION,
          "Source request did not guard private input");
  }

  private static NavigationGuardDialog guard() {
    return form.equals("Selection")
        ? resources.selectionGuard
        : form.equals("Edit") ? resources.editGuard : source.dialog;
  }

  private static boolean pending() {
    return form.equals("Selection")
        ? resources.probeSelectionPending()
        : form.equals("Edit") ? resources.probeEditPending() : source.pending();
  }

  private static void cancelModel() {
    if (form.equals("Source")) source.decide(SearchNavigation.Decision.CANCEL);
    else resources.reset();
  }

  private static Map<String, Object> receipt(int scenario, float global, float scale) {
    var g = guard();
    var modal = g.layout.bounds();
    require(
        DialogBounds.contains(g.layout.workArea().bounds(), modal)
            && DialogBounds.contains(modal, g.chrome.body()),
        "Draft guard modal/body outside work");
    for (var b : g.buttons().values())
      require(DialogBounds.contains(modal, b), "Draft guard action outside modal");
    var row = new LinkedHashMap<String, Object>();
    row.put("scenario", scenario);
    row.put("form", form);
    row.put("globalScale", global);
    row.put("windowScale", scale);
    row.put("work", g.layout.workArea().bounds());
    row.put("modal", modal);
    row.put("body", g.chrome.body());
    row.put("buttons", g.buttons());
    row.put("request", g.requestVersion());
    return row;
  }

  private static void press(float[] at) {
    require(at != null, "Missing draft guard action");
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
    ImGui.begin("Resource decision fixture", ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, form.equals("Source") ? "Properties" : "Resources");
    DialogLayout.observe(state, form.equals("Source") ? "Properties" : "Resources");
    if (form.equals("Source")) source.draw();
    else resources.probeDrawDecisions();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
