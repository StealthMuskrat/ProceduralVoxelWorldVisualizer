package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.editor.ViewportCommands.Command;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred real command chrome and bookmark opening; view effects are controlled fixtures. */
public final class ViewportCommandProbe {
  private static EditorState state;
  private static BookmarkPanel bookmarks;
  private static ViewportCommands[] panels;
  private static final Object[] views = {new Object(), new Object(), new Object()};
  private static final UUID[] noises = new UUID[3];
  private static final long[] epochs = new long[3];
  private static final String[] modes = {"Grayscale", "Threshold Voxels", "Threshold Voxels"};
  private static final Coordinates[] picked = {
    new Coordinates(7, null, -9), new Coordinates(17, 18, 19), new Coordinates(27, 28, 29)
  };
  private static int tab, width = 1200, height = 900, expectedHost, reads, regenerations;
  private static float global = 1, multiplier = 1;
  private static boolean visible = true,
      open,
      fail,
      successorDialog,
      successorMenu,
      replaceOnReview;
  private static String surface = "surface";
  private static Path autoPath;
  private static final List<Delivery> deliveries = new ArrayList<>();

  private record Delivery(int tab, String command, Coordinates picked) {}

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
                  for (int i = 0; i < 2; i++) {
                    var n = new Noise();
                    n.id = i == 0 ? "two" : "three";
                    n.dimension = i == 0 ? 2 : 3;
                    n.algorithm.key = "CONSTANT";
                    n.algorithm.parameters.clear();
                    n.algorithm.parameters.put("value", i == 0 ? .125 : .25);
                    (i == 0 ? p.noise2D : p.noise3D).add(n);
                  }
                }),
            "Fixture rejected");
        var projectFile = out.resolve("viewport-project.json");
        state.document.save(projectFile);
        require(state.document.open(projectFile).isEmpty(), "Fixture reopen failed");
        noises[0] = state.project().noise2D.getFirst().uuid;
        noises[1] = state.project().noise3D.getFirst().uuid;
        var originalNoises = noises.clone();
        state.select("noise2D", noises[0]);
        var draft = state.draft;
        var selected = state.selected;
        ((Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var engine = state.engine;
        long revision = state.revision;
        var project = ProjectIO.JSON.valueToTree(state.project());
        panels =
            new ViewportCommands[] {
              new ViewportCommands(state), new ViewportCommands(state), new ViewportCommands(state)
            };
        bookmarks =
            new BookmarkPanel(
                state,
                new BookmarkPanel.Navigation() {
                  public Coordinates current(Tab t) {
                    balanced();
                    return new Coordinates(
                        100 + t.ordinal(),
                        t.threeDimensional() ? 200 + t.ordinal() : null,
                        -100 - t.ordinal());
                  }

                  public void teleport(Tab t, Coordinates point) {
                    throw new AssertionError("Opening a dialog teleported");
                  }
                });
        autoPath = out.resolve("user-config.json");
        boolean initialAuto = state.user.editor.autoRegenerate,
            initialGrid = state.user.visualization.grid;
        var cases = new ArrayList<Map<String, Object>>();
        for (tab = 0; tab < 3; tab++) {
          frames(4);
          capture(cases, "toolbar");
          open();
          capture(cases, "menu");
          escape();
        }
        tab = 0;
        dimensions(1200, 900, 1, 1);
        frames(4);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        for (tab = 0; tab < 3; tab++) {
          frames(4);
          for (var command : List.of(Command.REGENERATE, Command.AUTO))
            escapeAt(panels[tab].toolbarCenter(command));
          for (var command : ViewportCommands.menuCommands(tab)) {
            open();
            escapeAt(panels[tab].menuCenter(command));
          }
        }
        require(deliveries.isEmpty() && !bookmarks.pending(), "Escape authorized viewport command");
        tab = 0;
        for (var command : List.of(Command.REGENERATE, Command.AUTO))
          for (int variant = 0; variant < 7; variant++) {
            bookmarks.cancel();
            surface = "surface";
            noises[0] = originalNoises[0];
            state.user.editor.autoRegenerate = initialAuto;
            state.user.visualization.grid = initialGrid;
            frames(4);
            int id = panels[0].toolbarIds().get(command.name());
            press(panels[0].toolbarCenter(command));
            switch (variant) {
              case 0 -> noises[0] = UUID.randomUUID();
              case 1 -> epochs[0]++;
              case 2 -> state.user.visualization.grid = !initialGrid;
              case 3 -> state.user.editor.autoRegenerate = !initialAuto;
              case 4 -> bookmarks.openAdd(Tab.NOISE_2D, new Coordinates(1, null, 2));
              case 5 -> surface = "successor-surface";
              default ->
                  require(
                      state.document.open(projectFile).isEmpty(), "Controlled replacement failed");
            }
            frame();
            release();
            frames(3);
            require(
                deliveries.isEmpty() && id != panels[0].toolbarIds().get(command.name()),
                "Held toolbar adopted successor context");
          }
        bookmarks.cancel();
        surface = "surface";
        noises[0] = originalNoises[0];
        state.user.editor.autoRegenerate = initialAuto;
        state.user.visualization.grid = initialGrid;
        frames(4);
        press(panels[0].toolbarCenter(Command.REGENERATE));
        visible = false;
        frame();
        visible = true;
        frame();
        release();
        frames(3);
        require(deliveries.isEmpty(), "Hidden toolbar kept held command");
        press(panels[0].toolbarCenter(Command.REGENERATE));
        tab = 1;
        frame();
        tab = 0;
        frame();
        release();
        frames(3);
        require(deliveries.isEmpty(), "Tab return kept held command");
        press(panels[0].toolbarCenter(Command.REGENERATE));
        replaceOnReview = true;
        release();
        frames(3);
        require(
            deliveries.isEmpty() && !replaceOnReview,
            "Complete toolbar did not recheck live review");
        for (tab = 0; tab < 3; tab++) {
          frames(4);
          open();
          press(panels[tab].menuCenter(Command.ADD_BOOKMARK));
          var original = picked[tab];
          picked[tab] = new Coordinates(3, tab == 0 ? null : 4, 5);
          frame();
          release();
          frames(3);
          picked[tab] = original;
          require(
              deliveries.isEmpty() && !bookmarks.pending(),
              "Held menu adopted successor picked coordinate");
          open();
          press(panels[tab].menuCenter(Command.REGENERATE));
          require(state.document.open(projectFile).isEmpty(), "Menu replacement failed");
          frame();
          release();
          frames(3);
          require(deliveries.isEmpty(), "Held menu crossed document lifetime");
        }
        tab = 0;
        frames(4);
        open();
        press(panels[0].menuCenter(Command.REGENERATE));
        visible = false;
        frame();
        visible = true;
        frame();
        release();
        frames(3);
        require(deliveries.isEmpty(), "Hidden context menu retained command");
        open();
        press(panels[0].menuCenter(Command.REGENERATE));
        escape();
        open();
        move(panels[0].menuCenter(Command.REGENERATE));
        release();
        frames(3);
        require(deliveries.isEmpty(), "Reopened menu adopted predecessor release");
        escape();
        // Explicit routes: callbacks are injected view effects; bookmark forms and Auto persistence
        // are real.
        for (tab = 0; tab < 3; tab++) {
          frames(4);
          click(panels[tab].toolbarCenter(Command.REGENERATE));
        }
        for (tab = 0; tab < 3; tab++)
          for (var command : ViewportCommands.menuCommands(tab)) {
            frames(4);
            open();
            click(panels[tab].menuCenter(command));
            if (command == Command.ADD_BOOKMARK) {
              require(
                  bookmarks.pending()
                      && bookmarks.rawText("X").equals(Integer.toString(picked[tab].x())),
                  "Add lost reviewed picked coordinates");
              bookmarks.cancel();
              frames(4);
            } else if (command == Command.TELEPORT) {
              require(
                  bookmarks.pending() && bookmarks.rawText("X").equals(Integer.toString(100 + tab)),
                  "Teleport lost current tab coordinate");
              bookmarks.cancel();
              frames(4);
            }
          }
        tab = 0;
        frames(4);
        open();
        click(panels[0].menuCenter(Command.GRID));
        require(
            state.user.visualization.grid == initialGrid,
            "Grid did not restore through explicit toggle");
        var occupied = out.resolve("occupied");
        Files.createDirectory(occupied);
        Files.writeString(occupied.resolve("keep"), "original");
        autoPath = occupied;
        frames(4);
        var beforeIds = panels[0].toolbarIds();
        click(panels[0].toolbarCenter(Command.AUTO));
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && beforeIds.equals(panels[0].toolbarIds())
                && Files.readString(occupied.resolve("keep")).equals("original"),
            "Failed Auto save changed active value, owner or destination");
        autoPath = out.resolve("user-config.json");
        click(panels[0].toolbarCenter(Command.AUTO));
        require(state.user.editor.autoRegenerate != initialAuto, "Explicit Auto retry failed");
        state.user.save(out.resolve("auto-toggled.json"));
        click(panels[0].toolbarCenter(Command.AUTO));
        fail = true;
        open();
        click(panels[0].menuCenter(Command.REGENERATE));
        fail = false;
        frames(4);
        int delivered = deliveries.size();
        frames(5);
        require(deliveries.size() == delivered, "Failed menu replayed automatically");
        open();
        click(panels[0].menuCenter(Command.REGENERATE));
        successorDialog = true;
        open();
        click(panels[0].menuCenter(Command.REGENERATE));
        successorDialog = false;
        frames(4);
        require(bookmarks.pending(), "Menu cleanup overwrote callback successor dialog");
        bookmarks.cancel();
        frames(4);
        successorMenu = true;
        open();
        click(panels[0].menuCenter(Command.REGENERATE));
        successorMenu = false;
        frames(4);
        require(
            !panels[0].menuIds().isEmpty(), "Failed predecessor overwrote explicit successor menu");
        escape();
        require(
            deliveries.size() == 22 && regenerations == 7,
            "Unexpected view command delivery count");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && !state.document.dirty()
                && state.document.historyBytes() == 0
                && state.draft == draft
                && state.selected.equals(selected)
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Viewport commands changed protected project/private/preferences/runtime state");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("viewport-events.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        Files.writeString(
            out.resolve("viewport-commands-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("deliveries", deliveries),
                    Map.entry("regenerations", regenerations),
                    Map.entry("escapePreserved", true),
                    Map.entry("toolbarSourceScoped", true),
                    Map.entry("completeReviewRechecked", true),
                    Map.entry("hiddenTabReopenScoped", true),
                    Map.entry("menuDocumentPickScoped", true),
                    Map.entry("callbacksBalanced", true),
                    Map.entry("successorsPreserved", true),
                    Map.entry("autoSaveAtomic", true),
                    Map.entry("privatePreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("gpuGenerationVerified", false))));
        System.out.println(
            "VIEWPORT_COMMANDS_NATIVE_PASS source-owned toolbar/menu / balanced callbacks / atomic"
                + " Auto / explicit retries");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static ViewportActionContext source() {
    return ViewportActionContext.capture(
        state,
        views[tab],
        surface,
        tab,
        epochs[tab],
        noises[tab],
        null,
        modes[tab],
        "Final",
        bookmarks.interactionOwner(),
        picked[tab]);
  }

  private static ViewportActionContext sample() {
    reads++;
    if (replaceOnReview && reads == 2) {
      replaceOnReview = false;
      epochs[tab]++;
    }
    return source();
  }

  private static void apply(Command command, ViewportActionContext review) {
    balanced();
    if (fail) throw new IllegalStateException("viewport route boundary");
    if (successorDialog) {
      bookmarks.openAdd(Tab.values()[tab], picked[tab]);
      throw new IllegalStateException("viewport successor dialog");
    }
    if (successorMenu) {
      panels[tab].openContext(source());
      throw new IllegalStateException("viewport successor menu");
    }
    switch (command) {
      case REGENERATE -> {
        epochs[tab]++;
        regenerations++;
      }
      case AUTO -> state.user.applyAutoRegenerate(!review.auto(), autoPath);
      case GRID -> state.user.visualization.grid = !review.grid();
      case TELEPORT -> bookmarks.openTeleport(Tab.values()[tab]);
      case ADD_BOOKMARK -> bookmarks.openAdd(Tab.values()[tab], review.picked());
      default -> {
        modes[tab] = command.label;
        epochs[tab]++;
      }
    }
    deliveries.add(
        new Delivery(
            tab, command.name(), command == Command.ADD_BOOKMARK ? review.picked() : null));
  }

  private static void balanced() {
    require(
        ImGui.getID("viewport-parent-sentinel") == expectedHost,
        "Callback ran inside toolbar/menu native scopes");
  }

  private static void capture(List<Map<String, Object>> cases, String form) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    var ids = form.equals("toolbar") ? panels[tab].toolbarIds() : panels[tab].menuIds();
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      var current = form.equals("toolbar") ? panels[tab].toolbarIds() : panels[tab].menuIds();
      require(ids.equals(current), "Reflow changed command IDs");
      var receipt = new LinkedHashMap<String, Object>();
      receipt.put("tab", tab);
      receipt.put("form", form);
      receipt.put("scenario", i);
      receipt.put("globalScale", global);
      receipt.put("windowScale", multiplier);
      receipt.put("ids", current);
      receipt.put(
          "actions",
          form.equals("toolbar") ? panels[tab].toolbarBounds() : panels[tab].menuBounds());
      if (form.equals("toolbar"))
        receipt.put("window", new DialogBounds.Rect(16, 16, width, height));
      else {
        receipt.put("popup", panels[tab].popupBounds());
        receipt.put("list", panels[tab].layout.listBounds());
      }
      cases.add(receipt);
    }
  }

  private static void open() {
    frames(3);
    move(new float[] {48, 160});
    open = true;
    frames(4);
    require(!panels[tab].menuIds().isEmpty(), "Context menu did not open");
  }

  private static void dimensions(int w, int h, float g, float m) {
    width = w;
    height = h;
    global = g;
    multiplier = m;
  }

  private static void move(float[] point) {
    require(point != null, "Missing command target");
    ImGui.getIO().addMousePosEvent(point[0], point[1]);
    frame();
  }

  private static void press(float[] point) {
    move(point);
    ImGui.getIO().addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static void click(float[] point) {
    press(point);
    release();
    frames(4);
  }

  private static void escape() {
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void escapeAt(float[] point) {
    press(point);
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
    ImGui.begin("Viewport commands");
    ImGui.setWindowFontScale(multiplier);
    expectedHost = ImGui.getID("viewport-parent-sentinel");
    reads = 0;
    if (visible) {
      panels[tab].toolbar(ViewportCommandProbe::sample, ViewportCommandProbe::apply);
      ImGui.textWrapped("Preview control fixture");
      if (open) {
        panels[tab].openContext(source());
        open = false;
      }
      panels[tab].context(ViewportCommandProbe::sample, ViewportCommandProbe::apply);
    }
    ImGui.end();
    bookmarks.drawDialogs();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
