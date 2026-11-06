package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred coherent explicit/pointer context and pre-candidate diagnostic snapshot ownership. */
class DiagnosticContextOwnershipTest {
  private record Fixture(ProjectData project, Noise a, Noise b, Item first, Item other, Membership group) {}
  private static Fixture fixture() {
    var p = new ProjectData(); var a = new Noise(); a.id = "a"; a.uuid = new UUID(0, 0xabc);
    var b = new Noise(); b.id = "b"; b.uuid = new UUID(0, 0xdef);
    var first = new Item(); first.id = "first"; first.uuid = new UUID(0, 0xabc1);
    var other = new Item(); other.id = "other"; other.uuid = new UUID(0, 0xdef1);
    a.processing.add(first); b.processing.add(other); p.noise2D.addAll(List.of(a, b));
    var group = new Membership(); group.id = "group"; group.uuid = new UUID(0, 0x123); p.voxelGroups.add(group);
    return new Fixture(p, a, b, first, other, group);
  }
  private static Diagnostic issue(String path, UUID source, UUID item, String parameter) {
    return new Diagnostic("ORIGINAL", Diagnostic.Severity.WARNING, "original text", path, source, item, parameter);
  }
  private static Diagnostic enrich(Fixture f, Diagnostic input) {
    var result = DiagnosticLocations.enrich(f.project, List.of(input)).getFirst();
    assertEquals(input.code(), result.code()); assertEquals(input.severity(), result.severity());
    assertEquals(input.message(), result.message()); assertEquals(input.jsonPointer(), result.jsonPointer()); return result;
  }

  @Test
  void conflictingExplicitIdentitiesCannotBorrowUnrelatedControlOwnerOrParameter() {
    var f = fixture(); String path = "/noise2D/1/processing/0/parameters/expression";
    var source = enrich(f, issue(path, f.a.uuid, null, null));
    assertEquals(f.a.uuid, source.sourceUuid()); assertNull(source.itemUuid()); assertNull(source.parameterKey());
    var control = enrich(f, issue(path, null, f.first.uuid, null));
    assertNull(control.sourceUuid()); assertEquals(f.first.uuid, control.itemUuid()); assertNull(control.parameterKey());
    var both = enrich(f, issue(path, f.a.uuid, f.first.uuid, null));
    assertEquals(f.a.uuid, both.sourceUuid()); assertEquals(f.first.uuid, both.itemUuid()); assertNull(both.parameterKey());
    var explicit = issue(path, f.a.uuid, f.first.uuid, "inputs/owned~/key"); assertEquals(explicit, enrich(f, explicit));
    var contradictory = enrich(f, issue("/noise2D/0/processing/0/parameters/expression", f.b.uuid, f.first.uuid, null));
    assertEquals(f.b.uuid, contradictory.sourceUuid()); assertEquals(f.first.uuid, contradictory.itemUuid()); assertNull(contradictory.parameterKey());
  }

  @Test
  void agreeingPartialContextStillResolvesEscapedPropertiesAndUppercaseUuidPaths() {
    var f = fixture(); String path = "/noise2D/0/processing/0/inputs/a~1b~0c/constant";
    for (var input : List.of(issue(path, f.a.uuid, null, null), issue(path, null, f.first.uuid, null), issue(path, null, null, null))) {
      var result = enrich(f, input); assertEquals(f.a.uuid, result.sourceUuid()); assertEquals(f.first.uuid, result.itemUuid()); assertEquals("a/b~c", result.parameterKey());
    }
    var uuidPath = "/noise/" + f.a.uuid.toString().toUpperCase(Locale.ROOT) + "/processing/" + f.first.uuid.toString().toUpperCase(Locale.ROOT) + "/parameters/expression";
    var result = enrich(f, issue(uuidPath, null, null, "owned"));
    assertEquals(f.a.uuid, result.sourceUuid()); assertEquals(f.first.uuid, result.itemUuid()); assertEquals("owned", result.parameterKey());
  }

