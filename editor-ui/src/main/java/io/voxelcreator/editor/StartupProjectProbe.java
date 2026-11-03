package io.voxelcreator.editor;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Deferred real Desktop explicit-project startup, guarded before first frame and during teardown.
 */
final class StartupProjectProbe {
  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var rows = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("SUCCESS", "MALFORMED", "SHUTDOWN")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      System.setProperty("voxel.configDir", directory.toString());
      var user = new UserConfig();
      user.workspace.width = 640;
      user.workspace.height = 480;
      user.save(directory.resolve("user-config.json"));
      var project = new ProjectData();
      project.project.seed = 99;
      var noise = new Noise();
      noise.id = "launched";
      noise.algorithm.key = "CONSTANT";
      noise.algorithm.parameters.clear();
      noise.algorithm.parameters.put("value", .75);
      project.noise2D.add(noise);
      var file = directory.resolve("project with spaces-\u03b1.json").toAbsolutePath();
      Files.writeString(
          file,
          mode.equals("MALFORMED") ? "{ malformed" : ProjectIO.JSON.writeValueAsString(project));
      String source = Files.readString(file);
      var arguments = new String[] {file.toString()};
      var app = new Desktop(arguments);
      arguments[0] = directory.resolve("wrong-mutated-argument.json").toString();
      var active = app.startupState().project();
      var lifetime = app.startupState().document.sessionToken();
      var release = new CountDownLatch(1);
      var entered = new CountDownLatch(1);
      var worker = new AtomicReference<Thread>();
      var phase = new AtomicReference<String>("");
      var uiThread = Thread.currentThread();
      int[] frames = {0}, blockedFrames = {0};
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      app.observeStartup(
          stage -> {
            var state = app.startupState();
            if (stage.equals("backends-ready")) {
              state.document.setValidator(
                  candidate -> {
                    worker.set(Thread.currentThread());
                    entered.countDown();
                    try {
                      if (!release.await(30, TimeUnit.SECONDS))
                        throw new IllegalStateException("Desktop launch gate timeout");
                    } catch (InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException("Desktop launch gate cancelled", interrupted);
                    }
                    return NoiseCompiler.validate(candidate);
                  });
            } else if (stage.equals("services-ready")) {
              require(
                  state.project() == active
                      && state.document.sessionToken() == lifetime
                      && state.document.path() == null
                      && !state.document.dirty(),
                  "Desktop prepared a launch file on the startup thread");
            } else if (stage.equals("presented")) {
              require(System.nanoTime() < deadline, "Desktop launch frame deadline");
              frames[0]++;
              var current = app.startupProjectPhase();
              if (release.getCount() != 0 && !mode.equals("MALFORMED")) {
                require(
                    state.project() == active
                        && state.document.path() == null
                        && current == ProjectFileWorkflow.Phase.LOADING,
                    "Desktop launch replaced before gate release");
                blockedFrames[0]++;
              }
              if (mode.equals("MALFORMED")) {
                if (current == ProjectFileWorkflow.Phase.OPEN) {
                  require(
                      state.project() == active && state.document.path() == null,
                      "Rejected Desktop launch lost empty state");
                  phase.set(current.name());
                  app.stopStartupProbe();
                }
              } else if (frames[0] >= 3 && entered.getCount() == 0) {
                if (mode.equals("SHUTDOWN")) {
                  require(entered.getCount() == 0, "Shutdown never reached background compiler");
                  phase.set(current.name());
                  app.stopStartupProbe();
                } else {
                  release.countDown();
                  if (state.project().project.seed == 99) {
                    require(
                        state.document.path().equals(file)
                            && !state.document.dirty()
                            && state.document.sessionToken() != lifetime
                            && current == ProjectFileWorkflow.Phase.IDLE,
                        "Desktop launch checkpoint or lifecycle incorrect");
                    phase.set(current.name());
                    app.stopStartupProbe();
                  }
                }
              }
            }
          });
      try {
        app.startDesktop();
      } finally {
        release.countDown();
      }
      require(
          app.startupReleased() && !app.startupVisible() && app.getHandle() == 0,
          "Desktop launch leaked native/worker ownership");
      var state = app.startupState();
      boolean opened = mode.equals("SUCCESS");
      require(
          state.project().project.seed == (opened ? 99 : 0)
              && state.project().noise2D.size() == (opened ? 1 : 0),
          "Late launch result published during teardown");
      require(
          mode.equals("MALFORMED") || worker.get() != uiThread, "Launch compiler used UI thread");
      require(source.equals(Files.readString(file)), "Desktop launch rewrote supplied project");
      Files.writeString(
          directory.resolve("active.json"), ProjectIO.JSON.writeValueAsString(state.project()));
      rows.add(
          Map.of(
              "mode",
              mode,
              "frames",
              frames[0],
              "blockedFrames",
              blockedFrames[0],
              "phaseBeforeClose",
              phase.get(),
              "released",
              true,
              "seed",
              state.project().project.seed,
              "workerOffUi",
              mode.equals("MALFORMED") || worker.get() != uiThread,
              "sourceBytesPreserved",
              true));
    }
    Files.writeString(
        out.resolve("startup-project-native.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "realDesktop", true, "cases", rows)));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
