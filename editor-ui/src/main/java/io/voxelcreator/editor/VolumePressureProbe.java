package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;

/**
 * Controlled pressure time, real production workers, native textures and the actual Resume button.
 */
final class VolumePressureProbe {
  private static EditorState state;
  private static Viewports views;
  private static long clock;
  private static int maximum;

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    var report = new LinkedHashMap<String, Object>();
    var stages = new ArrayList<Map<String, Object>>();
    glfwShowWindow(handle);
    glfwFocusWindow(handle);
    glfwPollEvents();
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      var p = state.user.performance;
      p.sceneCpuMiB = 256;
      p.sceneGpuMiB = 16;
      p.cacheMiB = 64;
      p.uploadKiB = 16;
      p.maxPreviewDimension = 256;
      p.previewCellBudget = 256L * 256 * 256;
      state.user.editor.opportunitiesPerSecond = 20;
      require(
          state.change(
              project -> {
                var noise = new Noise();
                noise.id = "volume-pressure";
                noise.dimension = 3;
                noise.algorithm.key = "CONSTANT";
                var item = ProcessingRegistry.create("EXPRESSION", "density", 3);
                item.parameters.put("expression", ProjectIO.JSON.valueToTree("1"));
                noise.processing.add(item);
                noise.outputBinding = Binding.earlier(item.uuid);
                project.noise3D.add(noise);
              }),
          "Pressure project");
      var id = state.project().noise3D.getFirst().uuid;
      var configured = ProjectIO.JSON.valueToTree(p);
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      clock = System.nanoTime();
      maximum = 0;
      views = new Viewports(state, new PropertyPanel(state), handle);
      try {
        views.probeVolumeClock(clock);
        views.probeVolumeStream(id, 64, 16, 16, 16);
        ready(64, -32, -32, -32);
        stages.add(stage("configured", 1, out));
        long generation = views.probeVolumeMemory().generation();
        long stamp = views.probeSubmissionStamp(1),
            version = views.probeVolumeStatistics().version();
        bad(1);
        ready(64, -32, -32, -32);
        require(
            stamp != views.probeSubmissionStamp(1)
                && version < views.probeVolumeStatistics().version()
                && views.probeVolumeTextureSize() == 32
                && views.probeVolumeSampleStep() == 2,
            "First pressure step did not reduce display sampling before extent");
        stages.add(stage("coarse-grid", 1, out));
        version = views.probeVolumeStatistics().version();
        p.uploadKiB = 1;
        bad(2);
        until(() -> views.probeVolumeStatistics().pending());
        require(
            views.probeVolumeResidentSize() == 64
                && views.probeVolumeStatistics().version() == version
                && views.probeVolumeOrigin().equals(new Vector3d(-32)),
            "Partial reduced window published");
        ready(32, 0, 0, 0);
        stages.add(stage("reduced", 1, out));
        p.uploadKiB = 16;
        require(
            views.probeVolumeMemory().generation() == generation
                && views.probeVolumeMemory().compilations() == 1,
            "Pressure changed compiled source");
        stamp = views.probeSubmissionStamp(1);
        bad(3);
        frames(2);
        require(
            views.probeVolumeMemory().bytes() == 0
                && views.probeVolumeMemory().limit() == 0
                && stamp == views.probeSubmissionStamp(1),
            "Cache pressure resampled or retained tiles");
        stages.add(stage("cache-released", 1, out));
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Fallback changed document");
        report.put("initialViewOnly", true);

        state.user.editor.autoRegenerate = false;
        require(
            state.change(
                project ->
                    project
                        .noise3D
                        .getFirst()
                        .processing
                        .getFirst()
                        .parameters
                        .put("expression", ProjectIO.JSON.valueToTree("0"))),
            "Manual committed edit");
        before = ProjectIO.JSON.valueToTree(state.project());
        revision = state.revision;
        history = state.document.historyBytes();
        bad(4);
        views.probeVolumeTravel(48, 16, 16);
        frames(4);
        require(
            stamp == views.probeSubmissionStamp(1)
                && views.probeVolumeReservation() == 0
                && !views.probeVolumeStatistics().pending()
                && views.probeVolumeOrigin().equals(new Vector3d()),
            "Deferred window allocated/published");
        stages.add(stage("deferred", 1, out));
        good(3);
        until(() -> views.probeVolumeStatistics().pending());
        version = views.probeVolumeStatistics().version();
        bad(4);
        bad(5);
        frames(3);
        require(
            !views.probeVolumeStatistics().pending()
                && views.probeVolumeReservation() == 0
                && views.probeVolumeStatistics().version() == version
                && views.probeVolumeOrigin().equals(new Vector3d()),
            "Pause accepted incomplete replacement");
        stages.add(stage("paused-upload", 1, out));
        stamp = views.probeSubmissionStamp(1);
        views.probeVolumeTravel(-16, 16, 16);
        views.probeVolumeRegenerate();
        clock += 20_000_000_000L;
        frames(3);
        require(
            views.probeVolumePressureLevel() == 5
                && stamp == views.probeSubmissionStamp(1)
                && views.probeVolumeMemory().generation() == generation,
            "Paused Regenerate or low pressure resumed work");
        clickResume();
        require(views.probeVolumePressureLevel() == 3, "Resume did not return to reduced quality");
        ready(32, -32, 0, 0);
        stages.add(stage("resumed-new-source", 0, out));
        require(
            views.probeVolumeMemory().generation() > generation
                && views.probeVolumeMemory().compilations() == 2,
            "Explicit paused regeneration lost source adoption");
        good(2);
        good(1);
        ready(64, -64, -32, -32);
        good(0);
        ready(64, -64, -32, -32);
        stages.add(stage("restored", 0, out));

        // A newly tightened CPU limit rejects the unit grid; level-1 display sampling can
        // recover it before reducing visibility. Neither a failed candidate nor an automatic
        // quality change edits preferences.
        p.sceneCpuMiB = 1;
        views.probeVolumeTravel(16, 16, 16);
        until(() -> views.probeVolumeMessage().contains("CPU MiB"));
        require(
            views.probeVolumeResidentSize() == 64 && !views.probeVolumeStatistics().pending(),
            "CPU rejection published");
        bad(1);
        ready(64, -32, -32, -32);
        stages.add(stage("cpu-recovered", 0, out));
        require(
            views.probeVolumeMemory().bytes() + views.probeVolumePlan().workingBytes() <= 1L << 20,
            "Recovered CPU plan exceeds tightened limit");
        p.sceneCpuMiB = 256;
        good(0);
        ready(64, -32, -32, -32);
        p.uploadKiB = 1;
        views.probeVolumeTravel(48, 16, 16);
        until(() -> views.probeVolumeStatistics().pending());
        version = views.probeVolumeStatistics().version();
        for (int level = 1; level <= 4; level++) bad(level);
        until(() -> views.probeVolumeStatistics().pending());
        p.sceneGpuMiB = 1;
        until(() -> views.probeVolumeMessage().contains("GPU MiB"));
        require(
            !views.probeVolumeStatistics().pending()
                && views.probeVolumeStatistics().version() == version
                && views.probeVolumeResidentSize() == 64,
            "Mid-upload GPU limit failed to cancel candidate");
        clock += 1_000_000_000L;
        frame();
        require(
            views.probeVolumePressureLevel() == 5, "Deferred budget rejection did not escalate");
        p.sceneGpuMiB = 16;
        p.uploadKiB = 16;
        clickResume();
        ready(32, 32, 0, 0);
        good(2);
        good(1);
        ready(64, 0, -32, -32);
        good(0);
        ready(64, 0, -32, -32);
        stages.add(stage("budget-retry", 0, out));
        report.put("deferredBudgetEscalated", true);

        // A queued window reserves its whole peak before its worker starts. Critical pressure
        // reclaims that queue slot and reservation while preserving accepted GPU data.
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var jobs = new ArrayList<Future<?>>();
        try {
          for (int i = 0; i < 2; i++)
            jobs.add(
                state.background.submit(
                    () -> {
                      entered.countDown();
                      try {
                        if (!release.await(20, TimeUnit.SECONDS))
                          throw new AssertionError("Worker timeout");
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
          require(entered.await(5, TimeUnit.SECONDS), "Workers not occupied");
          views.probeVolumeTravel(80, 16, 16);
          until(() -> views.probeVolumeReservation() != 0);
          var queued = views.streamingStatistics();
          require(
              queued.cpuBytes() + queued.reserved() >= VolumeWindows.workingBytes(64),
              "Queued window peak not visible in profiler reservation");
          report.put(
              "queuedMemory",
              Map.of(
                  "cpuBytes",
                  queued.cpuBytes(),
                  "reserved",
                  queued.reserved(),
                  "peak",
                  views.probeVolumeReservation(),
                  "queueSize",
                  state.background.getQueue().size()));
          version = views.probeVolumeStatistics().version();
          for (int level = 1; level <= 5; level++) bad(level);
          require(
              views.probeVolumeReservation() == 0 && state.background.getQueue().isEmpty(),
              "Paused queued window not reclaimed");
        } finally {
          release.countDown();
          for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        frames(3);
        require(
            views.probeVolumeStatistics().version() == version, "Late canceled worker published");
        stages.add(stage("paused-queue", 0, out));
        clickResume();
        ready(32, 64, 0, 0);
        stages.add(stage("queue-resumed", 0, out));
        require(
            views.probeVolumeConfiguredSize() == 64
                && configured.equals(ProjectIO.JSON.valueToTree(p)),
            "Pressure overwrote configured preferences");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Pressure changed document/history");
        report.put("postEditViewOnly", true);
        report.put("configuredPreserved", true);
        report.put("midUploadCanceled", true);
        report.put("queuedReservationReclaimed", true);
        report.put("explicitResume", true);
        report.put("stages", stages);
        report.put("maximumUploadBytes", maximum);
        report.put("formatVersion", 1);
        new ProjectIO().save(out.resolve("volume-pressure-project.json"), state.project());
      } finally {
        views.close();
      }
    }
    require(glGetError() == GL_NO_ERROR, "Pressure GL error");
    Files.writeString(
        out.resolve("volume-pressure-native.json"), ProjectIO.JSON.writeValueAsString(report));
    glfwHideWindow(handle);
    System.out.println(
        "VOLUME_PRESSURE_NATIVE_PASS coarse display samples / reduced extent / released cache /"
            + " deferred windows / paused upload and queue / actual Resume button / source"
            + " retention / budget retry / gradual restoration / view-only");
  }

  private static void bad(int expected) {
    clock += 1_000_000_000L;
    views.probeVolumePressure(clock, .99, .99, 10, true);
    require(views.probeVolumePressureLevel() == expected, "Pressure escalation " + expected);
    frame();
  }

  private static void good(int expected) {
    // A stable interval is measured by the production observer using actual current allocations.
    frames(2);
    clock += 6_000_000_000L;
    frame();
    require(views.probeVolumePressureLevel() == expected, "Pressure restoration " + expected);
  }

  private static void clickResume() {
    frames(2);
    float[] at = views.probeAdmissionControl("volume-resume");
    require(at != null, "Resume button missing");
    ImGui.getIO().addMousePosEvent(at[0], at[1]);
    frame();
    ImGui.getIO().addMouseButtonEvent(0, true);
    frame();
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static Map<String, Object> stage(String name, int value, Path out) throws Exception {
    int side = views.probeVolumeResidentSize(), n = views.probeVolumeTextureSize();
    var origin = views.probeVolumeOrigin();
    var encoding = views.probeVolumeEncoding();
    require(
        encoding.offset() == value
            && encoding.scale() == 1
            && encoding.min() == value
            && encoding.max() == value,
        "Uniform pressure encoding " + name);
    glBindTexture(GL_TEXTURE_3D, views.probeVolumeResidentTexture());
    var texels = BufferUtils.createFloatBuffer(n * n * n);
    glGetTexImage(GL_TEXTURE_3D, 0, GL_RED, GL_FLOAT, texels);
    var bytes = ByteBuffer.allocate(n * n * n * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < texels.capacity(); i++) {
      float actual = texels.get(i);
      require(
          Float.floatToRawIntBits(actual) == 0,
          "Pressure encoded texel changed at " + name + ": " + i);
      bytes.putFloat(actual);
    }
    Files.write(out.resolve(name + ".bin"), bytes.array());
    var memory = views.probeVolumeMemory();
    var row = new LinkedHashMap<String, Object>();
    row.put("name", name);
    row.put("value", value);
    row.put("encoding", encoding);
    row.put("side", side);
    row.put("textureSide", n);
    row.put("sampleStep", views.probeVolumeSampleStep());
    row.put("configured", views.probeVolumeConfiguredSize());
    row.put("level", views.probeVolumePressureLevel());
    row.put("origin", List.of(origin.x, origin.y, origin.z));
    row.put("cacheBytes", memory.bytes());
    row.put("cacheLimit", memory.limit());
    row.put("workingBytes", memory.workingBytes());
    row.put("reserved", views.probeVolumeReservation());
    row.put("generation", memory.generation());
    row.put("compilations", memory.compilations());
    row.put("version", views.probeVolumeStatistics().version());
    row.put("pending", views.probeVolumeStatistics().pending());
    row.put("message", views.probeVolumeMessage());
    var pixel = views.probeSurfacePixel();
    boolean hit = !name.equals("deferred") && !name.equals("paused-upload");
    double transmittance = Math.exp(-(hit ? value : 0) * .01 * side);
    double[] background = {.055, .075, .1};
    for (int c = 0; c < 3; c++) {
      double expected =
          (c == 0 ? 127 * (1 - transmittance) : 0) + 255 * transmittance * background[c];
      require(Math.abs(pixel.get(c) - expected) <= 2, "Pressure optics " + name + ": " + pixel);
    }
    require(pixel.get(3) == 255, "Pressure alpha");
    row.put("pixel", pixel);
    return row;
  }

  private static void ready(int side, int x, int y, int z) {
    until(
        () ->
            views.probeNoiseReady(1)
                && views.probeVolumeResidentSize() == side
                && views.probeVolumeSampleStep() == views.probeVolumePlan().sampleStep()
                && views.probeVolumeOrigin().equals(new Vector3d(x, y, z)));
    frames(2);
  }

  private static void until(BooleanSupplier done) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!done.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end)
        throw new AssertionError("Volume pressure timeout: " + views.probeVolumeMessage());
      Thread.yield();
    }
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    clock += 10_000_000L;
    views.probeVolumeClock(clock);
    glfwPollEvents();
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(950, 900, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(false, ImGuiCond.Always);
    views.draw();
    ImGui.render();
    maximum = Math.max(maximum, views.probeVolumeStatistics().lastBytes());
    require(views.probeVolumeStatistics().lastBytes() <= 16384, "Pressure upload byte budget");
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
