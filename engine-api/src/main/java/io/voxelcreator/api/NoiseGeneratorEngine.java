package io.voxelcreator.api;

import io.voxelcreator.model.Diagnostic;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Bounded CPU ownership. Coordinators are separate virtual threads and never occupy generation
 * workers.
 */
public final class NoiseGeneratorEngine implements AutoCloseable {
  final ThreadPoolExecutor workers;
  private final FairWorkQueue queue;
  private final Object contextGate = new Object();
  private final AtomicInteger reclaimCursor = new AtomicInteger();
  final long memoryBudget;
  final AtomicLong cacheBytes = new AtomicLong(),
      stagingBytes = new AtomicLong(),
      collectionBytes = new AtomicLong(),
      nanos = new AtomicLong(),
      completed = new AtomicLong();
  private final Set<WorldContext> contexts = new LinkedHashSet<>();
  private final List<Consumer<Diagnostic>> listeners = new CopyOnWriteArrayList<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public NoiseGeneratorEngine() {
    this(
        Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)),
        128,
        512L * 1024 * 1024);
  }

  public NoiseGeneratorEngine(int threads, int queueCapacity, long memoryBytes) {
    if (threads < 1 || threads > 64 || queueCapacity < 1 || memoryBytes < 1024 * 1024)
      throw new IllegalArgumentException("Invalid runtime budgets");
    memoryBudget = memoryBytes;
    queue = new FairWorkQueue(queueCapacity);
    workers =
        new ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            queue,
            Thread.ofPlatform().daemon(true).name("voxel-generation-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public WorldContext openWorld(CompiledProject compiled, WorldOptions options) {
    if (closed.get()) throw new IllegalStateException("Engine closed");
    var c = new WorldContext(this, compiled, options);
    synchronized (contextGate) {
      contexts.add(c);
    }
    if (closed.get()) {
      c.close();
      throw new IllegalStateException("Engine closed");
    }
    return c;
  }

  public WorldContext openWorld(CompiledProject compiled) {
    return openWorld(compiled, WorldOptions.defaults());
  }

  public void addDiagnosticListener(Consumer<Diagnostic> listener) {
    listeners.add(Objects.requireNonNull(listener));
  }

  void report(String code, String message) {
    var d = Diagnostic.error(code, "/runtime", message);
    for (var listener : listeners)
      try {
        listener.accept(d);
      } catch (RuntimeException ignored) {
      }
  }

  private enum Reservation {
    STAGING,
    CACHE,
    COLLECTION
  }

  private synchronized boolean tryReserve(long bytes, Reservation kind) {
    if (bytes < 0
        || closed.get()
        || bytes > memoryBudget - stagingBytes.get() - cacheBytes.get() - collectionBytes.get())
      return false;
    if (kind == Reservation.COLLECTION && bytes > memoryBudget / 2 - collectionBytes.get())
      return false;
    switch (kind) {
      case STAGING -> stagingBytes.addAndGet(bytes);
      case CACHE -> cacheBytes.addAndGet(bytes);
      case COLLECTION -> collectionBytes.addAndGet(bytes);
    }
    return true;
  }

  private boolean reserve(long bytes, Reservation kind) {
    if (bytes < 0 || bytes > memoryBudget) return false;
    if (tryReserve(bytes, kind)) return true;
    if (closed.get()) return false;
    if (kind == Reservation.COLLECTION && bytes > memoryBudget / 2 - collectionBytes.get())
      return false;
    if (bytes > memoryBudget - stagingBytes.get() - collectionBytes.get()) return false;
    List<WorldContext> candidates;
    synchronized (contextGate) {
      candidates = List.copyOf(contexts);
    }
    int size = candidates.size(),
        start = size == 0 ? 0 : Math.floorMod(reclaimCursor.getAndIncrement(), size);
    for (int i = 0; i < size; i++) {
      long missing =
          bytes - (memoryBudget - stagingBytes.get() - cacheBytes.get() - collectionBytes.get());
      if (missing > 0) candidates.get((start + i) % size).reclaimCache(missing);
      if (tryReserve(bytes, kind)) return true;
    }
    return false;
  }

  boolean reserveStaging(long bytes) {
    return reserve(bytes, Reservation.STAGING);
  }

  boolean reserveCache(long bytes) {
    return reserve(bytes, Reservation.CACHE);
  }

  boolean reserveCollection(long bytes) {
    return reserve(bytes, Reservation.COLLECTION);
  }

  void promote(Runnable task, GenerationPriority priority) {
    queue.promote(task, priority);
  }

  public SchedulerStats schedulerStats() {
    return queue.statistics();
  }

  void forget(WorldContext c) {
    synchronized (contextGate) {
      contexts.remove(c);
    }
  }

  public EngineMetrics metrics() {
    return new EngineMetrics(
        workers.getCorePoolSize(),
        workers.getActiveCount(),
        workers.getQueue().size(),
        completed.get(),
        nanos.get(),
        cacheBytes.get(),
        stagingBytes.get(),
        memoryBudget,
        collectionBytes.get());
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      List<WorldContext> snapshot;
      synchronized (contextGate) {
        snapshot = List.copyOf(contexts);
      }
      for (var c : snapshot) c.close();
      workers.shutdownNow();
    }
  }
}
