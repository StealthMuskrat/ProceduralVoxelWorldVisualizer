package io.voxelcreator.api;

import static io.voxelcreator.api.WorldOptions.CachePolicy;

import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Isolated seed, cache and cancellation scope. Completed snapshots remain valid after eviction or
 * close.
 */
public final class WorldContext implements AutoCloseable {
  private final NoiseGeneratorEngine engine;
  private final CompiledProject compiled;
  private final WorldOptions options;
  private final WorldPlan plan;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final LinkedHashMap<ChunkPos, VoxelChunk> cache = new LinkedHashMap<>(16, .75f, true);
  private final Set<ChunkPos> pinned = new HashSet<>();
  private long bytes = 0, hits = 0, misses = 0;
  private final Set<CompletableFuture<VoxelChunk>> futures = ConcurrentHashMap.newKeySet();
  private final Set<StreamingTask> regions = ConcurrentHashMap.newKeySet();

  WorldContext(NoiseGeneratorEngine e, CompiledProject p, WorldOptions o) {
    engine = e;
    compiled = p;
    options = o;
    plan = p.plan(o.seedOverride() == null ? p.defaultSeed() : o.seedOverride());
  }

  public int chunkSize() {
    return options.rootChunkSize();
  }

  public VoxelRegistry registry() {
    return compiled.registry();
  }

  private void check() {
    if (closed.get()) throw new IllegalStateException("World context closed");
  }

  public VoxelChunk generateChunk(ChunkPos pos) {
    return generate(pos, new CancellationToken());
  }

  private VoxelChunk generate(ChunkPos pos, CancellationToken token) {
    check();
    token.throwIfCancelled();
    synchronized (cache) {
      var old = cache.get(pos);
      if (old != null) {
        hits++;
        return old;
      }
      misses++;
    }
    long reserve = plan.stagingEstimate(chunkSize());
    if (!engine.reserveStaging(reserve))
      throw new RejectedExecutionException("Generation memory budget reached");
    long started = System.nanoTime();
    try {
      CancellationToken scope = new CancellationToken(() -> closed.get() || token.isCancelled());
      VoxelChunk result = plan.generate(pos, chunkSize(), scope);
      token.throwIfCancelled();
      check();
      engine.completed.incrementAndGet();
      retain(result);
      return result;
    } finally {
      engine.stagingBytes.addAndGet(-reserve);
      engine.nanos.addAndGet(System.nanoTime() - started);
    }
  }

  private void retain(VoxelChunk result) {
    if (options.cachePolicy() == CachePolicy.NO_CACHE) return;
    synchronized (cache) {
      if (closed.get() || cache.containsKey(result.position())) return;
      long needed = result.estimatedBytes(), limit = options.cacheBytes();
      var it = cache.entrySet().iterator();
      while (bytes + needed > limit && it.hasNext()) {
        var entry = it.next();
        if (pinned.contains(entry.getKey())) continue;
        bytes -= entry.getValue().estimatedBytes();
        engine.cacheBytes.addAndGet(-entry.getValue().estimatedBytes());
        it.remove();
      }
      if (bytes + needed <= limit && engine.reserveCache(needed)) {
        cache.put(result.position(), result);
        bytes += needed;
      }
    }
  }

  public CompletableFuture<VoxelChunk> generateChunkAsync(ChunkPos pos) {
    return generateChunkAsync(pos, new CancellationToken());
  }

  public CompletableFuture<VoxelChunk> generateChunkAsync(ChunkPos pos, CancellationToken token) {
    check();
    Objects.requireNonNull(token);
    var future = new CompletableFuture<VoxelChunk>();
    futures.add(future);
    future.whenComplete((v, e) -> futures.remove(future));
    try {
      engine.workers.execute(
          () -> {
            if (future.isDone()) return;
            try {
              future.complete(generate(pos, token));
            } catch (Throwable e) {
              future.completeExceptionally(e);
            }
          });
    } catch (RejectedExecutionException e) {
      future.completeExceptionally(e);
      engine.report("QUEUE_OVERLOAD", e.getMessage());
    }
    return future;
  }

