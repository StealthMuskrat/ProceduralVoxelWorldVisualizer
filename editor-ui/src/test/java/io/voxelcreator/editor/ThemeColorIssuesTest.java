package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ThemeColorIssuesTest {
  private static final String ROW = "status:Warning";

  @Test
  void validComponentsStayUnblockedWithoutChangingTheCandidate() {
    var issues = new ThemeColorIssues();
    float[] values = {.1f, .2f, .3f, 1};
    assertTrue(issues.observe(ROW, values, 15));
    assertFalse(issues.any());
    assertArrayEquals(new float[] {.1f, .2f, .3f, 1}, values);
  }

  @Test
  void invalidComponentsRemainBlockingWhenAValidFallbackIsMerelyRendered() {
    var issues = new ThemeColorIssues();
    assertFalse(issues.observe(ROW, new float[] {Float.NaN, .2f, .3f, 1}, 1));
    assertTrue(issues.bad(ROW, 0));
    assertFalse(issues.observe(ROW, new float[] {.1f, .2f, .3f, 1}, 0));
    assertFalse(issues.observe(ROW, new float[] {.1f, .4f, .3f, 1}, 2));
    assertTrue(issues.any());
  }

  @Test
  void onlyExplicitCorrectionsOfTheAffectedAxesClearTheirBits() {
    var issues = new ThemeColorIssues();
    assertFalse(issues.observe(ROW, new float[] {-1, .2f, .3f, Float.POSITIVE_INFINITY}, 9));
    assertFalse(issues.observe(ROW, new float[] {.1f, .2f, .3f, 1}, 1));
    assertEquals(Set.of(3), issues.problems().get(ROW));
    assertTrue(issues.observe(ROW, new float[] {.1f, .2f, .3f, 1}, 8));
    assertFalse(issues.any());
  }

  @Test
  void resetsAreScopedAndSnapshotsCannotClearTheLedger() {
    var issues = new ThemeColorIssues();
    issues.observe(ROW, new float[] {2, 0, 0, 1}, 1);
    issues.observe("color:0", new float[] {0, 0, 0, -1}, 8);
    var snapshot = issues.problems();
    assertThrows(UnsupportedOperationException.class, snapshot::clear);
    assertThrows(UnsupportedOperationException.class, () -> snapshot.get(ROW).clear());
    issues.clear(ROW);
    assertTrue(issues.any());
    assertEquals(Set.of(0), snapshot.get(ROW));
    assertTrue(issues.blocked("color:0"));
    issues.clearAll();
    assertFalse(issues.any());
  }

  @Test
  void invalidUnflaggedChannelsStillBlockBeforePublication() {
    var issues = new ThemeColorIssues();
    assertFalse(issues.observe(ROW, new float[] {0, 0, Float.NaN, 1}, 0));
    assertEquals(Set.of(2), issues.problems().get(ROW));
  }

  @Test
  void unsupportedRowsAndMalformedMasksCannotAllocateExtraProblems() {
    var issues = new ThemeColorIssues();
    assertThrows(IllegalArgumentException.class, () -> issues.observe("foreign", new float[4], 1));
    assertThrows(IllegalArgumentException.class, () -> issues.observe(ROW, new float[3], 1));
    assertThrows(IllegalArgumentException.class, () -> issues.observe(ROW, new float[4], 16));
    assertThrows(IllegalArgumentException.class, () -> issues.observe(ROW, new float[4], -1));
    assertThrows(IllegalArgumentException.class, () -> issues.bad(ROW, 4));
    assertThrows(IllegalArgumentException.class, () -> issues.clear("foreign"));
    assertFalse(issues.any());
  }
}
