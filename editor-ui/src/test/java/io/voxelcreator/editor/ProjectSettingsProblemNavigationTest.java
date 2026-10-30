package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.Diagnostic;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred correction navigation: exact display precedes supported candidate locations. */
class ProjectSettingsProblemNavigationTest {
  private Map<String, String> text() {
    return new LinkedHashMap<>(Map.of("seed", "+00017", "minY", "-", "maxY", "+003"));
  }

  @Test
  void agreeingRawTextIsNeverParsedOrNormalizedByNavigation() {
    var raw = text();
    assertNull(ProjectSettingsProblemNavigation.first(raw, Map.copyOf(raw), List.of(), List.of()));
    assertEquals(Map.of("seed", "+00017", "minY", "-", "maxY", "+003"), raw);
  }

  @Test
  void displayMismatchPrecedesFeedbackWithoutRepairingAnOversizedBuffer() {
    var owned = text();
    var displayed = text();
    displayed.put("maxY", "1".repeat(129));
    assertEquals(
        "maxY",
        ProjectSettingsProblemNavigation.first(
            owned,
            displayed,
            List.of(Diagnostic.error("INVALID", "/project/seed", "Invalid")),
            List.of()));
    assertEquals("+003", owned.get("maxY"));
    assertEquals(129, displayed.get("maxY").length());
  }

  @Test
  void missingAndNumericallyEquivalentDisplayUseFieldOrder() {
    var owned = text();
    var displayed = text();
    displayed.remove("maxY");
    displayed.put("seed", "17");
    assertEquals(
        "seed", ProjectSettingsProblemNavigation.first(owned, displayed, List.of(), List.of()));
    displayed.put("seed", owned.get("seed"));
    assertEquals(
        "maxY", ProjectSettingsProblemNavigation.first(owned, displayed, List.of(), List.of()));
    assertEquals("+00017", owned.get("seed"));
  }

  @Test
  void candidateAndLocalFeedbackUseCanonicalFieldOrderWithoutRewritingBounds() {
    var raw = text();
    assertEquals(
        "minY",
        ProjectSettingsProblemNavigation.first(
            raw,
            raw,
            List.of(Diagnostic.error("BOUNDS", "/project/maxY", "Bounds")),
            List.of(Diagnostic.error("CANDIDATE", "/project/minY", "Minimum"))));
    assertEquals("-", raw.get("minY"));
    assertEquals("+003", raw.get("maxY"));
  }

  @Test
  void resourceAndUnsupportedMetadataDiagnosticsDoNotInventASettingsField() {
    var raw = text();
    var problems =
        List.of(
            new Diagnostic(
                "RESOURCE",
                Diagnostic.Severity.ERROR,
                "Resource",
                "/project/seed",
                UUID.randomUUID(),
                null,
                "seed"),
            new Diagnostic(
                "CONTROL",
                Diagnostic.Severity.ERROR,
                "Control",
                "/project/maxY",
                null,
                UUID.randomUUID(),
                "maxY"),
            Diagnostic.error("FORMAT", "/formatVersion", "Format"),
            Diagnostic.error("ORDER", "/project/rootOrder/noise2D/0", "Order"));
    assertNull(ProjectSettingsProblemNavigation.first(raw, raw, problems, List.of()));
  }

  @Test
  void exactCandidateProjectPointerIsRevealableAndDoesNotNeedAGlobalIssueList() {
    var raw = text();
    assertEquals(
        "maxY",
        ProjectSettingsProblemNavigation.first(
            raw, raw, List.of(), List.of(Diagnostic.error("BOUNDS", "/project", "Bounds"))));
    assertEquals(
        "seed",
        ProjectSettingsProblemNavigation.first(
            raw,
            raw,
            List.of(),
            List.of(
                new Diagnostic(
                    "SEED",
                    Diagnostic.Severity.ERROR,
                    "Seed",
                    "/project/seed",
                    null,
                    null,
                    "project/seed"))));
  }
}
