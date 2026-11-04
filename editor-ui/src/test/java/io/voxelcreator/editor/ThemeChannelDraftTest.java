package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ThemeChannelDraftTest {
  private final ThemePalette.Rgba opening = new ThemePalette.Rgba(.1f, .2f, .3f, 1);

  @Test
  void untouchedChannelsRetainEveryStoredFloatBitIncludingSignedZero() {
    for (int bits : new int[] {0, 0x80000000, 1, 0x00800000, 0x3eaaaaab, 0x3f7fffff, 0x3f800000}) {
      float value = Float.intBitsToFloat(bits);
      var color = new ThemePalette.Rgba(value, value, value, value);
      var draft = new ThemeChannelDraft(color);
      for (int frame = 0; frame < 50; frame++) {
        draft.synchronize(color);
        for (float channel : draft.candidate())
          assertEquals(bits, Float.floatToRawIntBits(channel));
      }
    }
  }

  @Test
  void acceptedExactTextKeepsItsSpellingInsteadOfUsingTheSliderReadout() {
    var draft = new ThemeChannelDraft(opening);
    draft.edit(0, "+.123456789");
    var next = ThemePalette.Rgba.of(draft.candidate());
    assertEquals(Float.floatToRawIntBits(.12345679f), Float.floatToRawIntBits(next.r()));
    draft.accepted(next);
    for (int frame = 0; frame < 50; frame++) draft.synchronize(next);
    assertEquals("+.123456789", draft.text(0));
    assertEquals("0.2", draft.text(1));
  }

  @Test
  void rejectedChannelAndValidSiblingRemainPrivateUntilTheWholeColorIsCorrected() {
    var draft = new ThemeChannelDraft(opening);
    var issues = new ThemeColorIssues();
    draft.edit(0, "-");
    assertFalse(issues.observe("status:Warning", draft.candidate(), 1));
    draft.edit(1, "+.7654321");
    assertFalse(issues.observe("status:Warning", draft.candidate(), 2));
    draft.synchronize(opening);
    assertEquals("-", draft.text(0));
    assertEquals("+.7654321", draft.text(1));
    assertThrows(IllegalArgumentException.class, () -> ThemePalette.Rgba.of(draft.candidate()));
    draft.edit(0, ".125");
    assertTrue(issues.observe("status:Warning", draft.candidate(), 1));
    var repaired = ThemePalette.Rgba.of(draft.candidate());
    assertEquals(new ThemePalette.Rgba(.125f, .7654321f, .3f, 1), repaired);
    draft.accepted(repaired);
    draft.synchronize(repaired);
    assertEquals("+.7654321", draft.text(1));
    assertEquals(new ThemePalette.Rgba(.1f, .2f, .3f, 1), opening);
  }

  @Test
  void strictTextRejectsNativeSpecialsJavaLiteralsWhitespaceAndOutOfDomainValues() {
    for (String raw :
        List.of(
            "",
            "-",
            ".",
            "1e",
            " 0.5",
            "0.5 ",
            "NaN",
            "Infinity",
            "0x1.0p-1",
            "0.5f",
            "1,0",
            "١",
            "-0.00001",
            "1.000001",
            "1e999",
            "9".repeat(129)))
      assertThrows(IllegalArgumentException.class, () -> ThemeChannelDraft.parse(raw), raw);
    assertEquals(0, ThemeChannelDraft.parse("+0.000"));
    assertEquals(1, ThemeChannelDraft.parse("1e0"));
    assertEquals(.5f, ThemeChannelDraft.parse("+.5"));
    assertEquals(0x80000000, Float.floatToRawIntBits(ThemeChannelDraft.parse("-0")));
  }

  @Test
  void candidateArraysCannotChangeOwnedTextOrAcceptedPreview() {
    var draft = new ThemeChannelDraft(opening);
    var array = draft.candidate();
    array[0] = Float.NaN;
    assertArrayEquals(opening.array(), draft.candidate());
    assertEquals("0.1", draft.text(0));
  }

  @Test
  void externalReplacementAndExplicitResetRetireRejectedRawChannels() {
    var draft = new ThemeChannelDraft(opening);
    draft.edit(0, "-");
    draft.synchronize(opening);
    assertEquals("-", draft.text(0));
    var replacement = new ThemePalette.Rgba(.9f, .8f, .7f, .6f);
    draft.synchronize(replacement);
    assertArrayEquals(replacement.array(), draft.candidate());
    draft.edit(3, "1e");
    draft.replace(replacement);
    assertEquals("0.6", draft.text(3));
  }

  @Test
  void malformedEditsAndMismatchedAcceptanceDoNotLoseTheCurrentRawDraft() {
    var draft = new ThemeChannelDraft(opening);
    draft.edit(0, "+.25000");
    assertThrows(IllegalArgumentException.class, () -> draft.edit(0, "0".repeat(129)));
    assertThrows(IllegalArgumentException.class, () -> draft.edit(4, "0"));
    assertThrows(IllegalArgumentException.class, () -> draft.text(-1));
    assertThrows(IllegalArgumentException.class, () -> draft.accepted(opening));
    assertEquals("+.25000", draft.text(0));
    draft.synchronize(opening);
    assertEquals("+.25000", draft.text(0));
  }

  @Test
  void acceptedColorStaysInThePrivateThemeSessionUntilItsExistingPublicationAction() {
    var user = new UserConfig();
    var session = new ThemeEditSession(user);
    var draft = new ThemeChannelDraft(opening);
    draft.edit(0, "+.123456789");
    var candidate = ThemePalette.Rgba.of(draft.candidate());
    session.edit(session.draft().status("Warning", candidate));
    draft.accepted(candidate);
    assertEquals(ThemePalette.starting("Dark"), ThemePalette.from(user.appearance));
    assertEquals(candidate, session.draft().status("Warning"));
    assertTrue(session.changed());
    session.revert(user);
    assertEquals(ThemePalette.starting("Dark"), session.draft());
  }
  @Test
  void inputSnapshotsRetainExactTextAndRejectChangeThenRestoration() {
    var draft = new ThemeChannelDraft(opening);
    var before = draft.input();
    assertTrue(draft.pristine());
    assertThrows(UnsupportedOperationException.class, () -> before.text().set(0, "0"));
    draft.edit(0, "+.100");
    assertFalse(draft.pristine());
    assertNotEquals(before, draft.input());
    draft.edit(0, "0.1");
    assertEquals(before.text(), draft.input().text());
    assertNotEquals(before, draft.input());
    assertFalse(draft.pristine());
  }

  @Test
  void equalValueResetAndEqualReplacementRowDoNotReviveSnapshotOwnership() {
    var draft = new ThemeChannelDraft(opening);
    var before = draft.input();
    draft.replace(opening);
    assertNotEquals(before, draft.input());
    assertNotEquals(before, new ThemeChannelDraft(opening).input());
    assertFalse(draft.pristine());
  }
}
