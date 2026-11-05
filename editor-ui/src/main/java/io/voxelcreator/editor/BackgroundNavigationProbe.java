package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production source/Find/reference Apply controls across worker and owner delivery. */
final class BackgroundNavigationProbe {
  private static EditorState state;
  private static PropertyPanel properties;
  private static ResourceFindPanel find;
  private static GuardedNavigation source;
  private static String route;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String kind : List.of("source", "find", "reference")) {
      route = kind;
      for (String mode :
          kind.equals("source")
              ? List.of("success", "cancel", "escape", "retry", "stale", "after-stale", "close", "ready-cancel", "ready-escape")
              : List.of("success", "cancel", "ready-cancel", "ready-escape")) {
        var directory = out.resolve(kind + "-" + mode);
        Files.createDirectories(directory);
        try (var editor = new EditorState(new UserConfig(), false)) {
          state = editor;
          require(
              state.change(
                  p -> {
                    for (String id : List.of("source", "destination")) {
                      var n = new Noise();
                      n.id = id;
                      n.algorithm.key = "CONSTANT";
                      n.algorithm.parameters.clear();
                      n.algorithm.parameters.put("value", id.equals("source") ? .125 : .375);
                      p.noise2D.add(n);
                    }
                  }),
              "Navigation fixture");
          state.document.save(directory.resolve("before.json"));
          byte[] original = Files.readAllBytes(directory.resolve("before.json"));
          state.select("noise2D", state.project().noise2D.getFirst().uuid);
          var editing = state.selected;
          ((Noise) state.draft).algorithm.parameters.put("value", .625);
          var draft = state.draft;
          var privateValues = ProjectIO.JSON.valueToTree(draft);
          var preferences = ProjectIO.JSON.valueToTree(state.user);
          Files.writeString(directory.resolve("preferences-before.json"), preferences.toString());
          var tasks = new ArrayDeque<Runnable>();
          var ui = new ArrayDeque<Runnable>();
          var fresh = new AtomicBoolean(true);
          var fail = new AtomicBoolean(mode.equals("retry"));
          state.document.setValidator(
              p -> {
                if (fail.getAndSet(false)) throw new AssertionError("navigation worker fixture");
                return NoiseCompiler.validate(p);
              });
          properties = new PropertyPanel(state, tasks::add, ui::add);
          find = new ResourceFindPanel(state, properties, System::nanoTime, tasks::add, ui::add);
          var reveals = new int[1];
          source =
              new GuardedNavigation(
                  state,
                  properties,
                  target -> {
                    reveals[0]++;
                    return properties.revealSearch(target);
                  },
                  tasks::add,
                  ui::add);
          var target =
              new EditorEvents.Target(
                  state.project().project.uuid,
                  state.project().noise2D.getLast().uuid,
                  null,
                  null,
                  "",
                  false);
          try {
            if (kind.equals("source"))
              require(
                  source.request(
                          target,
                          () ->
                              fresh.get()
                                  && (!mode.equals("after-stale")
                                      || state
                                              .project()
                                              .noise2D
                                              .getFirst()
                                              .algorithm
                                              .parameters
                                              .get("value")
                                          == .125))
                      == SearchNavigation.Result.NEEDS_DECISION,
                  "Source request");
            else if (kind.equals("reference")) properties.goToControl(target.resource(), null);
            else {
              var filter =
                  new ResourceSearchIndex.Filter(
                      "destination",
                      "noise2D",
                      "All",
                      "noise2D",
                      null,
                      null,
                      false,
                      ResourceSearchIndex.Mode.ID);
              find.filter(filter);
              frames(4);
              var hit = new ResourceSearchIndex(state.project()).search(filter).getFirst();
              click(find.center(hit.key()));
            }
            frames(4);
            click(button("APPLY"));
            require(tasks.size() == 1 && pending(), "Navigation Apply did not queue once");
            long work = dialog().requestVersion();
            for (int i = 0; i < 12; i++) {
              frame();
              require(
                  pending()
                      && state.draft == draft
                      && state.selected.equals(editing)
                      && state.project().noise2D.getFirst().algorithm.parameters.get("value")
                          == .125,
                  "Preparing navigation changed source/draft");
              require(
                  dialog().requestVersion() == work, "Pending gesture identity changed per frame");
            }
            state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
            require(
                pending() && state.draft == draft,
                "Worker published navigation before owner delivery");
            if (mode.startsWith("ready-")) {
              if (mode.equals("ready-cancel")) hold(button("CANCEL"));
              ui.remove().run();
              require(pending() && state.draft == draft && editing.equals(state.selected)
                  && dialog().requestVersion() == work
                  && state.project().noise2D.getFirst().algorithm.parameters.get("value") == .125,
                  "Ready delivery committed or changed Cancel identity before modal input");
              if (mode.equals("ready-cancel")) ImGui.getIO().addMouseButtonEvent(0, false);
              else ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
              frame(); ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(3);
            } else {
              if (mode.equals("cancel")) click(button("CANCEL"));
              else if (mode.equals("escape")) {
                ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
                frame();
                ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
                frames(3);
              } else if (mode.equals("stale")) fresh.set(false);
              else if (mode.equals("close")) source.close();
              ui.remove().run();
              frames(3);
            }
            if (mode.equals("retry")) {
              require(
                  pending() && state.draft == draft && source.progress().contains("failed"),
                  "Failure lost navigation retry/draft");
              require(tasks.isEmpty(), "Failure automatically retried");
              click(button("APPLY"));
              state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
              require(state.draft == draft, "Retry published before owner delivery");
              ui.remove().run();
              frames(3);
            }
            boolean applied = List.of("success", "retry", "after-stale").contains(mode);
            boolean navigated = applied && !mode.equals("after-stale");
            require(
                !pending()
                    && state.selected.equals(navigated ? target.resource() : editing)
                    && state.project().noise2D.getFirst().algorithm.parameters.get("value")
                        == (applied ? .625 : .125),
                "Navigation terminal values");
            if (!applied)
              require(
                  state.draft == draft
                      && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft)),
                  "Retired Apply changed private draft");
            if (kind.equals("source"))
              require(
                  reveals[0] == (navigated ? 1 : 0),
                  "Source reveal replayed or followed stale target");
            state.document.save(directory.resolve("after.json"));
            if (applied) {
              var accepted = ProjectIO.JSON.valueToTree(state.project());
              state.document.undo();
              state.document.save(directory.resolve("undone.json"));
              require(
                  state.project().noise2D.getFirst().algorithm.parameters.get("value") == .125,
                  "Navigation one Undo");
              state.document.redo();
              require(
                  accepted.equals(ProjectIO.JSON.valueToTree(state.project())), "Navigation Redo");
              state.document.save(directory.resolve("redone.json"));
            }
            require(
                preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                    && Arrays.equals(
                        original, Files.readAllBytes(directory.resolve("before.json"))),
                "Navigation wrote preferences or original bytes");
            Files.writeString(
                directory.resolve("preferences.json"),
                ProjectIO.JSON.writeValueAsString(state.user));
            cases.add(
                Map.of(
                    "route",
                    kind,
                    "mode",
                    mode,
                    "applied",
                    applied,
                    "navigated",
                    navigated,
                    "responsiveFrames",
                    12,
                    "privateUntilPublication",
                    true,
                    "oneUndoRedo",
                    applied,
                    "preferencesRetained",
                    true,
                    "originalBytesRetained",
                    true,
                    "readyFrameCancellation",
                    mode.startsWith("ready-")));
          } finally {
            source.close();
            find.close();
            properties.close();
            frames(2);
          }
        }
      }
    }
    Files.writeString(
        out.resolve("background-navigation.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static boolean pending() {
    return route.equals("source")
        ? source.pending()
        : route.equals("find") ? find.navigationPending() : properties.searchBlocked();
  }

  private static NavigationGuardDialog dialog() {
    return route.equals("source")
        ? source.dialog
        : route.equals("find") ? find.guardDialog : properties.referenceDialog;
  }

  private static float[] button(String name) {
    return dialog().button(name);
  }

  private static void hold(float[] at) {
    require(at != null, "Navigation control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]); frame(); io.addMouseButtonEvent(0, true); frame();
  }

  private static void click(float[] at) {
    hold(at); ImGui.getIO().addMouseButtonEvent(0, false); frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.getIO().setDisplaySize(1400, 1000);
    ImGui.getIO().setFontGlobalScale(1);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1400, 1000, ImGuiCond.Always);
    ImGui.begin("Background Navigation Host");
    int root = ImGui.getID("navigation-root");
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(1200, 900, ImGuiCond.Always);
    if (route.equals("find")) find.draw(new ImBoolean(true));
    else properties.draw();
    if (route.equals("source")) source.draw();
    require(root == ImGui.getID("navigation-root"), "Navigation left unbalanced native scopes");
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
