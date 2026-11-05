package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.model.ProjectIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred three-form configuration publication and worker ownership definitions. */
class BackgroundPreferenceApplyTest {
  @TempDir Path directory;
  static final String ENDPOINT = "https://updates.example.invalid/latest.json";
  static final String ORIGIN = "https://updates.example.invalid";

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean reject;
    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("preference queue full"); tasks.add(task);
    }
    void run() {
      tasks.removeIf(r -> r instanceof Future<?> f && f.isCancelled()); tasks.remove().run();
    }
  }

  static final class Form {
    final UserConfig user;
    final int kind;
    final PerformanceEditSession limits;
    final UiScaleEditSession scales;
    final UpdateTrustSession trust;
    boolean mismatch;
    Form(UserConfig user, int kind) {
      this.user = user; this.kind = kind;
      limits = new PerformanceEditSession(user);
      scales = new UiScaleEditSession(user);
      trust = new UpdateTrustSession(user);
      switch (kind) {
        case 0 -> limits.edit(PerformancePreferences.Field.CACHE_MIB, "256");
        case 1 -> { scales.editGlobal("1.25"); scales.override("Resources", true); scales.editWindow("Resources", "1.5"); }
        case 2 -> { trust.editEndpoint(ENDPOINT); trust.editOrigins(ORIGIN); }
      }
    }
    Map<PerformancePreferences.Field, String> limitsShown() { return mismatch ? Map.of() : limits.text(); }
    UiScaleEditSession.Text scalesShown() {
      var t = scales.text();
      return mismatch ? new UiScaleEditSession.Text("2", t.windows(), t.enabled()) : t;
    }
    UpdateTrustSession.Text trustShown() { return mismatch ? new UpdateTrustSession.Text("", "") : trust.text(); }
    UserConfig.PreferenceRequest request() {
      return switch (kind) {
        case 0 -> limits.request(user, limitsShown());
        case 1 -> scales.request(user, scalesShown());
        default -> trust.request(user, trustShown());
      };
    }
    BooleanSupplier guard() {
      long v = switch (kind) { case 0 -> limits.version(); case 1 -> scales.version(); default -> trust.version(); };
      return () -> switch (kind) {
        case 0 -> limits.ownsInput(user, v, limitsShown());
        case 1 -> scales.ownsInput(user, v, scalesShown());
        default -> trust.ownsInput(user, v, trustShown());
      };
    }
    void equivalentRawEdit() {
      switch (kind) {
        case 0 -> limits.edit(PerformancePreferences.Field.CACHE_MIB, "+256");
        case 1 -> scales.editGlobal("1.250");
        case 2 -> trust.editOrigins(ORIGIN + "/\n");
      }
    }
    void revert() { switch (kind) { case 0 -> limits.revert(user); case 1 -> scales.revert(user); case 2 -> trust.revert(user); } }
    Object raw() { return switch (kind) { case 0 -> limits.text(); case 1 -> scales.text(); default -> trust.text(); }; }
    void expected(ObjectNode tree) {
      switch (kind) {
        case 0 -> ((ObjectNode) tree.path("performance")).put("cacheMiB", 256);
        case 1 -> {
          ((ObjectNode) tree.path("appearance")).put("globalScale", 1.25f);
          ((ObjectNode) tree.path("appearance").path("windowScales")).put("Resources", 1.5f);
        }
        case 2 -> {
          ((ObjectNode) tree.path("performance")).put("updateEndpoint", ENDPOINT);
          ((ObjectNode) tree.path("performance")).putArray("trustedOrigins").add(ORIGIN);
        }
      }
    }
  }

  static UserConfig user() {
    var u = new UserConfig(); u.workspace.docking = "active dock";
    u.appearance.windowScales.put("Extension Pane", 1.75f); return u;
  }

  static void start(PreferenceApplySession work, Form form) { work.start(form.request(), form.guard()); }
  static void deliver(Workers worker, Queue<Runnable> ui) { worker.run(); ui.remove().run(); }

  @Test
  void realWorkerPreparesDetachedBytesAndOnlyOwnerDecisionPublishes() throws Exception {
    var entered = new AtomicReference<Thread>(); var caller = Thread.currentThread();
    var files = new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { entered.set(Thread.currentThread()); Files.write(temp, bytes); }
      public void replace(Path temp, Path target) throws IOException {
        assertSame(caller, Thread.currentThread());
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
    });
    var user = new UserConfig(files); var form = new Form(user, 0);
    var target = directory.resolve("user.json"); Files.writeString(target, "previous");
    var before = ProjectIO.JSON.valueToTree(user); var ui = new LinkedBlockingQueue<Runnable>();
    try (var worker = Executors.newSingleThreadExecutor();
        var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
      start(work, form); var delivery = ui.poll(10, TimeUnit.SECONDS); assertNotNull(delivery);
      assertNotSame(caller, entered.get()); assertEquals(before, ProjectIO.JSON.valueToTree(user));
      assertEquals("previous", Files.readString(target)); delivery.run(); delivery.run();
      assertTrue(work.ready()); assertEquals("previous", Files.readString(target));
      worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
      assertTrue(work.accept()); assertFalse(work.accept());
      assertEquals(256, user.performance.cacheMiB);
      assertEquals(ProjectIO.JSON.valueToTree(user), ProjectIO.JSON.readTree(target.toFile()));
    }
  }

  @Test
  void eachFormInstallsOnlyItsOwnedFieldsAndRetainsNestedOwnersAndExtensions() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      var user = user(); var form = new Form(user, kind);
      var expected = (ObjectNode) ProjectIO.JSON.valueToTree(user); form.expected(expected);
      var appearance = user.appearance; var performance = user.performance; var workspace = user.workspace;
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var target = directory.resolve("user.json");
      try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
        start(work, form); deliver(worker, ui); assertTrue(work.accept());
        assertEquals(expected, ProjectIO.JSON.valueToTree(user));
        assertEquals(expected, ProjectIO.JSON.readTree(target.toFile()));
        assertSame(appearance, user.appearance); assertSame(performance, user.performance); assertSame(workspace, user.workspace);
        assertEquals(1.75f, user.appearance.windowScales.get("Extension Pane"));
      }
    }
  }

  @Test
  void equivalentRawEditsAndExplicitEqualValueRevertRetirePreparedAuthority() throws Exception {
    for (int kind = 0; kind < 3; kind++) for (boolean revert : new boolean[] {false, true}) {
      var user = user(); var form = new Form(user, kind); var before = ProjectIO.JSON.valueToTree(user);
      var target = directory.resolve("user.json"); Files.writeString(target, "previous");
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
        start(work, form); deliver(worker, ui); assertTrue(work.ready());
        if (revert) form.revert(); else form.equivalentRawEdit();
        assertFalse(work.accept()); assertFalse(work.running());
        assertEquals(before, ProjectIO.JSON.valueToTree(user)); assertEquals("previous", Files.readString(target));
      }
    }
  }

  @Test
  void displayedLedgerDisagreementRejectsAllThreeReadyForms() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      var user = user(); var form = new Form(user, kind); var raw = form.raw();
      var target = directory.resolve("user.json"); Files.writeString(target, "previous");
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
        start(work, form); deliver(worker, ui); form.mismatch = true;
        assertFalse(work.accept()); assertEquals(raw, form.raw()); assertEquals("previous", Files.readString(target));
      }
    }
  }

  @Test
  void unrelatedLivePreferenceChangeCannotBeOverwrittenAndExplicitRetryComposesLatest() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      var user = user(); var form = new Form(user, kind); var target = directory.resolve("user.json");
      Files.writeString(target, "previous"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
        start(work, form); deliver(worker, ui); user.workspace.docking = "newer dock";
        assertFalse(work.accept()); assertEquals("previous", Files.readString(target));
        var expected = (ObjectNode) ProjectIO.JSON.valueToTree(user); form.expected(expected);
        start(work, form); deliver(worker, ui); assertTrue(work.accept());
        assertEquals(expected, ProjectIO.JSON.valueToTree(user)); assertEquals(expected, ProjectIO.JSON.readTree(target.toFile()));
      }
    }
  }

  @Test
  void simultaneousPrivateFormsCannotLoseTheFirstAcceptedPreferenceChange() throws Exception {
    var user = user(); var limits = new Form(user, 0); var scales = new Form(user, 1);
    var target = directory.resolve("user.json"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    try (var a = new PreferenceApplySession(user, target, worker, ui::add, text -> {});
        var b = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
      start(a, limits); start(b, scales); deliver(worker, ui); deliver(worker, ui);
      assertTrue(a.accept()); byte[] first = Files.readAllBytes(target);
      assertFalse(b.accept()); assertArrayEquals(first, Files.readAllBytes(target));
      assertEquals(1, user.appearance.globalScale);
      start(b, scales); deliver(worker, ui); assertTrue(b.accept());
      assertEquals(256, user.performance.cacheMiB); assertEquals(1.25f, user.appearance.globalScale);
      assertEquals(ProjectIO.JSON.valueToTree(user), ProjectIO.JSON.readTree(target.toFile()));
    }
  }

  @Test
  void ordinarySaveDuringPreparationWritesOnlyActiveValues() throws Exception {
    var user = user(); var form = new Form(user, 0); var target = directory.resolve("user.json");
    var before = ProjectIO.JSON.valueToTree(user); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
      start(work, form); worker.run(); user.save(target);
      assertEquals(before, ProjectIO.JSON.readTree(target.toFile()));
      ui.remove().run(); assertTrue(work.accept()); assertEquals(256, user.performance.cacheMiB);
    }
  }

  @Test
  void cancellationAndDuplicateOldDeliveryCannotPublishIntoSuccessor() throws Exception {
    var user = user(); var form = new Form(user, 1); var target = directory.resolve("user.json");
    Files.writeString(target, "previous"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
      start(work, form); worker.run(); var old = ui.remove(); work.cancel();
      assertEquals("previous", Files.readString(target)); start(work, form); old.run(); old.run();
      assertTrue(work.running()); assertFalse(work.ready()); deliver(worker, ui); assertTrue(work.accept());
    }
  }

  @Test
  void readyCancelOrClosePreservesPrivateRawInputAndPreviousDestination() throws Exception {
    for (boolean close : new boolean[] {false, true}) {
      var user = user(); var form = new Form(user, 2); var raw = form.raw();
      var target = directory.resolve("user.json"); Files.writeString(target, "previous");
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
        start(work, form); deliver(worker, ui); assertTrue(work.ready());
        if (close) work.close(); else work.cancel();
        assertFalse(work.accept()); assertEquals(raw, form.raw()); assertEquals("previous", Files.readString(target));
      }
    }
  }

  @Test
  void schedulerHandoffAndExternalCancellationEndPendingAndNeedExplicitRetry() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var user = user(); var form = new Form(user, 0); var target = directory.resolve("user.json");
      Files.writeString(target, "previous"); var worker = new Workers(); worker.reject = mode == 0;
      var ui = new ArrayDeque<Runnable>(); var broken = new AtomicBoolean(mode == 1); var reports = new ArrayList<String>();
      try (var work = new PreferenceApplySession(user, target, worker, action -> {
        if (broken.get()) throw new AssertionError("handoff"); ui.add(action);
      }, reports::add)) {
        start(work, form);
        if (mode == 1) worker.run();
        if (mode == 2) ((Future<?>) worker.tasks.getFirst()).cancel(false);
        assertFalse(work.running()); long version = work.version();
        for (int i = 0; i < 8; i++) assertFalse(work.running());
        assertEquals(version, work.version()); assertEquals(1, reports.size());
        assertEquals("previous", Files.readString(target)); broken.set(false); worker.reject = false;
        start(work, form); deliver(worker, ui); assertTrue(work.accept());
      }
    }
  }

  @Test
  void writeAndAtomicReplaceFaultsPreserveActiveValuesAndAllowCorrection() throws Exception {
    for (boolean writing : new boolean[] {false, true}) {
      var broken = new AtomicBoolean(true);
      var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
        public void write(Path temp, byte[] bytes) throws IOException {
          Files.write(temp, bytes); if (writing && broken.get()) throw new AssertionError("write");
        }
        public void replace(Path temp, Path target) throws IOException {
          if (!writing && broken.get()) throw new AtomicMoveNotSupportedException(temp.toString(), target.toString(), "provider");
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
      }));
      var form = new Form(user, 0); var raw = form.raw(); var before = ProjectIO.JSON.valueToTree(user);
      var target = directory.resolve("user.json"); Files.writeString(target, "previous");
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
        start(work, form); deliver(worker, ui); assertFalse(work.accept());
        assertEquals(before, ProjectIO.JSON.valueToTree(user)); assertEquals(raw, form.raw());
        assertEquals("previous", Files.readString(target)); broken.set(false);
        start(work, form); deliver(worker, ui); assertTrue(work.accept());
      }
    }
  }

  @Test
  void invalidWholePreferenceSnapshotNeverReplacesFileOrPublishesPatch() throws Exception {
    var user = user(); var form = new Form(user, 0); user.workspace.width = 1;
    var target = directory.resolve("user.json"); Files.writeString(target, "previous");
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
      start(work, form); deliver(worker, ui); assertFalse(work.accept());
      assertEquals("previous", Files.readString(target)); assertEquals(128, user.performance.cacheMiB);
      user.workspace.width = 1440; start(work, form); deliver(worker, ui); assertTrue(work.accept());
    }
  }

  @Test
  void sessionRequestsRejectInvalidAndMismatchedRawInputBeforeScheduling() {
    var user = user();
    var limits = new PerformanceEditSession(user); limits.edit(PerformancePreferences.Field.CACHE_MIB, "-");
    assertThrows(IllegalArgumentException.class, () -> limits.request(user, limits.text()));
    var scales = new UiScaleEditSession(user); scales.editGlobal("3");
    assertThrows(IllegalArgumentException.class, () -> scales.request(user, scales.text()));
    var trust = new UpdateTrustSession(user); trust.editEndpoint("http://untrusted.invalid");
    assertThrows(IllegalArgumentException.class, () -> trust.request(user, trust.text()));
    for (int kind = 0; kind < 3; kind++) {
      var form = new Form(user, kind); form.mismatch = true;
      assertThrows(IllegalStateException.class, form::request);
    }
  }

  @Test
  void reentrantInputGateCannotHideNewerLivePreferenceValues() throws Exception {
    var user = user(); var form = new Form(user, 0); var target = directory.resolve("user.json");
    Files.writeString(target, "previous"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    var mutate = new AtomicBoolean(false);
    try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
      work.start(form.request(), () -> { if (mutate.get()) user.workspace.docking = "newer"; return true; });
      deliver(worker, ui); assertTrue(work.ready()); mutate.set(true);
      assertFalse(work.accept()); assertEquals("newer", user.workspace.docking);
      assertEquals("previous", Files.readString(target)); assertEquals(128, user.performance.cacheMiB);
    }
  }

  @Test
  void successfulNoValueChangeStillConsumesApplyAndCanReloadExactRawState() throws Exception {
    var user = user(); var edit = new PerformanceEditSession(user);
    edit.edit(PerformancePreferences.Field.CACHE_MIB, "+128"); long version = edit.version();
    var before = ProjectIO.JSON.valueToTree(user); var target = directory.resolve("user.json");
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
      work.start(edit.request(user, edit.text()), () -> edit.ownsInput(user, version, edit.text()));
      worker.run(); var completion = ui.remove(); completion.run(); assertTrue(work.accept()); completion.run();
      assertFalse(work.accept()); assertEquals(before, ProjectIO.JSON.valueToTree(user));
      edit.revert(user); assertEquals("128", edit.text().get(PerformancePreferences.Field.CACHE_MIB));
    }
  }

  @Test
  void lateWorkerAfterClosureDisposesItsOwnSiblingWithoutActivating() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var cleaned = new CountDownLatch(1);
    var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException {
        Files.write(temp, bytes); entered.countDown(); boolean interrupted = false;
        try {
          long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          while (true) {
            try {
              if (!release.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)) throw new IOException("fixture timeout");
              break;
            } catch (InterruptedException cancelled) { interrupted = true; }
          }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
      }
      public void replace(Path temp, Path target) { fail("Retired worker published preferences"); }
      public void delete(Path temp) throws IOException { Files.delete(temp); cleaned.countDown(); }
    }));
    var form = new Form(user, 0); var target = directory.resolve("user.json"); Files.writeString(target, "previous");
    var ui = new LinkedBlockingQueue<Runnable>();
    try (var worker = Executors.newSingleThreadExecutor();
        var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
      start(work, form); assertTrue(entered.await(10, TimeUnit.SECONDS)); work.close(); release.countDown();
      assertTrue(cleaned.await(10, TimeUnit.SECONDS)); assertTrue(ui.isEmpty());
      assertEquals("previous", Files.readString(target)); assertEquals(128, user.performance.cacheMiB);
    } finally { release.countDown(); }
  }

  @Test
  void directPreparedRequestConsumesEvenAnEqualValuePublication() throws Exception {
    var user = user(); var target = directory.resolve("user.json");
    var request = user.requestPerformance(PerformancePreferences.from(user));
    try (var first = request.prepare(target); var second = request.prepare(target)) {
      request.publish(first, user); byte[] accepted = Files.readAllBytes(target);
      assertFalse(request.current(user));
      assertThrows(IllegalStateException.class, () -> request.publish(second, user));
      assertArrayEquals(accepted, Files.readAllBytes(target));
    }
  }

  @Test
  void cleanupAndReportingErrorsCannotStrandACancelledFormOrItsSuccessor() throws Exception {
    var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); }
      public void replace(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
      public void delete(Path temp) throws IOException { Files.delete(temp); throw new AssertionError("cleanup"); }
    }));
    var form = new Form(user, 0); var target = directory.resolve("user.json");
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    try (var work = new PreferenceApplySession(user, target, worker, ui::add,
        text -> { throw new AssertionError("report"); })) {
      start(work, form); deliver(worker, ui); assertDoesNotThrow(work::cancel);
      assertFalse(work.running()); start(work, form); deliver(worker, ui);
      assertTrue(work.accept()); assertEquals(256, user.performance.cacheMiB);
    }
  }


  @Test
  void completePerformancePatchPreservesAllFourteenFieldsAndExactLongBudget() throws Exception {
    var user = user(); var edit = new PerformanceEditSession(user);
    var raw = Map.ofEntries(
        Map.entry(PerformancePreferences.Field.WORKERS, "4"),
        Map.entry(PerformancePreferences.Field.MAX_DIMENSION, "64"),
        Map.entry(PerformancePreferences.Field.DEFAULT_EXTENT, "16"),
        Map.entry(PerformancePreferences.Field.PREVIEW_CELLS, "9007199254740993"),
        Map.entry(PerformancePreferences.Field.CACHE_MIB, "256"),
        Map.entry(PerformancePreferences.Field.ENGINE_MIB, "1024"),
        Map.entry(PerformancePreferences.Field.SCENE_CPU_MIB, "256"),
        Map.entry(PerformancePreferences.Field.SCENE_GPU_MIB, "64"),
        Map.entry(PerformancePreferences.Field.UPLOAD_KIB, "4096"),
        Map.entry(PerformancePreferences.Field.RENDER_DISTANCE, "2"),
        Map.entry(PerformancePreferences.Field.VERTICAL_DISTANCE, "2"),
        Map.entry(PerformancePreferences.Field.LOD_START, "3"),
        Map.entry(PerformancePreferences.Field.LOD_QUALITY, "2"),
        Map.entry(PerformancePreferences.Field.STREAM_CHUNKS, "512"));
    raw.forEach(edit::edit);
    var expected = (ObjectNode) ProjectIO.JSON.valueToTree(user);
    var performance = (ObjectNode) expected.path("performance");
    performance.put("workers", 4).put("maxPreviewDimension", 64)
        .put("previewCellBudget", 9007199254740993L).put("cacheMiB", 256).put("engineMiB", 1024)
        .put("sceneCpuMiB", 256).put("sceneGpuMiB", 64).put("uploadKiB", 4096)
        .put("renderDistance", 2).put("verticalDistance", 2).put("lodStart", 3)
        .put("lodQuality", 2).put("streamChunks", 512);
    ((ObjectNode) expected.path("visualization")).put("previewSize", 16);
    long version = edit.version();
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var target = directory.resolve("user.json");
    try (var work = new PreferenceApplySession(user, target, worker, ui::add, text -> {})) {
      work.start(edit.request(user, edit.text()), () -> edit.ownsInput(user, version, edit.text()));
      deliver(worker, ui); assertTrue(work.accept());
      assertEquals(expected, ProjectIO.JSON.valueToTree(user));
      assertEquals(expected, ProjectIO.JSON.readTree(target.toFile()));
      assertEquals(9007199254740993L, user.performance.previewCellBudget);
    }
  }

}
