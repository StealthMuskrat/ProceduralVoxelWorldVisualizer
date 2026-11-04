package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import imgui.flag.ImGuiCol;
import io.voxelcreator.model.ProjectIO;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred theme/binding snapshot, preview-owner and recording-epoch definitions. */
class BackgroundAppearanceInputTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    public void execute(Runnable task) { tasks.add(task); }
    void run() { tasks.remove().run(); }
  }

  static ThemePalette palette() {
    return ThemePalette.starting("Light")
        .color(ImGuiCol.Text, new ThemePalette.Rgba(.125f, .25f, .5f, 1))
        .status("Warning", new ThemePalette.Rgba(.5f, .25f, .125f, .75f));
  }

  static final class Form {
    final UserConfig user;
    final boolean theme;
    final ThemeEditSession appearance;
    final ShortcutEditSession bindings;
    Form(UserConfig user, boolean theme) {
      this.user = user; this.theme = theme;
      appearance = new ThemeEditSession(user);
      bindings = new ShortcutEditSession(user.input.shortcuts);
      if (theme) appearance.edit(palette()); else bindings.edit("Profiler", "alt+f9");
    }
    UserConfig.PreferenceRequest request() {
      return theme ? appearance.request(user, appearance.draft()) : bindings.request(user, bindings.values());
    }
    BooleanSupplier guard() {
      long version = theme ? appearance.version() : bindings.version();
      return () -> theme ? appearance.ownsInput(user, version, appearance.draft())
          : bindings.ownsInput(user, version, bindings.values());
    }
    void revert() {
      if (theme) appearance.revert(user); else bindings.revert(user.input.shortcuts);
    }
    Object raw() { return theme ? appearance.draft() : bindings.values(); }
  }

  static void deliver(Workers worker, Queue<Runnable> ui) { worker.run(); ui.remove().run(); }
  static void start(PreferenceApplySession work, Form form) { work.start(form.request(), form.guard()); }

  @Test
  void themePreparationKeepsLiveOwnersAndInstallsOnlyExactPaletteFields() throws Exception {
    var user = new UserConfig(); user.appearance.windowScales.put("Extension Pane", 1.75f);
    var openingAppearance = user.appearance; var openingInput = user.input;
    var form = new Form(user, true); var before = ProjectIO.JSON.valueToTree(user);
    var expected = (ObjectNode) before.deepCopy();
    var a = (ObjectNode) expected.path("appearance"); a.put("theme", "Light");
    ((ObjectNode) a.path("colors")).putArray(Integer.toString(ImGuiCol.Text)).add(.125f).add(.25f).add(.5f).add(1f);
    ((ObjectNode) a.path("statusColors")).putArray("Warning").add(.5f).add(.25f).add(.125f).add(.75f);
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = directory.resolve("theme.json");
    Files.writeString(file, "previous");
    try (var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {})) {
      start(work, form); deliver(worker, ui);
      assertEquals(before, ProjectIO.JSON.valueToTree(user)); assertEquals("previous", Files.readString(file));
      assertTrue(work.accept()); assertFalse(work.accept());
      assertSame(openingAppearance, user.appearance); assertSame(openingInput, user.input);
      assertEquals(expected, ProjectIO.JSON.valueToTree(user));
      assertEquals(palette(), ThemePalette.from(user.appearance));
      assertEquals(ProjectIO.JSON.valueToTree(user), ProjectIO.JSON.readTree(file.toFile()));
    }
  }

  @Test
  void bindingPreparationCanonicalizesOnlyDetachedMapAndReloadIsExplicit() throws Exception {
    var user = new UserConfig(); var owner = user.input; var appearance = user.appearance;
    var form = new Form(user, false); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    var file = directory.resolve("bindings.json");
    var expected = (ObjectNode) ProjectIO.JSON.valueToTree(user);
    ((ObjectNode) expected.path("input").path("shortcuts")).put("Profiler", "Alt+F9");
    try (var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {})) {
      start(work, form); deliver(worker, ui);
      assertEquals("Ctrl+Shift+P", user.input.shortcuts.get("Profiler"));
      assertTrue(work.accept()); assertSame(owner, user.input); assertSame(appearance, user.appearance);
      assertEquals(expected, ProjectIO.JSON.valueToTree(user));
      assertEquals("alt+f9", form.bindings.values().get("Profiler"));
      form.revert(); assertEquals("Alt+F9", form.bindings.values().get("Profiler"));
    }
  }

  @Test
  void equalValueThemeAndBindingRequestsAreSingleUse() throws Exception {
    for (boolean theme : new boolean[] {true, false}) {
      var user = new UserConfig(); var form = new Form(user, theme); form.revert();
      var request = form.request(); var file = directory.resolve("equal.json");
      try (var first = request.prepare(file); var second = request.prepare(file)) {
        request.publish(first, user); byte[] accepted = Files.readAllBytes(file);
        assertFalse(request.current(user));
        assertThrows(IllegalStateException.class, () -> request.publish(second, user));
        assertArrayEquals(accepted, Files.readAllBytes(file));
      }
    }
  }

  @Test
  void unrelatedPreferencesCancelOldSnapshotsAndExplicitRetryComposesLatestValues() throws Exception {
    for (boolean theme : new boolean[] {true, false}) {
      var user = new UserConfig(); var form = new Form(user, theme);
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = directory.resolve("latest.json");
      Files.writeString(file, "previous");
      try (var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {})) {
        start(work, form); worker.run(); user.workspace.docking = "latest docking"; ui.remove().run();
        assertFalse(work.accept()); assertEquals("previous", Files.readString(file));
        assertTrue(theme ? form.appearance.current(user) : form.bindings.current(user));
        start(work, form); deliver(worker, ui); assertTrue(work.accept());
        assertEquals("latest docking", user.workspace.docking);
        assertEquals(ProjectIO.JSON.valueToTree(user), ProjectIO.JSON.readTree(file.toFile()));
      }
    }
  }

  @Test
  void equivalentEditsAndEqualRevertRetirePrivateAuthority() throws Exception {
    for (boolean theme : new boolean[] {true, false}) for (boolean revert : new boolean[] {true, false}) {
      var user = new UserConfig(); var form = new Form(user, theme); var guard = form.guard();
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = directory.resolve("raw.json");
      Files.writeString(file, "previous");
      try (var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {})) {
        work.start(form.request(), guard); deliver(worker, ui);
        if (revert) form.revert();
        else if (theme) form.appearance.edit(palette()); else form.bindings.edit("Profiler", "Alt+F9");
        assertFalse(guard.getAsBoolean()); assertFalse(work.accept());
        assertEquals("previous", Files.readString(file));
      }
    }
  }

  @Test
  void mismatchedDisplaysConflictsAndStaleDomainsRejectBeforeScheduling() {
    var user = new UserConfig(); var theme = new ThemeEditSession(user);
    assertThrows(IllegalStateException.class, () -> theme.request(user, palette()));
    var bindings = new ShortcutEditSession(user.input.shortcuts);
    assertThrows(IllegalStateException.class, () -> bindings.request(user, Map.of()));
    bindings.edit("Profiler", "Ctrl+S");
    assertThrows(IllegalArgumentException.class, () -> bindings.request(user, bindings.values()));
    user.appearance.theme = "Light"; user.input.shortcuts.put("Help", "F6");
    assertThrows(IllegalStateException.class, () -> theme.request(user, theme.draft()));
    assertThrows(IllegalStateException.class, () -> bindings.request(user, bindings.values()));
  }

  @Test
  void completionReadyCancelAndCloseKeepRawDraftAndRejectDuplicateDelivery() throws Exception {
    for (boolean theme : new boolean[] {true, false}) for (boolean close : new boolean[] {true, false}) {
      var user = new UserConfig(); var form = new Form(user, theme); var raw = form.raw();
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = directory.resolve("cancel.json");
      Files.writeString(file, "previous");
      try (var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {})) {
        start(work, form); worker.run(); var completion = ui.remove(); completion.run();
        assertTrue(work.ready()); if (close) work.close(); else work.cancel(); completion.run();
        assertFalse(work.accept()); assertEquals(raw, form.raw()); assertEquals("previous", Files.readString(file));
        if (!close) { start(work, form); completion.run(); deliver(worker, ui); assertTrue(work.accept()); }
      }
    }
  }

  @Test
  void competingThemeAndBindingsSerializeByFullActiveSnapshot() throws Exception {
    var user = new UserConfig(); var theme = new Form(user, true); var bindings = new Form(user, false);
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = directory.resolve("competing.json");
    try (var a = new PreferenceApplySession(user, file, worker, ui::add, text -> {});
        var b = new PreferenceApplySession(user, file, worker, ui::add, text -> {})) {
      start(a, theme); start(b, bindings); deliver(worker, ui); deliver(worker, ui);
      assertTrue(a.accept()); byte[] accepted = Files.readAllBytes(file);
      assertFalse(b.accept()); assertArrayEquals(accepted, Files.readAllBytes(file));
      start(b, bindings); deliver(worker, ui); assertTrue(b.accept());
      assertEquals(palette(), ThemePalette.from(user.appearance));
      assertEquals("Alt+F9", user.input.shortcuts.get("Profiler"));
    }
  }

  static final class Style implements ThemePreview.Style {
    Object stamp = new Object(); ThemePalette shown;
    public Object owner() { return stamp; }
    public Object apply(ThemePalette palette) { shown = palette; return stamp = new Object(); }
  }

  @Test
  void foreignNativeStyleRetiresReadySaveAndClosePreservesItsOwnership() throws Exception {
    var user = new UserConfig(); var form = new Form(user, true); var style = new Style();
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = directory.resolve("foreign.json");
    Files.writeString(file, "previous");
    try (var preview = new ThemePreview(user, form.appearance, style);
        var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {})) {
      preview.refresh(); Object stamp = style.owner(); var input = form.guard();
      work.start(form.request(), () -> !preview.paused() && style.owner() == stamp && input.getAsBoolean());
      deliver(worker, ui); var foreign = ThemePalette.starting("Dark"); style.apply(foreign);
      Object foreignStamp = style.owner(); preview.refresh();
      assertTrue(preview.paused()); assertFalse(work.accept()); preview.close();
      assertSame(foreignStamp, style.owner()); assertEquals(foreign, style.shown);
      assertEquals("previous", Files.readString(file)); assertEquals(palette(), form.appearance.draft());
    }
  }

  @Test
  void acceptedThemeReloadAndPreviewCloseKeepTheSavedPalette() throws Exception {
    var user = new UserConfig(); var form = new Form(user, true); var style = new Style();
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = directory.resolve("accepted.json");
    try (var preview = new ThemePreview(user, form.appearance, style);
        var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {})) {
      preview.refresh(); start(work, form); deliver(worker, ui); assertTrue(work.accept());
      form.revert(); preview.refresh(); preview.close();
      assertEquals(palette(), style.shown); assertEquals(palette(), ThemePalette.from(user.appearance));
    }
  }

  @Test
  void recordingStartCancelAndCaptureRetireAnOlderApplyEvenAfterSuppressionClears() throws Exception {
    for (boolean recordChord : new boolean[] {true, false}) {
      var user = new UserConfig(); var form = new Form(user, false); var capture = new ShortcutRecording(user);
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = directory.resolve("recording.json");
      Files.writeString(file, "previous"); long epoch = capture.epoch(); var input = form.guard();
      try (var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {})) {
        work.start(form.request(), () -> capture.epoch() == epoch && !capture.suppressed() && input.getAsBoolean());
        deliver(worker, ui); assertTrue(capture.start(form.bindings, "Profiler"));
        if (recordChord) {
          capture.poll(form.bindings, true, false, false, Optional.empty());
          capture.poll(form.bindings, true, true, false, Shortcuts.parse("Alt+F8"));
          assertEquals("Alt+F8", form.bindings.values().get("Profiler"));
        } else capture.cancel();
        assertTrue(capture.suppressed()); capture.beginFrame(); assertFalse(capture.suppressed());
        assertNotEquals(epoch, capture.epoch()); assertFalse(work.accept());
        assertEquals("previous", Files.readString(file));
        assertEquals("Ctrl+Shift+P", user.input.shortcuts.get("Profiler"));
      }
    }
  }

  @Test
  void themeAndBindingWriteOrAtomicReplaceFailuresPreserveDraftForExplicitRetry() throws Exception {
    for (boolean theme : new boolean[] {true, false}) for (boolean writing : new boolean[] {true, false}) {
      var broken = new AtomicBoolean(true);
      var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
        public void write(Path temp, byte[] bytes) throws IOException {
          Files.write(temp, bytes); if (writing && broken.get()) throw new IOException("write");
        }
        public void replace(Path temp, Path target) throws IOException {
          if (!writing && broken.get()) throw new AtomicMoveNotSupportedException(temp.toString(), target.toString(), "provider");
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
      }));
      var form = new Form(user, theme); var raw = form.raw(); var before = ProjectIO.JSON.valueToTree(user);
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = directory.resolve("fault.json");
      Files.writeString(file, "previous");
      try (var work = new PreferenceApplySession(user, file, worker, ui::add, text -> {})) {
        start(work, form); deliver(worker, ui); assertFalse(work.accept());
        assertEquals(before, ProjectIO.JSON.valueToTree(user)); assertEquals(raw, form.raw());
        assertEquals("previous", Files.readString(file)); broken.set(false);
        start(work, form); deliver(worker, ui); assertTrue(work.accept());
      }
    }
  }
}
