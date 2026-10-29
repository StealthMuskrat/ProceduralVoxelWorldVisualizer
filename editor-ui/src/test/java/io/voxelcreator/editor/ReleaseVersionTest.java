package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Deferred independent SemVer precedence and strict grammar expectations. */
class ReleaseVersionTest {
  @Test
  void canonicalPrereleaseOrderIsStrictAndBuildMetadataDoesNotChangePrecedence() {
    var ordered =
        List.of(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0");
    for (int i = 0; i < ordered.size(); i++)
      for (int j = 0; j < ordered.size(); j++)
        assertEquals(
            Integer.signum(i - j),
            Integer.signum(
                ReleaseVersion.parse(ordered.get(i))
                    .compareTo(ReleaseVersion.parse(ordered.get(j)))));
    assertEquals(
        0,
        ReleaseVersion.parse("1.0.0+build.001").compareTo(ReleaseVersion.parse("1.0.0+another")));
  }

  @Test
  void numericComparisonHasNoMachineIntegerOverflowAndNumericPrereleasePrecedesText() {
    assertTrue(
        ReleaseVersion.parse("999999999999999999999999999.0.0")
                .compareTo(ReleaseVersion.parse("99999999999999999999999999.99.99"))
            > 0);
    assertTrue(
        ReleaseVersion.parse("1.0.0-999999999999999999999999999")
                .compareTo(ReleaseVersion.parse("1.0.0-a"))
            < 0);
    assertTrue(ReleaseVersion.parse("1.0.0-10").compareTo(ReleaseVersion.parse("1.0.0-2")) > 0);
    assertTrue(ReleaseVersion.parse("1.0.0-A").compareTo(ReleaseVersion.parse("1.0.0-a")) < 0);
  }

  @Test
  void malformedAndOversizedVersionsRejectBeforeComparison() {
    for (var value :
        List.of(
            "",
            "v1.0.0",
            "1.0",
            "01.0.0",
            "1.00.0",
            "1.0.01",
            "1.0.0-01",
            "1.0.0-a.01",
            "1.0.0-",
            "1.0.0+",
            "1.0.0+a..b",
            "1.0.0-a_b",
            "1.0.0 ",
            "1.0.0+" + "x".repeat(251)))
      assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(value), value);
  }
}
