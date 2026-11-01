package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class RendererStartupTest {
  @Test
  void failureWaitsForExplicitRetryAndHealthyResourcesNeverRestart() {
    var calls = new AtomicInteger();
    var disposed = new AtomicInteger();
    var value = new Object();
    var renderer =
        new RendererStartup<>(
            () -> {
              if (calls.incrementAndGet() < 3) throw new IllegalStateException("x".repeat(5000));
              return value;
            },
            r -> {
              assertSame(value, r);
              disposed.incrementAndGet();
            });
    assertFalse(renderer.initialize());
    assertEquals(1024, renderer.failure().length());
    for (int i = 0; i < 100; i++) assertFalse(renderer.initialize());
    assertEquals(1, calls.get());
    assertFalse(renderer.retry());
    assertEquals(2, renderer.attempts());
    assertTrue(renderer.retry());
    assertSame(value, renderer.value());
    assertEquals("", renderer.failure());
    assertTrue(renderer.retry());
    assertTrue(renderer.initialize());
    assertEquals(3, calls.get());
    renderer.close();
    renderer.close();
    assertEquals(1, disposed.get());
    assertFalse(renderer.retry());
    assertNull(renderer.value());
  }

  @Test
  void oneFailedViewportDoesNotPreventIndependentResourcesFromStartingOrClosing() {
    var count = new AtomicInteger();
    var a = new RendererStartup<>(Object::new, r -> count.incrementAndGet());
    var b =
        new RendererStartup<Object>(
            () -> {
              throw new IllegalStateException("bad program");
            },
            r -> fail("Unpublished resource"));
    var c = new RendererStartup<>(Object::new, r -> count.incrementAndGet());
    assertTrue(a.initialize());
    assertFalse(b.initialize());
    assertTrue(c.initialize());
    assertFalse(a.busy());
    assertFalse(b.busy());
    assertFalse(c.busy());
    a.close();
    b.close();
    c.close();
    assertEquals(2, count.get());
  }

  @Test
  void closureDuringAFactoryCallDisposesTheCandidateWithoutPublishingOrRevivingIt() {
    var holder = new AtomicReference<RendererStartup<Object>>();
    var disposed = new AtomicInteger();
    var renderer =
        new RendererStartup<>(
            () -> {
              assertTrue(holder.get().busy());
              assertFalse(holder.get().retry());
              holder.get().close();
              return new Object();
            },
            r -> disposed.incrementAndGet());
    holder.set(renderer);
    assertFalse(renderer.initialize());
    assertTrue(renderer.closed());
    assertFalse(renderer.busy());
    assertNull(renderer.value());
    assertEquals(1, disposed.get());
    assertFalse(renderer.retry());
    assertEquals(1, renderer.attempts());
  }

  @Test
  void nullFactoriesAndThrowingDisposalHaveBoundedTerminalOwnership() {
    var empty = new RendererStartup<Object>(() -> null, r -> fail("Null publication"));
    assertFalse(empty.initialize());
    assertFalse(empty.failure().isBlank());
    empty.close();
    var count = new AtomicInteger();
    var renderer =
        new RendererStartup<>(
            Object::new,
            r -> {
              count.incrementAndGet();
              throw new IllegalStateException("close");
            });
    assertTrue(renderer.initialize());
    assertThrows(IllegalStateException.class, renderer::close);
    renderer.close();
    assertNull(renderer.value());
    assertTrue(renderer.closed());
    assertEquals(1, count.get());
  }
}
