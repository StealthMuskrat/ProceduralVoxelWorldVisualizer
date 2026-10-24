package io.voxelcreator.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Versioned independently zlib-compressed adaptive trees, semantic dictionary and indexed coverage.
 * Reads legacy version 1; writes palette-packed version 2 by default.
 */
public final class VoxelArchive {
  private static final long MAGIC = 0x505643574f524c44L, FOOTER = 0x505643494e444558L;
  private static final int MAX_DICTIONARY_BYTES = 16 * 1024 * 1024;
  private static final int MAX_PAYLOAD = 100 * 1024 * 1024, MAX_ENTRIES = 1_000_000;

  public enum Format {
    LEGACY_V1(1),
    PACKED_V2(2);
    private final int version;

    Format(int version) {
      this.version = version;
    }

    public int version() {
      return version;
    }
  }

  private record Entry(ChunkPos position, int size, long offset, int length) {}

  public static Writer openWorldWriter(Path path, VoxelRegistry registry) throws IOException {
    return openWorldWriter(path, registry, Format.PACKED_V2);
  }

  public static Writer openWorldWriter(Path path, VoxelRegistry registry, Format format)
      throws IOException {
    return new Writer(path, registry, format);
  }

  public static Reader openWorldReader(Path path, VoxelRegistry registry) throws IOException {
    return new Reader(path, registry);
  }

  public static void writeSingleChunk(Path path, VoxelChunk chunk, VoxelRegistry registry)
      throws IOException {
    try (var w = openWorldWriter(path, registry)) {
      w.append(chunk);
      w.finish();
    }
  }

  public static VoxelChunk readSingleChunk(Path path, VoxelRegistry registry) throws IOException {
    try (var r = openWorldReader(path, registry)) {
      if (r.coverage().size() != 1) throw new IOException("Expected one covered chunk");
      return r.read(r.coverage().iterator().next()).orElseThrow();
    }
  }

  public static final class Writer implements AutoCloseable {
    private final Path destination, temp;
    private final RandomAccessFile file;
    private final VoxelRegistry registry;
    private final Format format;
    private final Map<ChunkPos, Entry> index = new LinkedHashMap<>();
    private boolean finished = false, closed = false;

