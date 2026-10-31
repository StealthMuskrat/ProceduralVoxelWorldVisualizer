package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred selector lifecycle and callback boundaries; no GLFW, renderer or OS clipboard. */
public final class SelectorDecisionProbe {
  private static EditorState state;
  private static ResourcePicker resource;
  private static DraftChoicePicker local, unbound;
  private static PropertyPanel properties;
  private static Object owner;
  private static long ownerVersion;
  private static UUID alpha, beta, resourceValue;
  private static String localValue = "alpha", mode = "resource", kind = "noise2D";
  private static boolean nullable = true, visible = true, fail, reenter;
  private static List<DraftChoicePicker.Choice> choices;
  private static int width = 1200, height = 900, expectedHost;
  private static float global = 1, multiplier = 1;
  private static final List<Delivery> deliveries = new ArrayList<>();

  private record Delivery(String picker, String value) {}

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
                  for (int i = 0; i < 96; i++) {
                    var n = new Noise();
                    n.id = i == 0 ? "alpha" : i == 1 ? "beta" : "filler-" + i;
                    n.algorithm.key = "CONSTANT";
                    n.algorithm.parameters.clear();
                    n.algorithm.parameters.put("value", i == 0 ? .125 : i == 1 ? .25 : 0);
                    p.noise2D.add(n);
                  }
                }),
            "Fixture rejected");
        var projectFile = out.resolve("protected-project.json");
        state.document.save(projectFile);
        require(state.document.open(projectFile).isEmpty(), "Fixture reopen failed");
        alpha = state.project().noise2D.getFirst().uuid;
        beta = state.project().noise2D.get(1).uuid;
        resourceValue = alpha;
        state.select("noise2D", alpha);
        var draft = state.draft;
        ((Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        owner = draft;
        resource = new ResourcePicker(state, () -> owner, () -> ownerVersion);
        local = new DraftChoicePicker(state, () -> owner, () -> ownerVersion);
        unbound = new DraftChoicePicker();
        properties = new PropertyPanel(state);
        var rows = new ArrayList<DraftChoicePicker.Choice>();
        for (int i = 0; i < 96; i++)
          rows.add(
              new DraftChoicePicker.Choice(
                  i == 0 ? "alpha" : i == 1 ? "beta" : "filler-" + i,
                  i == 0 ? "Alpha" : i == 1 ? "Beta" : "Filler " + i,
                  "Scalar ### snow 雪"));
        choices = List.copyOf(rows);
        var originalChoices = choices;
        var cases = new ArrayList<Map<String, Object>>();
        for (var type : List.of("resource", "local"))
          for (boolean optional : List.of(true, false)) {
            mode = type;
            nullable = optional;
            open();
            query("");
            frames(4);
            int all = submitted();
            require(all > 0 && all < 96, "Unfiltered selector lost clipping");
            query("beta");
            frames(4);
            capture(cases, all);
            escape();
          }
        dimensions(1200, 900, 1, 1);
        frames(4);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        for (var type : List.of("resource", "local")) {
          mode = type;
          nullable = true;
          open();
          query("beta");
          frames(4);
          escapeAt(option());
          require(deliveries.isEmpty(), "Escape authorized selected row");
          open();
          escapeAt(none());
          require(deliveries.isEmpty(), "Escape authorized None");
          open();
          query("beta");
          frames(4);
          var ids = ids();
          long evaluations = type.equals("local") ? local.evaluations() : 0;
          press(option());
          query(" BETA ");
          frame();
          release();
          frames(3);
          require(
              deliveries.isEmpty()
                  && !ids.equals(ids())
                  && (!type.equals("local") || evaluations == local.evaluations()),
              "Held row adopted equal-normalized query");
          escape();
          for (int variant = 0; variant < 5; variant++) {
            resourceValue = alpha;
            localValue = "alpha";
            nullable = true;
            kind = "noise2D";
            open();
            query("beta");
            frames(4);
            press(option());
            switch (variant) {
              case 0 -> owner = new Object();
              case 1 -> ownerVersion++;
              case 2 -> {
                resourceValue = beta;
                localValue = "beta";
              }
              case 3 -> nullable = false;
              case 4 -> {
                require(state.document.open(projectFile).isEmpty(), "Reopen failed");
              }
              default -> throw new AssertionError();
            }
            frame();
            release();
            frames(3);
            require(deliveries.isEmpty(), "Held row adopted changed selector owner");
          }
          resourceValue = alpha;
          localValue = "alpha";
          nullable = true;
          open();
          query("beta");
          frames(4);
          press(option());
          visible = false;
          frame();
          visible = true;
          frame();
          release();
          frames(3);
          require(deliveries.isEmpty(), "Omitted selector kept held row alive");
          open();
          query("beta");
          frames(4);
          press(option());
          escape();
          open();
          move(option());
          release();
          frames(3);
          require(deliveries.isEmpty(), "Reopened selector adopted predecessor release");
          escape();
        }
        mode = "resource";
        open();
        query("beta");
        frames(4);
        press(option());
        kind = "noise3D";
        frame();
        release();
        frames(3);
        require(deliveries.isEmpty(), "Resource kind change adopted held UUID");
        kind = "noise2D";
        mode = "local";
        open();
        query("beta");
        frames(4);
        press(option());
        var mutable = new ArrayList<>(choices);
        mutable.set(
            1, new DraftChoicePicker.Choice("beta", "Beta replacement", "new displayed detail"));
        choices = mutable;
        frame();
        release();
        frames(3);
        require(deliveries.isEmpty(), "Held local row adopted changed choice payload");
        press(option());
        Collections.swap(mutable, 0, 1);
        frame();
        release();
        frames(3);
        require(deliveries.isEmpty(), "Held local row adopted in-place choice reorder");
        escape();
        choices = originalChoices;
        // Both production Properties selectors carry the private draft owner, beyond their labels.
        for (var type : List.of("property-resource", "property-local")) {
          mode = type;
          resourceValue = alpha;
          localValue = "alpha";
          open();
          query("beta");
          frames(4);
          press(option());
          state.draft = ProjectIO.JSON.convertValue(draft, Noise.class);
          frame();
          release();
          frames(3);
          require(deliveries.isEmpty(), "Properties selector adopted replacement private draft");
          state.draft = draft;
          frames(3);
        }
        // Failures consume the old row, reopen with retained query and require a new click.
        for (var type : List.of("resource", "local")) {
          mode = type;
          resourceValue = alpha;
          localValue = "alpha";
          open();
          query("beta");
          frames(4);
          var before = ids();
          fail = true;
          click(option());
          fail = false;
          frames(4);
          require(
              deliveries.isEmpty()
                  && query().equals("beta")
                  && !feedback().isEmpty()
                  && !before.equals(ids()),
              "Failure lost retained selector retry or reused consumed row");
          reenter = true;
          click(option());
          reenter = false;
          frames(4);
          require(
              deliveries.isEmpty() && feedback().isEmpty() && popup() != null,
              "Failed predecessor overwrote explicit successor opening");
          escape();
        }
        mode = "resource";
        resourceValue = alpha;
        nullable = true;
        open();
        query("beta");
        frames(4);
        click(option());
        require(resourceValue.equals(beta), "Explicit UUID selection failed");
        open();
        click(none());
        require(resourceValue == null, "Explicit nullable UUID choice failed");
        mode = "local";
        localValue = "alpha";
        open();
        query("beta");
        frames(4);
        click(option());
        require(localValue.equals("beta"), "Explicit local key failed");
        open();
        click(none());
        require(localValue == null, "Explicit local None failed");
        mode = "property-resource";
        resourceValue = alpha;
        open();
        query("beta");
        frames(4);
        click(option());
        mode = "property-local";
        localValue = "alpha";
        open();
        query("beta");
        frames(4);
        click(option());
        mode = "unbound-local";
        localValue = "alpha";
        open();
        query("beta");
        frames(4);
        fail = true;
        click(option());
        fail = false;
        frames(4);
        require(!feedback().isEmpty(), "State-free selector lost private error feedback");
        click(option());
        require(deliveries.size() == 7, "Selection callback repeated or lost");
        open();
        query("absent-choice");
        frames(4);
        require(submitted() == 0, "Empty query retained choice rows");
        escape();
        require(
            resource.widgetCount() <= 128 && local.contexts() <= 128,
            "Selector query cache exceeded bound");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Selector decisions changed project/preferences/private/runtime ownership");
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("selector-events.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        Files.writeString(
            out.resolve("selector-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("alpha", alpha),
                    Map.entry("beta", beta),
                    Map.entry("escapePreserved", true),
                    Map.entry("heldQueriesBlocked", true),
                    Map.entry("ownerChangesRetired", true),
                    Map.entry("choiceChangesScoped", true),
                    Map.entry("hiddenReopenScoped", true),
                    Map.entry("propertyOwnersScoped", true),
                    Map.entry("callbacksBalanced", true),
                    Map.entry("failureRetryScoped", true),
                    Map.entry("successorsPreserved", true),
                    Map.entry("nullableRoutes", true),
                    Map.entry("stateFreeRetry", true),
                    Map.entry("emptyRowsPreserved", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("deliveries", deliveries),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "SELECTOR_DECISIONS_NATIVE_PASS exact owners / displayed rows / consumed balanced"
                + " callbacks / None / retry / successors");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static boolean resourceMode() {
    return mode.endsWith("resource");
  }

  private static ResourcePicker rp() {
    return mode.startsWith("property") ? properties.probeResourcePicker() : resource;
  }

  private static DraftChoicePicker lp() {
    return mode.startsWith("property")
        ? properties.probeDraftPicker()
        : mode.startsWith("unbound") ? unbound : local;
  }

  private static void deliver(String value) {
    require(
        ImGui.getID("selector-parent-sentinel") == expectedHost,
        "Callback ran inside selector native scopes");
    if (reenter) {
      owner = new Object();
      if (resourceMode()) rp().probeOpen();
      else lp().probeOpen();
      throw new IllegalStateException(resourceMode() ? "resource successor" : "local successor");
    }
    if (fail)
      throw new IllegalStateException(resourceMode() ? "resource boundary" : "local boundary");
    deliveries.add(new Delivery(mode, value));
    if (resourceMode()) resourceValue = value == null ? null : UUID.fromString(value);
    else localValue = value;
  }

  private static void open() {
    frames(3);
    if (resourceMode()) rp().probeOpen();
    else lp().probeOpen();
    frames(4);
    require(popup() != null, "Selector did not open");
  }

  private static void query(String value) {
    if (ImGui.isMouseDown(0)) {
      if (resourceMode()) rp().probeQuery(value);
      else lp().probeQuery(value);
      return;
    }
    var r = layout().queryBounds();
    click(new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2});
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addKeyEvent(ImGuiKey.Backspace, true);
    frame();
    io.addKeyEvent(ImGuiKey.Backspace, false);
    frame();
    if (!value.isEmpty()) io.addInputCharactersUTF8(value);
    frames(3);
  }

  private static String query() {
    return resourceMode() ? rp().query() : lp().query();
  }

  private static String feedback() {
    return resourceMode() ? rp().feedback() : lp().feedback();
  }

  private static float[] option() {
    return resourceMode() ? rp().optionCenter(beta) : lp().option("beta");
  }

  private static float[] none() {
    return resourceMode() ? rp().noneCenter() : lp().noneCenter();
  }

  private static Map<String, Integer> ids() {
    return resourceMode() ? rp().rowIds() : lp().rowIds();
  }

  private static int submitted() {
    return resourceMode() ? rp().submitted() : lp().submitted();
  }

  private static PopupLayout layout() {
    return resourceMode() ? rp().layout : lp().layout;
  }

  private static DialogBounds.Rect popup() {
    return layout().bounds();
  }

  private static void capture(List<Map<String, Object>> cases, int all) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> first = null;
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      require(popup() != null && submitted() == 1, "Filtered selector disappeared during reflow");
      if (first == null) first = ids();
      require(first.equals(ids()), "Reflow changed selector row identity");
      cases.add(
          Map.ofEntries(
              Map.entry("form", mode),
              Map.entry("nullable", nullable),
              Map.entry("scenario", i),
              Map.entry("globalScale", global),
              Map.entry("windowScale", multiplier),
              Map.entry("popup", popup()),
              Map.entry("query", layout().queryBounds()),
              Map.entry("list", layout().listBounds()),
              Map.entry("ids", ids()),
              Map.entry("unfilteredRows", all),
              Map.entry("stride", resourceMode() ? rp().rowStride() : lp().rowStride())));
    }
  }

  private static void dimensions(int w, int h, float scale, float windowScale) {
    width = w;
    height = h;
    global = scale;
    multiplier = windowScale;
  }

  private static void move(float[] at) {
    ImGui.getIO().addMousePosEvent(at[0], at[1]);
    frame();
  }

  private static void press(float[] at) {
    move(at);
    ImGui.getIO().addMouseButtonEvent(0, true);
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

  private static void escape() {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void escapeAt(float[] at) {
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
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Selector decisions");
    ImGui.setWindowFontScale(multiplier);
    expectedHost = ImGui.getID("selector-parent-sentinel");
    if (visible) {
      if (mode.equals("property-resource"))
        properties.noisePicker(
            "Value", resourceValue, value -> deliver(value == null ? null : value.toString()));
      else if (resourceMode())
        rp().draw(
                "Value",
                kind,
                resourceValue,
                nullable ? "None" : null,
                value -> deliver(value == null ? null : value.toString()));
      else
        lp().draw(
                "Value",
                localValue,
                choices,
                nullable ? "None" : null,
                SelectorDecisionProbe::deliver);
    }
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
