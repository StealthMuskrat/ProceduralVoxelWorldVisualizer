package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.Diagnostic;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Deferred definitions: presentation bounds never invent equivalent events or destinations. */
class EditorEventIdentityTest {
  private final UUID project = UUID.randomUUID(),
      resource = UUID.randomUUID(),
      control = UUID.randomUUID();
  private final EditorEvents events =
      new EditorEvents(10, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

  private EditorEvents.Target target(String parameter, String pointer) {
    return new EditorEvents.Target(project, resource, control, parameter, pointer, false);
  }

  private EditorEvents.Entry publish(String code, String message, EditorEvents.Target target) {
    return events.publish(
        code, Diagnostic.Severity.ERROR, EditorEvents.Subsystem.PROJECT, message, target, true);
  }

  private void resourceOnly(EditorEvents.Target target) {
    assertEquals(project, target.project());
    assertEquals(resource, target.resource());
    assertNull(target.control());
    assertNull(target.parameter());
    assertEquals("", target.pointer());
    assertFalse(target.settings());
  }

  @Test
  void currentDiagnosticTargetsRetainExactLongKeysAndPointersBeforeHistoryRetention() {
    String name = "a".repeat(300);
    String parameter = "inputs/" + name;
    String pointer = "/noise2D/0/processing/0/inputs/" + name + "/type";
    var diagnostic =
        new Diagnostic(
            "BAD", Diagnostic.Severity.ERROR, "bad", pointer, resource, control, parameter);
    var exact = EditorEvents.Target.diagnostic(project, diagnostic);
    assertEquals(parameter, exact.parameter());
    assertEquals(pointer, exact.pointer());
    assertTrue(DiagnosticNavigation.inputTarget(exact, control, name));
    String clippedName = parameter.substring(0, 255).substring(7) + "…";
    assertFalse(DiagnosticNavigation.inputTarget(exact, control, clippedName));
    var retained = publish("BAD", "bad", exact).target();
    resourceOnly(retained);
    assertFalse(DiagnosticNavigation.inputTarget(retained, control, clippedName));
    assertEquals(parameter, exact.parameter());
    String longPointer = pointer + "/" + "x".repeat(2048);
    var longPath =
        EditorEvents.Target.diagnostic(
            project,
            new Diagnostic(
                "BAD",
                Diagnostic.Severity.ERROR,
                "bad",
                longPointer,
                resource,
                control,
                "expression"));
    assertEquals(longPointer, longPath.pointer());
  }

  @Test
  void distinctCodesBeyondTheDisplayBoundNeverShareAnOccurrenceGroup() {
    String prefix = "C".repeat(100);
    var a = publish(prefix + "a", "same", null);
    var b = publish(prefix + "b", "same", null);
    assertEquals(96, a.code().length());
    assertEquals(a.code(), b.code());
    assertNotEquals(a.id(), b.id());
    var repeat = publish(prefix + "a", "same", null);
    assertEquals(a.id(), repeat.id());
    assertEquals(2, repeat.count());
    assertEquals(1, events.get(b.id()).orElseThrow().count());
  }

  @Test
  void longParameterSuffixesAndOriginalControlUuidsRemainDistinctAfterResourceFallback() {
    String prefix = "inputs/" + "a".repeat(300);
    var a = publish("BAD", "same", target(prefix + "a", "/runtime"));
    var b = publish("BAD", "same", target(prefix + "b", "/runtime"));
    var other =
        publish(
            "BAD",
            "same",
            new EditorEvents.Target(
                project, resource, UUID.randomUUID(), prefix + "a", "/runtime", false));
    resourceOnly(a.target());
    assertEquals(a.target(), b.target());
    assertEquals(a.target(), other.target());
    assertEquals(3, Set.of(a.id(), b.id(), other.id()).size());
    var repeat = publish("BAD", "same", target(prefix + "a", "/runtime"));
    assertEquals(a.id(), repeat.id());
    assertEquals(2, repeat.count());
  }

  @Test
  void oversizedPointersNeverBecomeTruncatedControlOrProjectSettingsHints() {
    String prefix = "/noise2D/0/processing/0/parameters/" + "x".repeat(2048);
    var a = publish("BAD", "same", target("parameters/value/2", prefix + "a"));
    var b = publish("BAD", "same", target("parameters/value/2", prefix + "b"));
    resourceOnly(a.target());
    assertEquals(a.target(), b.target());
    assertNotEquals(a.id(), b.id());
    var focus = new PropertyFocus();
    focus.request(a.target(), new Object());
    assertFalse(focus.matches(control, "parameters/value/2"));
    assertFalse(focus.matches(null, "parameters/value/0"));
    var settings =
        publish(
            "BAD",
            "settings",
            new EditorEvents.Target(
                project, null, null, "seed", "/project/seed/" + "x".repeat(2048), true));
    assertEquals(project, settings.target().project());
    assertNull(settings.target().resource());
    assertFalse(settings.target().settings());
    assertNull(ProjectSettingsSession.field(settings.target()));
  }

  @Test
  void fingerprintFramesFieldsNullsAndSettingsScopeWithoutConcatenationAliases() {
    String prefix = "x".repeat(300);
    var ids = new HashSet<Long>();
    for (var original :
        List.of(
            target(prefix + "a", "bc"),
            target(prefix + "ab", "c"),
            target(prefix, null),
            target(prefix, ""),
            new EditorEvents.Target(project, resource, control, prefix, "", true))) {
      var entry = publish("BAD", "same", original);
      assertTrue(ids.add(entry.id()));
      resourceOnly(entry.target());
    }
    assertEquals(5, events.counts().retained());
  }

  @Test
  void originalUtf16SuffixesRemainDistinctEvenWhenUtf8WouldReplaceThem() {
    String prefix = "x".repeat(8192);
    var ids = new HashSet<Long>();
    for (String suffix :
        List.of(String.valueOf((char) 0xd800), String.valueOf((char) 0xd801), "?")) {
      var entry = publish("BAD", prefix + suffix, null);
      assertTrue(ids.add(entry.id()));
      assertEquals(8192, entry.message().length());
      var repeated = publish("BAD", prefix + suffix, null);
      assertEquals(entry.id(), repeated.id());
      assertEquals(2, repeated.count());
    }
    assertEquals(3, events.counts().retained());
  }

  @Test
  void displayClippingDoesNotSplitAnExistingSupplementaryCharacter() {
    String pair = new String(Character.toChars(0x1f642));
    String code = "c".repeat(94) + pair + "tail";
    String message = "m".repeat(8190) + pair + "tail";
    var entry = publish(code, message, null);
    assertEquals("c".repeat(94) + "…", entry.code());
    assertEquals("m".repeat(8190) + "…", entry.message());
    assertTrue(entry.code().length() <= 96);
    assertTrue(entry.message().length() <= 8192);
    assertEquals(entry.id(), publish(code, message, null).id());
  }

  @Test
  void exactLimitsAndExistingNullDefaultsRemainCompatible() {
    var exact = target("p".repeat(256), "/" + "p".repeat(2047));
    var entry = publish("C".repeat(96), "m".repeat(8192), exact);
    assertEquals(exact, entry.target());
    assertEquals(96, entry.code().length());
    assertEquals(8192, entry.message().length());
    var defaults = publish(null, null, null);
    var repeat = publish("UNSPECIFIED", "No details supplied", null);
    assertEquals(defaults.id(), repeat.id());
    assertEquals(2, repeat.count());
    var emptyTarget =
        publish(
            "UNSPECIFIED",
            "No details supplied",
            new EditorEvents.Target(null, null, null, null, null, false));
    assertNotEquals(defaults.id(), emptyTarget.id());
  }

  @Test
  void collidingPresentationsKeepIndependentAcknowledgementRecurrenceAndEviction() {
    var store = new EditorEvents(2, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    String prefix = "inputs/" + "x".repeat(300);
    var a =
        store.publish(
            "BAD", Diagnostic.Severity.ERROR, EditorEvents.Subsystem.PROJECT, "same",
            target(prefix + "a", ""), true);
    var b =
        store.publish(
            "BAD", Diagnostic.Severity.ERROR, EditorEvents.Subsystem.PROJECT, "same",
            target(prefix + "b", ""), true);
    var reviewed = store.view();
    assertTrue(store.markRead(a));
    store.resolve(a.id());
    assertTrue(store.dismiss(a.id()));
    assertSame(b, store.get(b.id()).orElseThrow());
    assertEquals(1, store.counts().unread());
    assertEquals(1, store.counts().actionRequired());
    var repeat =
        store.publish(
            "BAD", Diagnostic.Severity.ERROR, EditorEvents.Subsystem.PROJECT, "same",
            target(prefix + "a", ""), false);
    assertEquals(a.id(), repeat.id());
    assertEquals(2, repeat.count());
    assertFalse(repeat.dismissed());
    assertFalse(repeat.read());
    assertFalse(repeat.actionRequired());
    assertEquals(2, reviewed.counts().unread());
    store.publish(
        "NEW", Diagnostic.Severity.ERROR, EditorEvents.Subsystem.PROJECT, "new", null, false);
    assertTrue(store.get(b.id()).isEmpty());
    assertTrue(store.get(a.id()).isPresent());
    assertEquals(1, store.counts().evicted());
  }

  @Test
  void concurrentLongDestinationsGroupOnlyTheirOwnExactOccurrences() throws Exception {
    String prefix = "inputs/" + "x".repeat(300);
    try (var workers = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 4; i++) {
        var original = target(prefix + i, "/runtime");
        jobs.add(
            () -> {
              for (int n = 0; n < 64; n++) publish("BAD", "same", original);
              return null;
            });
      }
      for (var job : workers.invokeAll(jobs)) job.get();
    }
    assertEquals(4, events.counts().retained());
    assertEquals(0, events.counts().evicted());
    for (var entry : events.snapshot()) {
      assertEquals(64, entry.count());
      resourceOnly(entry.target());
    }
  }
}
