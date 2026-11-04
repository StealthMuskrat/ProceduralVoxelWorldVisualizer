package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deferred production-widget cases, nested in ExpandedExpressionProbe's existing native context.
 */
final class BackgroundExpressionFeedbackProbe {
  private static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean reject;

    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("feedback queue unavailable");
      tasks.add(task);
    }

    void runAll() {
      while (!tasks.isEmpty()) tasks.remove().run();
    }
  }

  private static PropertyPanel panel;
  private static Workers workers;
  private static ArrayDeque<Runnable> ui;
  private static AtomicLong clock;
  private static boolean rejectHandoff;

  static void run(Path out) throws Exception {
    var cases = new ArrayList<Map<String, Object>>();
    try (var state = open()) {
      var committed = ProjectIO.JSON.valueToTree(state.project());
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      long revision = state.revision, history = state.document.historyBytes();
      frames(12);
      require(
          panel.probeExpressionFeedback().view().phase()
              == BackgroundExpressionFeedback.Phase.RUNNING,
          "Inline work was not queued");
      workers.runAll();
      frames(12);
      require(
          panel.probeExpressionFeedback().view().result() == null && ui.size() == 1,
          "Undelivered feedback became visible");
      require(
          panel.probeExpressionFeedback().launches() == 1,
          "Busy frames recompiled unchanged source");
      deliver();
      frames(2);
      require(
          panel.probeExpressionFeedback().view().result().valid(),
          "Delivered inline feedback missing");
      require(
          committed.equals(ProjectIO.JSON.valueToTree(state.project()))
              && draft.equals(ProjectIO.JSON.valueToTree(state.draft)),
          "Feedback changed authored data");
      require(
          revision == state.revision && history == state.document.historyBytes(),
          "Feedback published history");
      cases.add(
          Map.of(
              "id",
              "queued-and-held",
              "queuedFrames",
              12,
              "heldFrames",
              12,
              "launches",
              1,
              "displayOnly",
              true));

      click(panel.probeExpressionControl("text"));
      replace("é+unknown");
      frames(12);
      require(
          panel.probeExpressionFeedback().view().result() == null
              && panel.probeExpressionFeedback().launches() == 1,
          "Typing did not coalesce");
      quiet();
      settle();
      var error = panel.probeExpressionFeedback().view().result();
      require(
          !error.valid() && error.start() == 2 && error.end() == 9,
          "Current inline UTF-16 error span");
      click(panel.probeExpressionControl("focus"));
      frames(4);
      require(
          new ExpressionSelection.Range(3, 10).equals(panel.probeExpressionSelection()),
          "Current inline UTF-8 token selection");
      ImGui.getIO().addInputCharactersUTF8("bias");
      frames(3);
      require(
          "é+bias".equals(ExpressionFeedback.source(item(state))), "Inline selection replacement");
      require(
          panel.probeExpressionSelection() == null
              && panel.probeExpressionFeedback().view().result() == null,
          "Old inline span survived correction");
      cases.add(
          Map.of(
              "id",
              "inline-unicode-coalescing",
              "quietFrames",
              12,
              "utf16Start",
              2,
              "utf16End",
              9,
              "utf8Start",
              3,
              "utf8End",
              10,
              "oldSpanRetired",
              true));
    } finally {
      closePanel();
    }

    try (var state = open()) {
      frames(4);
      settle();
      click(panel.probeExpressionControl("expand"));
      frames(4);
      settle();
      click(panel.probeExpandedExpressionControl("text"));
      replace("é+\nunknown");
      quiet();
      settle();
      click(panel.probeExpandedExpressionControl("selectError"));
      frames(4);
      require(
          new ExpressionSelection.Range(4, 11).equals(panel.probeExpandedExpressionSelection()),
          "Expanded current Unicode span");
      ImGui.getIO().addInputCharactersUTF8("bias");
      frames(3);
      require(
          "é+\nbias".equals(panel.probeExpandedExpressionText()), "Expanded Unicode replacement");
      require(
          panel.probeExpandedExpressionSelection() == null,
          "Old expanded error selection survived typing");
      var session = panel.probeExpandedExpressionSession();
      require(
          session.feedbackWork().view().result() == null,
          "Expanded feedback did not wait after typing");
      var committed = ProjectIO.JSON.valueToTree(state.project());
      long history = state.document.historyBytes(), revision = state.revision;
      click(panel.probeExpandedExpressionControl("stage"));
      frames(3);
      require(
          !panel.probeExpandedExpressionOpen() && session.closed(),
          "Pending Use Text did not close");
      require(
          "é+\nbias".equals(ExpressionFeedback.source(item(state))),
          "Pending Use Text lost exact text");
      settle();
      require(
          committed.equals(ProjectIO.JSON.valueToTree(state.project()))
              && revision == state.revision
              && history == state.document.historyBytes(),
          "Pending Use Text committed data");
      cases.add(
          Map.of(
              "id",
              "expanded-current-span",
              "utf8Start",
              4,
              "utf8End",
              11,
              "pendingUseText",
              true,
              "exactText",
              "é+\nbias",
              "privateStage",
              true));
    } finally {
      closePanel();
    }

    for (boolean handoff : new boolean[] {false, true}) {
      try (var state = open()) {
        workers.reject = !handoff;
        rejectHandoff = handoff;
        frames(4);
        if (handoff) workers.runAll();
        frames(12);
        require(
            panel.probeExpressionFeedback().view().retryable(),
            "Failure did not offer explicit retry");
        long launches = panel.probeExpressionFeedback().launches();
        quiet();
        frames(12);
        require(
            panel.probeExpressionFeedback().launches() == launches,
            "Failed source automatically resubmitted");
        workers.reject = rejectHandoff = false;
        click(panel.probeExpressionControl("retry"));
        settle();
        require(
            panel.probeExpressionFeedback().view().result().valid(),
            "Feedback Retry did not recover");
        click(panel.probeExpressionControl("expand"));
        frames(4);
        var session = panel.probeExpandedExpressionSession();
        workers.runAll(); // Hold this opening's completion until after Cancel/close.
        click(panel.probeExpandedExpressionControl("cancel"));
        frames(3);
        deliver();
        require(
            session.closed() && !panel.probeExpandedExpressionOpen(),
            "Cancel did not retire expanded feedback");
        require(
            "x+bias".equals(ExpressionFeedback.source(item(state))),
            "Cancelled feedback staged text");
        cases.add(
            Map.of(
                "id",
                handoff ? "handoff-retry" : "schedule-retry",
                "busyFrames",
                24,
                "explicitRetry",
                true,
                "lateCancelIgnored",
                true));
      } finally {
        closePanel();
      }
    }

    try (var state = open()) {
      frames(4);
      settle();
      click(panel.probeExpressionControl("expand"));
      frames(4);
      var session = panel.probeExpandedExpressionSession();
      workers.runAll();
      click(panel.probeExpandedExpressionControl("text"));
      replace("x+9");
      ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
      frame();
      ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
      frames(3);
      deliver();
      require(
          !panel.probeExpandedExpressionOpen() && session.closed(),
          "Escape did not retire expanded work");
      require("x+bias".equals(ExpressionFeedback.source(item(state))), "Escape staged text");
      click(panel.probeExpressionControl("expand"));
      frames(4);
      var successor = panel.probeExpandedExpressionSession();
      workers.runAll();
      panel.close();
      deliver();
      require(
          successor.closed()
              && successor.feedbackWork().view().phase()
                  == BackgroundExpressionFeedback.Phase.CLOSED,
          "Panel close retained child feedback authority");
      cases.add(
          Map.of(
              "id",
              "escape-and-close",
              "escapePreservedDraft",
              true,
              "successorClosed",
              true,
              "lateCompletionIgnored",
              true));
    } finally {
      closePanel();
    }
    Files.writeString(
        out.resolve("background-expression-native.json"),
        ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "cases", cases)));
  }

  private static EditorState open() {
    var state = new EditorState(new UserConfig(), false);
    require(
        state.change(
            p -> {
              var noise = new Noise();
              noise.id = "formula";
              var item = ProcessingRegistry.create("EXPRESSION", "formula");
              item.parameters.put("expression", ProjectIO.JSON.valueToTree("x+bias"));
              item.inputs.put("bias", Binding.constant(2));
              item.inputs.put("é", Binding.constant(0));
              noise.processing.add(item);
              noise.outputBinding = Binding.earlier(item.uuid);
              p.noise2D.add(noise);
            }),
        "Background expression fixture rejected");
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    workers = new Workers();
    ui = new ArrayDeque<>();
    clock = new AtomicLong();
    rejectHandoff = false;
    panel =
        new PropertyPanel(
            state,
            workers,
            completion -> {
              if (rejectHandoff) throw new AssertionError("feedback handoff unavailable");
              ui.add(completion);
            },
            clock::get);
    return state;
  }

  private static Item item(EditorState state) {
    return ((Noise) state.draft).processing.getFirst();
  }

  private static void closePanel() {
    if (panel != null) panel.close();
    panel = null;
  }

  private static void quiet() {
    clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS);
    frames(2);
  }

  private static void deliver() {
    while (!ui.isEmpty()) ui.remove().run();
  }

  private static void settle() {
    workers.runAll();
    deliver();
    frames(2);
  }

  private static void replace(String value) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    var lines = value.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        io.addKeyEvent(ImGuiKey.Enter, true);
        frame();
        io.addKeyEvent(ImGuiKey.Enter, false);
        frame();
      }
      io.addInputCharactersUTF8(lines[i]);
      frame();
    }
    frames(2);
  }

  private static void click(float[] center) {
    require(center != null, "Background expression control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 800, ImGuiCond.Always);
    ImGui.begin("Background expression feedback workflow");
    panel.probeExpressionPanel();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
