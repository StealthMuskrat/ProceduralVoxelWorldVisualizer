package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL43.*;

import io.voxelcreator.storage.ChunkPos;
import java.nio.*;
import java.util.*;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/**
 * Render-thread-only shared packed arena. GL 4.3 indirect batches have a GL 3.0 per-draw fallback.
 */
public final class SceneGpuRenderer implements AutoCloseable {
  public record Statistics(
      int resident,
      int visible,
      int culled,
      int draws,
      long gpuBytes,
      long uploadedBytes,
      boolean indirect,
      int largestFree,
      int capacityBytes,
      long freeBytes,
      long relocatedBytes,
      long compactionRemainingBytes,
      long compactions,
      boolean compacting,
      boolean compactionRequested,
      int retiring) {
    public Statistics(
        int resident,
        int visible,
        int culled,
        int draws,
        long gpuBytes,
        long uploadedBytes,
        boolean indirect,
        int largestFree) {
      this(
          resident,
          visible,
          culled,
          draws,
          gpuBytes,
          uploadedBytes,
          indirect,
          largestFree,
          0,
          0,
          0,
          0,
          0,
          false,
          false,
          0);
    }
  }

  private record Resident(
      long version, ArenaAllocator.Slice slice, int count, boolean points, int unitsPerBlock) {}

  private record Draw(ChunkPos position, int offset, int count) {}

  private ArenaCompaction compaction;
  private final Set<ChunkPos> retiring = new HashSet<>();
  private boolean compactionRequested, closed;
  private long relocated, compactions;
  private ByteBuffer relocationScratch;
  private int vao, vbo, origins, commands, program;
  private final ArenaAllocator arena;
  private final Map<ChunkPos, Resident> resident = new LinkedHashMap<>();
  private final Map<ChunkPos, Long> drawnVersions = new HashMap<>();
  private long epoch = -1, uploaded;
  private int visible, culled, draws;
  private boolean indirect;

  public SceneGpuRenderer(int bytes) {
    arena = new ArenaAllocator(bytes);
    try {
      vao = glGenVertexArrays();
      vbo = glGenBuffers();
      origins = glGenBuffers();
      commands = glGenBuffers();
      program = link();
      glBindVertexArray(vao);
      glBindBuffer(GL_ARRAY_BUFFER, vbo);
      glBufferData(GL_ARRAY_BUFFER, bytes, GL_DYNAMIC_DRAW);
      if (glGetError() != GL_NO_ERROR)
        throw new IllegalStateException("GPU arena allocation failed; reduce GPU budget");
      glEnableVertexAttribArray(0);
      glVertexAttribPointer(0, 3, GL_UNSIGNED_SHORT, false, 16, 0);
      glEnableVertexAttribArray(1);
      glVertexAttribPointer(1, 3, GL_BYTE, true, 16, 6);
      glEnableVertexAttribArray(2);
      glVertexAttribIPointer(2, 1, GL_UNSIGNED_INT, 16, 12);
      glEnableVertexAttribArray(3);
      glVertexAttribPointer(3, 1, GL_UNSIGNED_BYTE, true, 16, 9);
      glBindVertexArray(0);
      glBindBuffer(GL_ARRAY_BUFFER, 0);
    } catch (RuntimeException failure) {
      close();
      throw failure;
    }
  }

  public void epoch(long value) {
    if (value == epoch) return;
    pending = null;
    pendingSlice = null;
    compaction = null;
    compactionRequested = false;
    retiring.clear();
    arena.reset();
    resident.clear();
    drawnVersions.clear();
    epoch = value;
    uploaded = relocated = 0;
  }

  public boolean hasData() {
    return !resident.isEmpty();
  }

  /** Versions submitted by the latest render; excludes empty, culled and retiring allocations. */
  public Map<ChunkPos, Long> pickingVersions() {
    return Map.copyOf(drawnVersions);
  }

  public void beginFrame() {
    uploaded = relocated = 0;
    if (compactionRequested && pending == null && compaction == null) startCompaction();
  }

  private ChunkScene.Upload pending;
  private ArenaAllocator.Slice pendingSlice;
  private int progress;
  private ByteBuffer scratch;

  public boolean contains(ChunkScene.Upload upload) {
    var entry = resident.get(upload.position());
    return upload.epoch() == epoch
        && entry != null
        && !retiring.contains(upload.position())
        && entry.version == upload.version();
  }

  public boolean pending() {
    return pending != null;
  }