  public int getVoxelTypeId(BlockPos p) {
    check();
    if (p.y() < compiled.minY() || p.y() > compiled.maxY()) return 0;
    int s = chunkSize();
    return generateChunk(ChunkPos.fromBlock(p, s))
        .getVoxelTypeId(Math.floorMod(p.x(), s), Math.floorMod(p.y(), s), Math.floorMod(p.z(), s));
  }

  /** Exact same-cell replay useful for low-volume inspection without populating a chunk cache. */
  public GenerationTrace trace(BlockPos p) {
    check();
    return plan.trace(p);
  }

  public int sampleStage(BlockPos p, int operationCount) {
    check();
    if (operationCount < 0) throw new IllegalArgumentException("Negative stage");
    return plan.stageSample(p.x(), p.y(), p.z(), operationCount);
  }

  public CacheStats cacheStats() {
    synchronized (cache) {
      return new CacheStats(hits, misses, cache.size(), bytes, pinned.size());
    }
  }

  public void evictChunk(ChunkPos p) {
    synchronized (cache) {
      if (pinned.contains(p)) throw new IllegalStateException("Chunk pinned");
      var c = cache.remove(p);
      if (c != null) {
        bytes -= c.estimatedBytes();
        engine.cacheBytes.addAndGet(-c.estimatedBytes());
      }
    }
  }

  public void clearCache() {
    synchronized (cache) {
      var it = cache.entrySet().iterator();
      while (it.hasNext()) {
        var e = it.next();
        if (pinned.contains(e.getKey())) continue;
        bytes -= e.getValue().estimatedBytes();
        engine.cacheBytes.addAndGet(-e.getValue().estimatedBytes());
        it.remove();
      }
    }
  }

  public boolean pinChunk(ChunkPos p) {
    check();
    if (options.cachePolicy() != CachePolicy.USER_MANAGED)
      throw new IllegalStateException("Pin requires USER_MANAGED");
    synchronized (cache) {
      return cache.containsKey(p) && pinned.add(p);
    }
  }

  public boolean unpinChunk(ChunkPos p) {
    check();
    synchronized (cache) {
      return pinned.remove(p);
    }
  }

  public RegionTask streamRegion(RegionRequest request, ChunkConsumer consumer) {
    check();
    Objects.requireNonNull(consumer);
    if (regions.size() >= 16)
      throw new RejectedExecutionException("Too many active region streams");
    var task = new StreamingTask(request, consumer);
    regions.add(task);
    Thread.ofVirtual().name("voxel-region-coordinator").start(task::run);
    return task;
  }

  public RegionResult collectRegion(RegionRequest request) {
    check();
    long count =
        request
            .bounds()
            .clipY(compiled.minY(), compiled.maxY())
            .map(r -> r.chunkCount(chunkSize()))
            .orElse(0L);
    long worst = Math.multiplyExact(count, 128L + 5L * chunkSize() * chunkSize() * chunkSize());
    if (worst > engine.memoryBudget / 2)
      throw new RejectedExecutionException(
          "Collected region exceeds memory budget; use streamRegion");
    var chunks = new ArrayList<VoxelChunk>();
    var task =
        streamRegion(
            request,
            c -> {
              chunks.add(c);
              return CompletableFuture.completedFuture(null);
            });
    var summary = task.completion().join();
    return new RegionResult(List.copyOf(chunks), summary);
  }