  @Test
  void reorderingCannotMixPartialAuthorityAndCapturedFullContextRemainsStable() {
    var f = fixture(); String path = "/noise2D/0/processing/0/parameters/expression";
    var partial = issue(path, f.a.uuid, null, null); var captured = enrich(f, partial);
    Collections.reverse(f.project.noise2D);
    var later = enrich(f, partial); assertEquals(f.a.uuid, later.sourceUuid()); assertNull(later.itemUuid()); assertNull(later.parameterKey());
    assertEquals(captured, enrich(f, captured));
    Collections.reverse(f.project.noise2D); var replacement = new Item(); replacement.id = "replacement"; f.a.processing.addFirst(replacement);
    var itemOnly = enrich(f, issue(path, null, f.first.uuid, null));
    assertNull(itemOnly.sourceUuid()); assertEquals(f.first.uuid, itemOnly.itemUuid()); assertNull(itemOnly.parameterKey());
    assertEquals(captured, enrich(f, captured));
  }

  @Test
  void unresolvedAliasesAndNoncanonicalIndicesCannotInventResourceOrControlIdentity() {
    var f = fixture(); UUID missing = new UUID(0, 0x999);
    for (String path : List.of("/noise/" + missing, "/membership/" + missing, "/noise/0-0-0-0-abc",
        "/noise2D/+0/processing/0/parameters/expression", "/noise2D/00/processing/0/parameters/expression", "/noise2D/01/processing/0/parameters/expression")) {
      var result = enrich(f, issue(path, null, null, null)); assertNull(result.sourceUuid()); assertNull(result.itemUuid());
    }
    for (String index : List.of("+0", "00", "-0", "2147483648")) {
      var result = enrich(f, issue("/noise2D/0/processing/" + index + "/parameters/expression", null, null, null));
      assertEquals(f.a.uuid, result.sourceUuid()); assertNull(result.itemUuid());
    }
    assertEquals(f.group.uuid, enrich(f, issue("/membership/" + f.group.uuid, null, null, null)).sourceUuid());
  }

  @Test
  void diagnosticsAreCapturedBeforeCandidateSerializationCanReplaceTheCallerList() {
    for (boolean reject : new boolean[] {false, true}) {
      var original = issue("/", null, null, null); var source = new ArrayList<>(List.of(original)); var reads = new AtomicInteger(); var changed = new AtomicBoolean();
      var p = new ProjectData();
      p.noise2D = new AbstractList<>() {
        public int size() {
          reads.incrementAndGet(); if (changed.compareAndSet(false, true)) { source.clear(); source.add(Diagnostic.error("LATE", "/", "replacement")); }
          if (reject) throw new IllegalStateException("serialization rejected"); return 0;
        }
        public Noise get(int index) { throw new AssertionError("Unexpected element read"); }
      };
      var result = DiagnosticLocations.enrich(p, source);
      assertTrue(reads.get() > 0); assertEquals("LATE", source.getFirst().code()); assertEquals(List.of(original), result);
      assertThrows(UnsupportedOperationException.class, result::clear); source.clear(); assertEquals(List.of(original), result);
    }
  }

  @Test
  void nullRowsRejectAndEmptyDiagnosticsReturnBeforeCandidateAccess() {
    var reads = new AtomicInteger(); var p = new ProjectData();
    p.noise2D = new AbstractList<>() { public int size() { reads.incrementAndGet(); throw new AssertionError("Candidate read"); } public Noise get(int i) { throw new AssertionError(); } };
    assertThrows(NullPointerException.class, () -> DiagnosticLocations.enrich(p, null));
    assertThrows(NullPointerException.class, () -> DiagnosticLocations.enrich(p, Arrays.asList((Diagnostic) null)));
    var source = new ArrayList<Diagnostic>(); var empty = DiagnosticLocations.enrich(p, source); source.add(issue("/", null, null, null));
    assertTrue(empty.isEmpty()); assertThrows(UnsupportedOperationException.class, () -> empty.add(source.getFirst())); assertEquals(0, reads.get());
  }

  @Test
  void completedValidationStillEnrichesOrdinaryCandidateLocationsWithoutMutation() throws Exception {
    var p = new ProjectData(); var noise = new Noise(); noise.id = ""; p.noise2D.add(noise);
    String authored = ProjectIO.JSON.writeValueAsString(p);
    var issue = Validator.validate(p).stream().filter(d -> d.code().equals("INVALID_ID") && d.jsonPointer().equals("/noise2D/0/id")).findFirst().orElseThrow();
    assertEquals(noise.uuid, issue.sourceUuid()); assertNull(issue.itemUuid()); assertEquals("id", issue.parameterKey());
    assertEquals(authored, ProjectIO.JSON.writeValueAsString(p));
  }
}
