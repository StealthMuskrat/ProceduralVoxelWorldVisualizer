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
  final long memoryBudget;
  final AtomicLong cacheBytes = new AtomicLong(),
      stagingBytes = new AtomicLong(),
      collectionBytes = new AtomicLong(),
      nanos = new AtomicLong(),
      completed = new AtomicLong();
  private final Set<WorldContext> contexts = ConcurrentHashMap.newKeySet();
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
    workers =
        new ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofPlatform().daemon(true).name("voxel-generation-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public WorldContext openWorld(CompiledProject compiled, WorldOptions options) {
    if (closed.get()) throw new IllegalStateException("Engine closed");
    var c = new WorldContext(this, compiled, options);
    contexts.add(c);
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

  synchronized boolean reserveStaging(long bytes) {
    if (bytes < 0
        || bytes > memoryBudget - stagingBytes.get() - cacheBytes.get() - collectionBytes.get())
      return false;
    stagingBytes.addAndGet(bytes);
    return true;
  }

  synchronized boolean reserveCache(long bytes) {
    if (bytes < 0
        || bytes > memoryBudget - stagingBytes.get() - cacheBytes.get() - collectionBytes.get())
      return false;
    cacheBytes.addAndGet(bytes);
    return true;
  }

  synchronized boolean reserveCollection(long bytes) {
    if (bytes < 0
        || bytes > memoryBudget / 2 - collectionBytes.get()
        || bytes > memoryBudget - stagingBytes.get() - cacheBytes.get() - collectionBytes.get())
      return false;
    collectionBytes.addAndGet(bytes);
    return true;
  }

  void forget(WorldContext c) {
    contexts.remove(c);
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
      for (var c : List.copyOf(contexts)) c.close();
      workers.shutdownNow();
    }
  }
}
