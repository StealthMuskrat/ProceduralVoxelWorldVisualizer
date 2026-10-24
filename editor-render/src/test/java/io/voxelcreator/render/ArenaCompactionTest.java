package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArenaCompactionTest {
  @Test
  void packedLayoutsAreNoOpsAndZeroMeshesNeverConsumeSpace() {
    var arena = new ArenaAllocator(256);
    var zero = arena.allocate(0);
    var first = arena.allocate(64);
    assertNull(arena.beginCompaction());
    assertEquals(192, arena.freeBytes());
    arena.release(zero);
    arena.release(first);
    assertNull(arena.beginCompaction());
    assertEquals(256, arena.largestFreeRange());
  }

  @Test
  void layoutPreservesOrderFreezesMutationAndCommitsOneFreeTail() {
    var arena = new ArenaAllocator(256);
    var gap = arena.allocate(16);
    var a = arena.allocate(96);
    var b = arena.allocate(48);
    arena.release(gap);
    var layout = arena.beginCompaction();
    assertEquals(new ArenaAllocator.Slice(0, 96), layout.target(a));
    assertEquals(new ArenaAllocator.Slice(96, 48), layout.target(b));
    assertThrows(UnsupportedOperationException.class, () -> layout.moves().clear());
    assertThrows(IllegalStateException.class, () -> arena.allocate(16));
    assertThrows(IllegalStateException.class, () -> arena.release(a));
    assertThrows(IllegalStateException.class, arena::beginCompaction);
    assertEquals(144, arena.usedBytes());
    var cursor = new ArenaCompaction(layout, s -> 48);
    while (!cursor.complete()) cursor.copied(cursor.next(48));
    arena.commitCompaction(layout);
    assertEquals(112, arena.freeBytes());
    assertEquals(112, arena.largestFreeRange());
    assertEquals(new ArenaAllocator.Slice(144, 112), arena.allocate(112));
    assertThrows(IllegalArgumentException.class, () -> arena.commitCompaction(layout));
  }

  @Test
  void overlappingMovesPreserveEveryByteAfterEveryAcknowledgedPrimitive() {
    var arena = new ArenaAllocator(256);
    byte[] bytes = new byte[256];
    var gap = arena.allocate(16);
    var a = arena.allocate(96);
    var b = arena.allocate(48);
    var expected = new LinkedHashMap<ArenaAllocator.Slice, byte[]>();
    for (var slice : List.of(a, b)) {
      byte[] original = new byte[slice.length()];
      for (int i = 0; i < original.length; i++) original[i] = (byte) (slice.offset() + i);
      System.arraycopy(original, 0, bytes, slice.offset(), original.length);
      expected.put(slice, original);
    }
    arena.release(gap);
    var cursor = new ArenaCompaction(arena.beginCompaction(), s -> 48);
    assertNull(cursor.next(47));
    assertEquals(0, cursor.copiedBytes());
    var first = cursor.next(48);
    assertEquals(16, first.sourceOffset());
    assertEquals(0, first.targetOffset());
    copy(bytes, first);
    cursor.copied(first);
    verify(bytes, cursor, expected);
    assertEquals(
        List.of(new ArenaCompaction.Segment(0, 48), new ArenaCompaction.Segment(64, 48)),
        cursor.segments(a));
    while (!cursor.complete()) {
      var step = cursor.next(48);
      copy(bytes, step);
      cursor.copied(step);
      verify(bytes, cursor, expected);
    }
    assertEquals(144, cursor.copiedBytes());
    assertEquals(0, cursor.remainingBytes());
    assertNull(cursor.next(100));
  }

  @Test
  void randomizedFragmentationMatchesIndependentByteOracleAtEveryIntermediateLayout() {
    var random = new Random(0x719ab);
    for (int trial = 0; trial < 40; trial++) {
      var arena = new ArenaAllocator(16384);
      var expected = new LinkedHashMap<ArenaAllocator.Slice, byte[]>();
      var quantum = new HashMap<ArenaAllocator.Slice, Integer>();
      byte[] memory = new byte[16384];
      for (int i = 0; i < 40; i++) {
        int q = random.nextBoolean() ? 16 : 48;
        var slice = arena.allocate(q * (1 + random.nextInt(8)));
        if (slice == null) break;
        byte[] bytes = new byte[slice.length()];
        random.nextBytes(bytes);
        expected.put(slice, bytes);
        quantum.put(slice, q);
        System.arraycopy(bytes, 0, memory, slice.offset(), bytes.length);
      }
      for (var slice : List.copyOf(expected.keySet()))
        if (random.nextInt(3) == 0) {
          arena.release(slice);
          expected.remove(slice);
        }
      var layout = arena.beginCompaction();
      if (layout == null) continue;
      var cursor = new ArenaCompaction(layout, quantum::get);
      while (!cursor.complete()) {
        int budget = 48 + random.nextInt(400);
        var step = cursor.next(budget);
        assertNotNull(step);
        assertTrue(step.bytes() <= budget);
        assertTrue(step.targetOffset() < step.sourceOffset());
        copy(memory, step);
        cursor.copied(step);
        verify(memory, cursor, expected);
      }
      arena.commitCompaction(layout);
      assertEquals(arena.freeBytes(), arena.largestFreeRange());
      for (var slice : expected.keySet()) arena.release(layout.target(slice));
      assertEquals(16384, arena.largestFreeRange());
      assertEquals(0, arena.usedBytes());
    }
  }

  @Test
  void copyAcknowledgementsCannotBeDuplicatedForgedOrAdvancedBeforeSuccess() {
    var arena = new ArenaAllocator(256);
    var gap = arena.allocate(16);
    var a = arena.allocate(96);
    arena.release(gap);
    var cursor = new ArenaCompaction(arena.beginCompaction(), s -> 48);
    var step = cursor.next(48);
    assertThrows(IllegalStateException.class, () -> cursor.next(48));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            cursor.copied(
                new ArenaCompaction.Copy(step.sourceOffset(), step.targetOffset(), step.bytes())));
    assertEquals(List.of(new ArenaCompaction.Segment(a.offset(), a.length())), cursor.segments(a));
    cursor.retry();
    var retry = cursor.next(48);
    assertEquals(step, retry);
    assertThrows(IllegalArgumentException.class, () -> cursor.copied(step));
    cursor.copied(retry);
    assertThrows(IllegalArgumentException.class, () -> cursor.copied(retry));
    assertThrows(IllegalArgumentException.class, () -> cursor.next(-1));
  }

  @Test
  void workspaceAndPrimitiveBoundariesLimitLargeCopies() {
    for (int q : new int[] {16, 48}) {
      var arena = new ArenaAllocator(1024 * 1024);
      var gap = arena.allocate(16);
      var a = arena.allocate(q * 10000);
      arena.release(gap);
      var cursor = new ArenaCompaction(arena.beginCompaction(), s -> q);
      var step = cursor.next(Integer.MAX_VALUE);
      assertTrue(step.bytes() <= 65536);
      assertEquals(0, step.bytes() % q);
      cursor.copied(step);
      assertTrue(cursor.remainingBytes() > 0);
      assertEquals(a.length() - step.bytes(), cursor.remainingBytes());
    }
  }

  @Test
  void incompleteTriangleTailRetainsTheSameGlPrimitiveSemantics() {
    var arena = new ArenaAllocator(128);
    var gap = arena.allocate(16);
    var a = arena.allocate(64);
    arena.release(gap);
    var cursor = new ArenaCompaction(arena.beginCompaction(), s -> 48);
    cursor.copied(cursor.next(48));
    assertEquals(2, cursor.segments(a).size());
    assertNull(cursor.next(15));
    cursor.copied(cursor.next(16));
    assertTrue(cursor.complete());
    assertEquals(List.of(new ArenaCompaction.Segment(0, 64)), cursor.segments(a));
  }

  @Test
  void resetInvalidatesOldLayoutsAndAllowsFreshFullBudgetAllocation() {
    var arena = new ArenaAllocator(256);
    var gap = arena.allocate(16);
    arena.allocate(96);
    arena.release(gap);
    var layout = arena.beginCompaction();
    var other = new ArenaAllocator(256);
    assertThrows(IllegalArgumentException.class, () -> other.commitCompaction(layout));
    arena.reset();
    assertThrows(IllegalArgumentException.class, () -> arena.commitCompaction(layout));
    assertEquals(new ArenaAllocator.Slice(0, 256), arena.allocate(256));
    assertThrows(NullPointerException.class, () -> layout.target(new ArenaAllocator.Slice(64, 16)));
  }

  @Test
  void invalidReleaseDoesNotLoseALiveAllocation() {
    var arena = new ArenaAllocator(256);
    var a = arena.allocate(64);
    assertThrows(
        IllegalArgumentException.class,
        () -> arena.release(new ArenaAllocator.Slice(a.offset(), 32)));
    assertEquals(64, arena.usedBytes());
    assertEquals(192, arena.freeBytes());
    arena.release(a);
    assertEquals(256, arena.largestFreeRange());
  }

  private static void copy(byte[] memory, ArenaCompaction.Copy step) {
    byte[] staging =
        Arrays.copyOfRange(memory, step.sourceOffset(), step.sourceOffset() + step.bytes());
    System.arraycopy(staging, 0, memory, step.targetOffset(), step.bytes());
  }

  private static void verify(
      byte[] memory, ArenaCompaction cursor, Map<ArenaAllocator.Slice, byte[]> expected) {
    for (var entry : expected.entrySet()) {
      var actual = new ByteArrayOutputStream();
      for (var segment : cursor.segments(entry.getKey()))
        actual.write(memory, segment.offset(), segment.bytes());
      assertArrayEquals(entry.getValue(), actual.toByteArray());
    }
  }
}