  public boolean stage(ChunkScene.Upload upload) {
    if (closed) throw new IllegalStateException("Renderer closed");
    if (upload.epoch() != epoch) throw new IllegalArgumentException("Obsolete scene upload");
    if (pending != null || compaction != null) return false;
    if (compactionRequested && startCompaction()) return false;
    var old = resident.get(upload.position());
    if (old != null && old.version == upload.version()) return true;
    var allocation = arena.allocate(upload.mesh().byteSize());
    if (allocation == null) {
      if (arena.freeBytes() >= upload.mesh().byteSize()) requestCompaction();
      return false;
    }
    pending = upload;
    pendingSlice = allocation;
    progress = 0;
    return true;
  }

  public ChunkScene.Upload advance(int budget) {
    if (budget < 0) throw new IllegalArgumentException("Negative upload budget");
    if (pending == null) return null;
    int bytes = Math.min(budget, pending.mesh().byteSize() - progress);
    if (bytes > 0) {
      if (scratch == null || scratch.capacity() < bytes)
        scratch = BufferUtils.createByteBuffer(bytes);
      scratch.clear();
      scratch.limit(bytes);
      pending.mesh().writeRangeTo(scratch, progress, bytes);
      scratch.flip();
      glBindBuffer(GL_ARRAY_BUFFER, vbo);
      glBufferSubData(GL_ARRAY_BUFFER, pendingSlice.offset() + progress, scratch);
      glBindBuffer(GL_ARRAY_BUFFER, 0);
      if (glGetError() != GL_NO_ERROR) {
        arena.release(pendingSlice);
        pending = null;
        pendingSlice = null;
        throw new IllegalStateException("Packed mesh upload failed");
      }
      uploaded += bytes;
      progress += bytes;
    }
    if (progress < pending.mesh().byteSize()) return null;
    var complete = pending;
    var old =
        resident.put(
            complete.position(),
            new Resident(
                complete.version(),
                pendingSlice,
                complete.mesh().vertexCount(),
                complete.mesh().points(),
                complete.mesh().unitsPerBlock()));
    if (old != null) arena.release(old.slice);
    pending = null;
    pendingSlice = null;
    return complete;
  }

  public void remove(ChunkPos position) {
    drawnVersions.remove(position);
    if (compaction != null) {
      if (resident.containsKey(position)) retiring.add(position);
      return;
    }
    if (pending != null && pending.position().equals(position)) {
      arena.release(pendingSlice);
      pending = null;
      pendingSlice = null;
    }
    var old = resident.remove(position);
    if (old != null) arena.release(old.slice);
  }

  public boolean compacting() {
    return compaction != null;
  }

  /** Explicit requests defer until an existing upload is published. No duplicate GPU arena. */
  public boolean requestCompaction() {
    if (closed) throw new IllegalStateException("Renderer closed");
    if (compaction != null) return false;
    if (pending != null) {
      compactionRequested = true;
      return true;
    }
    return startCompaction();
  }

  private boolean startCompaction() {
    compactionRequested = false;
    var layout = arena.beginCompaction();
    if (layout == null) return false;
    var primitives = new HashMap<ArenaAllocator.Slice, Integer>();
    for (var entry : resident.values())
      if (entry.slice.length() > 0) primitives.put(entry.slice, entry.points ? 16 : 48);
    compaction =
        new ArenaCompaction(layout, slice -> Objects.requireNonNull(primitives.get(slice)));
    return true;
  }

  /**
   * In-place downward copies use at most 64 KiB CPU readback staging and the caller's transfer
   * budget. GL 1.5 readback/subdata works on the GL 3.0 path too. Each completed range is
   * immediately drawable, so even an overlapping move never exposes partially overwritten old
   * geometry.
   */
  public int advanceCompaction(int byteBudget) {
    if (byteBudget < 0) throw new IllegalArgumentException("Negative compaction budget");
    int moved = 0;
    while (compaction != null) {
      var copy = compaction.next(byteBudget - moved);
      if (copy == null) break;
      if (relocationScratch == null)
        relocationScratch = BufferUtils.createByteBuffer(ArenaCompaction.MAX_COPY_BYTES);
      relocationScratch.clear();
      relocationScratch.limit(copy.bytes());
      glBindBuffer(GL_ARRAY_BUFFER, vbo);
      glGetBufferSubData(GL_ARRAY_BUFFER, copy.sourceOffset(), relocationScratch);
      if (glGetError() != GL_NO_ERROR) {
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        compaction.retry();
        throw new IllegalStateException("GPU arena relocation read failed");
      }
      relocationScratch.position(0);
      glBufferSubData(GL_ARRAY_BUFFER, copy.targetOffset(), relocationScratch);
      glBindBuffer(GL_ARRAY_BUFFER, 0);
      if (glGetError() != GL_NO_ERROR) {
        compaction.retry();
        throw new IllegalStateException("GPU arena relocation write failed");
      }
      compaction.copied(copy);
      moved += copy.bytes();
      relocated += copy.bytes();
      if (compaction.complete()) {
        var layout = compaction.layout();
        resident.replaceAll(
            (position, entry) ->
                new Resident(
                    entry.version,
                    layout.target(entry.slice),
                    entry.count,
                    entry.points,
                    entry.unitsPerBlock));
        arena.commitCompaction(layout);
        compaction = null;
        compactions++;
        for (var position : retiring) {
          var entry = resident.remove(position);
          if (entry != null) arena.release(entry.slice);
        }
        retiring.clear();
      }
    }
    return moved;
  }

