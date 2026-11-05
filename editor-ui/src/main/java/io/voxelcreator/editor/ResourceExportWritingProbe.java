package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Deferred production export controls, private staging and exact final package snapshots. */
final class ResourceExportWritingProbe {
  private static EditorState state;
  private static ResourcePackageDialog dialog;
  private static ResourceImportPanel imports;
  private static Queue<Runnable> ui;
  private static boolean holdUi;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("success", "cancel", "escape", "raw", "retry", "close", "ready-cancel", "ready-escape", "ready-close", "ready-failure-cancel")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      var target = directory.resolve("selection 资源.pvresources.json");
      var prior = new ProjectData();
      prior.noise2D.add(noise("prior", .25));
      String originalTarget =
          ProjectIO.JSON.writeValueAsString(Map.of("packageVersion", 1, "resources", prior));
      Files.writeString(target, originalTarget);
      Files.writeString(directory.resolve("prior-package.pvresources.json"), originalTarget);
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
                  p.noise2D.add(noise("field", .625));
                }),
            "Export fixture rejected");
        var projectPath = directory.resolve("source.json");
        state.document.save(projectPath);
        byte[] projectBytes = Files.readAllBytes(projectPath);
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private-field";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var original = ProjectIO.JSON.valueToTree(state.project());
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var privateValues = ProjectIO.JSON.valueToTree(state.draft);
        var draft = state.draft;
        var selected = state.selected;
        long revision = state.revision, history = state.document.historyBytes();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        var replacements = new AtomicInteger();
        var deletions = new AtomicInteger();
        var fail = new AtomicBoolean(mode.equals("retry"));
        var uiThread = Thread.currentThread();
        var files =
            new ResourcePackageFiles(
                new ResourcePackageFiles.Storage() {
                  public void write(Path temp, byte[] bytes) throws IOException {
                    require(Thread.currentThread() != uiThread, "Package staging ran on UI thread");
                    try (var channel =
                        FileChannel.open(
                            temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                      var buffer = ByteBuffer.wrap(bytes);
                      while (buffer.hasRemaining()) channel.write(buffer);
                      channel.force(true);
                    }
                    writes.incrementAndGet();
                    entered.countDown();
                    boolean waiting = true;
                    while (waiting)
                      try {
                        if (!release.await(20, TimeUnit.SECONDS))
                          throw new IOException("Export gate timed out");
                        waiting = false;
                      } catch (InterruptedException ignored) {
                        /* Non-cooperative provider boundary. */
                      }
                    if (mode.equals("ready-failure-cancel")) throw new IOException("held staging failure");
                  }

                  public void replace(Path temp, Path destination) throws IOException {
                    require(
                        Thread.currentThread() == uiThread, "Package publication left UI owner");
                    replacements.incrementAndGet();
                    if (fail.getAndSet(false))
                      throw new AtomicMoveNotSupportedException(
                          temp.toString(), destination.toString(), "injected");
                    Files.move(
                        temp,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                  }

                  public void delete(Path temp) throws IOException {
                    Files.deleteIfExists(temp);
                    deletions.incrementAndGet();
                  }
                });
        var resources = new ResourcePanel(state, new PropertyPanel(state));
        imports = new ResourceImportPanel(state, () -> false, () -> {});
        ui = new ConcurrentLinkedQueue<>();
        holdUi = true;
        try (var workspace =
            new ResourcePackageDialog(
                state,
                resources,
                imports,
                () -> false,
                files,
                state.background,
                ui::add,
                (source, destination) ->
                    ImportEditSession.prepare(destination, files.read(source)))) {
          dialog = workspace;
          require(dialog.open("Export"), "Export did not open");
          frames(6);
          click(dialog.control("included"));
          require(resources.canExport(), "Included list did not acknowledge preview");
          var source = resources.exportView().session();
          var preview = source.preview();
          click(dialog.control("location"));
          path(target.toString());
          click(dialog.control("submit"));
          await(() -> entered.getCount() == 0, "Export worker did not start");
          for (int i = 0; i < 12; i++) {
            frame();
            require(
                dialog.pending() && dialog.exportRunning() && !source.closed(),
                "Loading frame lost export ownership");
            require(
                DialogBounds.contains(dialog.layout.bounds(), dialog.chrome().body()),
                "Export body escaped modal");
            for (var rect : dialog.chrome().actions().values())
              require(
                  DialogBounds.contains(dialog.layout.bounds(), rect),
                  "Export action escaped modal");
          }
          require(
              Files.readString(target).equals(originalTarget) && replacements.get() == 0,
              "Staging replaced destination");
          if (mode.equals("escape")) {
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
            frame();
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
            frame();
            require(!dialog.pending(), "Escape retained export");
            release.countDown();
            await(() -> deletions.get() == 1, "Late export result was not cleaned");
          } else {
            release.countDown();
            await(() -> !ui.isEmpty(), "Export completion was not queued");
            require(
                Files.readString(target).equals(originalTarget),
                "Worker published before UI delivery");
            if (mode.startsWith("ready-")) {
              var request = dialog.exportRequestToken();
              if (!mode.equals("ready-escape") && !mode.equals("ready-close")) hold(dialog.control("cancel"));
              ui.remove().run();
              require(dialog.pending() && dialog.exportRunning() && !source.closed()
                  && request == dialog.exportRequestToken() && dialog.error().isEmpty()
                  && replacements.get() == 0 && Files.readString(target).equals(originalTarget),
                  "Ready staging replaced destination, closed review or changed Cancel authority");
              if (mode.equals("ready-escape")) {
                ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true); frame();
                ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(3);
              } else if (mode.equals("ready-close")) { dialog.close(); frames(3); }
              else { ImGui.getIO().addMouseButtonEvent(0, false); frames(3); }
              require(!dialog.pending() && replacements.get() == 0, "Ready retirement replaced destination or retained modal");
            } else if (mode.equals("cancel")) click(dialog.control("cancel"));
            else if (mode.equals("raw")) state.project().project.seed = 18;
            else if (mode.equals("close")) dialog.close();
          }
          holdUi = false;
          await(() -> !dialog.exportRunning(), "Export completion did not finish its owner handoff");
          frames(5);
          boolean published = mode.equals("success") || mode.equals("retry");
          if (mode.equals("retry")) {
            require(
                dialog.pending()
                    && !dialog.error().isEmpty()
                    && dialog.pathText().equals(target.toString())
                    && source.preview() == preview
                    && source.canExport(),
                "Failed export lost retry path or reviewed preview");
            require(
                Files.readString(target).equals(originalTarget),
                "Failed replacement changed prior package");
            click(dialog.control("submit"));
            await(() -> !dialog.pending(), "Export retry did not complete");
          } else if (mode.equals("raw")) {
            require(
                dialog.pending() && !dialog.error().isEmpty(),
                "Raw source change did not reject export");
            click(dialog.control("cancel"));
          }
          require(
              !dialog.pending() && !imports.pending(),
              "Completed or cancelled Export retained a modal");
          if (published) {
            var exported = files.read(target);
            require(
                exported.noise2D.size() == 1
                    && exported.noise2D.getFirst().id.equals("field")
                    && exported.noise2D.getFirst().algorithm.parameters.get("value") == .625,
                "Published package differs from reviewed committed data");
            require(
                source.closed() && resources.exportSelection.isEmpty(),
                "Success retained export ownership");
          } else
            require(
                Files.readString(target).equals(originalTarget),
                "Retired export changed destination");
          var expected = original.deepCopy();
          if (mode.equals("raw"))
            ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("project"))
                .put("seed", 18);
          require(
              expected.equals(ProjectIO.JSON.valueToTree(state.project()))
                  && state.revision == revision
                  && state.document.historyBytes() == history
                  && state.draft == draft
                  && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
                  && selected.equals(state.selected)
                  && state.multiSelection.equals(Set.of(selected))
                  && state.seedInputs.text("seedOffset").equals("-")
                  && preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
              "Export changed committed/private/history/preference state");
          require(
              Arrays.equals(projectBytes, Files.readAllBytes(projectPath)),
              "Export wrote source project");
          try (var paths = Files.list(directory)) {
            require(
                paths.noneMatch(p -> p.getFileName().toString().startsWith(".voxel-package-")),
                "Unpublished export sibling retained");
          }
          state.document.save(directory.resolve("source-after.json"));
          cases.add(
              Map.of(
                  "mode",
                  mode,
                  "published",
                  published,
                  "responsiveFrames",
                  12,
                  "writes",
                  writes.get(),
                  "replacements",
                  replacements.get(),
                  "deletions",
                  deletions.get(),
                  "privateRetained",
                  true,
                  "originalBytesRetained",
                  true,
                  "readyRetired",
                  mode.startsWith("ready-")));
        } finally {
          release.countDown();
        }
      }
    }
    Files.writeString(
        out.resolve("background-export.json"),
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
    ImGui.begin("Background resource export reference");
    ImGui.text("Reviewed package staging");
    ImGui.end();
    dialog.draw();
    imports.draw();
    ImGui.render();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void hold(float[] at) {
    require(at != null, "Missing ready Export Cancel control");
    var io = ImGui.getIO(); io.addMousePosEvent(at[0], at[1]); frame();
    io.addMouseButtonEvent(0, true); frame();
  }

  private static void click(float[] at) {
    require(at != null, "Missing native export control");
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
    require(dialog.pathText().equals(value), "Native export path changed");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
