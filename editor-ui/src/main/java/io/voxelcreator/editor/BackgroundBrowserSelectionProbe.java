package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred real tree/manager selection controls with held worker and owner-delivery boundaries. */
final class BackgroundBrowserSelectionProbe {
  private static ResourcePanel resources;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode :
        List.of(
            "single", "range", "empty", "manager", "cancel", "escape", "retry", "raw", "close")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      try (var state = new EditorState(new UserConfig(), false)) {
        require(
            state.change(
                p -> {
                  for (int i = 0; i < 3; i++) {
                    var n = new Noise();
                    n.id = String.valueOf((char) ('a' + i));
                    n.algorithm.key = "CONSTANT";
                    n.algorithm.parameters.clear();
                    n.algorithm.parameters.put("value", new double[] {.125, .375, .875}[i]);
                    p.noise2D.add(n);
                  }
                  var voxel = new VoxelType();
                  voxel.id = "solid";
                  p.voxelTypes.add(voxel);
                }),
            "Browser background fixture");
        state.document.save(directory.resolve("before.json"));
        byte[] original = Files.readAllBytes(directory.resolve("before.json"));
        UUID a = state.project().noise2D.getFirst().uuid,
            b = state.project().noise2D.get(1).uuid,
            c = state.project().noise2D.getLast().uuid,
            voxel = state.project().voxelTypes.getFirst().uuid;
        state.select("noise2D", a);
        ((Noise) state.draft).algorithm.parameters.put("value", .625);
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        Files.writeString(directory.resolve("preferences-before.json"), preferences.toString());
        var tasks = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(mode.equals("retry"));
        state.document.setValidator(
            p -> {
              if (fail.getAndSet(false)) throw new AssertionError("selection worker fixture");
              return NoiseCompiler.validate(p);
            });
        try (var properties = new PropertyPanel(state)) {
          resources = new ResourcePanel(state, properties, tasks::add, ui::add, () -> false);
          try {
            frames(4);
            if (mode.equals("range")) {
              click(resources.probeSelectionRow(a));
              modifierClick(c, ImGuiKey.ModShift);
            } else if (mode.equals("empty")) modifierClick(a, ImGuiKey.ModCtrl);
            else if (mode.equals("manager")) {
              resources.showManager();
              frames(4);
              ImGui.setWindowPos("Voxel Manager", 780, 0, ImGuiCond.Always);
              frames(4);
              click(resources.probeSelectionRow(voxel));
            } else click(resources.probeSelectionRow(b));
            require(resources.probeSelectionPending(), "Browser selection bypassed modified draft");
            click(resources.probeSelectionButton("APPLY"));
            var selection = resources.probeSelectionSession();
            require(
                selection.preparing() && !selection.busy() && tasks.size() == 1,
                "Single cancellable selection task");
            long work = resources.selectionGuard.requestVersion();
            for (int i = 0; i < 12; i++) {
              frame();
              require(
                  selection.preparing()
                      && state.draft == draft
                      && a.equals(state.selected)
                      && state.project().noise2D.getFirst().algorithm.parameters.get("value")
                          == .125,
                  "Preparation replaced current document/draft/selection");
              require(
                  resources.selectionGuard.requestVersion() == work,
                  "Selection gesture ID changed during unchanged work");
            }
            state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
            require(
                selection.preparing() && state.draft == draft && a.equals(state.selected),
                "Worker published before UI delivery");
            if (mode.equals("cancel")) click(resources.probeSelectionButton("CANCEL"));
            else if (mode.equals("escape")) {
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
              frame();
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
              frames(3);
            } else if (mode.equals("raw"))
              state.project().noise2D.getLast().algorithm.parameters.put("value", .75);
            else if (mode.equals("close")) resources.close();
            ui.remove().run();
            frames(3);
            if (mode.equals("retry")) {
              require(
                  selection.pending()
                      && !selection.preparing()
                      && state.draft == draft
                      && selection.progress().contains("failed")
                      && tasks.isEmpty(),
                  "Selection failure/retry state");
              click(resources.probeSelectionButton("APPLY"));
              state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
              require(
                  a.equals(state.selected) && state.draft == draft,
                  "Retry published before UI delivery");
              ui.remove().run();
              frames(3);
            }
            boolean applied =
                List.of("single", "range", "empty", "manager", "retry").contains(mode);
            UUID active =
                !applied
                    ? a
                    : mode.equals("range")
                        ? c
                        : mode.equals("empty") ? null : mode.equals("manager") ? voxel : b;
            var expected =
                !applied
                    ? List.of(a)
                    : mode.equals("range")
                        ? List.of(a, b, c)
                        : mode.equals("empty") ? List.<UUID>of() : List.of(active);
            require(
                !selection.pending()
                    && !selection.preparing()
                    && !selection.busy()
                    && Objects.equals(active, state.selected)
                    && expected.equals(List.copyOf(state.multiSelection)),
                "Terminal browser selection");
            require(
                state.project().noise2D.getFirst().algorithm.parameters.get("value")
                    == (applied ? .625 : .125),
                "Selection applied wrong values");
            if (!applied)
              require(
                  state.draft == draft
                      && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft)),
                  "Cancelled/stale selection lost private input");
            state.document.save(directory.resolve("after.json"));
            if (applied) {
              var accepted = ProjectIO.JSON.valueToTree(state.project());
              state.document.undo();
              state.document.save(directory.resolve("undone.json"));
              require(
                  state.project().noise2D.getFirst().algorithm.parameters.get("value") == .125
                      && Objects.equals(active, state.selected),
                  "Selection one Undo or selection history isolation");
              state.document.redo();
              require(
                  accepted.equals(ProjectIO.JSON.valueToTree(state.project())), "Selection Redo");
              state.document.save(directory.resolve("redone.json"));
            }
            require(
                preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                    && Arrays.equals(
                        original, Files.readAllBytes(directory.resolve("before.json"))),
                "Selection changed preferences/original bytes");
            Files.writeString(
                directory.resolve("preferences.json"),
                ProjectIO.JSON.writeValueAsString(state.user));
            cases.add(
                Map.of(
                    "mode",
                    mode,
                    "applied",
                    applied,
                    "responsiveFrames",
                    12,
                    "selectionCount",
                    expected.size(),
                    "privateUntilPublication",
                    true,
                    "selectionOutsideHistory",
                    true,
                    "oneUndoRedo",
                    applied,
                    "preferencesRetained",
                    true,
                    "originalBytesRetained",
                    true));
          } finally {
            resources.close();
            frames(2);
          }
        }
      }
    }
    Files.writeString(
        out.resolve("background-browser-selection.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static void modifierClick(UUID id, int modifier) {
    ImGui.getIO().addKeyEvent(modifier, true);
    frame();
    click(resources.probeSelectionRow(id));
    ImGui.getIO().addKeyEvent(modifier, false);
    frames(3);
  }

  private static void click(float[] at) {
    require(at != null, "Browser control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
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
    ImGui.getIO().setDisplaySize(1600, 1000);
    ImGui.getIO().setFontGlobalScale(1);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(700, 850, ImGuiCond.Always);
    resources.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