  public CompletableFuture<RegionResult> collectRegionAsync(RegionRequest request) {
    check();
    var future = new CompletableFuture<RegionResult>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                future.complete(collectRegion(request));
              } catch (Throwable e) {
                future.completeExceptionally(e);
              }
            });
    return future;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      for (var task : regions) task.cancel();
      for (var future : futures)
        future.completeExceptionally(new CancellationException("World context closed"));
      synchronized (cache) {
        pinned.clear();
        clearCache();
      }
      engine.forget(this);
    }
  }

  private final class StreamingTask implements RegionTask {
    final RegionRequest request;
    final ChunkConsumer consumer;
    final CancellationToken token = new CancellationToken();
    final CompletableFuture<RegionSummary> done = new CompletableFuture<>();
    final Optional<RegionBounds> effective;
    final long total;
    final AtomicLong completed = new AtomicLong(), failed = new AtomicLong();
    volatile String state = "PENDING";
    volatile long cancelled = 0;
    final List<RegionSummary.ChunkFailure> failures = new ArrayList<>();

    StreamingTask(RegionRequest r, ChunkConsumer c) {
      request = r;
      consumer = c;
      effective = r.bounds().clipY(compiled.minY(), compiled.maxY());
      total = effective.map(b -> b.chunkCount(chunkSize())).orElse(0L);
    }

    public boolean cancel() {
      return !done.isDone() && token.cancel();
    }

    public TaskProgress progress() {
      return new TaskProgress(
          state,
          Math.max(0, total - completed.get() - failed.get() - cancelled),
          completed.get(),
          failed.get(),
          cancelled);
    }

    public CompletableFuture<RegionSummary> completion() {
      return done;
    }

    record Pending(ChunkPos pos, CompletableFuture<VoxelChunk> future) {}

    void run() {
      state = "RUNNING";
      var pending = new ArrayList<Pending>();
      try {
        if (effective.isPresent()) {
          var r = effective.get();
          int size = chunkSize();
          long minX = Math.floorDiv(r.min().x(), size),
              minY = Math.floorDiv(r.min().y(), size),
              minZ = Math.floorDiv(r.min().z(), size),
              maxX = Math.floorDiv(r.max().x(), size),
              maxY = Math.floorDiv(r.max().y(), size),
              maxZ = Math.floorDiv(r.max().z(), size);
          long x = minX, y = minY, z = minZ;
          boolean more = true;
          while ((more || !pending.isEmpty()) && !token.isCancelled() && !closed.get()) {
            while (more && pending.size() < request.maxInFlightChunks() && !token.isCancelled()) {
              var pos = new ChunkPos((int) x, (int) y, (int) z);
              pending.add(new Pending(pos, generateChunkAsync(pos, token)));
              if (++z > maxZ) {
                z = minZ;
                if (++y > maxY) {
                  y = minY;
                  if (++x > maxX) more = false;
                }
              }
            }
            Pending chosen = pending.getFirst();
            if (request.order() == RegionRequest.DeliveryOrder.COMPLETION) {
              CompletableFuture.anyOf(
                      pending.stream().map(Pending::future).toArray(CompletableFuture[]::new))
                  .handle((v, error) -> null)
                  .join();
              chosen = pending.stream().filter(p -> p.future.isDone()).findFirst().orElseThrow();
            }
            String phase = "GENERATION";
            try {
              var chunk = chosen.future.join();
              token.throwIfCancelled();
              phase = "DELIVERY";
              var accepted = consumer.accept(chunk);
              if (accepted == null)
                throw new IllegalStateException("ChunkConsumer returned null stage");
              // Polling permits cancellation/close even when a consumer never completes.
              var ack = accepted.toCompletableFuture();
              while (!ack.isDone()) {
                token.throwIfCancelled();
                try {
                  ack.get(50, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                }
              }
              ack.join();
              completed.incrementAndGet();
            } catch (CancellationException e) {
              if (token.isCancelled() || closed.get()) token.cancel();
              else {
                recordFailure(chosen.pos, phase, e);
                if (request.failurePolicy() == RegionRequest.FailurePolicy.FAIL_FAST)
                  token.cancel();
              }
            } catch (Exception e) {
              recordFailure(chosen.pos, phase, e);
              if (request.failurePolicy() == RegionRequest.FailurePolicy.FAIL_FAST) token.cancel();
            }
            pending.remove(chosen);
          }
        }
        cancelled = total - completed.get() - failed.get();
        state =
            cancelled > 0 ? "CANCELLED" : failed.get() > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        done.complete(
            new RegionSummary(request.bounds(), effective, progress(), List.copyOf(failures)));
      } catch (Throwable e) {
        token.cancel();
        state = "FAILED";
        cancelled = total - completed.get() - failed.get();
        done.completeExceptionally(e);
      } finally {
        token.cancel();
        for (var p : pending) p.future.cancel(false);
        regions.remove(this);
      }
    }

    void recordFailure(ChunkPos pos, String phase, Throwable e) {
      failed.incrementAndGet();
      Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
      if (failures.size() < 1024)
        failures.add(
            new RegionSummary.ChunkFailure(pos, phase, String.valueOf(cause.getMessage())));
      engine.report("CHUNK_" + phase + "_FAILED", String.valueOf(cause.getMessage()));
    }
  }
}
