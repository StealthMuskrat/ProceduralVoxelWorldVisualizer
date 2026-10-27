package io.voxelcreator.render;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.api.Profiler;
import io.voxelcreator.storage.BlockPos;
import io.voxelcreator.storage.ChunkPos;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.joml.Vector3d;

/**
 * UI-owned incremental scene. Worker results are immutable, epoch guarded and bounded by job
 * admission.
 */
public final class ChunkScene implements AutoCloseable {
  public interface Source extends AutoCloseable {
    SceneChunk load(ChunkPos position, CancellationToken token) throws Exception;

    default void close() {}
  }

  public record Config(
      int size,
      int radius,
      int verticalRadius,
      int lodStart,
      int quality,
      int jobs,
      long cpuBytes,
      boolean points,
      int dataChunks) {
    public Config {
      if (size < 2
          || size > 256
          || Integer.bitCount(size) != 1
          || radius < 0
          || radius > 4
          || verticalRadius < 0
          || verticalRadius > 2
          || lodStart < 1
          || quality < 0
          || quality > 2
          || jobs < 1
          || jobs > 8
          || cpuBytes < 1_048_576
          || dataChunks < 27
          || dataChunks > 2048) throw new IllegalArgumentException("Scene configuration");
    }
  }

  public record Upload(
      long epoch, ChunkPos position, long version, PackedMesh mesh, DisplayLod cells) {
    public Upload(long epoch, ChunkPos position, long version, PackedMesh mesh) {
      this(epoch, position, version, mesh, null);
    }
  }

  public record Pick(BlockPos position, boolean displayProxy) {}

  public record Statistics(
      int resident,
      int wanted,
      int activeJobs,
      int meshed,
      long cpuBytes,
      long reserved,
      long generated,
      long meshes,
      long discarded,
      String status) {}

  private static final AtomicLong EPOCHS = new AtomicLong();
  private final long epoch = EPOCHS.incrementAndGet();
  private final Source source;
  private final Function<ChunkPos, Profiler.Scope> profiling;
  private final ExecutorService executor;
  private Config config;
  private final LinkedHashMap<ChunkPos, Entry> entries = new LinkedHashMap<>();
  private final ConcurrentLinkedQueue<Result> ready = new ConcurrentLinkedQueue<>();
  private final Set<ChunkPos> wanted = new LinkedHashSet<>(),
      loadWindow = new HashSet<>(),
      required = new HashSet<>();
  private final ArrayDeque<ChunkPos> removed = new ArrayDeque<>();
  public final RenderPressure pressure;
  private long sequence, frame, cpuBytes, reserved, generated, meshes, discarded;
  private int active;
  private String status = "Loading nearby chunks";
  private boolean closed, rejected;
  private String lastError;
  private Vector3d center = new Vector3d(), eye = new Vector3d();

  private static final class Entry {
    final ChunkPos position;
    SceneChunk data;
    DisplayLod lod;
    DisplayLod displayedLod;
    long displayedVersion;
    PackedMesh mesh;
    long version, jobId, meshSignature, lastUsed, reservation;
    int targetLod = -1;
    boolean working, dirty = true, uploaded, displayed, failed;
    CancellationToken token;
    Future<?> future;

    Entry(ChunkPos p) {
      position = p;
    }
  }

  private record Result(
      ChunkPos position,
      long jobId,
      SceneChunk data,
      DisplayLod lod,
      PackedMesh mesh,
      long signature,
      Throwable error) {}

  public ChunkScene(Source source, ExecutorService executor, Config config) {
    this(source, executor, config, new RenderPressure());
  }

  public ChunkScene(
      Source source, ExecutorService executor, Config config, RenderPressure pressure) {
    this(source, executor, config, pressure, p -> null);
  }

  public ChunkScene(
      Source source,
      ExecutorService executor,
      Config config,
      RenderPressure pressure,
      Function<ChunkPos, Profiler.Scope> profiling) {
    this.profiling = Objects.requireNonNull(profiling);
    this.source = Objects.requireNonNull(source);
    this.executor = Objects.requireNonNull(executor);
    this.config = config;
    this.pressure = Objects.requireNonNull(pressure);
  }

