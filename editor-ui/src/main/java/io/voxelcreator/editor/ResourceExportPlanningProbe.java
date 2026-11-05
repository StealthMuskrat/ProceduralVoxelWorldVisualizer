package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deferred actual export modal controls across held catalog/closure and owner-delivery boundaries.
 */
final class ResourceExportPlanningProbe {
  private static ResourcePackageDialog dialog;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    ImGui.getIO().setDisplaySize(1400, 1000);
    for (String mode :
        List.of("initial", "selection", "reload", "cancel", "escape", "retry", "close", "ready-cancel", "ready-selection-cancel", "ready-reload-escape", "ready-close")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      var target = directory.resolve("selection.pvresources.json");
      var prior = new ProjectData();
      prior.noise2D.add(noise("prior", .25));
      String priorBytes =
          ProjectIO.JSON.writeValueAsString(Map.of("packageVersion", 1, "resources", prior));
      Files.writeString(target, priorBytes);
      Files.writeString(directory.resolve("prior-package.json"), priorBytes);
      try (var state = new EditorState(new UserConfig(), false)) {
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.put("Resources", 1f);
        require(state.change(ResourceExportPlanningProbe::fixture), "Planning fixture rejected");
        state.document.save(directory.resolve("source.json"));
        var sourceBytes = Files.readAllBytes(directory.resolve("source.json"));
        state.select("noise2D", state.project().noise2D.getLast().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var draft = state.draft;
        var privateValue = ProjectIO.JSON.valueToTree(draft);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var tasks = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var reject = new AtomicBoolean(mode.equals("retry"));
        Executor executor =
            task -> {
              if (reject.get()) throw new RejectedExecutionException("held catalog fixture");
              tasks.add(task);
            };
        var resources =
            new ResourcePanel(state, new PropertyPanel(state), executor, ui::add, () -> false);
        var imports = new ResourceImportPanel(state, () -> false, () -> {});
        var files = new ResourcePackageFiles();
        try (var workspace =
            new ResourcePackageDialog(
                state,
                resources,
                imports,
                () -> false,
                files,
                executor,
                ui::add,
                (source, destination) ->
                    ImportEditSession.prepare(destination, files.read(source)))) {
          dialog = workspace;
          require(dialog.open("Export"), "Background export opening");
          frames(4);
          var session = resources.exportView().session();
          require(
              session.preview() == null && !resources.canExport(),
              "Initial catalog prepared on owner");
          if (mode.equals("retry")) {
            require(session.canRetryPlan(), "Scheduler failure lost retry");
            reject.set(false);
            click(dialog.control("reload"));
          }
          if (mode.equals("selection") || mode.equals("reload") || mode.equals("ready-selection-cancel") || mode.equals("ready-reload-escape")) {
            finish(state, tasks);
            ui.remove().run();
            frames(4);
            click(dialog.control("included"));
            require(resources.canExport(), "Initial Included review");
            if (mode.equals("selection") || mode.equals("ready-selection-cancel")) {
              click(dialog.control("choices"));
              var view = resources.exportView();
              click(view.probeControl(state.project().noise2D.getLast().uuid.toString()));
              click(view.probeControl(state.project().noise2D.getFirst().uuid.toString()));
            } else {
              require(
                  state.change(
                      p -> {
                        p.project.seed = 18;
                        p.noise2D.getFirst().algorithm.parameters.put("value", .25);
                      }),
                  "Reload edit");
              frames(3);
              click(dialog.control("reload"));
            }
          }
          var held = session.preview();
          for (int i = 0; i < 12; i++) {
            frame();
            require(
                session.planning() && !resources.canExport() && session.preview() == held,
                "Held preparation changed review");
            require(
                DialogBounds.contains(dialog.layout.bounds(), dialog.chrome().body()),
                "Preparation body escaped modal");
            for (var rect : dialog.chrome().actions().values())
              require(
                  DialogBounds.contains(dialog.layout.bounds(), rect),
                  "Preparation action escaped modal");
          }
          finish(state, tasks);
          require(
              session.preview() == held && !resources.canExport(),
              "Worker adopted before UI delivery");
          var request = session.planningToken();
          if (mode.startsWith("ready-")) {
            if (!mode.equals("ready-reload-escape") && !mode.equals("ready-close")) hold(dialog.control("cancel"));
            ui.remove().run();
            require(session.planning() && session.planningToken() == request && session.preview() == held
                && !resources.canExport() && dialog.pending() && dialog.error().isEmpty(),
                "Ready catalog/closure installed or changed current cancellation ownership");
            if (mode.equals("ready-reload-escape")) {
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true); frame();
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(3);
            } else if (mode.equals("ready-close")) { dialog.close(); frames(3); }
            else { ImGui.getIO().addMouseButtonEvent(0, false); frames(3); }
            require(!dialog.pending() && session.preview() == held, "Ready retirement installed an obsolete closure");
          } else {
            if (mode.equals("cancel")) click(dialog.control("cancel"));
            else if (mode.equals("escape")) {
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
              frame();
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
              frames(3);
            } else if (mode.equals("close")) dialog.close();
            ui.remove().run();
          }
          boolean published = List.of("initial", "selection", "reload", "retry").contains(mode);
          if (published) {
            require(session.planning() && session.preview() == held && !resources.canExport(),
                "Delivery installed or acknowledged the replacement Included plan");
            frame(); // Current dialog controls finish before accepting its shown request.
            require(
                session.preview() != held && !resources.canExport(),
                "Replacement inherited Included authority");
            frames(4);
            click(dialog.control("included"));
            require(resources.canExport(), "Fresh Included review missing");
            require(
                session.preview().included().size() == (mode.equals("selection") ? 1 : 2),
                "Dependency closure differs");
            click(dialog.control("location"));
            path(target.toString());
            click(dialog.control("submit"));
            require(
                dialog.exportRunning() && Files.readString(target).equals(priorBytes),
                "File staged/published outside explicit boundary");
            finish(state, tasks);
            require(Files.readString(target).equals(priorBytes), "Worker replaced destination");
            ui.remove().run();
            frames(4);
            require(!dialog.pending(), "Export publication did not finish");
          } else
            require(
                !dialog.pending() && Files.readString(target).equals(priorBytes),
                "Retired preview wrote output");
          require(
              state.draft == draft
                  && privateValue.equals(ProjectIO.JSON.valueToTree(state.draft))
                  && state.seedInputs.text("seedOffset").equals("-")
                  && preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
              "Planning changed private/preference values");
          require(
              Arrays.equals(sourceBytes, Files.readAllBytes(directory.resolve("source.json"))),
              "Planning changed source bytes");
          state.document.save(directory.resolve("source-after.json"));
          Files.writeString(
              directory.resolve("preferences.json"), ProjectIO.JSON.writeValueAsString(state.user));
          cases.add(
              Map.of(
                  "mode",
                  mode,
                  "published",
                  published,
                  "responsiveFrames",
                  12,
                  "freshIncludedRequired",
                  true,
                  "privateRetained",
                  true,
                  "preferencesRetained",
                  true,
                  "originalBytesRetained",
                  true,
                  "readyRetired",
                  mode.startsWith("ready-")));
        }
      }
    }
    Files.writeString(
        out.resolve("export-planning.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private static void fixture(ProjectData p) {
    p.project.seed = 17;
    var base = noise("base", .125);
    var derived = noise("derived", 0);
    var item = ProcessingRegistry.create("ADD", "offset", 2);
    var ref = new Binding();
    ref.sourceKind = "Noise Generator Reference";
    ref.noiseGeneratorUuid = base.uuid;
    item.inputs.put("a", ref);
    item.inputs.put("b", Binding.constant(.5));
    derived.processing.add(item);
    derived.outputBinding = Binding.earlier(item.uuid);
    p.noise2D.add(base);
    p.noise2D.add(derived);
    p.project.rootOrder.put("noise2D", new ArrayList<>(List.of(base.uuid, derived.uuid)));
  }

  private static void finish(EditorState state, ArrayDeque<Runnable> tasks) throws Exception {
    while (!tasks.isEmpty()) {
      var task = tasks.remove();
      if (task instanceof Future<?> future && future.isCancelled()) continue;
      state.background.submit(task).get(10, TimeUnit.SECONDS);
      return;
    }
    throw new AssertionError("No pending background preparation");
  }

  private static void path(String text) {
    click(dialog.control("Path"));
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(4);
  }

  private static void hold(float[] at) {
    require(at != null, "Missing ready preview Cancel control");
    var io = ImGui.getIO(); io.addMousePosEvent(at[0], at[1]); frame();
    io.addMouseButtonEvent(0, true); frame();
  }

  private static void click(float[] at) {
    require(at != null, "Planning control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(4);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    dialog.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
