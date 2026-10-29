package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred production-panel export review, protected path, failure/retry and window lifetime. */
public final class ProfileExportProbe {
  private static ProfilePanel panel;
  private static EditorState current;
  private static final ImBoolean open = new ImBoolean(true);
  private static boolean collapsed;
  private static final Viewports.StreamingStatistics EMPTY =
      new Viewports.StreamingStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, false);

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1500, 1600);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        current = state;
        require(
            state.change(
                p -> {
                  var noise = new Noise();
                  noise.id = "field";
                  noise.algorithm.key = "CONSTANT";
                  noise.algorithm.parameters.clear();
                  noise.algorithm.parameters.put("value", .125);
                  p.noise2D.add(noise);
                }),
            "Fixture rejected");
        Path projectFile = out.resolve("protected-project.json");
        state.document.save(projectFile);
        byte[] projectBytes = Files.readAllBytes(projectFile);
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var user = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        panel =
            new ProfilePanel(
                state,
                target -> {
                  throw new AssertionError("Unexpected navigation");
                });
        panel.probeWorkControls();
        var first = state.engine.profiler().start(Profiler.Options.defaults());
        state
            .engine
            .profiler()
            .scope(7, state.project().project.uuid, null)
            .gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 123, "reviewed,雪");
        state.engine.profiler().stop();
        frames(3);
        long queries = panel.rowQueries();
        frames(4);
        require(panel.rowQueries() == queries, "Stopped snapshot rows rebuilt");
        path(projectFile);
        click("prepare-export");
        var review = panel.exportReview();
        var frozen = review.snapshot();
        require(
            frozen.captureId().equals(first.snapshot().captureId()) && frozen.samples().size() == 1,
            "Prepare did not own displayed capture");
        click("export-json");
        require(
            review.available() && Arrays.equals(projectBytes, Files.readAllBytes(projectFile)),
            "Capture export changed the active project or consumed rejected review");
        require(
            state.events.snapshot().stream()
                .anyMatch(
                    e ->
                        e.code().equals("PROFILE_EXPORT_FAILED")
                            && e.subsystem() == EditorEvents.Subsystem.IMPORT_EXPORT),
            "Export failure was not logged");
        var next = state.engine.profiler().start(Profiler.Options.defaults());
        state
            .engine
            .profiler()
            .scope(8, state.project().project.uuid, null)
            .gauge(Profiler.Kind.CACHE_BYTES, Profiler.Unit.BYTES, 999, "replacement");
        state.engine.profiler().stop();
        collapsed = true;
        frames(2);
        require(
            panel.exportReview() == review && panel.displayedSnapshot() == frozen,
            "Collapsed window processed replacement snapshot");
        collapsed = false;
        frames(2);
        require(
            panel.displayedSnapshot().captureId().equals(next.snapshot().captureId())
                && panel.exportReview().snapshot() == frozen,
            "Live replacement retargeted review");
        state.engine.profiler().clear();
        frames(2);
        require(
            panel.displayedSnapshot() == null
                && panel.exportReview() == review
                && review.available(),
            "Clear discarded independent export review");
        Path target = out.resolve("reviewed.json");
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep"), "retained");
        path(target);
        click("export-json");
        require(
            review.available()
                && panel.exportReview() == review
                && Files.readString(target.resolve("keep")).equals("retained"),
            "Write failure lost review");
        Files.delete(target.resolve("keep"));
        Files.delete(target);
        click("export-json");
        require(!review.available(), "Accepted review was not consumed");
        var written = ProjectIO.JSON.readTree(target.toFile());
        require(
            written.path("captureId").asText().equals(frozen.captureId().toString())
                && written.path("samples").size() == 1
                && written.path("samples").get(0).path("value").asLong() == 123,
            "Retry exported a later capture");
        // A companion API export allows independent JSON/CSV replay of this exact owned token.
        new ProfileExportSession(frozen)
            .write(out.resolve("reviewed.csv").toString(), true, projectFile);
        byte[] accepted = Files.readAllBytes(target);
        click("export-json");
        require(
            Arrays.equals(accepted, Files.readAllBytes(target)),
            "Consumed UI review repeated a write");
        var third = state.engine.profiler().start(Profiler.Options.defaults());
        state
            .engine
            .profiler()
            .scope(8, state.project().project.uuid, null)
            .gauge(Profiler.Kind.CACHE_BYTES, Profiler.Unit.BYTES, 999, "csv replacement");
        state.engine.profiler().stop();
        open.set(false);
        frames(2);
        require(panel.displayedSnapshot() == null, "Closed window refreshed controls");
        open.set(true);
        path(out.resolve("replacement.csv"));
        click("prepare-export");
        var replacement = panel.exportReview();
        require(
            replacement != review
                && replacement.snapshot().captureId().equals(third.snapshot().captureId()),
            "Explicit Prepare did not replace review");
        click("export-csv");
        require(
            !replacement.available()
                && Files.readString(out.resolve("replacement.csv")).contains(",999,BYTES,"),
            "Actual CSV button did not publish reviewed measurements");
        new ProfileExportSession(replacement.snapshot())
            .write(out.resolve("replacement.json").toString(), false, projectFile);
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && user.equals(ProjectIO.JSON.valueToTree(state.user))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.draft == draft
                && draft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset"))
                && Arrays.equals(projectBytes, Files.readAllBytes(projectFile)),
            "Export changed document/private state");
        try (var files = Files.walk(out)) {
          require(
              files.noneMatch(p -> p.getFileName().toString().startsWith(".profile-")),
              "Temporary export leaked");
        }
        Files.writeString(
            out.resolve("profile-export-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("protectedProject", true),
                    Map.entry("reviewSurvivedReplacementAndClear", true),
                    Map.entry("failedWriteRetry", true),
                    Map.entry("acceptedConsumed", true),
                    Map.entry("explicitPrepareReplaced", true),
                    Map.entry("collapsedAndClosed", true),
                    Map.entry("cachedRows", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("actualJsonAndCsvButtons", true))));
        System.out.println(
            "PROFILE_EXPORT_NATIVE_PASS owned review / protected project / retry / JSON+CSV /"
                + " private input");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void path(Path value) {
    panel.probeExportPath(value.toString());
    frames(3);
  }

  private static void click(String control) {
    float[] center = panel.probeWorkControl(control);
    require(center != null, "Missing export control " + control);
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    frame(); // Deliver the production Error Center queue after the release-frame button action.
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    current.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1300, 1500, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
    panel.draw(open, EMPTY);
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