  public boolean points() {
    return config.points();
  }

  public long epoch() {
    return epoch;
  }

  public void configure(Config value) {
    if (value.size != config.size || value.points != config.points)
      throw new IllegalArgumentException("New scene required for changed source layout");
    config = value;
  }

  public boolean rejected() {
    boolean value = rejected;
    rejected = false;
    return value;
  }

  public boolean hasDisplay() {
    return entries.values().stream().anyMatch(e -> e.mesh != null && wanted.contains(e.position));
  }

  public Set<ChunkPos> wanted() {
    return Set.copyOf(wanted);
  }

  public Statistics statistics() {
    return new Statistics(
        entries.size(),
        wanted.size(),
        active,
        (int) entries.values().stream().filter(e -> e.mesh != null).count(),
        cpuBytes,
        reserved,
        generated,
        meshes,
        discarded,
        status);
  }

  public void tick(Vector3d center, Vector3d eye, int completions) {
    if (closed) return;
    this.center.set(center);
    this.eye.set(eye);
    frame++;
    drain(completions);
    plan();
    evict();
    if (pressure.paused()) {
      status = pressure.message();
      return;
    }
    var priority = new ArrayList<>(loadWindow);
    priority.sort(
        Comparator.comparingDouble((ChunkPos p) -> distanceSquared(p, this.center))
            .thenComparing(Comparator.naturalOrder()));
    for (var position : priority) {
      if (active >= (pressure.preload() ? config.jobs : 1)) break;
      Entry entry = entries.get(position);
      if (entry == null) {
        if (entries.size() >= config.dataChunks) {
          if (required.contains(position)) {
            rejected = true;
            status = "Streaming chunk limit reached";
          }
          continue;
        }
        entry = new Entry(position);
        entries.put(position, entry);
      }
      entry.lastUsed = frame;
      if (entry.working || entry.failed) continue;
      if (entry.data == null) {
        if (!pressure.acceptExpensive()) continue;
        long bytes = 16L * config.size * config.size * config.size;
        if (admit(bytes)) {
          submitData(entry, bytes);
        }
        continue;
      }
      if (!wanted.contains(position)) continue;
      int selected =
          LodSelector.choose(
              position,
              config.size,
              this.eye,
              entry.targetLod,
              config.lodStart,
              config.quality,
              pressure.lodBias());
      if (selected != entry.targetLod) {
        entry.targetLod = selected;
        entry.dirty = true;
      }
      long signature = signature(entry);
      if (entry.mesh != null && signature == entry.meshSignature && !entry.dirty) continue;
      // Wait for requested neighbors before first meshing; all faces/AO use matching immutable halo
      // snapshots.
      if (!haloReady(position)) continue;
      long bytes =
          3
              * ((576L * config.size * config.size * config.size) / (1L << selected)
                  + 4096L * config.size * config.size);
      if (admit(bytes)) submitMesh(entry, bytes, signature, selected);
    }
    status =
        lastError != null
            ? "Chunk failed; Regenerate to retry: " + lastError
            : pressure.level() > 0
                ? pressure.message()
                : active > 0 ? "Streaming nearby chunks" : "Ready";
  }

  private boolean admit(long bytes) {
    if (cpuBytes + reserved + bytes <= config.cpuBytes) return true;
    rejected = true;
    status = "Scene CPU budget reached; previous display retained";
    return false;
  }

  private void plan() {
    wanted.clear();
    loadWindow.clear();
    int radius = pressure.radius(config.radius), vertical = Math.min(radius, config.verticalRadius);
    int cx = chunk(center.x), cy = chunk(center.y), cz = chunk(center.z);
    for (int z = -radius; z <= radius; z++)
      for (int y = -vertical; y <= vertical; y++)
        for (int x = -radius; x <= radius; x++) {
          var position = safe(cx + x, cy + y, cz + z);
          if (position != null) wanted.add(position);
        }
    for (var position : wanted) {
      loadWindow.add(position);
      for (int z = -1; z <= 1; z++)
        for (int y = -1; y <= 1; y++)
          for (int x = -1; x <= 1; x++) {
            var neighbor = safe(position.x() + x, position.y() + y, position.z() + z);
            if (neighbor != null) loadWindow.add(neighbor);
          }
    }
    required.clear();
    required.addAll(loadWindow);
    if (pressure.preload())
      for (var position : wanted)
        for (int z = -2; z <= 2; z++)
          for (int y = -2; y <= 2; y++)
            for (int x = -2; x <= 2; x++) {
              var p = safe(position.x() + x, position.y() + y, position.z() + z);
              if (p != null) loadWindow.add(p);
            }
    // A reduced preload window never removes the halo required by current visible meshes.
    for (var entry : entries.values())
      if (entry.working && !loadWindow.contains(entry.position)) cancel(entry);
  }

