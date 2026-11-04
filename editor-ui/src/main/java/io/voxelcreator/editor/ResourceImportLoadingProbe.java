package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Deferred real package controls and worker gates, nested in the existing import workflow. */
final class ResourceImportLoadingProbe {
  private static EditorState state;
  private static ResourcePackageDialog dialog;
  private static ResourceImportPanel panel;
  private static Queue<Runnable> ui;
  private static boolean holdUi;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("success", "cancel", "escape", "raw", "retry", "close")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      var source = directory.resolve("资源 package.pvresources.json");
      var incoming = new ProjectData();
      incoming.noise2D.add(noise("incoming", .75));
      String packageBytes =
          ProjectIO.JSON.writeValueAsString(Map.of("packageVersion", 1, "resources", incoming));
      Files.writeString(source, mode.equals("retry") ? "{\"packageVersion\":2}" : packageBytes);
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.put("Resources", 1f);
        require(
            state.change(
                p -> {
                  p.project.seed = 17;
                  p.project.minY = -2;
                  p.project.maxY = 2;
                  p.noise2D.add(noise("existing", .125));
                }),
            "Background import fixture rejected");
        state.document.save(directory.resolve("before.json"));
        byte[] beforeBytes = Files.readAllBytes(directory.resolve("before.json"));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private-field";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var original = ProjectIO.JSON.valueToTree(state.project());
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var draft = state.draft;
        var draftValues = ProjectIO.JSON.valueToTree(draft);
        var selected = state.selected;
        long revision = state.revision, history = state.document.historyBytes();
        var resources = new ResourcePanel(state, new PropertyPanel(state));
        panel = new ResourceImportPanel(state, () -> false, () -> {});
        ui = new ConcurrentLinkedQueue<>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var files = new ResourcePackageFiles();
        var uiThread = Thread.currentThread();
        try (var workspace =
            new ResourcePackageDialog(
                state,
                resources,
                panel,
                () -> false,
                files,
                state.background,
                ui::add,
                (file, destination) -> {
                  require(
                      Thread.currentThread() != uiThread, "Package preparation ran on UI thread");
                  entered.countDown();
                  if (!release.await(20, TimeUnit.SECONDS))
                    throw new IllegalStateException("Import gate timed out");
                  return ImportEditSession.prepare(destination, files.read(file));
                })) {
          dialog = workspace;
          holdUi = true;
          require(dialog.open("Import"), "Background package path did not open");
          frames(6);
          path(source.toString());
          click(dialog.control("submit"));
          await(() -> entered.getCount() == 0, "Package worker did not start");
          for (int i = 0; i < 12; i++) {
            frame();
            require(
                dialog.pending() && dialog.importRunning() && !panel.pending(),
                "Loading frame lost request ownership");
            require(
                DialogBounds.contains(dialog.layout.bounds(), dialog.chrome().body()),
                "Loading body escaped modal");
            for (var box : dialog.chrome().actions().values())
              require(
                  DialogBounds.contains(dialog.layout.bounds(), box),
                  "Loading action escaped modal");
          }
          require(
              original.equals(ProjectIO.JSON.valueToTree(state.project())),
              "Loading changed committed values");
          if (mode.equals("escape")) {
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
            frame();
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
            frame();
            require(!dialog.pending(), "Escape retained the loading dialog");
            release.countDown();
            await(() -> !ui.isEmpty(), "Cancelled worker did not retire its callback");
          } else {
            release.countDown();
            await(() -> !ui.isEmpty(), "Package completion did not reach UI queue");
            require(
                dialog.pending() && !panel.pending(), "Worker opened review without UI delivery");
            if (mode.equals("cancel")) click(dialog.control("cancel"));
            else if (mode.equals("close")) dialog.close();
            else if (mode.equals("raw")) state.project().project.seed = 18;
          }
          holdUi = false;
          frames(5);
          boolean imported = mode.equals("success") || mode.equals("retry");
          if (mode.equals("retry")) {
            require(
                dialog.pending()
                    && !panel.pending()
                    && !dialog.error().isEmpty()
                    && dialog.pathText().equals(source.toString()),
                "Malformed package lost retry path");
            Files.writeString(source, packageBytes);
            click(dialog.control("submit"));
            await(panel::pending, "Corrected package did not open review");
          } else if (mode.equals("raw")) {
            require(
                dialog.pending() && !panel.pending() && !dialog.error().isEmpty(),
                "Changed values opened stale review");
            click(dialog.control("cancel"));
          }
          if (imported) {
            require(
                !dialog.pending() && panel.pending() && panel.session().canApply(),
                "Ready preview was not visibly reviewed");
            var session = panel.session();
            var preview = session.preview();
            frames(4);
            require(preview == session.preview(), "Ready review rebuilt its initial plan");
            click(panel.control("apply"));
            require(
                !panel.pending() && state.project().noise2D.size() == 2,
                "Native Import did not apply one reviewed transaction");
            var after = ProjectIO.JSON.valueToTree(state.project());
            state.document.undo();
            require(
                original.equals(ProjectIO.JSON.valueToTree(state.project())),
                "Import Undo was not one transaction");
            state.document.redo();
            require(
                after.equals(ProjectIO.JSON.valueToTree(state.project())),
                "Import Redo changed its reviewed target");
          } else {
            require(!dialog.pending() && !panel.pending(), "Retired package opened late review");
            var expected = original.deepCopy();
            if (mode.equals("raw"))
              ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("project"))
                  .put("seed", 18);
            require(
                expected.equals(ProjectIO.JSON.valueToTree(state.project())),
                "Retired package changed active project");
            require(
                state.revision == revision && state.document.historyBytes() == history,
                "Retired package moved history");
          }
          require(
              state.draft == draft
                  && draftValues.equals(ProjectIO.JSON.valueToTree(draft))
                  && selected.equals(state.selected)
                  && state.multiSelection.equals(Set.of(selected))
                  && state.seedInputs.text("seedOffset").equals("-")
                  && preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
              "Import preparation changed private inputs or preferences");
          require(
              Arrays.equals(beforeBytes, Files.readAllBytes(directory.resolve("before.json"))),
              "Import preparation wrote source project");
          state.document.save(directory.resolve("after.json"));
          Files.writeString(
              directory.resolve("preferences.json"), ProjectIO.JSON.writeValueAsString(state.user));
          cases.add(
              Map.of(
                  "mode",
                  mode,
                  "imported",
                  imported,
                  "responsiveFrames",
                  12,
                  "privateRetained",
                  true,
                  "preferencesRetained",
                  true,
                  "originalBytesRetained",
                  true));
        } finally {
          release.countDown();
          panel.cancel();
        }
      }
    }
    Files.writeString(
        out.resolve("background-import.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static Noise noise(String id, double value) {
    var noise = new Noise();
    noise.id = id;
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", value);
    return noise;
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      frame();
      Thread.sleep(1);
    }
    require(condition.getAsBoolean(), message);
  }

  private static void frame() {
    if (!holdUi) {
      Runnable value;
      while ((value = ui.poll()) != null) value.run();
    }
    ImGui.getIO().setDisplaySize(1200, 900);
    ImGui.getIO().setFontGlobalScale(1);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1200, 900, ImGuiCond.Always);
    ImGui.begin("Background resource import reference");
    ImGui.text("Resource package preparation");
    ImGui.end();
    dialog.draw();
    panel.draw();
    ImGui.render();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void click(float[] at) {
    require(at != null, "Missing native import control");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    frames(4);
  }

  private static void path(String value) {
    click(dialog.control("Path"));
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(value);
    frames(4);
    require(dialog.pathText().equals(value), "Package path input changed Unicode/space path");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
