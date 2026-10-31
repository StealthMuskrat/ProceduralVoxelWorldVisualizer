package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.*;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred native Inspector/retained Logs lifetime replay; explicit refresh uses a synchronous
 * fixture.
 */
public final class TraceLifetimeProbe {
  private static EditorState state;
  private static TraceSnapshot shown;
  private static InspectorPanel inspector;
  private static TracePanel inspectorTrace, logTrace;
  private static DiagnosticsPanel logs;
  private static GuardedNavigation guard;
  private static final InspectorCoordinates coordinates = new InspectorCoordinates();
  private static final Map<String, ImString> buffers = new LinkedHashMap<>();
  private static final List<Map<String, Object>> cases = new ArrayList<>();
  private static final List<String> copies = new ArrayList<>();
  private static long event;
  private static int form, refreshes;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDisplaySize(1432, 1132);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(TraceWorkflowProbe::fixture), "Fixture rejected");
        var sourceFile = out.resolve("trace-source.json");
        state.document.save(sourceFile);
        var changed = ProjectIO.copy(state.project());
        changed.worldGenerator.layers.getFirst().defaults.action = "CLEAR";
        var changedFile = out.resolve("trace-current.json");
        new ProjectIO().save(changedFile, changed);
        var bad = out.resolve("bad-project.json");
        Files.writeString(bad, "{}");
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = state.draft;
        ((ProjectData.Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var properties = new PropertyPanel(state);
        guard = new GuardedNavigation(state, properties);
        inspectorTrace = new TracePanel(state, guard, copies::add);
        logTrace = new TracePanel(state, guard, copies::add);
        logs = new DiagnosticsPanel(state, guard::request, copies::add);
        logs.tracePanel(logTrace);
        coordinates.set(new BlockPos(7, 1, -2));
        inspector =
            new InspectorPanel(
                state,
                coordinates,
                TraceLifetimeProbe::buffer,
                (axis, value) -> {
                  coordinates.edit(axis, value);
                  buffer(axis).set(value);
                },
                key -> {});
        shown = capture(new BlockPos(2, 0, 3));
        event = state.publishTrace(shown);
        var original = shown;
        long originalEvent = event, revision = state.revision;
        var details = original.details();
        var raw = raw();
        long rawVersion = coordinates.version();
        captureCases("initial");
        state.document.save(out.resolve("saved-copy.json"));
        captureCases("saved");
        require(!state.document.open(bad).isEmpty(), "Bad project opened");
        captureCases("failed-open");

        // A current trace source link opens the production private-draft guard. Reopening the same
        // project before Discard must retire the old source intent without replacing that draft.
        show(0);
        click(inspectorTrace.goToCenter());
        frames(3);
        require(guard.pending(), "Source link did not open its draft guard");
        require(state.document.open(sourceFile).isEmpty(), "Identical reopen failed");
        frames(2);
        click(guard.button("DISCARD"));
        frames(2);
        require(
            !guard.pending() && state.draft == draft,
            "Old source guard crossed a document boundary");
        captureCases("identical-open");

        // Refresh eligibility changes with the document token. A held gesture cannot borrow a
        // successor document even when project UUID and EditorState.revision are unchanged.
        show(0);
        press(inspector.control("trace-refresh"));
        require(state.document.open(sourceFile).isEmpty(), "Held Refresh reopen failed");
        frame();
        release();
        frames(2);
        require(refreshes == 0 && shown == original, "Held Refresh crossed document lifetimes");
        require(state.document.open(changedFile).isEmpty(), "Changed reopen failed");
        captureCases("changed-open");
        show(0);
        click(inspector.control("trace-refresh"));
        frames(3);
        require(
            refreshes == 1
                && shown != original
                && shown.trace().position().equals(new BlockPos(2, 0, 3))
                && shown.trace().finalType() == 0,
            "Explicit Refresh did not bind the current CLEAR document");
        var refreshed = shown;
        captureCases("refreshed");
        state.document.save(out.resolve("current-after.json"));
        captureCases("refreshed-saved");
        shown = original;
        event = originalEvent;
        captureCases("retained-original");
        show(2);
        click(logTrace.controlAt("control"));
        frames(2);
        require(!guard.pending(), "Historical source control remained actionable");
        click(logTrace.controlAt("copy-trace"));
        frames(2);
        require(
            copies.equals(List.of(details)), "Historical Copy did not retain exact trace details");
        require(
            state.traces.size() == 2
                && state.traces.get(originalEvent).orElseThrow() == original
                && original.details().equals(details),
            "Retained history was mutated or replaced");
        require(
            revision == state.revision && rawVersion == coordinates.version() && raw.equals(raw()),
            "Refresh changed revision or separately entered XYZ");
        require(
            state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Direct-session fixture changed private Properties text");
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Trace lifetime changed preferences");
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("original-trace.json"),
            ProjectIO.JSON.writeValueAsString(original.trace()));
        Files.writeString(
            out.resolve("current-trace.json"),
            ProjectIO.JSON.writeValueAsString(refreshed.trace()));
        Files.writeString(out.resolve("original-details.txt"), details);
        Files.writeString(
            out.resolve("trace-lifetime-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fixture", "native-inspector-logs-synchronous-refresh"),
                    Map.entry("cases", cases),
                    Map.entry("refreshes", refreshes),
                    Map.entry("raw", raw),
                    Map.entry("retainedSnapshots", state.traces.size()),
                    Map.entry("sourceGuardExpired", true),
                    Map.entry("heldRefreshRejected", true),
                    Map.entry("explicitRefreshRebound", true),
                    Map.entry("historicalCopyExact", true),
                    Map.entry("privatePreserved", true),
                    Map.entry("revisionPreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("backgroundRefreshVerified", false))));
        System.out.println(
            "TRACE_LIFETIME_NATIVE_PASS current/Save/failed Open / same-UUID replacement / owned"
                + " Refresh / immutable retained Logs");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static TraceSnapshot capture(BlockPos position) {
    var source = ProjectIO.copy(state.project());
    try (var world = state.engine.openWorld(new CompiledProject(source))) {
      var trace = world.trace(position);
      require(
          trace.finalType() == world.getVoxelTypeId(position), "Trace/canonical voxel mismatch");
      return new TraceSnapshot(state.document.sessionToken(), source, state.revision, trace);
    }
  }

  private static void captureCases(String phase) {
    var project = ProjectIO.JSON.valueToTree(state.project());
    long revision = state.revision, history = state.document.historyBytes();
    boolean stale =
        phase.equals("identical-open")
            || phase.equals("changed-open")
            || phase.equals("retained-original");
    for (int next = 0; next < 3; next++) {
      show(next);
      var panel = form == 2 ? logTrace : inspectorTrace;
      if (form != 1)
        require(
            (panel.goToCenter() == null) == stale,
            "Native source eligibility disagrees with document lifetime");
      if (form != 2)
        require(
            inspector.freshnessNoticeVisible() == stale,
            "Inspector freshness notice disagrees with document lifetime");
      cases.add(
          Map.ofEntries(
              Map.entry("phase", phase),
              Map.entry("form", form),
              Map.entry("freshness", shown.freshness(state).name()),
              Map.entry("reopened", shown.reopened(state)),
              Map.entry("sourceCurrent", shown.sameDocument(state)),
              Map.entry("controlAvailable", form != 1 && panel.goToCenter() != null),
              Map.entry("traceRows", form == 1 ? 0 : panel.submittedRows()),
              Map.entry("finalType", shown.trace().finalType()),
              Map.entry("revision", state.revision)));
    }
    require(
        project.equals(ProjectIO.JSON.valueToTree(state.project()))
            && revision == state.revision
            && history == state.document.historyBytes(),
        "Trace presentation changed authored state");
  }

  private static void show(int next) {
    form = next;
    if (form == 1) inspector.showCoordinates(false);
    else inspector.showTrace();
    logs.reveal(event);
    frames(2);
    if (form != 1) {
      (form == 2 ? logTrace : inspectorTrace).filter("Compare threshold");
      frames(3);
    }
  }

  private static ImString buffer(String axis) {
    return buffers.computeIfAbsent(
        axis, key -> new ImString(coordinates.text(key), CoordinateText.MAX_CHARACTERS + 1));
  }

  private static Map<String, String> raw() {
    var raw = new LinkedHashMap<String, String>();
    for (var axis : coordinates.axes()) raw.put(axis, buffer(axis).get());
    return raw;
  }

  private static void dispatch(InspectorPanel.Action action) {
    if (action == InspectorPanel.Action.REFRESH) {
      refreshes++;
      shown = capture(shown.trace().position());
      event = state.publishTrace(shown);
    } else require(action == InspectorPanel.Action.NONE, "Unexpected Inspector action " + action);
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(1400, 1100, ImGuiCond.Always);
    if (form == 2) {
      inspector.suspend();
      inspectorTrace.hide(shown);
      logs.drawLogs(new ImBoolean(true));
    } else {
      ImGui.begin("Voxel Inspector");
      UiScale.window(state.user, "Voxel Inspector");
      dispatch(
          inspector.draw(() -> new InspectorPanel.Status(shown, null, "", "", ""), inspectorTrace));
      ImGui.end();
      logTrace.hide(shown);
    }
    guard.draw();
    ImGui.render();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void press(float[] point) {
    require(point != null, "Missing native control");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static void click(float[] point) {
    press(point);
    release();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