  private int chunk(double value) {
    double coordinate = StrictMath.floor(value / config.size);
    return (int)
        Math.max(
            Math.floorDiv(Integer.MIN_VALUE, config.size),
            Math.min(Math.floorDiv(Integer.MAX_VALUE, config.size), coordinate));
  }

  private ChunkPos safe(int x, int y, int z) {
    int min = Math.floorDiv(Integer.MIN_VALUE, config.size),
        max = Math.floorDiv(Integer.MAX_VALUE, config.size);
    return x < min || y < min || z < min || x > max || y > max || z > max
        ? null
        : new ChunkPos(x, y, z);
  }

  private double distanceSquared(ChunkPos p, Vector3d point) {
    double dx = ((double) p.x() + .5) * config.size - point.x,
        dy = ((double) p.y() + .5) * config.size - point.y,
        dz = ((double) p.z() + .5) * config.size - point.z;
    return dx * dx + dy * dy + dz * dz;
  }

  private void submitData(Entry entry, long bytes) {
    start(entry, bytes);
    long id = entry.jobId;
    CancellationToken token = entry.token;
    try {
      entry.future =
          executor.submit(
              () -> {
                try {
                  var data = source.load(entry.position, token);
                  if (data == null
                      || !data.position().equals(entry.position)
                      || data.size() != config.size)
                    throw new IllegalArgumentException("Source returned a mismatched chunk");
                  token.throwIfCancelled();
                  ready.add(new Result(entry.position, id, data, null, null, 0, null));
                } catch (Throwable error) {
                  ready.add(new Result(entry.position, id, null, null, null, 0, error));
                }
              });
    } catch (RejectedExecutionException full) {
      finish(entry);
      rejected = true;
      status = "Background queue is full";
    }
  }

