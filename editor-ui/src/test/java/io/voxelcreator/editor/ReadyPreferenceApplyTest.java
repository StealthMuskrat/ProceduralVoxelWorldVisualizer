package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred ready preference/profile decisions; no native context or update transport needed. */
class ReadyPreferenceApplyTest {
  @TempDir Path directory;

  static PreferenceFiles files(AtomicBoolean broken) {
    return new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException {
        Files.write(temp, bytes);
        if (broken.get()) throw new AssertionError("held preparation failure");
      }
      public void replace(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
    });
  }

  Path target() throws IOException {
    var file = directory.resolve("config.json");
    Files.writeString(file, "previous");
    return file;
  }

  void unchanged(Path file) throws IOException {
    assertEquals("previous", Files.readString(file));
    try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
  }

  static long profileFailures(EditorState state) {
    return state.events.snapshot().stream()
        .filter(e -> e.code().equals("APPLICATION_PROFILE_APPLY_FAILED"))
        .mapToLong(EditorEvents.Entry::count).sum();
  }

  @Test
  void readyFailureKeepsCancelIdentityAndCanBeCancelledOrClosedAcrossThreeForms() throws Exception {
    for (int kind = 0; kind < 3; kind++) for (boolean close : new boolean[] {false, true}) {
      var user = new UserConfig(files(new AtomicBoolean(true)));
      var form = new BackgroundPreferenceApplyTest.Form(user, kind);
      var raw = form.raw(); var before = ProjectIO.JSON.valueToTree(user);
      var worker = new BackgroundPreferenceApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var reports = new ArrayList<String>(); var file = target();
      try (var work = new PreferenceApplySession(user, file, worker, ui::add, reports::add)) {
        BackgroundPreferenceApplyTest.start(work, form); long version = work.version();
        worker.run(); var delivery = ui.remove(); delivery.run(); delivery.run();
        assertTrue(work.running()); assertTrue(work.ready()); assertEquals(version, work.version());
        assertTrue(reports.isEmpty()); assertFalse(work.message().contains("failed"));
        if (close) work.close(); else work.cancel();
        delivery.run(); assertFalse(work.accept(version));
        assertTrue(reports.isEmpty()); assertEquals(raw, form.raw());
        assertEquals(before, ProjectIO.JSON.valueToTree(user)); unchanged(file);
      }
    }
  }

  @Test
  void acceptedFailureReportsOnceAndExplicitRetryPublishesOriginalPrivateInput() throws Exception {
    var broken = new AtomicBoolean(true); var user = new UserConfig(files(broken));
    var form = new BackgroundPreferenceApplyTest.Form(user, 0);
    var worker = new BackgroundPreferenceApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
    var reports = new ArrayList<String>(); var file = target();
    try (var work = new PreferenceApplySession(user, file, worker, ui::add, reports::add)) {
      BackgroundPreferenceApplyTest.start(work, form); worker.run(); var old = ui.remove(); old.run();
      assertTrue(reports.isEmpty()); assertFalse(work.accept()); assertEquals(1, reports.size());
      long version = work.version(); old.run();
      for (int i = 0; i < 8; i++) assertFalse(work.accept());
      assertEquals(version, work.version()); assertEquals(1, reports.size()); unchanged(file);
      broken.set(false); BackgroundPreferenceApplyTest.start(work, form);
      worker.run(); ui.remove().run(); assertTrue(work.accept()); old.run();
      assertEquals(256, user.performance.cacheMiB);
      assertEquals(ProjectIO.JSON.valueToTree(user), ProjectIO.JSON.readTree(file.toFile()));
    }
  }

  @Test
  void themeAndBindingFailuresAlsoWaitForCurrentCancelWithoutDiscardingPrivateInput() throws Exception {
    for (boolean theme : new boolean[] {true, false}) {
      var user = new UserConfig(files(new AtomicBoolean(true)));
      var form = new BackgroundAppearanceInputTest.Form(user, theme); var raw = form.raw();
      var worker = new BackgroundPreferenceApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var reports = new ArrayList<String>(); var file = target();
      try (var work = new PreferenceApplySession(user, file, worker, ui::add, reports::add)) {
        work.start(form.request(), form.guard()); long version = work.version();
        worker.run(); ui.remove().run(); assertEquals(version, work.version());
        assertTrue(work.ready()); assertTrue(reports.isEmpty());
        work.cancel(); assertFalse(work.accept(version));
        assertEquals(raw, form.raw()); assertTrue(reports.isEmpty()); unchanged(file);
      }
    }
  }

  @Test
  void changedInputDisplayOrActiveSourceRetiresReadyFailureWithoutReportingIt() throws Exception {
    for (int change = 0; change < 3; change++) {
      var user = new UserConfig(files(new AtomicBoolean(true)));
      var form = new BackgroundPreferenceApplyTest.Form(user, 1);
      var worker = new BackgroundPreferenceApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var reports = new ArrayList<String>(); var file = target();
      try (var work = new PreferenceApplySession(user, file, worker, ui::add, reports::add)) {
        BackgroundPreferenceApplyTest.start(work, form); worker.run(); ui.remove().run();
        switch (change) {
          case 0 -> form.equivalentRawEdit();
          case 1 -> form.mismatch = true;
          case 2 -> user.workspace.docking = "new active";
        }
        assertFalse(work.accept()); assertFalse(work.running());
        assertTrue(reports.isEmpty()); unchanged(file);
      }
    }
  }

  @Test
  void oldShownVersionCannotAcceptReadySuccessorOrItsFailure() throws Exception {
    for (boolean failing : new boolean[] {false, true}) {
      var user = new UserConfig(files(new AtomicBoolean(failing)));
      var form = new BackgroundPreferenceApplyTest.Form(user, 0);
      var worker = new BackgroundPreferenceApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var reports = new ArrayList<String>(); var file = target();
      try (var work = new PreferenceApplySession(user, file, worker, ui::add, reports::add)) {
        BackgroundPreferenceApplyTest.start(work, form); long old = work.version();
        worker.run(); var predecessor = ui.remove(); work.cancel();
        BackgroundPreferenceApplyTest.start(work, form); worker.run(); ui.remove().run();
        predecessor.run(); assertFalse(work.accept(old)); assertTrue(work.ready());
        assertTrue(reports.isEmpty()); assertEquals("previous", Files.readString(file));
        assertEquals(!failing, work.accept(work.version()));
        assertEquals(failing ? 1 : 0, reports.size());
      }
    }
  }

  @Test
  void inlineDeliveryThenHandoffOrExecutorFailureCannotActivatePreparedValues() throws Exception {
    for (boolean executorFailure : new boolean[] {false, true}) {
      var user = new UserConfig(); var form = new BackgroundPreferenceApplyTest.Form(user, 0);
      var reports = new ArrayList<String>(); var file = target();
      Executor worker = executorFailure ? task -> { task.run(); throw new AssertionError("execute"); } : Runnable::run;
      try (var work = new PreferenceApplySession(user, file, worker, action -> {
        action.run(); if (!executorFailure) throw new AssertionError("handoff after delivery");
      }, reports::add)) {
        BackgroundPreferenceApplyTest.start(work, form);
        assertFalse(work.accept()); assertFalse(work.running()); assertEquals(1, reports.size());
        assertEquals(128, user.performance.cacheMiB); unchanged(file);
      }
    }
  }

  @Test
  void reentrantInputGuardCannotAcceptRetireOrReportAgainstAReadySuccessor() throws Exception {
    for (int outcome = 0; outcome < 3; outcome++) {
      int result = outcome;
      var user = new UserConfig(); var form = new BackgroundPreferenceApplyTest.Form(user, 0);
      var worker = new BackgroundPreferenceApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var reports = new ArrayList<String>(); var file = target(); var fire = new AtomicBoolean();
      var ref = new AtomicReference<PreferenceApplySession>();
      try (var work = new PreferenceApplySession(user, file, worker, ui::add, reports::add)) {
        ref.set(work);
        work.start(form.request(), () -> {
          if (fire.getAndSet(false)) {
            ref.get().cancel(); BackgroundPreferenceApplyTest.start(ref.get(), form);
            worker.run(); ui.remove().run();
            if (result == 2) throw new IllegalStateException("retired guard");
            return result == 0;
          }
          return true;
        });
        worker.run(); ui.remove().run(); fire.set(true);
        assertFalse(work.accept()); assertTrue(work.ready()); assertTrue(reports.isEmpty());
        assertEquals("previous", Files.readString(file)); assertTrue(work.accept());
        assertEquals(256, user.performance.cacheMiB);
      }
    }
  }

  @Test
  void acceptedFailureReporterCanCreateAReadySuccessorWithoutOldAttemptConsumingIt() throws Exception {
    var broken = new AtomicBoolean(true); var user = new UserConfig(files(broken));
    var form = new BackgroundPreferenceApplyTest.Form(user, 0);
    var worker = new BackgroundPreferenceApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
    var file = target(); var reports = new AtomicInteger(); var ref = new AtomicReference<PreferenceApplySession>();
    try (var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {
      reports.incrementAndGet(); broken.set(false);
      BackgroundPreferenceApplyTest.start(ref.get(), form); worker.run(); ui.remove().run();
    })) {
      ref.set(work); BackgroundPreferenceApplyTest.start(work, form);
      worker.run(); ui.remove().run(); assertFalse(work.accept());
      assertEquals(1, reports.get()); assertTrue(work.ready());
      assertEquals("previous", Files.readString(file)); assertTrue(work.accept());
    }
  }

  @Test
  void profileReadyFailureKeepsVersionAndCancelOrCloseSuppressesPrimaryEvent() throws Exception {
    for (boolean close : new boolean[] {false, true}) try (var state = BackgroundMetadataApplyTest.state()) {
      var edit = new MetadataEditSession(state.metadata); edit.draft().controls.get("noise").label = "Private";
      var worker = new BackgroundMetadataApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var file = target(); var active = state.metadata;
      try (var work = new MetadataApplySession(state, worker, ui::add,
          () -> BackgroundMetadataApplyTest.raw(edit), files(new AtomicBoolean(true)))) {
        work.start(edit, file); long version = work.version(); worker.run(); var old = ui.remove(); old.run(); old.run();
        assertTrue(work.ready()); assertTrue(work.running()); assertEquals(version, work.version());
        assertEquals(0, profileFailures(state));
        if (close) work.close(); else work.cancel(); old.run();
        assertFalse(work.accept(version)); assertEquals(0, profileFailures(state));
        assertSame(active, state.metadata); assertEquals("Private", edit.draft().controls.get("noise").label);
        unchanged(file);
      }
    }
  }

  @Test
  void acceptedProfileFailureReportsOnceAndRetryActivatesExactlyItsWrittenValues() throws Exception {
    try (var state = BackgroundMetadataApplyTest.state()) {
      var edit = new MetadataEditSession(state.metadata); edit.draft().controls.get("noise").label = "Private";
      var worker = new BackgroundMetadataApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var file = target(); var broken = new AtomicBoolean(true);
      try (var work = new MetadataApplySession(state, worker, ui::add,
          () -> BackgroundMetadataApplyTest.raw(edit), files(broken))) {
        work.start(edit, file); worker.run(); var old = ui.remove(); old.run();
        assertEquals(0, profileFailures(state)); assertFalse(work.accept());
        assertEquals(1, profileFailures(state)); old.run(); assertFalse(work.accept()); unchanged(file);
        broken.set(false); work.start(edit, file); worker.run(); ui.remove().run(); assertTrue(work.accept());
        assertEquals("Private", state.metadata.controls.get("noise").label);
        assertEquals(ProjectIO.JSON.valueToTree(state.metadata), ProjectIO.JSON.readTree(file.toFile()));
      }
    }
  }

  @Test
  void changedProfileDraftRawDisplayOrActiveSourceRetiresReadyFailureWithoutPrimaryEvent() throws Exception {
    for (int change = 0; change < 4; change++) try (var state = BackgroundMetadataApplyTest.state()) {
      var edit = new MetadataEditSession(state.metadata);
      String id = "noise2D.algorithm.CONSTANT.value";
      edit.inputs().override(id, true); edit.inputs().editAll(id, List.of("0.75"));
      var display = new AtomicReference<>(BackgroundMetadataApplyTest.raw(edit));
      var worker = new BackgroundMetadataApplyTest.Workers(); var ui = new ArrayDeque<Runnable>(); var file = target();
      try (var work = new MetadataApplySession(state, worker, ui::add, display::get, files(new AtomicBoolean(true)))) {
        work.start(edit, file); worker.run(); ui.remove().run();
        switch (change) {
          case 0 -> edit.draft().controls.get("noise").label = "Changed";
          case 1 -> edit.inputs().editAll(id, List.of("+0.75"));
          case 2 -> display.set(Map.of(id, List.of("0.750")));
          case 3 -> state.metadata = AppMetadata.copy(state.metadata);
        }
        assertFalse(work.accept()); assertFalse(work.running()); assertEquals(0, profileFailures(state)); unchanged(file);
      }
    }
  }

  @Test
  void profileOldShownVersionCannotAcceptReadySuccessor() throws Exception {
    try (var state = BackgroundMetadataApplyTest.state()) {
      var edit = new MetadataEditSession(state.metadata);
      var worker = new BackgroundMetadataApplyTest.Workers(); var ui = new ArrayDeque<Runnable>(); var file = target();
      try (var work = BackgroundMetadataApplyTest.writer(state, edit, worker, ui)) {
        work.start(edit, file); long old = work.version(); worker.run(); var delivery = ui.remove(); work.cancel();
        work.start(edit, file); worker.run(); ui.remove().run(); delivery.run();
        assertFalse(work.accept(old)); assertTrue(work.ready()); assertEquals("previous", Files.readString(file));
        assertTrue(work.accept(work.version())); assertEquals(0, profileFailures(state));
      }
    }
  }

  @Test
  void profileInlineDeliveryThenHandoffFailureCannotHideBehindReadyState() throws Exception {
    try (var state = BackgroundMetadataApplyTest.state()) {
      var edit = new MetadataEditSession(state.metadata); var file = target();
      try (var work = new MetadataApplySession(state, Runnable::run, action -> {
        action.run(); throw new AssertionError("profile handoff after delivery");
      }, () -> BackgroundMetadataApplyTest.raw(edit), new PreferenceFiles())) {
        work.start(edit, file); assertFalse(work.accept()); assertFalse(work.running());
        assertEquals(1, profileFailures(state)); unchanged(file);
      }
    }
  }

  @Test
  void reentrantProfileDisplayCannotAcceptRetireOrReportAgainstReadySuccessor() throws Exception {
    for (int outcome = 0; outcome < 3; outcome++) try (var state = BackgroundMetadataApplyTest.state()) {
      int result = outcome; var edit = new MetadataEditSession(state.metadata);
      var worker = new BackgroundMetadataApplyTest.Workers(); var ui = new ArrayDeque<Runnable>(); var file = target();
      var ref = new AtomicReference<MetadataApplySession>(); var fire = new AtomicBoolean();
      try (var work = new MetadataApplySession(state, worker, ui::add, () -> {
        if (fire.getAndSet(false)) {
          ref.get().cancel(); ref.get().start(edit, file); worker.run(); ui.remove().run();
          if (result == 2) throw new IllegalStateException("retired display");
          if (result == 1) return Map.of("missing", List.of("changed"));
        }
        return BackgroundMetadataApplyTest.raw(edit);
      }, new PreferenceFiles())) {
        ref.set(work); work.start(edit, file); worker.run(); ui.remove().run(); fire.set(true);
        assertFalse(work.accept()); assertTrue(work.ready()); assertEquals(0, profileFailures(state));
        assertEquals("previous", Files.readString(file)); assertTrue(work.accept());
      }
    }
  }

  @Test
  void reentrantProfileCaptureCannotOverwriteItsReadySuccessor() throws Exception {
    try (var state = BackgroundMetadataApplyTest.state()) {
      var first = new MetadataEditSession(state.metadata); first.draft().controls.get("noise").label = "First";
      var next = new MetadataEditSession(state.metadata); next.draft().controls.get("noise").label = "Second";
      var active = new AtomicReference<>(first); var fire = new AtomicBoolean(true);
      var worker = new BackgroundMetadataApplyTest.Workers(); var ui = new ArrayDeque<Runnable>(); var file = target();
      var ref = new AtomicReference<MetadataApplySession>();
      try (var work = new MetadataApplySession(state, worker, ui::add, () -> {
        if (fire.getAndSet(false)) {
          active.set(next); ref.get().start(next, file); worker.run(); ui.remove().run();
        }
        return BackgroundMetadataApplyTest.raw(active.get());
      }, new PreferenceFiles())) {
        ref.set(work); work.start(first, file);
        assertTrue(work.ready()); assertTrue(worker.tasks.isEmpty()); assertTrue(ui.isEmpty());
        assertEquals("previous", Files.readString(file)); assertTrue(work.accept());
        assertEquals("Second", state.metadata.controls.get("noise").label);
        assertEquals(0, profileFailures(state));
      }
    }
  }

  @Test
  void closeRejectsCleanupReporterAttemptToStartANewPreferenceWorker() throws Exception {
    var attempts = new AtomicInteger(); var worker = new BackgroundPreferenceApplyTest.Workers();
    var ui = new ArrayDeque<Runnable>(); var ref = new AtomicReference<PreferenceApplySession>();
    var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); }
      public void replace(Path temp, Path target) throws IOException { Files.move(temp, target); }
      public void delete(Path temp) throws IOException { Files.deleteIfExists(temp); throw new IOException("cleanup"); }
    }));
    var form = new BackgroundPreferenceApplyTest.Form(user, 0); var file = target();
    try (var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {
      attempts.incrementAndGet(); BackgroundPreferenceApplyTest.start(ref.get(), form);
    })) {
      ref.set(work); BackgroundPreferenceApplyTest.start(work, form); worker.run(); ui.remove().run();
      work.close(); assertEquals(1, attempts.get()); assertTrue(worker.tasks.isEmpty());
      assertFalse(work.accept()); unchanged(file);
    }
  }

  @Test
  void activeSaveFailureWaitsForOwnerFrameOrCanBeClosedWithoutPrimaryReport() throws Exception {
    for (boolean close : new boolean[] {false, true}) {
      var broken = new AtomicBoolean(true); var user = new UserConfig(files(broken));
      var worker = new PreferenceSaveSessionTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var reports = new ArrayList<String>(); var file = target();
      try (var saves = new PreferenceSaveSession(user, file, worker, ui::add, text -> {}, reports::add)) {
        saves.request(true); worker.run(); var old = ui.remove(); old.run();
        assertTrue(saves.pending()); assertTrue(saves.probeWork().ready()); assertTrue(reports.isEmpty());
        if (close) { saves.close(); old.run(); saves.frame(); assertTrue(reports.isEmpty()); }
        else {
          saves.frame(); assertEquals(1, reports.size()); assertFalse(saves.pending());
          saves.request(false); assertTrue(worker.tasks.isEmpty());
          broken.set(false); saves.request(true); worker.run(); ui.remove().run(); saves.frame();
          assertFalse(saves.pending()); assertEquals(ProjectIO.JSON.valueToTree(user), ProjectIO.JSON.readTree(file.toFile()));
        }
        if (close) unchanged(file);
      }
    }
  }
}
