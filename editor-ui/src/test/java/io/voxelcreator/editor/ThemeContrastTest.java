package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import imgui.flag.ImGuiCol;
import io.voxelcreator.editor.ThemePalette.Rgba;
import java.util.*;
import org.junit.jupiter.api.Test;

class ThemeContrastTest {
  private static final Rgba BLACK = new Rgba(0, 0, 0, 1), WHITE = new Rgba(1, 1, 1, 1);

  @Test
  void analyticalBlackWhiteEqualAndPrimaryVectors() {
    assertEquals(21, ThemeContrast.ratio(BLACK, WHITE));
    assertEquals(1, ThemeContrast.ratio(WHITE, WHITE));
    assertEquals(.2126, ThemeContrast.luminance(new Rgba(1, 0, 0, 1)), 1e-15);
    assertEquals(.7152, ThemeContrast.luminance(new Rgba(0, 1, 0, 1)), 1e-15);
    assertEquals(.0722, ThemeContrast.luminance(new Rgba(0, 0, 1, 1)), 1e-15);
    assertEquals(
        2.913937547600913, ThemeContrast.ratio(new Rgba(1, 0, 0, 1), new Rgba(0, 1, 0, 1)), 1e-14);
  }

  @Test
  void gammaTransferAndIndependentMidGrayVector() {
    assertEquals(5.280822809644651, ThemeContrast.ratio(new Rgba(.5f, .5f, .5f, 1), BLACK), 1e-13);
    float c = .04f;
    assertEquals(c / 12.92, ThemeContrast.luminance(new Rgba(c, c, c, 1)), 1e-15);
    assertTrue(ThemeContrast.ratio(new Rgba(.46f, .46f, .46f, 1), WHITE) > 4.5);
    assertTrue(ThemeContrast.ratio(new Rgba(.47f, .47f, .47f, 1), WHITE) < 4.5);
  }

  @Test
  void sourceOverUsesAlphaBeforeGammaAndPreservesBackgroundAtZeroAlpha() {
    var half = ThemeContrast.over(new Rgba(0, 0, 0, .5f), WHITE);
    assertEquals(new Rgba(.5f, .5f, .5f, 1), half);
    assertEquals(3.976653024912438, ThemeContrast.ratio(half, WHITE), 1e-13);
    assertEquals(WHITE, ThemeContrast.over(new Rgba(1, 0, 0, 0), WHITE));
    assertEquals(
        new Rgba(0, 0, 0, 0), ThemeContrast.over(new Rgba(1, 0, 0, 0), new Rgba(0, 1, 0, 0)));
    var result = ThemeContrast.over(new Rgba(1, 0, 0, .5f), new Rgba(0, 0, 1, .5f));
    assertEquals(.75, result.a());
    assertEquals(2.0 / 3, result.r(), 1e-7);
    assertEquals(1.0 / 3, result.b(), 1e-7);
    assertThrows(IllegalArgumentException.class, () -> ThemeContrast.ratio(result, WHITE));
  }

  @Test
  void randomizedSymmetryRangeAndCompositionMetamorphisms() {
    var random = new Random(0x7152);
    for (int i = 0; i < 1000; i++) {
      var a = new Rgba(random.nextFloat(), random.nextFloat(), random.nextFloat(), 1);
      var b = new Rgba(random.nextFloat(), random.nextFloat(), random.nextFloat(), 1);
      double ratio = ThemeContrast.ratio(a, b);
      assertTrue(ratio >= 1 && ratio <= 21);
      assertEquals(ratio, ThemeContrast.ratio(b, a));
      assertEquals(a, ThemeContrast.over(a, b));
      assertEquals(1, ThemeContrast.ratio(a, a));
    }
  }

  @Test
  void completeAuditRetainsNamedTextAndControlTargetsAndDetectsBadColors() {
    var colors = new TreeMap<Integer, Rgba>();
    for (int i = 0; i < ImGuiCol.COUNT; i++) colors.put(i, BLACK);
    colors.put(ImGuiCol.Text, WHITE);
    colors.put(ImGuiCol.TextDisabled, WHITE);
    var checks = ThemeContrast.audit(colors, ThemePalette.starting("Dark"));
    assertEquals(26, checks.size());
    assertEquals(21, checks.getFirst().ratio());
    assertEquals(3, checks.stream().filter(c -> c.target() == 3).count());
    colors.put(ImGuiCol.Text, BLACK);
    assertTrue(
        ThemeContrast.audit(colors, ThemePalette.starting("Dark")).stream()
            .filter(c -> c.label().startsWith("Text /"))
            .allMatch(ThemeContrast.Check::attention));
    assertThrows(UnsupportedOperationException.class, () -> checks.clear());
  }

  @Test
  void transparencyUsesDeclaredBackdropAndAlphaTextCannotMasqueradeAsOpaque() {
    var colors = new TreeMap<Integer, Rgba>();
    for (int i = 0; i < ImGuiCol.COUNT; i++) colors.put(i, new Rgba(0, 0, 0, 0));
    colors.put(ImGuiCol.Text, WHITE);
    assertEquals(21, ThemeContrast.audit(colors, ThemePalette.starting("Dark")).getFirst().ratio());
    assertEquals(1, ThemeContrast.audit(colors, ThemePalette.starting("Light")).getFirst().ratio());
    colors.put(ImGuiCol.Text, new Rgba(1, 1, 1, 0));
    assertEquals(1, ThemeContrast.audit(colors, ThemePalette.starting("Dark")).getFirst().ratio());
  }
}
