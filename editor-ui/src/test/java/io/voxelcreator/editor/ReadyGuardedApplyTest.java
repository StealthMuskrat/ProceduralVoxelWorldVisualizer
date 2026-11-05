package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred readiness/cancellation boundaries shared by source navigation and browser selection. */
class ReadyGuardedApplyTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    public void execute(Runnable task) { tasks.add(task); }
    void run() { tasks.remove().run(); }
  }

  static EditorState state() {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(s.change(p -> {
      for (String id : List.of("source", "target", "successor")) {
        var n = new Noise(); n.id = id; n.algorithm.key = "CONSTANT";
        n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", .125); p.noise2D.add(n);
      }
    }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    ((Noise) s.draft).algorithm.parameters.put("value", .625);
    return s;
  }

  static final class Form implements AutoCloseable {
    final EditorState state;
    final Workers workers = new Workers();
    final Queue<Runnable> ui = new ArrayDeque<>();
    final SearchNavigation navigation;
    final ResourceSelectionSession selection;
    final List<Object> completed = new ArrayList<>();
    BooleanSupplier gate = () -> true;
    Consumer<Object> afterCompletion = value -> {};
    Form(EditorState s, boolean browser) {
      state = s;
      Consumer<Object> finish = value -> { completed.add(value); afterCompletion.accept(value); };
      navigation = browser ? null : new SearchNavigation(s, workers, ui::add, finish::accept);
      selection = browser ? new ResourceSelectionSession(s, () -> {}, workers, ui::add,
          () -> !gate.getAsBoolean(), finish::accept) : null;
    }
    UUID id(int index) { return state.project().noise2D.get(index).uuid; }
    void request(int index) {
      if (navigation != null) assertEquals(SearchNavigation.Result.NEEDS_DECISION, navigation.request(
          new EditorEvents.Target(state.project().project.uuid, id(index), null, null, "", false)));
      else assertEquals(ResourceSelectionSession.Result.NEEDS_DECISION, selection.click(id(index), false));
    }
    void apply() {
      if (navigation != null) assertEquals(SearchNavigation.Result.NEEDS_DECISION,
          navigation.decide(SearchNavigation.Decision.APPLY, () -> gate.getAsBoolean(), () -> true));
      else assertEquals(ResourceSelectionSession.Result.NEEDS_DECISION, selection.decide(SearchNavigation.Decision.APPLY));
    }
    void start() { request(1); apply(); }
    Runnable deliver() { workers.run(); var callback = ui.remove(); callback.run(); return callback; }
    void accept() { if (navigation != null) navigation.acceptPrepared(); else selection.acceptPrepared(); }
    void cancel() {
      if (navigation != null) navigation.decide(SearchNavigation.Decision.CANCEL);
      else selection.decide(SearchNavigation.Decision.CANCEL);
    }
    boolean pending() { return navigation != null ? navigation.pending() : selection.pending(); }
    boolean preparing() { return navigation != null ? navigation.preparing() : selection.preparing(); }
    long version() { return navigation != null ? navigation.workVersion() : selection.workVersion(); }
    public void close() { if (navigation != null) navigation.close(); else selection.close(); }
  }

  @Test
  void deliveredResultStaysPrivateAndKeepsGestureVersionUntilOneOwnerAcceptance() {
    for (boolean browser : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, browser)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft; var source = s.selected;
      long revision = s.revision; form.start(); long version = form.version(); var completion = form.deliver();
      completion.run(); assertTrue(form.pending()); assertTrue(form.preparing()); assertEquals(version, form.version());
      assertSame(draft, s.draft); assertEquals(source, s.selected); assertTrue(form.completed.isEmpty());
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      form.accept(); form.accept(); completion.run();
      assertFalse(form.pending()); assertEquals(form.id(1), s.selected); assertEquals(revision + 1, s.revision);
      assertEquals(1, form.completed.size()); s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      s.document.redo(); assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
    }
  }

  @Test
  void completionReadyCancelRetainsPrivateInputAndExistingRedo() {
    for (boolean browser : new boolean[] {true, false}) try (var s = state()) {
      s.change(p -> p.project.seed++); s.document.undo(); s.revision++; s.refreshDraft();
      ((Noise) s.draft).algorithm.parameters.put("value", .625);
      try (var form = new Form(s, browser)) {
        var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
        long bytes = s.document.historyBytes(), revision = s.revision;
        form.start(); var completion = form.deliver(); form.cancel(); completion.run(); form.accept();
        assertFalse(form.pending()); assertSame(draft, s.draft); assertTrue(s.document.canRedo());
        assertEquals(bytes, s.document.historyBytes()); assertEquals(revision, s.revision);
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertTrue(form.completed.isEmpty());
      }
    }
  }

  @Test
  void readyAndUndeliveredClosureRejectRepeatedCallbacksAndNewRequests() {
    for (boolean browser : new boolean[] {true, false}) for (boolean ready : new boolean[] {true, false})
      try (var s = state(); var form = new Form(s, browser)) {
        var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
        form.start(); form.workers.run(); var completion = form.ui.remove(); if (ready) completion.run();
        form.close(); completion.run(); completion.run(); form.accept();
        assertFalse(form.pending()); assertSame(draft, s.draft); assertTrue(form.completed.isEmpty());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        if (form.navigation != null) assertEquals(SearchNavigation.Result.UNAVAILABLE, form.navigation.request(
            new EditorEvents.Target(s.project().project.uuid, form.id(1), null, null, "", false)));
        else assertEquals(ResourceSelectionSession.Result.UNAVAILABLE, form.selection.click(form.id(1), false));
      }
  }

  @Test
  void readyPredecessorCannotPopulateOrConsumeSuccessorWork() {
    for (boolean browser : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, browser)) {
      form.start(); var old = form.deliver(); form.cancel(); form.request(2); form.apply();
      old.run(); form.accept(); assertTrue(form.pending()); assertTrue(form.completed.isEmpty());
      assertEquals(form.id(0), s.selected); form.deliver(); form.accept();
      assertEquals(form.id(2), s.selected); assertEquals(1, form.completed.size());
    }
  }

  @Test
  void invalidRawEditThenExplicitRestorationCannotReviveReadyAuthority() {
    for (boolean browser : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, browser)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); form.start(); form.deliver();
      s.seedInputs.edit(s, "seedOffset", "Seed", "-"); s.seedInputs.clear(s);
      form.accept(); assertFalse(form.pending()); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      assertEquals(form.id(0), s.selected); assertFalse(form.preparing());
    }
  }

  @Test
  void newerDiagnosticIdentityContentsAndScopeWinAfterDelivery() {
    for (boolean browser : new boolean[] {true, false}) for (int mode = 0; mode < 3; mode++)
      try (var s = state(); var form = new Form(s, browser)) {
        var shared = new ArrayList<Diagnostic>(); s.issues = shared;
        form.start(); form.deliver(); var newer = Diagnostic.error("NEW", "/", "newer result");
        if (mode == 0) s.issues = List.of(newer); else if (mode == 1) shared.add(newer); else s.validationScope = "newer scope";
        var issues = s.issues; String scope = s.validationScope;
        form.accept(); assertFalse(form.pending()); assertSame(issues, s.issues); assertEquals(scope, s.validationScope);
        assertEquals(.125, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      }
  }

  @Test
  void changedCommittedValuesCheckpointPolicyOrPrivateIdentityRejectReadyPreparation() throws Exception {
    for (boolean browser : new boolean[] {true, false}) for (int mode = 0; mode < 4; mode++)
      try (var s = state(); var form = new Form(s, browser)) {
        var file = directory.resolve("source.json"); s.document.save(file);
        form.start(); form.deliver();
        if (mode == 0) s.project().project.seed++;
        else if (mode == 1) s.document.save(file);
        else if (mode == 2) s.document.setValidator(NoiseCompiler::validate);
        else s.refreshDraft();
        var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
        form.accept(); assertFalse(form.pending()); assertSame(draft, s.draft);
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertEquals(form.id(0), s.selected);
      }
  }

  @Test
  void gateBecomingUnavailableAfterDeliveryPreventsCommitAndDestinationInstallation() {
    for (boolean browser : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, browser)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
      form.start(); form.deliver(); form.gate = () -> false; form.accept();
      assertFalse(form.pending()); assertSame(draft, s.draft); assertEquals(form.id(0), s.selected);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertEquals(1, form.completed.size());
    }
  }

  @Test
  void reentrantReadyGateCanReplaceIntentWithoutOldPublicationOrCompletion() {
    for (boolean browser : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, browser)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); form.start(); form.deliver();
      var once = new AtomicBoolean(true);
      form.gate = () -> { if (once.getAndSet(false)) { form.cancel(); form.request(2); } return true; };
      form.accept(); assertTrue(form.pending()); assertTrue(form.completed.isEmpty()); assertEquals(form.id(0), s.selected);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); form.apply(); form.deliver(); form.accept();
      assertEquals(form.id(2), s.selected); assertEquals(1, form.completed.size());
    }
  }

  @Test
  void cancellingAReadyWorkerFailureDoesNotPublishRejectedFeedback() {
    for (boolean browser : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, browser)) {
      s.document.setValidator(p -> { throw new AssertionError("worker result"); });
      var issues = s.issues; String scope = s.validationScope; form.start(); form.deliver();
      assertTrue(form.preparing()); form.cancel(); form.accept();
      assertSame(issues, s.issues); assertEquals(scope, s.validationScope); assertTrue(form.completed.isEmpty());
      assertEquals(.125, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
    }
  }

  @Test
  void rejectionFeedbackWaitsForOwnerAcceptanceAndExplicitRetryUsesSuccessor() {
    for (boolean browser : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, browser)) {
      var diagnostics = new ArrayList<Diagnostic>(); diagnostics.add(Diagnostic.error("REJECT", "/", "rejected"));
      s.document.setValidator(p -> diagnostics); var original = s.issues;
      form.start(); form.deliver(); assertSame(original, s.issues); assertTrue(form.preparing());
      form.accept(); assertTrue(form.pending()); assertFalse(form.preparing()); assertEquals(1, s.issues.size());
      diagnostics.clear(); assertEquals(1, s.issues.size()); form.apply(); form.deliver();
      assertEquals(.125, s.project().noise2D.getFirst().algorithm.parameters.get("value")); form.accept();
      assertFalse(form.pending()); assertTrue(s.issues.isEmpty()); assertEquals(1, form.completed.size());
    }
  }

  @Test
  void throwingTerminalCallbackCannotReplayAnAcceptedReadyTransaction() {
    for (boolean browser : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, browser)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); long revision = s.revision;
      form.afterCompletion = value -> { throw new AssertionError("completion"); };
      form.start(); var completion = form.deliver(); form.accept(); completion.run(); form.accept();
      assertFalse(form.pending()); assertEquals(form.id(1), s.selected); assertEquals(revision + 1, s.revision);
      assertEquals(1, form.completed.size()); s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
    }
  }
}