    Writer(Path path, VoxelRegistry registry, Format format) throws IOException {
      this.format = Objects.requireNonNull(format);
      this.registry = Objects.requireNonNull(registry);
      destination = path.toAbsolutePath();
      Files.createDirectories(destination.getParent());
      temp = Files.createTempFile(destination.getParent(), ".voxel-archive-", ".tmp");
      file = new RandomAccessFile(temp.toFile(), "rw");
      try {
        file.writeLong(MAGIC);
        file.writeInt(format.version);
        file.writeInt(1);
        file.writeInt(registry.size());
        if (registry.size() > MAX_ENTRIES) throw new IOException("Dictionary count limit");
        for (String id : registry.semanticIds()) {
          file.writeUTF(id);
          if (file.getFilePointer() > 20L + MAX_DICTIONARY_BYTES)
            throw new IOException("Dictionary byte limit");
        }
      } catch (IOException | RuntimeException failure) {
        try {
          file.close();
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        try {
          Files.deleteIfExists(temp);
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    public void append(VoxelChunk chunk) throws IOException {
      if (closed || finished) throw new IOException("Writer finished");
      if (index.size() >= MAX_ENTRIES) throw new IOException("Archive index limit reached");
      if (index.containsKey(chunk.position())) throw new IOException("Duplicate chunk position");
      long offset = 0;
      int length = 0;
      if (!chunk.isEmpty()) {
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new DeflaterOutputStream(buffer))) {
          java.util.function.IntUnaryOperator mapping =
              id -> {
                registry.semanticId(id);
                return id;
              };
          if (format == Format.LEGACY_V1) chunk.writeTree(out, mapping);
          else chunk.writePackedTree(out, mapping);
        }
        byte[] payload = buffer.toByteArray();
        if (payload.length > MAX_PAYLOAD) throw new IOException("Payload limit");
        offset = file.getFilePointer();
        length = payload.length;
        try {
          file.write(payload);
        } catch (IOException failure) {
          try {
            file.setLength(offset);
            file.seek(offset);
          } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
          }
          throw failure;
        }
      }
      index.put(chunk.position(), new Entry(chunk.position(), chunk.size(), offset, length));
    }

    public void finish() throws IOException {
      if (finished) return;
      if (closed) throw new IOException("Writer closed");
      long start = file.getFilePointer();
      file.writeInt(index.size());
      var entries = new ArrayList<>(index.values());
      entries.sort(Comparator.comparing(Entry::position));
      for (var e : entries) {
        file.writeInt(e.position.x());
        file.writeInt(e.position.y());
        file.writeInt(e.position.z());
        file.writeInt(e.size);
        file.writeLong(e.offset);
        file.writeInt(e.length);
      }
      file.writeLong(start);
      file.writeLong(FOOTER);
      file.getFD().sync();
      file.close();
      closed = true;
      try {
        Files.move(
            temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
      }
      finished = true;
    }

    @Override
    public void close() throws IOException {
      if (!closed) {
        closed = true;
        file.close();
      }
      if (!finished) Files.deleteIfExists(temp);
    }
  }

  public static final class Reader implements AutoCloseable {
    private final RandomAccessFile file;
    private final Map<ChunkPos, Entry> index;
    private final List<String> dictionary;
    private final int[] remap;
    private final int version;
    private boolean closed = false;

    Reader(Path path, VoxelRegistry registry) throws IOException {
      file = new RandomAccessFile(path.toFile(), "r");
      try {
        long fileLength = file.length();
        if (fileLength < 40 || file.readLong() != MAGIC)
          throw new IOException("Invalid archive signature");
        version = file.readInt();
        if (version != 1 && version != 2)
          throw new IOException("Unsupported archive version " + version);
        if (file.readInt() != 1) throw new IOException("Unsupported compression identifier");
        int count = file.readInt();
        if (count < 1 || count > 1_000_000) throw new IOException("Dictionary count out of bounds");
        var names = new ArrayList<String>();
        var unique = new HashSet<String>();
        remap = new int[count];
        for (int i = 0; i < count; i++) {
          String id = file.readUTF();
          if (file.getFilePointer() > 20L + MAX_DICTIONARY_BYTES
              || file.getFilePointer() > fileLength - 20)
            throw new IOException("Dictionary byte limit or truncated header");
          if (id.isBlank()
              || !java.text.Normalizer.isNormalized(id, java.text.Normalizer.Form.NFC)
              || !unique.add(id)) throw new IOException("Invalid dictionary");
          names.add(id);
          try {
            remap[i] = registry.runtimeId(id);
          } catch (IllegalArgumentException e) {
            remap[i] = -1;
          }
        }
        if (!names.getFirst().equals("EMPTY"))
          throw new IOException("Dictionary index 0 must be EMPTY");
        dictionary = List.copyOf(names);
        long dataStart = file.getFilePointer();
        file.seek(fileLength - 16);
        long indexStart = file.readLong();
        if (file.readLong() != FOOTER || indexStart < dataStart || indexStart > fileLength - 20)
          throw new IOException("Invalid index footer/offset");
        file.seek(indexStart);
        int entries = file.readInt();
        if (entries < 0
            || entries > MAX_ENTRIES
            || indexStart + 4L + 28L * entries != fileLength - 16)
          throw new IOException("Invalid index size");
        var map = new LinkedHashMap<ChunkPos, Entry>();
        var ranges = new ArrayList<long[]>();
        for (int i = 0; i < entries; i++) {
          var pos = new ChunkPos(file.readInt(), file.readInt(), file.readInt());
          int size = file.readInt();
          try {
            VoxelChunk.validateSize(size);
          } catch (IllegalArgumentException e) {
            throw new IOException(e);
          }
          long offset = file.readLong();
          int len = file.readInt();
          if (len < 0
              || len > MAX_PAYLOAD
              || len == 0 && offset != 0
              || len > 0 && (offset < dataStart || offset > indexStart - len))
            throw new IOException("Invalid payload offset/length");
          if (map.put(pos, new Entry(pos, size, offset, len)) != null)
            throw new IOException("Duplicate archive coordinate");
          if (len > 0) ranges.add(new long[] {offset, offset + len});
        }
        ranges.sort(Comparator.comparingLong(a -> a[0]));
        long expected = dataStart;
        for (long[] range : ranges) {
          if (range[0] != expected)
            throw new IOException("Overlapping or unindexed archive payload bytes");
          expected = range[1];
        }
        if (expected != indexStart) throw new IOException("Unindexed archive payload bytes");
        index = Collections.unmodifiableMap(map);
      } catch (Throwable e) {
        file.close();
        if (e instanceof IOException io) throw io;
        throw new IOException("Malformed archive", e);
      }
    }

    public int version() {
      return version;
    }

    public Set<ChunkPos> coverage() {
      return index.keySet();
    }

    public List<String> dictionary() {
      return dictionary;
    }

    public boolean contains(ChunkPos pos) {
      return index.containsKey(pos);
    }

    public boolean hasStoredPayload(ChunkPos pos) {
      var e = index.get(pos);
      return e != null && e.length > 0;
    }

    public synchronized Optional<VoxelChunk> read(ChunkPos pos) throws IOException {
      if (closed) throw new IOException("Reader closed");
      var e = index.get(pos);
      if (e == null) return Optional.empty();
      if (e.length == 0) return Optional.of(VoxelChunk.uniform(pos, e.size, 0));
      byte[] payload = new byte[e.length];
      file.seek(e.offset);
      file.readFully(payload);
      var missing = new LinkedHashSet<String>();
      var inflater = new Inflater();
      var compressed = new ByteArrayInputStream(payload);
      try (var zipped = new InflaterInputStream(compressed, inflater);
          var bounded = new LimitedInputStream(zipped, 80L * 1024 * 1024);
          var in = new DataInputStream(bounded)) {
        VoxelChunk chunk =
            VoxelChunk.readTree(
                pos,
                e.size,
                in,
                value -> {
                  if (value < 0 || value >= remap.length)
                    throw new IllegalArgumentException("Dictionary index out of bounds");
                  int id = remap[value];
                  if (id < 0) {
                    missing.add(dictionary.get(value));
                    return 1;
                  }
                  return id;
                },
                version >= 2);
        if (in.read() != -1
            || !inflater.finished()
            || inflater.getRemaining() != 0
            || compressed.available() != 0)
          throw new IOException("Trailing or truncated compressed chunk data");
        if (!missing.isEmpty()) throw new IOException("Unresolved semantic IDs: " + missing);
        return Optional.of(chunk);
      } catch (RuntimeException ex) {
        throw new IOException("Corrupt archive chunk " + pos, ex);
      } finally {
        inflater.end();
      }
    }

    @Override
    public synchronized void close() throws IOException {
      if (!closed) {
        closed = true;
        file.close();
      }
    }
  }

  private static final class LimitedInputStream extends FilterInputStream {
    long remaining;

    LimitedInputStream(InputStream in, long limit) {
      super(in);
      remaining = limit;
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) throw new IOException("Decoded chunk limit");
      int v = super.read();
      if (v >= 0) remaining--;
      return v;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (remaining <= 0) throw new IOException("Decoded chunk limit");
      int n = super.read(b, off, (int) Math.min(len, remaining));
      if (n > 0) remaining -= n;
      return n;
    }
  }
}
