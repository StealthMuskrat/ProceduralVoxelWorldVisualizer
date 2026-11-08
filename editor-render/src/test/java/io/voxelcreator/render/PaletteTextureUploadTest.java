package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred palette packing, independent pixel storage, ownership and failure/retry definitions. */
class PaletteTextureUploadTest {
  private static final class Memory implements PaletteTextureUpload.Backend {
    final List<int[]> writes = new ArrayList<>();
    Throwable failure;
    boolean failAfter;
    int calls, width, height;
    public void write(int width, int height, IntBuffer pixels) {
      calls++;
      assertTrue(pixels.isDirect()); assertTrue(pixels.isReadOnly());
      assertEquals(0, pixels.position()); assertEquals(width * height, pixels.remaining());
      assertThrows(ReadOnlyBufferException.class, () -> pixels.put(0, 1));
      if (failure != null && !failAfter) fail(failure);
      this.width = width; this.height = height;
      var values = new int[pixels.remaining()]; pixels.get(values); writes.add(values);
      if (failure != null) fail(failure);
    }
  }
  private static void fail(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }
  private static Throwable fault(boolean error, AtomicInteger reads) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
    } : new IllegalStateException() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
    };
  }

  @Test
  void explicitLayoutsBoundBothTextureAxesAndPaddedByteCapacityBeforeAllocation() {
    for (int[] item : new int[][] {
        {1, 1, 1, 1, 1}, {2, 2, 2, 1, 2}, {3, 2, 2, 2, 4}, {4, 2, 2, 2, 4},
        {7, 3, 3, 3, 9}, {17, 8, 8, 3, 24}, {64, 8, 8, 8, 64},
        {65, 17, 17, 4, 68}, {256, 16384, 256, 1, 256}, {289, 17, 17, 17, 289},
        {268435456, 16384, 16384, 16384, 268435456}}) {
      assertEquals(new PaletteTextureUpload.Layout(item[2], item[3], item[4]), PaletteTextureUpload.layout(item[0], item[1]));
    }
    for (int[] item : new int[][] {
        {0, 1}, {-1, 16}, {1, 0}, {1, -1}, {5, 2}, {290, 17},
        {Integer.MAX_VALUE, 46341}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {536870911, 32768}})
      assertThrows(IllegalArgumentException.class, () -> PaletteTextureUpload.layout(item[0], item[1]));
  }

  @Test
  void packedChannelWordsKeepTheirOrderAndPaddingWithoutBorrowingCallerMemory() {
    var memory = new Memory(); var upload = new PaletteTextureUpload(memory);
    int[] source = {0x44332211, 0x88776655, 0xccbbaa99, 0x00ffeedd, 0, Integer.MIN_VALUE, Integer.MAX_VALUE};
    var original = source.clone(); assertTrue(upload.set(source, 3));
    assertEquals(3, upload.width()); assertEquals(3, memory.width); assertEquals(3, memory.height);
    int[] expected = {0x44332211, 0x88776655, 0xccbbaa99, 0x00ffeedd, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0};
    assertArrayEquals(expected, memory.writes.getFirst()); assertArrayEquals(original, source);
    assertFalse(upload.set(original.clone(), 3)); assertEquals(1, memory.calls);
    source[3] = 0x12345678; assertTrue(upload.set(source, 3)); assertEquals(2, memory.calls);
    assertArrayEquals(expected, memory.writes.getFirst()); expected[3] = 0x12345678;
    assertArrayEquals(expected, memory.writes.getLast()); assertFalse(upload.set(source.clone(), 3));
    assertTrue(upload.set(source, 4)); assertEquals(4, upload.width()); assertEquals(2, memory.height);
    assertArrayEquals(new int[] {0x44332211, 0x88776655, 0xccbbaa99, 0x12345678, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, 0}, memory.writes.getLast());
  }

  @Test
  void failedWritesNeverPublishCacheOrWidthAndIdenticalRequestsRemainRetryable() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) {
      var memory = new Memory(); var upload = new PaletteTextureUpload(memory); var reads = new AtomicInteger();
      var failure = fault(error, reads); int[] old = {10, 20, 30, 40}, next = {11, 22, 33, 44, 55, 66, 77};
      upload.set(old, 2); memory.failure = failure; memory.failAfter = after;
      for (int attempt = 0; attempt < 2; attempt++) {
        assertSame(failure, assertThrows(Throwable.class, () -> upload.set(next, 3)));
        assertEquals(2, upload.width()); assertEquals(attempt + 2, memory.calls);
      }
      memory.failure = null; assertTrue(upload.set(old.clone(), 2)); assertEquals(4, memory.calls);
      assertArrayEquals(new int[] {10, 20, 30, 40}, memory.writes.getLast());
      assertTrue(upload.set(next.clone(), 3)); assertEquals(5, memory.calls);
      assertEquals(3, upload.width()); assertArrayEquals(new int[] {11, 22, 33, 44, 55, 66, 77, 0, 0}, memory.writes.getLast());
      assertFalse(upload.set(next, 3)); assertEquals(5, memory.calls); assertEquals(0, reads.get());
      assertEquals(0, failure.getSuppressed().length); assertArrayEquals(new int[] {10, 20, 30, 40}, memory.writes.getFirst());
    }
  }

  @Test
  void invalidInputsDoNotCallBackendOrEvictAnAcceptedPalette() {
    var memory = new Memory(); var upload = new PaletteTextureUpload(memory); int[] colors = {1, 2, 3, 4};
    upload.set(colors, 2);
    assertThrows(NullPointerException.class, () -> upload.set(null, 2));
    assertThrows(IllegalArgumentException.class, () -> upload.set(new int[0], 2));
    assertThrows(IllegalArgumentException.class, () -> upload.set(new int[5], 2));
    for (int limit : new int[] {0, -1, 1}) assertThrows(IllegalArgumentException.class, () -> upload.set(colors, limit));
    assertEquals(1, memory.calls); assertEquals(2, upload.width()); assertFalse(upload.set(colors, 2));
    assertArrayEquals(new int[] {1, 2, 3, 4}, memory.writes.getFirst());
  }
}