  private void submitMesh(Entry entry, long bytes, long signature, int level) {
    var snapshot = new SceneChunk[27];
    for (int z = -1; z <= 1; z++)
      for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
          var p = safe(entry.position.x() + x, entry.position.y() + y, entry.position.z() + z);
          Entry neighbor = entries.get(p);
          if (neighbor != null && neighbor.data != null && neighbor.displayed && wanted.contains(p))
            snapshot[((z + 1) * 3 + y + 1) * 3 + x + 1] = neighbor.data;
        }
    SceneChunk data = entry.data;
    start(entry, bytes);
    long id = entry.jobId;
    CancellationToken token = entry.token;
    int size = config.size;
    boolean points = config.points;
    try {
      entry.future =
          executor.submit(
              () -> {
                var profile = profiling.apply(entry.position);
                long stamp = profile == null ? 0 : profile.begin();
                var outcome = Profiler.Outcome.FAILED;
                Result completion;
                try {
                  token.throwIfCancelled();
                  var lod = new DisplayLod(data, level);
                  MeshBuilder.Cells cells =
                      (x, y, z) -> {
                        if (x >= 0 && y >= 0 && z >= 0 && x < size && y < size && z < size)
                          return lod.get(x, y, z);
                        int dx = Math.floorDiv(x, size),
                            dy = Math.floorDiv(y, size),
                            dz = Math.floorDiv(z, size);
                        var neighbor = snapshot[((dz + 1) * 3 + dy + 1) * 3 + dx + 1];
                        return neighbor == null
                            ? 0
                            : neighbor.get(
                                Math.floorMod(x, size),
                                Math.floorMod(y, size),
                                Math.floorMod(z, size));
                      };
                  var mesh =
                      points
                          ? VoxelMesher.points(size, cells, 1 << level)
                          : VoxelMesher.greedy(size, cells);
                  token.throwIfCancelled();
                  completion = new Result(entry.position, id, null, lod, mesh, signature, null);
                  outcome = Profiler.Outcome.SUCCESS;
                } catch (Throwable error) {
                  if (error instanceof CancellationException) outcome = Profiler.Outcome.CANCELLED;
                  completion = new Result(entry.position, id, null, null, null, signature, error);
                } finally {
                  if (stamp != 0)
                    profile.finish(
                        stamp,
                        Profiler.Kind.MESH_CPU,
                        null,
                        "Streaming mesh / LOD " + level,
                        outcome);
                }
                ready.add(completion);
              });
    } catch (RejectedExecutionException full) {
      finish(entry);
      rejected = true;
      status = "Background queue is full";
    }
  }

  private void start(Entry entry, long bytes) {
    entry.working = true;
    entry.jobId = ++sequence;
    entry.token = new CancellationToken();
    entry.reservation = bytes;
    reserved += bytes;
    active++;
  }

  private void finish(Entry entry) {
    entry.working = false;
    active--;
    reserved -= entry.reservation;
    entry.reservation = 0;
    entry.future = null;
    entry.token = null;
  }

  private void cancel(Entry entry) {
    if (!entry.working) return;
    entry.token.cancel();
    if (entry.future != null) entry.future.cancel(true);
    entry.jobId = ++sequence;
    finish(entry);
  }

  private void drain(int limit) {
    for (int i = 0; i < limit; i++) {
      Result result = ready.poll();
      if (result == null) break;
      Entry entry = entries.get(result.position);
      if (entry == null || !entry.working || entry.jobId != result.jobId) {
        discarded++;
        continue;
      }
      finish(entry);
      if (result.error != null) {
        if (!(result.error instanceof CancellationException)) {
          lastError = String.valueOf(result.error.getMessage());
          if (result.error instanceof RejectedExecutionException
              || result.error instanceof OutOfMemoryError) rejected = true;
          else entry.failed = true;
        }
        continue;
      }
      if (result.data != null) {
        entry.data = result.data;
        cpuBytes += result.data.bytes();
        generated++;
        entry.dirty = true;
      } else {
        cpuBytes -= meshBytes(entry);
        entry.mesh = result.mesh;
        entry.lod = result.lod;
        cpuBytes += meshBytes(entry);
        entry.meshSignature = result.signature;
        entry.dirty = false;
        entry.uploaded = false;
        entry.version = ++sequence;
        meshes++;
      }
    }
  }

  private boolean haloReady(ChunkPos position) {
    for (int z = -1; z <= 1; z++)
      for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
          var p = safe(position.x() + x, position.y() + y, position.z() + z);
          if (p != null && loadWindow.contains(p)) {
            var entry = entries.get(p);
            if (entry == null || entry.data == null) return false;
          }
        }
    return true;
  }

  private long signature(Entry entry) {
    long value = entry.targetLod + 1;
    for (int z = -1; z <= 1; z++)
      for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
          if (x == 0 && y == 0 && z == 0) continue;
          var p = safe(entry.position.x() + x, entry.position.y() + y, entry.position.z() + z);
          var n = entries.get(p);
          value =
              value * 31
                  + (n != null && n.data != null && n.displayed && wanted.contains(p) ? 1 : 0);
        }
    return value;
  }

  public Set<ChunkPos> displaySet() {
    var result = new HashSet<>(wanted);
    boolean stable =
        wanted.stream()
            .allMatch(
                p -> {
                  var e = entries.get(p);
                  return e != null
                      && e.mesh != null
                      && e.uploaded
                      && !e.working
                      && e.meshSignature == signature(e);
                });
    for (var e : entries.values())
      if (!wanted.contains(e.position) && e.displayed) {
        if (stable) {
          e.displayed = false;
          e.uploaded = false;
          e.dirty = true;
          removed.add(e.position);
          cpuBytes -= meshBytes(e);
          e.mesh = null;
          e.lod = e.displayedLod = null;
          e.displayedVersion = 0;
        } else result.add(e.position);
      }
    return Set.copyOf(result);
  }

  private void evict() {
    var iterator = entries.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next().getValue();
      if (!loadWindow.contains(entry.position)
          || cpuBytes > config.cpuBytes && !wanted.contains(entry.position)) {
        cancel(entry);
        if (entry.data != null) cpuBytes -= entry.data.bytes();
        cpuBytes -= meshBytes(entry);
        removed.add(entry.position);
        iterator.remove();
      }
    }
  }

  public List<Upload> uploads(int count, long byteBudget) {
    var result = new ArrayList<Upload>();
    long bytes = 0;
    var sorted = new ArrayList<>(entries.values());
    sorted.sort(Comparator.comparingDouble(e -> distanceSquared(e.position, center)));
    for (var entry : sorted)
      if (wanted.contains(entry.position)
          && entry.mesh != null
          && !entry.uploaded
          && entry.meshSignature == signature(entry)
          && entry.lod.level() == entry.targetLod) {
        if (result.size() >= count
            || bytes + entry.mesh.byteSize() > byteBudget && !result.isEmpty()) break;
        result.add(new Upload(epoch, entry.position, entry.version, entry.mesh, entry.lod));
        bytes += entry.mesh.byteSize();
      }
    return List.copyOf(result);
  }

  public void uploaded(Upload upload) {
    if (closed || upload.epoch != epoch) return;
    Entry entry = entries.get(upload.position);
    if (entry != null && upload.version >= entry.displayedVersion) {
      cpuBytes -= meshBytes(entry);
      entry.displayed = true;
      entry.displayedLod = upload.cells;
      entry.displayedVersion = upload.version;
      if (entry.version == upload.version) entry.uploaded = true;
      cpuBytes += meshBytes(entry);
    }
  }

  private static long meshBytes(Entry entry) {
    return (entry.mesh == null ? 0 : entry.mesh.retainedBytes() + entry.lod.bytes())
        + (entry.displayedLod == null || entry.displayedLod == entry.lod
            ? 0
            : entry.displayedLod.bytes());
  }

  /** UI-thread traversal of the immutable cell inputs matching the versions actually drawn. */
  public Optional<Pick> pick(Camera.Ray ray, double distance, Map<ChunkPos, Long> drawnVersions) {
    if (closed) return Optional.empty();
    Objects.requireNonNull(drawnVersions);
    int n = config.size;
    var hit =
        VoxelRaycast.firstOccupied(
            ray,
            distance,
            (x, y, z) -> {
              var p = new ChunkPos(Math.floorDiv(x, n), Math.floorDiv(y, n), Math.floorDiv(z, n));
              var e = entries.get(p);
              return e != null
                      && e.displayedLod != null
                      && Objects.equals(drawnVersions.get(p), e.displayedVersion)
                  ? e.displayedLod.get(
                      Math.floorMod(x, n), Math.floorMod(y, n), Math.floorMod(z, n))
                  : 0;
            });
    return hit.map(
        p -> {
          var e =
              entries.get(
                  new ChunkPos(
                      Math.floorDiv(p.x(), n), Math.floorDiv(p.y(), n), Math.floorDiv(p.z(), n)));
          boolean proxy =
              e.displayedLod.exactType(
                      Math.floorMod(p.x(), n), Math.floorMod(p.y(), n), Math.floorMod(p.z(), n))
                  == 0;
          return new Pick(p, proxy);
        });
  }

  public void invalidateUploads() {
    for (var entry : entries.values()) {
      cpuBytes -= meshBytes(entry);
      entry.uploaded = false;
      entry.displayed = false;
      entry.displayedLod = null;
      entry.displayedVersion = 0;
      entry.dirty = true;
      cpuBytes += meshBytes(entry);
    }
  }

  public List<ChunkPos> removed() {
    var result = new ArrayList<ChunkPos>();
    while (!removed.isEmpty()) result.add(removed.removeFirst());
    return result;
  }

  public void reject() {
    rejected = true;
  }

  public void close() {
    if (closed) return;
    closed = true;
    for (var entry : entries.values()) cancel(entry);
    entries.clear();
    wanted.clear();
    loadWindow.clear();
    ready.clear();
    try {
      source.close();
    } catch (Exception ignored) {
    }
    cpuBytes = reserved = 0;
  }
}