  public Statistics statistics() {
    return new Statistics(
        resident.size(),
        visible,
        culled,
        draws,
        arena.usedBytes(),
        uploaded,
        indirect,
        arena.largestFreeRange(),
        arena.capacity(),
        arena.freeBytes(),
        relocated,
        compaction == null ? 0 : compaction.remainingBytes(),
        compactions,
        compaction != null,
        compactionRequested,
        retiring.size());
  }

  public void render(
      Matrix4f matrix,
      Vector3d origin,
      int size,
      Set<ChunkPos> wanted,
      int palette,
      int paletteWidth,
      boolean unlit,
      boolean useIndirect,
      boolean ao,
      float fogDistance) {
    visible = culled = draws = 0;
    drawnVersions.clear();
    indirect = useIndirect && GL.getCapabilities().OpenGL43;
    var frustum = new FrustumIntersection(matrix);
    var triangles = new TreeMap<Integer, List<Draw>>();
    var points = new TreeMap<Integer, List<Draw>>();
    for (var entry : resident.entrySet()) {
      var p = entry.getKey();
      if (!wanted.contains(p) || retiring.contains(p)) continue;
      float x = (float) ((double) p.x() * size - origin.x),
          y = (float) ((double) p.y() * size - origin.y),
          z = (float) ((double) p.z() * size - origin.z);
      if (!frustum.testAab(x, y, z, x + size, y + size, z + size)) {
        culled++;
        continue;
      }
      visible++;
      var value = entry.getValue();
      if (value.count > 0) {
        drawnVersions.put(p, value.version);
        var destination =
            (value.points ? points : triangles)
                .computeIfAbsent(value.unitsPerBlock, ignored -> new ArrayList<>());
        if (compaction == null) destination.add(new Draw(p, value.slice.offset(), value.count));
        else
          for (var segment : compaction.segments(value.slice))
            if (segment.bytes() > 0)
              destination.add(new Draw(p, segment.offset(), segment.bytes() / 16));
      }
    }
    glUseProgram(program);
    glUniformMatrix4fv(glGetUniformLocation(program, "matrix"), false, matrix.get(new float[16]));
    glUniform1i(glGetUniformLocation(program, "palette"), 0);
    glUniform1i(glGetUniformLocation(program, "paletteWidth"), paletteWidth);
    glUniform1f(glGetUniformLocation(program, "unlit"), unlit ? 1 : 0);
    glUniform1f(glGetUniformLocation(program, "fogDistance"), fogDistance);
    glUniform1f(glGetUniformLocation(program, "aoEnabled"), ao ? 1 : 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, palette);
    glBindVertexArray(vao);
    glEnable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glPointSize(3);
    for (var batch : triangles.entrySet())
      draw(batch.getValue(), GL_TRIANGLES, origin, size, batch.getKey());
    for (var batch : points.entrySet())
      draw(batch.getValue(), GL_POINTS, origin, size, batch.getKey());
    glBindVertexArray(0);
    glUseProgram(0);
  }

