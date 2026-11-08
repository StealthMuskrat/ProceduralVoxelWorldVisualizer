package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Deferred binding-level contracts; no ImGui context or native library is required. */
class TextBuffersTest {
  @Test
  void existingIdsSurviveBothFormerLimitsAndMultibyteBoundaries() {
    for (String value :
        new String[] {
          "a".repeat(257), "x".repeat(255) + "é界😀", "é界😀".repeat(2049)
        }) {
      for (int capacity : new int[] {256, 16384}) {
        var buffer = TextBuffers.editable(value, capacity);
        assertEquals(value, buffer.get());
        assertTrue(buffer.getBufferSize() > value.getBytes(StandardCharsets.UTF_8).length);
      }
    }
  }

  @Test
  void successiveReplacementGrowthAndShorteningPreserveExactText() {
    var buffer = TextBuffers.editable("start", 1);
    for (String value :
        new String[] {"界😀".repeat(4097), "é", "A".repeat(32769), "", "case distinct"}) {
      buffer.set(value);
      assertEquals(value, buffer.get());
    }
    assertTrue(buffer.inputData.isResizable, "Native InputText must also accept growing input");
  }

  @Test
  void buffersOwnTheirTextAndDoNotCanonicalizeRejectedInput() {
    String raw = " e\u0301\n" + "界".repeat(300);
    var first = TextBuffers.editable(raw, 256);
    var second = TextBuffers.editable(null, 256);
    assertEquals(raw, first.get());
    assertEquals("", second.get());
    second.set("independent");
    assertEquals(raw, first.get());
    assertEquals("independent", second.get());
  }
}
