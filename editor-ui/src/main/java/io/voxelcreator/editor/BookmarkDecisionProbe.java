package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred bookmark toolbar/picker boundaries; navigation and coordinate-capture failures are
 * fixtures.
 */
public final class BookmarkDecisionProbe {
  private static EditorState state;
  private static BookmarkPanel panel;
  private static Tab tab = Tab.WORLD;
  private static final Map<Tab, UUID> alpha = new EnumMap<>(Tab.class),
      beta = new EnumMap<>(Tab.class);
  private static final Map<Tab, Coordinates> camera = new EnumMap<>(Tab.class);
  private static final List<Jump> jumps = new ArrayList<>();
  private static int width = 1200, height = 900, expectedHost;
  private static float global = 1, multiplier = 1;
  private static boolean visible = true, allTabs, inDraw, failNavigation, successor;
  private static Runnable captureTransition, pickedTransition;
  private static Coordinates picked;
  private static Path protectedFile, guardFile;

  private record Jump(Tab tab, Coordinates coordinate) {}

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
                  var n = new Noise();
                  n.id = "existing";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                  for (var t : Tab.values())
                    for (int i = 0; i < 96; i++) {
                      var b = new Bookmark();
                      b.name = i == 0 ? "alpha" : i == 1 ? "beta" : "marker-" + i;
                      b.x = t.ordinal() * 10 + i + 1;
                      b.y = t.threeDimensional() ? t.ordinal() * 10 + i + 2 : null;
                      b.z = -b.x;
                      BookmarkTransactions.list(p, t).add(b);
                    }
                }),
            "Fixture rejected");
        protectedFile = out.resolve("protected-project.json");
        state.document.save(protectedFile);
        require(state.document.open(protectedFile).isEmpty(), "Fixture reopen failed");
        for (var t : Tab.values()) {
          alpha.put(t, BookmarkTransactions.list(state.project(), t).getFirst().uuid);
          beta.put(t, BookmarkTransactions.list(state.project(), t).get(1).uuid);
          camera.put(
              t,
              new Coordinates(
                  100 + t.ordinal(),
                  t.threeDimensional() ? 200 + t.ordinal() : null,
                  -100 - t.ordinal()));
        }
        var guarded = ProjectIO.copy(state.project());
        BookmarkTransactions.update(
            guarded, Tab.WORLD, alpha.get(Tab.WORLD), new Coordinates(300, 301, 302));
        guardFile = out.resolve("guard-target.json");
        new ProjectIO().save(guardFile, guarded);
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = state.draft;
        ((Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision;
        var engine = state.engine;
        panel =
            new BookmarkPanel(
                state,
                new BookmarkPanel.Navigation() {
                  public Coordinates current(Tab t) {
                    balanced();
                    if (captureTransition != null) {
                      var change = captureTransition;
                      captureTransition = null;
                      change.run();
                    }
                    return camera.get(t);
                  }

                  public Coordinates picked(Tab t) {
                    balanced();
                    if (pickedTransition != null) {
                      var change = pickedTransition;
                      pickedTransition = null;
                      change.run();
                    }
                    return picked;
                  }

                  public void teleport(Tab t, Coordinates point) {
                    balanced();
                    if (successor) {
                      panel.openAdd(t, camera.get(t));
                      throw new IllegalStateException("bookmark navigation successor");
                    }
                    if (failNavigation)
                      throw new IllegalStateException("bookmark navigation boundary");
                    jumps.add(new Jump(t, point));
                    camera.put(t, point);
                  }
                });
        var cases = new ArrayList<Map<String, Object>>();
        for (var t : Tab.values()) {
          tab = t;
          panel.probeSelect(t, alpha.get(t));
          frames(4);
          capture(cases, "toolbar");
          openChoices();
          type("beta");
          frames(4);
          capture(cases, "picker");
          escape();
        }
        dimensions(1200, 900, 1, 1);
        frames(4);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        for (var t : Tab.values()) {
          tab = t;
          prepare();
          for (var key : keys()) {
            escapeAt(at(key));
            require(
                jumps.isEmpty()
                    && !panel.pending()
                    && Objects.equals(panel.selected(tab), alpha.get(tab)),
                "Escape authorized bookmark toolbar");
          }
          openChoices();
          type("beta");
          frames(3);
          escapeAt(panel.option(beta.get(tab)));
          require(jumps.isEmpty(), "Escape authorized bookmark row");
        }
        tab = Tab.WORLD;
        for (var key : keys()) {
          prepare();
          int id = panel.toolbarIds(tab).get(key);
          press(at(key));
          panel.probeSelect(tab, beta.get(tab));
          frame();
          release();
          frames(3);
          require(
              jumps.isEmpty()
                  && !panel.pending()
                  && Objects.equals(panel.selected(tab), beta.get(tab))
                  && id != panel.toolbarIds(tab).get(key),
              "Held toolbar action adopted successor selection");
        }
        prepare();
        press(at("Delete Bookmark"));
        panel.openRename(tab, alpha.get(tab));
        frame();
        release();
        frames(3);
        require(
            panel.pending() && Objects.equals(panel.probeUuid(), alpha.get(tab)),
            "Pending dialog lost ownership to held toolbar");
        cancel();
        prepare();
        press(at("Delete Bookmark"));
        visible = false;
        frame();
        visible = true;
        frame();
        release();
        frames(3);
        require(!panel.pending(), "Hidden toolbar adopted old action");
        prepare();
        press(at("Delete Bookmark"));
        tab = Tab.NOISE_3D;
        frame();
        tab = Tab.WORLD;
        frame();
        release();
        frames(3);
        require(
            BookmarkTransactions.find(state.project(), tab, alpha.get(tab)) != null,
            "Tab switch kept held Delete alive");
        for (var t : Tab.values()) panel.probeSelect(t, alpha.get(t));
        allTabs = true;
        frames(4);
        var ids = new EnumMap<Tab, Map<String, Integer>>(Tab.class);
        for (var t : Tab.values()) ids.put(t, panel.toolbarIds(t));
        frames(5);
        for (var t : Tab.values())
          require(
              ids.get(t).equals(panel.toolbarIds(t)),
              "Independent bookmark tabs churned action IDs");
        allTabs = false;
        frames(4);
        prepare();
        openChoices();
        type("beta");
        frames(3);
        var rowIds = panel.choiceIds();
        press(panel.option(beta.get(tab)));
        panel.probeQuery(tab, " BETA ");
        frame();
        release();
        frames(3);
        require(
            jumps.isEmpty() && !rowIds.equals(panel.choiceIds()),
            "Held bookmark row adopted equal-normalized query");
        escape();
        prepare();
        openChoices();
        type("beta");
        frames(3);
        press(panel.option(beta.get(tab)));
        visible = false;
        frame();
        visible = true;
        frame();
        release();
        frames(3);
        require(jumps.isEmpty(), "Hidden picker retained held navigation");
        prepare();
        openChoices();
        type("beta");
        frames(3);
        press(panel.option(beta.get(tab)));
        escape();
        openChoices();
        move(panel.option(beta.get(tab)));
        release();
        frames(3);
        require(jumps.isEmpty(), "Reopened bookmark picker adopted predecessor release");
        escape();
        for (var key : keys()) {
          prepare();
          press(at(key));
          reopen(protectedFile);
          frame();
          release();
          frames(3);
          require(
              jumps.isEmpty() && !panel.pending(),
              "Held bookmark action crossed document lifetime");
        }
        prepare();
        openChoices();
        type("beta");
        frames(3);
        press(panel.option(beta.get(tab)));
        reopen(guardFile);
        frame();
        release();
        frames(3);
        require(
            jumps.isEmpty() && panel.selected(tab) == null,
            "Held bookmark row navigated replacement document");
        reopen(protectedFile);
        frames(4);
        prepare();
        openChoices();
        type("beta");
        frames(3);
        failNavigation = true;
        click(panel.option(beta.get(tab)));
        failNavigation = false;
        frames(4);
        require(
            jumps.isEmpty()
                && Objects.equals(panel.selected(tab), alpha.get(tab))
                && panel.query(tab).equals("beta")
                && !panel.choiceFeedback(tab).isEmpty(),
            "Failed navigation lost original selection/query/retry");
        click(panel.option(beta.get(tab)));
        require(
            jumps.size() == 1 && Objects.equals(panel.selected(tab), beta.get(tab)),
            "Explicit bookmark retry failed");
        prepare();
        openChoices();
        type("beta");
        frames(3);
        successor = true;
        click(panel.option(beta.get(tab)));
        successor = false;
        frames(4);
        require(
            panel.pending()
                && panel.choiceFeedback(tab).isEmpty()
                && Objects.equals(panel.selected(tab), alpha.get(tab)),
            "Failed picker navigation overwrote successor dialog or selection");
        cancel();
        // Capture callbacks can change the document; none may update or seed its successor.
        prepare();
        captureTransition = () -> reopen(guardFile);
        click(at("Update Coordinates"));
        require(
            !panel.pending()
                && BookmarkTransactions.coordinates(
                        BookmarkTransactions.find(state.project(), tab, alpha.get(tab)))
                    .equals(new Coordinates(300, 301, 302)),
            "Update capture mutated replacement document");
        new ProjectIO().save(out.resolve("blocked-capture.json"), state.project());
        reopen(protectedFile);
        frames(4);
        for (var key : List.of("Add Bookmark", "Teleport")) {
          prepare();
          captureTransition = () -> reopen(guardFile);
          click(at(key));
          require(!panel.pending(), "Coordinate getter seeded a successor document dialog");
          reopen(protectedFile);
          frames(4);
        }
        prepare();
        pickedTransition = () -> reopen(guardFile);
        click(at("Add Bookmark"));
        require(!panel.pending(), "Picked-coordinate getter seeded successor document");
        reopen(protectedFile);
        frames(4);
        new ProjectIO().save(out.resolve("before.json"), state.project());
        // Camera motion does not replace bookmark ownership; Update captures its accepted latest
        // point.
        prepare();
        press(at("Update Coordinates"));
        camera.put(tab, new Coordinates(51, 52, 53));
        frame();
        release();
        frames(3);
        require(
            BookmarkTransactions.coordinates(
                    BookmarkTransactions.find(state.project(), tab, alpha.get(tab)))
                .equals(new Coordinates(51, 52, 53)),
            "Update missed latest accepted camera capture");
        save(out, "updated");
        require(state.document.canUndo(), "Update Undo missing");
        state.document.undo();
        state.revision++;
        save(out, "update-undone");
        require(state.document.canRedo(), "Update Redo missing");
        state.document.redo();
        state.revision++;
        save(out, "update-redone");
        frames(4);
        click(at("Delete Bookmark"));
        require(
            panel.selected(tab) == null
                && BookmarkTransactions.find(state.project(), tab, alpha.get(tab)) == null,
            "Delete did not clear its selected target");
        save(out, "deleted");
        require(state.document.canUndo(), "Delete Undo missing");
        state.document.undo();
        state.revision++;
        save(out, "delete-undone");
        require(state.document.canRedo(), "Delete Redo missing");
        state.document.redo();
        state.revision++;
        save(out, "delete-redone");
        frames(4);
        panel.probeSelect(tab, beta.get(tab));
        frames(4);
        click(at("Rename"));
        require(
            panel.pending() && Objects.equals(panel.probeUuid(), beta.get(tab)),
            "Rename routed wrong selected UUID");
        cancel();
        picked = new Coordinates(7, 8, 9);
        click(at("Add Bookmark"));
        require(
            panel.pending() && panel.rawText("X").equals("7") && panel.rawText("Y").equals("8"),
            "Add lost picked coordinate");
        cancel();
        picked = null;
        click(at("Teleport"));
        require(
            panel.pending() && panel.rawText("X").equals("51"),
            "Teleport lost latest view coordinate");
        cancel();
        require(
            state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && state.revision == revision + 6
                && state.document.dirty()
                && state.document.historyBytes() > 0
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Bookmark decisions changed private/preferences/runtime ownership or edit count");
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("bookmark-events.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        var first = new LinkedHashMap<String, UUID>();
        var second = new LinkedHashMap<String, UUID>();
        for (var t : Tab.values()) {
          first.put(t.key(), alpha.get(t));
          second.put(t.key(), beta.get(t));
        }
        Files.writeString(
            out.resolve("bookmark-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("alpha", first),
                    Map.entry("beta", second),
                    Map.entry("escapePreserved", true),
                    Map.entry("heldToolbarScoped", true),
                    Map.entry("dialogOwnershipScoped", true),
                    Map.entry("tabLifetimesIndependent", true),
                    Map.entry("heldRowsScoped", true),
                    Map.entry("hiddenReopenScoped", true),
                    Map.entry("documentLifetimeScoped", true),
                    Map.entry("navigationRetryScoped", true),
                    Map.entry("callbacksBalanced", true),
                    Map.entry("successorPreserved", true),
                    Map.entry("captureRechecked", true),
                    Map.entry("latestViewCaptured", true),
                    Map.entry("updateDeleteHistory", true),
                    Map.entry("explicitDialogsRouted", true),
                    Map.entry("privatePreserved", true),
                    Map.entry("jumps", jumps),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "BOOKMARK_DECISIONS_NATIVE_PASS tab-owned toolbar/choices / capture recheck / balanced"
                + " navigation retry / exact Update Delete Undo Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void balanced() {
    if (inDraw)
      require(
          ImGui.getID("bookmark-parent-sentinel") == expectedHost,
          "Bookmark callback ran inside toolbar/picker native scopes");
  }

  private static void save(Path out, String name) throws Exception {
    new ProjectIO().save(out.resolve(name + ".json"), state.project());
  }

  private static void reopen(Path file) {
    try {
      require(state.document.open(file).isEmpty(), "Controlled reopen failed");
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void prepare() {
    cancel();
    panel.probeSelect(tab, alpha.get(tab));
    frames(4);
  }

  private static void cancel() {
    panel.cancel();
    frames(4);
  }

  private static List<String> keys() {
    return List.of(
        "picker", "Add Bookmark", "Teleport", "Rename", "Update Coordinates", "Delete Bookmark");
  }

  private static float[] at(String key) {
    var r = panel.toolbarBounds(tab).get(key);
    require(r != null, "Missing toolbar action " + key);
    return new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2};
  }

  private static void openChoices() {
    frames(3);
    panel.probeOpenChoices(tab);
    frames(4);
    require(panel.choicesLayout.bounds() != null, "Bookmark picker did not open");
  }

  private static void type(String value) {
    var r = panel.choicesLayout.queryBounds();
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
    io.addInputCharactersUTF8(value);
    frames(3);
  }

  private static void capture(List<Map<String, Object>> cases, String form) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      var current = form.equals("toolbar") ? panel.toolbarIds(tab) : panel.choiceIds();
      if (ids == null) ids = current;
      require(ids.equals(current), "Reflow changed bookmark decision identity");
      var row = new LinkedHashMap<String, Object>();
      row.put("form", form);
      row.put("tab", tab.key());
      row.put("scenario", i);
      row.put("globalScale", global);
      row.put("windowScale", multiplier);
      row.put("ids", current);
      if (form.equals("toolbar")) {
        row.put("window", new DialogBounds.Rect(16, 16, width, height));
        row.put("actions", panel.toolbarBounds(tab));
      } else {
        row.put("popup", panel.choicesLayout.bounds());
        row.put("query", panel.choicesLayout.queryBounds());
        row.put("list", panel.choicesLayout.listBounds());
        row.put("rows", panel.submitted());
        row.put("stride", panel.choiceStride());
        require(panel.submitted() == 1, "Filtered bookmark row disappeared");
      }
      cases.add(row);
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
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void escapeAt(float[] at) {
    press(at);
    ImGui.getIO().addMouseButtonEvent(0, false);
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
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
    ImGui.begin("Bookmark decisions");
    ImGui.setWindowFontScale(multiplier);
    expectedHost = ImGui.getID("bookmark-parent-sentinel");
    inDraw = true;
    if (visible) {
      if (allTabs) for (var t : Tab.values()) panel.draw(t);
      else panel.draw(tab);
    }
    inDraw = false;
    ImGui.end();
    panel.drawDialogs();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