  private void draw(List<Draw> entries, int mode, Vector3d origin, int size, int unitsPerBlock) {
    if (entries.isEmpty()) return;
    glUniform1f(glGetUniformLocation(program, "positionScale"), 1f / unitsPerBlock);
    if (indirect) {
      FloatBuffer offsets = BufferUtils.createFloatBuffer(entries.size() * 3);
      IntBuffer batch = BufferUtils.createIntBuffer(entries.size() * 4);
      for (int i = 0; i < entries.size(); i++) {
        var entry = entries.get(i);
        var p = entry.position();
        offsets
            .put((float) ((double) p.x() * size - origin.x))
            .put((float) ((double) p.y() * size - origin.y))
            .put((float) ((double) p.z() * size - origin.z));
        batch.put(entry.count()).put(1).put(entry.offset() / 16).put(i);
      }
      offsets.flip();
      batch.flip();
      glBindBuffer(GL_ARRAY_BUFFER, origins);
      glBufferData(GL_ARRAY_BUFFER, offsets, GL_STREAM_DRAW);
      glEnableVertexAttribArray(4);
      glVertexAttribPointer(4, 3, GL_FLOAT, false, 12, 0);
      glVertexAttribDivisor(4, 1);
      glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands);
      glBufferData(GL_DRAW_INDIRECT_BUFFER, batch, GL_STREAM_DRAW);
      glMultiDrawArraysIndirect(mode, 0L, entries.size(), 0);
      glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
      glDisableVertexAttribArray(4);
      glVertexAttribDivisor(4, 0);
      glBindBuffer(GL_ARRAY_BUFFER, 0);
      draws++;
    } else {
      glDisableVertexAttribArray(4);
      for (var entry : entries) {
        var p = entry.position();
        glVertexAttrib3f(
            4,
            (float) ((double) p.x() * size - origin.x),
            (float) ((double) p.y() * size - origin.y),
            (float) ((double) p.z() * size - origin.z));
        glDrawArrays(mode, entry.offset() / 16, entry.count());
        draws++;
      }
    }
  }

  private int link() {
    String vertex =
        """
        #version 130
        in vec3 position;in vec3 normal;in uint typeId;in float occlusion;in vec3 chunkOffset;
        uniform mat4 matrix;uniform float positionScale;out vec3 N;out float AO;flat out uint type;out float distanceToEye;
        void main(){vec4 clip=matrix*vec4(position*positionScale+chunkOffset,1);gl_Position=clip;N=normal;AO=occlusion;type=typeId;distanceToEye=clip.w;}
        """;
    String fragment =
        """
        #version 130
        in vec3 N;in float AO;flat in uint type;in float distanceToEye;uniform sampler2D palette;uniform int paletteWidth;uniform float unlit,fogDistance,aoEnabled;out vec4 color;
        void main(){vec3 base=texelFetch(palette,ivec2(int(type)%paletteWidth,int(type)/paletteWidth),0).rgb;float lighting=(.35+.65*max(0.0,dot(normalize(N),normalize(vec3(.4,1,.3)))))*mix(1.0,.5+.5*AO,aoEnabled);vec3 shaded=base*mix(lighting,1.0,unlit);float fog=fogDistance>0?clamp((distanceToEye-fogDistance*.55)/(fogDistance*.45),0.0,1.0):0.0;color=vec4(mix(shaded,vec3(.055,.075,.1),fog),1);}
        """;
    int vs = shader(GL_VERTEX_SHADER, vertex),
        fs = shader(GL_FRAGMENT_SHADER, fragment),
        p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glBindAttribLocation(p, 0, "position");
    glBindAttribLocation(p, 1, "normal");
    glBindAttribLocation(p, 2, "typeId");
    glBindAttribLocation(p, 3, "occlusion");
    glBindAttribLocation(p, 4, "chunkOffset");
    glLinkProgram(p);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (glGetProgrami(p, GL_LINK_STATUS) == 0) {
      String log = glGetProgramInfoLog(p);
      glDeleteProgram(p);
      throw new IllegalStateException("Packed GLSL link: " + log);
    }
    return p;
  }

  private int shader(int kind, String source) {
    int shader = glCreateShader(kind);
    glShaderSource(shader, source);
    glCompileShader(shader);
    if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
      String log = glGetShaderInfoLog(shader);
      glDeleteShader(shader);
      throw new IllegalStateException("Packed GLSL compile: " + log);
    }
    return shader;
  }

  public void close() {
    if (closed) return;
    closed = true;
    compaction = null;
    compactionRequested = false;
    retiring.clear();
    resident.clear();
    drawnVersions.clear();
    pending = null;
    pendingSlice = null;
    arena.reset();
    relocationScratch = null;
    scratch = null;
    glDeleteProgram(program);
    glDeleteBuffers(vbo);
    glDeleteBuffers(origins);
    glDeleteBuffers(commands);
    glDeleteVertexArrays(vao);
  }
}
