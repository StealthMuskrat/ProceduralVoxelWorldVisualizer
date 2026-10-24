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
      int largestFree) {}

  private record Resident(long version, ArenaAllocator.Slice slice, int count, boolean points) {}

  private int vao, vbo, origins, commands, program;
  private final ArenaAllocator arena;
  private final Map<ChunkPos, Resident> resident = new LinkedHashMap<>();
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
    if (pending != null) {
      arena.release(pendingSlice);
      pending = null;
      pendingSlice = null;
    }
    for (var entry : resident.values()) arena.release(entry.slice);
    resident.clear();
    epoch = value;
    uploaded = 0;
  }

  public boolean hasData() {
    return !resident.isEmpty();
  }

  public void beginFrame() {
    uploaded = 0;
  }

  private ChunkScene.Upload pending;
  private ArenaAllocator.Slice pendingSlice;
  private int progress;
  private ByteBuffer scratch;

  public boolean contains(ChunkScene.Upload upload) {
    var entry = resident.get(upload.position());
    return entry != null && entry.version == upload.version();
  }

  public boolean pending() {
    return pending != null;
  }

  public boolean stage(ChunkScene.Upload upload) {
    if (upload.epoch() != epoch) throw new IllegalArgumentException("Obsolete scene upload");
    if (pending != null) return false;
    var old = resident.get(upload.position());
    if (old != null && old.version == upload.version()) return true;
    var allocation = arena.allocate(upload.mesh().byteSize());
    if (allocation == null) return false;
    pending = upload;
    pendingSlice = allocation;
    progress = 0;
    return true;
  }

  public ChunkScene.Upload advance(int budget) {
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
                complete.mesh().points()));
    if (old != null) arena.release(old.slice);
    pending = null;
    pendingSlice = null;
    return complete;
  }

  public void remove(ChunkPos position) {
    if (pending != null && pending.position().equals(position)) {
      arena.release(pendingSlice);
      pending = null;
      pendingSlice = null;
    }
    var old = resident.remove(position);
    if (old != null) arena.release(old.slice);
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
        arena.largestFreeRange());
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
    indirect = useIndirect && GL.getCapabilities().OpenGL43;
    var frustum = new FrustumIntersection(matrix);
    var triangles = new ArrayList<Map.Entry<ChunkPos, Resident>>();
    var points = new ArrayList<Map.Entry<ChunkPos, Resident>>();
    for (var entry : resident.entrySet()) {
      var p = entry.getKey();
      if (!wanted.contains(p)) continue;
      float x = (float) ((double) p.x() * size - origin.x),
          y = (float) ((double) p.y() * size - origin.y),
          z = (float) ((double) p.z() * size - origin.z);
      if (!frustum.testAab(x, y, z, x + size, y + size, z + size)) {
        culled++;
        continue;
      }
      visible++;
      if (entry.getValue().count > 0) (entry.getValue().points ? points : triangles).add(entry);
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
    draw(triangles, GL_TRIANGLES, origin, size);
    draw(points, GL_POINTS, origin, size);
    glBindVertexArray(0);
    glUseProgram(0);
  }

  private void draw(
      List<Map.Entry<ChunkPos, Resident>> entries, int mode, Vector3d origin, int size) {
    if (entries.isEmpty()) return;
    if (indirect) {
      FloatBuffer offsets = BufferUtils.createFloatBuffer(entries.size() * 3);
      IntBuffer batch = BufferUtils.createIntBuffer(entries.size() * 4);
      for (int i = 0; i < entries.size(); i++) {
        var entry = entries.get(i);
        var p = entry.getKey();
        offsets
            .put((float) ((double) p.x() * size - origin.x))
            .put((float) ((double) p.y() * size - origin.y))
            .put((float) ((double) p.z() * size - origin.z));
        batch.put(entry.getValue().count).put(1).put(entry.getValue().slice.offset() / 16).put(i);
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
        var p = entry.getKey();
        glVertexAttrib3f(
            4,
            (float) ((double) p.x() * size - origin.x),
            (float) ((double) p.y() * size - origin.y),
            (float) ((double) p.z() * size - origin.z));
        glDrawArrays(mode, entry.getValue().slice.offset() / 16, entry.getValue().count);
        draws++;
      }
    }
  }

  private int link() {
    String vertex =
        """
        #version 130
        in vec3 position;in vec3 normal;in uint typeId;in float occlusion;in vec3 chunkOffset;
        uniform mat4 matrix;out vec3 N;out float AO;flat out uint type;out float distanceToEye;
        void main(){vec4 clip=matrix*vec4(position*.5+chunkOffset,1);gl_Position=clip;N=normal;AO=occlusion;type=typeId;distanceToEye=clip.w;}
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
    glDeleteProgram(program);
    glDeleteBuffers(vbo);
    glDeleteBuffers(origins);
    glDeleteBuffers(commands);
    glDeleteVertexArrays(vao);
  }
}
