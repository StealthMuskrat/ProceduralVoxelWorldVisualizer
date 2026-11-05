package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred current-control acceptance for Properties and independent Project Settings Apply. */
class ReadyDirectApplyTest {
  @TempDir Path directory;
  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    public void execute(Runnable task) { tasks.add(task); }
    void run() { tasks.remove().run(); }
  }
  static EditorState state() {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(s.change(p -> {
      p.project.seed = 5; p.project.minY = -2; p.project.maxY = 2;
      var n = new Noise(); n.id = "field"; n.algorithm.key = "CONSTANT";
      n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", .125); p.noise2D.add(n);
    }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    return s;
  }
  static final class Form implements AutoCloseable {
    final EditorState state;
    final Workers workers = new Workers();
    final Queue<Runnable> ui = new ArrayDeque<>();
    final PropertiesApplySession properties;
    final ProjectSettingsSession settings;
    Runnable gate = () -> {};
    Form(EditorState s, boolean projectSettings) {
      state = s; ((Noise) s.draft).algorithm.parameters.put("value", .625);
      properties = projectSettings ? null : new PropertiesApplySession(s, workers, ui::add, () -> { gate.run(); return false; });
      settings = projectSettings ? new ProjectSettingsSession(s, workers, ui::add, () -> { gate.run(); return false; }) : null;
      if (settings != null) {
        s.seedInputs.edit(s, "seedOffset", "Seed", "-");
        settings.edit("seed", "+000017"); settings.edit("minY", "-1"); settings.edit("maxY", "+1");
      }
    }
    Map<String, String> shown() {
      return Map.of("seed", settings.text("seed"), "minY", settings.text("minY"), "maxY", settings.text("maxY"));
    }
    void start() { if (properties != null) assertTrue(properties.start()); else assertFalse(settings.apply(state, shown())); }
    Runnable deliver() { workers.run(); var completion = ui.remove(); completion.run(); return completion; }
    void accept() { if (properties != null) properties.acceptPrepared(); else settings.acceptPrepared(shown()); }
    boolean pending() { return properties != null ? properties.running() : settings.applying(); }
    long version() { return properties != null ? properties.version() : settings.workVersion(); }
    void cancel() { if (properties != null) properties.cancel(); else settings.cancel(); }
    void suspend() { if (properties != null) properties.cancel(); else settings.suspendApply(); }
    boolean applied() { return properties != null ? properties.view().phase() == PropertiesApplySession.Phase.APPLIED
        : settings.closed() && state.project().project.seed == 17; }
    public void close() { if (properties != null) properties.close(); else settings.cancel(); }
  }

  @Test
  void readyDeliveryKeepsProjectPrivateAndStableAuthorityUntilOneAcceptance() {
    for (boolean settings : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, settings)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
      var privateValues = ProjectIO.JSON.valueToTree(draft); long seedVersion = s.seedInputs.version(s);
      form.start(); long version = form.version(); var completion = form.deliver(); completion.run();
      assertTrue(form.pending()); assertEquals(version, form.version()); assertSame(draft, s.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); form.accept(); form.accept(); completion.run();
      assertTrue(form.applied()); assertFalse(form.pending());
      if (settings) {
        assertEquals(17, s.project().project.seed); assertSame(draft, s.draft);
        assertEquals(privateValues, ProjectIO.JSON.valueToTree(s.draft));
        assertEquals(seedVersion, s.seedInputs.version(s)); assertEquals("-", s.seedInputs.text("seedOffset"));
      } else assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void readyCancelPreservesDocumentHistoryPrivateDraftAndIndependentRawInput() {
    for (boolean settings : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, settings)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft; long history = s.document.historyBytes();
      long revision = s.revision, inputs = s.seedInputs.version(s);
      form.start(); var completion = form.deliver(); form.cancel(); completion.run(); form.accept();
      assertFalse(form.pending()); assertSame(draft, s.draft); assertEquals(revision, s.revision);
      assertEquals(history, s.document.historyBytes()); assertEquals(inputs, s.seedInputs.version(s));
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void suspensionRetiresReadyWorkAndAllowsExplicitRetryWithExactPrivateInput() {
    for (boolean settings : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, settings)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
      form.start(); var completion = form.deliver(); long version = form.version(); form.suspend();
      assertFalse(form.pending()); assertNotEquals(version, form.version()); completion.run(); form.accept();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertSame(draft, s.draft);
      if (settings) { assertFalse(form.settings.closed()); assertEquals("+000017", form.settings.text("seed")); }
      form.start(); form.deliver(); form.accept(); assertTrue(form.applied());
    }
  }

  @Test
  void displayedProjectSettingsMismatchCannotPublishAnEarlierValidatedValue() {
    for (boolean missing : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, true)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); form.start(); form.deliver();
      var mismatch = missing ? Map.<String, String>of() : Map.of("seed", "18", "minY", "-1", "maxY", "+1");
      assertFalse(form.settings.acceptPrepared(mismatch)); assertFalse(form.pending());
      assertTrue(form.settings.applyFailure().contains("Displayed")); assertEquals("+000017", form.settings.text("seed"));
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); form.start(); form.deliver(); form.accept();
      assertEquals(17, s.project().project.seed);
    }
  }

  @Test
  void equivalentSettingsTextAndRestoredRawPropertiesInputRetireReadyAuthority() {
    for (boolean settings : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, settings)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); form.start(); var completion = form.deliver();
      if (settings) { form.settings.edit("seed", "+0017"); form.settings.edit("seed", "+000017"); }
      else { s.seedInputs.edit(s, "seedOffset", "Seed", "-"); s.seedInputs.clear(s); }
      completion.run(); form.accept(); assertFalse(form.pending()); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void changedFeedbackIdentityContentsOrScopeCannotBeOverwrittenAfterDelivery() {
    for (boolean settings : new boolean[] {true, false}) for (int mode = 0; mode < 3; mode++)
      try (var s = state(); var form = new Form(s, settings)) {
        var shared = new ArrayList<Diagnostic>(); s.issues = shared;
        form.start(); form.deliver(); var newer = Diagnostic.error("NEW", "/", "newer");
        if (mode == 0) s.issues = List.of(newer); else if (mode == 1) shared.add(newer); else s.validationScope = "newer scope";
        var issues = s.issues; String scope = s.validationScope;
        form.accept(); assertFalse(form.pending()); assertSame(issues, s.issues); assertEquals(scope, s.validationScope);
        assertEquals(5, s.project().project.seed); assertEquals(.125, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      }
  }

  @Test
  void changedSourceCheckpointOrValidationPolicyRejectsAReadyCandidate() throws Exception {
    for (boolean settings : new boolean[] {true, false}) for (int mode = 0; mode < 3; mode++)
      try (var s = state(); var form = new Form(s, settings)) {
        var file = directory.resolve("source.json"); s.document.save(file); form.start(); form.deliver();
        if (mode == 0) s.project().project.seed++;
        else if (mode == 1) s.document.save(file);
        else s.document.setValidator(NoiseCompiler::validate);
        var before = ProjectIO.JSON.valueToTree(s.project()); form.accept();
        assertFalse(form.pending()); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void reentrantReadyGateMayStartASuccessorWithoutOldPublication() {
    for (boolean settings : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, settings)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); form.start(); form.deliver();
      var once = new AtomicBoolean(true);
      form.gate = () -> { if (once.getAndSet(false)) { form.suspend(); form.start(); } };
      form.accept(); assertTrue(form.pending()); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      form.deliver(); form.accept(); assertTrue(form.applied());
    }
  }

  @Test
  void readyWorkerFailureCanBeCancelledWithoutInstallingAttemptFeedback() {
    for (boolean settings : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, settings)) {
      s.document.setValidator(p -> { throw new AssertionError("worker"); });
      var issues = s.issues; String scope = s.validationScope; form.start(); form.deliver();
      assertTrue(form.pending()); form.cancel(); form.accept(); assertSame(issues, s.issues); assertEquals(scope, s.validationScope);
      assertEquals(5, s.project().project.seed); assertEquals(.125, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
    }
  }

  @Test
  void rejectedFeedbackWaitsForAcceptanceAndRetainsExplicitRetry() {
    for (boolean settings : new boolean[] {true, false}) try (var s = state(); var form = new Form(s, settings)) {
      var diagnostics = new ArrayList<Diagnostic>(); diagnostics.add(Diagnostic.error("REJECT", "/", "rejected"));
      s.document.setValidator(p -> diagnostics); var issues = s.issues; form.start(); form.deliver(); assertSame(issues, s.issues);
      form.accept(); assertFalse(form.pending()); assertEquals(1, s.issues.size());
      diagnostics.clear(); assertEquals(1, s.issues.size()); form.start(); form.deliver(); form.accept(); assertTrue(form.applied());
    }
  }

  @Test
  void closedPredecessorDeliveryCannotPopulateANewPrivateForm() {
    for (boolean settings : new boolean[] {true, false}) try (var s = state(); var first = new Form(s, settings)) {
      var before = ProjectIO.JSON.valueToTree(s.project()); first.start(); var old = first.deliver(); first.close();
      try (var successor = new Form(s, settings)) {
        successor.start(); old.run(); successor.accept();
        assertTrue(successor.pending()); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        successor.deliver(); successor.accept(); assertTrue(successor.applied());
      }
    }
  }

  @Test
  void readyNoopPublicationConsumesOnceWithoutDroppingExistingRedo() {
    for (boolean settings : new boolean[] {true, false}) try (var s = state()) {
      s.change(p -> p.project.seed++); s.document.undo(); s.revision++; s.refreshDraft();
      try (var form = new Form(s, settings)) {
        if (settings) { form.settings.edit("seed", "+0005"); form.settings.edit("minY", "-2"); form.settings.edit("maxY", "+2"); }
        else s.document.setValidator(p -> { p.noise2D.getFirst().algorithm.parameters.put("value", .125); return NoiseCompiler.validate(p); });
        long bytes = s.document.historyBytes(); var draft = s.draft;
        form.start(); var completion = form.deliver(); form.accept(); completion.run(); form.accept();
        assertFalse(form.pending()); assertTrue(s.document.canRedo()); assertEquals(bytes, s.document.historyBytes());
        if (settings) { assertTrue(form.settings.closed()); assertSame(draft, s.draft); assertEquals("-", s.seedInputs.text("seedOffset")); }
        else assertEquals(PropertiesApplySession.Phase.APPLIED, form.properties.view().phase());
      }
    }
  }
}
