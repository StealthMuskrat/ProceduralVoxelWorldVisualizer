package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;

import java.nio.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

/** All methods belong exclusively to the render thread. CPU work never touches OpenGL. */
public final class GlViewport implements AutoCloseable {
  private int texture, framebuffer, depth, width, height, vao, vbo, program, palette;
  private Mesh mesh = new Mesh(new float[0], false);
  private boolean image = false, volume = false, retainFrame;
  private int volumeProgram, volumeCopyRead, volumeCopyDraw;
  private final VolumeTextureUpload volumeUpload;
  private SceneGpuRenderer sceneRenderer;
  private int sceneBudget, paletteWidth = 256;
  private boolean sceneActive, sceneIndirect = true, sceneAo = true;
  private float sceneFog;
  private org.joml.Vector3d sceneOrigin = new org.joml.Vector3d();
  private java.util.Set<io.voxelcreator.storage.ChunkPos> sceneWanted = java.util.Set.of();
  private int[] lastPalette;

  public SceneGpuRenderer scene(long epoch, int bytes) {
    volumeUpload.clear();
    retainFrame = false;
    if (sceneRenderer == null || sceneBudget != bytes) {
      if (sceneRenderer != null) sceneRenderer.close();
      sceneRenderer = null;
      sceneRenderer = new SceneGpuRenderer(bytes);
      sceneBudget = bytes;
    }
    sceneRenderer.epoch(epoch);
    sceneActive = true;
    image = false;
    volume = false;
    return sceneRenderer;
  }

  public SceneGpuRenderer sceneRenderer() {
    return sceneRenderer;
  }

  public void sceneView(
      org.joml.Vector3d origin,
      java.util.Set<io.voxelcreator.storage.ChunkPos> wanted,
      boolean indirect,
      boolean ao,
      float fog) {
    sceneOrigin.set(origin);
    sceneWanted = wanted;
    sceneIndirect = indirect;
    sceneAo = ao;
    sceneFog = fog;
  }

  public void sceneDisabled() {
    sceneActive = false;
  }

  /**
   * Release hidden streaming resources when the viewport has no live source. Render thread only.
   */
  public void releaseScene() {
    sceneActive = false;
    if (sceneRenderer != null) sceneRenderer.close();
    sceneRenderer = null;
    sceneBudget = 0;
    sceneWanted = java.util.Set.of();
  }

  private VolumeTransfer.Settings volumeSettings = new VolumeTransfer.Settings(-1, 1, .12, 0, 192);

  public GlViewport() {
    texture = glGenTextures();
    framebuffer = glGenFramebuffers();
    depth = glGenRenderbuffers();
    vao = glGenVertexArrays();
    vbo = glGenBuffers();
    palette = glGenTextures();
    program = link();
    volumeUpload =
        new VolumeTextureUpload(
            new VolumeTextureUpload.Backend() {
              public int allocate(int size) {
                if (size > glGetInteger(GL_MAX_3D_TEXTURE_SIZE))
                  throw new IllegalArgumentException("Volume exceeds device texture limit");
                int id = glGenTextures();
                int unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
                glBindTexture(GL_TEXTURE_3D, id);
                try {
                  glTexImage3D(
                      GL_TEXTURE_3D,
                      0,
                      GL_R32F,
                      size,
                      size,
                      size,
                      0,
                      GL_RED,
                      GL_FLOAT,
                      (ByteBuffer) null);
                  glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                  glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                  glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                  glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                  glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
                  int error = glGetError();
                  if (error != GL_NO_ERROR)
                    throw new IllegalStateException("Volume allocation GL error " + error);
                  return id;
                } catch (RuntimeException | Error failure) {
                  glDeleteTextures(id);
                  throw failure;
                } finally {
                  glBindTexture(GL_TEXTURE_3D, 0);
                  glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
                }
              }

              public void write(
                  int id, int x, int y, int z, int w, int h, int d, FloatBuffer values) {
                glBindTexture(GL_TEXTURE_3D, id);
                int alignment = glGetInteger(GL_UNPACK_ALIGNMENT);
                int swap = glGetInteger(GL_UNPACK_SWAP_BYTES);
                int row = glGetInteger(GL_UNPACK_ROW_LENGTH),
                    height = glGetInteger(GL_UNPACK_IMAGE_HEIGHT);
                int skipX = glGetInteger(GL_UNPACK_SKIP_PIXELS),
                    skipY = glGetInteger(GL_UNPACK_SKIP_ROWS),
                    skipZ = glGetInteger(GL_UNPACK_SKIP_IMAGES);
                int unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
                try {
                  glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
                  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
                  glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE);
                  glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
                  glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
                  glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
                  glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
                  glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
                  glTexSubImage3D(GL_TEXTURE_3D, 0, x, y, z, w, h, d, GL_RED, GL_FLOAT, values);
                  int error = glGetError();
                  if (error != GL_NO_ERROR)
                    throw new IllegalStateException("Volume upload GL error " + error);
                } finally {
                  glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
                  glPixelStorei(GL_UNPACK_SWAP_BYTES, swap);
                  glPixelStorei(GL_UNPACK_ROW_LENGTH, row);
                  glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, height);
                  glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipX);
                  glPixelStorei(GL_UNPACK_SKIP_ROWS, skipY);
                  glPixelStorei(GL_UNPACK_SKIP_IMAGES, skipZ);
                  glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
                  glBindTexture(GL_TEXTURE_3D, 0);
                }
              }

              public boolean supportsCopies() {
                return true;
              }

              public void copy(
                  int source,
                  int sx,
                  int sy,
                  int sz,
                  int destination,
                  int dx,
                  int dy,
                  int dz,
                  int w,
                  int h) {
                if (volumeCopyRead == 0) volumeCopyRead = glGenFramebuffers();
                if (volumeCopyDraw == 0) volumeCopyDraw = glGenFramebuffers();
                int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
                    draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
                try {
                  glBindFramebuffer(GL_READ_FRAMEBUFFER, volumeCopyRead);
                  glFramebufferTextureLayer(
                      GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, source, 0, sz);
                  glReadBuffer(GL_COLOR_ATTACHMENT0);
                  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, volumeCopyDraw);
                  glFramebufferTextureLayer(
                      GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, destination, 0, dz);
                  glDrawBuffer(GL_COLOR_ATTACHMENT0);
                  if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE
                      || glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Incomplete volume copy framebuffer");
                  glDisable(GL_SCISSOR_TEST);
                  glBlitFramebuffer(
                      sx,
                      sy,
                      sx + w,
                      sy + h,
                      dx,
                      dy,
                      dx + w,
                      dy + h,
                      GL_COLOR_BUFFER_BIT,
                      GL_NEAREST);
                  int error = glGetError();
                  if (error != GL_NO_ERROR)
                    throw new IllegalStateException("Volume copy GL error " + error);
                } finally {
                  // Detach before retiring textures; hidden FBO references must not retain old
                  // storage.
                  glBindFramebuffer(GL_READ_FRAMEBUFFER, volumeCopyRead);
                  glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, 0, 0, 0);
                  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, volumeCopyDraw);
                  glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, 0, 0, 0);
                  glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
                  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
                  if (scissor) glEnable(GL_SCISSOR_TEST);
                  else glDisable(GL_SCISSOR_TEST);
                }
              }

              public void delete(int id) {
                glDeleteTextures(id);
              }
            });
    volumeProgram = linkVolume();
  }

  public int texture() {
    return texture;
  }

  public void setImage(int w, int h, int[] rgba) {
    volumeUpload.clear();
    retainFrame = false;
    image = true;
    sceneActive = false;
    volume = false;
    width = height = 0;
    glBindTexture(GL_TEXTURE_2D, texture);
    IntBuffer b = BufferUtils.createIntBuffer(rgba.length);
    b.put(rgba).flip();
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glBindTexture(GL_TEXTURE_2D, 0);
    releaseScene();
  }

  public void setMesh(Mesh next) {
    volumeUpload.clear();
    retainFrame = false;
    image = false;
    sceneActive = false;
    volume = false;
    mesh = next;
    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, next.vertices(), GL_STATIC_DRAW);
    for (int a = 0; a < 3; a++) {
      glEnableVertexAttribArray(a);
      glVertexAttribPointer(a, a == 2 ? 1 : 3, GL_FLOAT, false, 28, a == 0 ? 0 : a == 1 ? 12 : 24);
    }
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    releaseScene();
  }

  /** Synchronous compatibility entry point; editor previews use stage/advanceVolume. */
  public void setVolume(VolumeData data) {
    stageVolume(data, Long.MAX_VALUE);
    advanceVolume(Integer.MAX_VALUE);
  }

  public boolean stageVolume(VolumeData data, long gpuBudget) {
    return stageVolume(data, gpuBudget, null);
  }

  public boolean stageVolume(VolumeData data, long gpuBudget, VolumeTextureUpload.Region region) {
    if (!volumeUpload.stage(data, gpuBudget, region)) return false;
    // Once its arena is retired, keep a previous streamed framebuffer through cancellation/failure.
    if (sceneActive) retainFrame = true;
    releaseScene();
    return true;
  }

  public int advanceVolume(int byteBudget) {
    long version = volumeUpload.statistics().version();
    int written = volumeUpload.advance(byteBudget);
    if (volumeUpload.statistics().version() != version) {
      retainFrame = false;
      image = false;
      volume = true;
      sceneActive = false;
    }
    return written;
  }

  public VolumeTextureUpload.Resident volumeResident() {
    return volumeUpload.resident();
  }

  public void cancelVolume() {
    volumeUpload.cancel();
  }

  public void releaseVolume() {
    volumeUpload.clear();
    volume = false;
  }

  public VolumeTextureUpload.Statistics volumeStatistics() {
    return volumeUpload.statistics();
  }

  public void volumeAppearance(
      double min, double max, double opacity, double threshold, int steps) {
    volumeSettings = new VolumeTransfer.Settings(min, max, opacity, threshold, steps);
  }

  public void setPalette(int[] colors) {
    if (java.util.Arrays.equals(lastPalette, colors)) return;
    lastPalette = colors.clone();
    glBindTexture(GL_TEXTURE_2D, palette);
    paletteWidth = Math.min(glGetInteger(GL_MAX_TEXTURE_SIZE), Math.max(1, colors.length));
    int rows = (colors.length + paletteWidth - 1) / paletteWidth;
    IntBuffer data = BufferUtils.createIntBuffer(paletteWidth * rows);
    data.put(colors);
    while (data.hasRemaining()) data.put(0);
    data.flip();
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, paletteWidth, rows, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glBindTexture(GL_TEXTURE_2D, 0);
  }

  public void render(int w, int h, Matrix4f matrix, boolean unlit, float alpha) {
    if (retainFrame
        || volumeUpload.statistics().pending()
        || image
        || sceneActive && !sceneRenderer.hasData()) return;
    w = Math.max(16, Math.min(4096, w));
    h = Math.max(16, Math.min(4096, h));
    int oldFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
    int[] oldViewport = new int[4];
    glGetIntegerv(GL_VIEWPORT, oldViewport);
    if (w != width || h != height) {
      width = w;
      height = h;
      glBindTexture(GL_TEXTURE_2D, texture);
      glTexImage2D(
          GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glBindRenderbuffer(GL_RENDERBUFFER, depth);
      glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
      glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
      glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
      if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        throw new IllegalStateException("Incomplete viewport framebuffer");
    }
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glViewport(0, 0, w, h);
    glClearColor(.055f, .075f, .1f, 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    if (sceneActive) {
      sceneRenderer.render(
          matrix,
          sceneOrigin,
          32,
          sceneWanted,
          palette,
          paletteWidth,
          unlit,
          sceneIndirect,
          sceneAo,
          sceneFog);
    } else if (volume) {
      glDisable(GL_DEPTH_TEST);
      glDisable(GL_CULL_FACE);
      glDisable(GL_BLEND);
      glUseProgram(volumeProgram);
      glUniformMatrix4fv(
          glGetUniformLocation(volumeProgram, "inverseMatrix"),
          false,
          new Matrix4f(matrix).invert().get(new float[16]));
      glUniform1f(glGetUniformLocation(volumeProgram, "extent"), volumeUpload.resident().size());
      var transfer = VolumeTransfer.of(volumeUpload.resident().encoding(), volumeSettings);
      glUniform1f(glGetUniformLocation(volumeProgram, "opacity"), transfer.opacity());
      glUniform1f(glGetUniformLocation(volumeProgram, "threshold"), transfer.threshold());
      glUniform2f(
          glGetUniformLocation(volumeProgram, "displayTransfer"), transfer.gain(), transfer.bias());
      glUniform1i(glGetUniformLocation(volumeProgram, "steps"), transfer.steps());
      glActiveTexture(GL_TEXTURE1);
      glBindTexture(GL_TEXTURE_3D, volumeUpload.resident().texture());
      glUniform1i(glGetUniformLocation(volumeProgram, "density"), 1);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, palette);
      glUniform1i(glGetUniformLocation(volumeProgram, "palette"), 0);
      glBindVertexArray(vao);
      glDrawArrays(GL_TRIANGLES, 0, 3);
      glActiveTexture(GL_TEXTURE1);
      glBindTexture(GL_TEXTURE_3D, 0);
      glActiveTexture(GL_TEXTURE0);
    } else {
      glEnable(GL_DEPTH_TEST);
      glDisable(GL_CULL_FACE);
      glUseProgram(program);
      glUniformMatrix4fv(glGetUniformLocation(program, "matrix"), false, matrix.get(new float[16]));
      glUniform1f(glGetUniformLocation(program, "unlit"), unlit ? 1 : 0);
      glUniform1f(glGetUniformLocation(program, "alpha"), alpha);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, palette);
      glUniform1i(glGetUniformLocation(program, "palette"), 0);
      glUniform1i(glGetUniformLocation(program, "paletteWidth"), paletteWidth);
      glBindVertexArray(vao);
      if (alpha < 1) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
      } else glDisable(GL_BLEND);
      glPointSize(mesh.points() ? 3 : 1);
      glDrawArrays(mesh.points() ? GL_POINTS : GL_TRIANGLES, 0, mesh.vertexCount());
    }
    glBindVertexArray(0);
    glUseProgram(0);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glBindFramebuffer(GL_FRAMEBUFFER, oldFbo);
    glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
  }

  private int link() {
    String vertex =
        "#version 130\n"
            + "in vec3 position;in vec3 normal;in float typeId;uniform mat4 matrix;out vec3 N;flat"
            + " out float type;void"
            + " main(){N=normal;type=typeId;gl_Position=matrix*vec4(position,1.0);}";
    String fragment =
        "#version 130\n"
            + "in vec3 N;flat in float type;uniform sampler2D palette;uniform int"
            + " paletteWidth;uniform float unlit;uniform float alpha;out vec4 color;void"
            + " main(){vec3"
            + " base=texelFetch(palette,ivec2(int(type)%paletteWidth,int(type)/paletteWidth),0).rgb;float"
            + " light=mix(0.35+0.65*max(0.0,dot(normalize(N),normalize(vec3(0.4,1.0,0.3)))),1.0,unlit);color=vec4(base*light,alpha);}";
    int vs = shader(GL_VERTEX_SHADER, vertex),
        fs = shader(GL_FRAGMENT_SHADER, fragment),
        p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glBindAttribLocation(p, 0, "position");
    glBindAttribLocation(p, 1, "normal");
    glBindAttribLocation(p, 2, "typeId");
    glLinkProgram(p);
    if (glGetProgrami(p, GL_LINK_STATUS) == 0)
      throw new IllegalStateException(glGetProgramInfoLog(p));
    glDeleteShader(vs);
    glDeleteShader(fs);
    return p;
  }

  private int linkVolume() {
    String vertex =
        """
        #version 130
        out vec2 screen;
        void main(){vec2 p=vec2(gl_VertexID==1?3.0:-1.0,gl_VertexID==2?3.0:-1.0);screen=p;gl_Position=vec4(p,0,1);}
        """;
    String fragment =
        """
        #version 130
        in vec2 screen;out vec4 color;
        uniform mat4 inverseMatrix;uniform sampler3D density;uniform sampler2D palette;
        uniform float extent,opacity,threshold;uniform vec2 displayTransfer;uniform int steps;
        void main(){
          vec3 background=vec3(.055,.075,.1);vec4 na=inverseMatrix*vec4(screen,-1,1),fa=inverseMatrix*vec4(screen,1,1);
          vec3 origin=na.xyz/na.w,direction=normalize(fa.xyz/fa.w-origin);
          float enter=0.0,exit=1e30;
          for(int axis=0;axis<3;axis++){
            // Unprojection and fullscreen interpolation can move an exact boundary by a few float ULPs.
            float boundaryTolerance=8.0*1.1920929e-7*max(1.0,extent);
            if(abs(direction[axis])<8.0*1.1920929e-7){
              if(origin[axis]<-boundaryTolerance||origin[axis]>extent+boundaryTolerance){color=vec4(background,1);return;}
              origin[axis]=clamp(origin[axis],0.0,extent);
            }else{
              float a=-origin[axis]/direction[axis],b=(extent-origin[axis])/direction[axis];
              enter=max(enter,min(a,b));exit=min(exit,max(a,b));
            }
          }
          if(exit<=enter){color=vec4(background,1);return;}
          float stepLength=(exit-enter)/float(steps);vec3 accumulated=vec3(0);float transmittance=1.0;
          for(int i=0;i<512;i++){
            if(i>=steps||transmittance<.005)break;
            vec3 p=origin+direction*(enter+(float(i)+.5)*stepLength);float raw=texture(density,p/extent).r;
            if(raw>=threshold){float t=clamp(raw*displayTransfer.x+displayTransfer.y,0.0,1.0),a=1.0-exp(-t*opacity*stepLength);
              vec3 base=texture(palette,vec2((t*255.0+.5)/256.0,.5)).rgb;accumulated+=transmittance*a*base;transmittance*=1.0-a;}
          }
          color=vec4(accumulated+transmittance*background,1);
        }
        """;
    int vs = shader(GL_VERTEX_SHADER, vertex),
        fs = shader(GL_FRAGMENT_SHADER, fragment),
        p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    if (glGetProgrami(p, GL_LINK_STATUS) == 0)
      throw new IllegalStateException(glGetProgramInfoLog(p));
    glDeleteShader(vs);
    glDeleteShader(fs);
    return p;
  }

  private int shader(int type, String source) {
    int s = glCreateShader(type);
    glShaderSource(s, source);
    glCompileShader(s);
    if (glGetShaderi(s, GL_COMPILE_STATUS) == 0)
      throw new IllegalStateException("Shader failed: " + glGetShaderInfoLog(s));
    return s;
  }

  @Override
  public void close() {
    if (sceneRenderer != null) sceneRenderer.close();
    glDeleteProgram(program);
    glDeleteProgram(volumeProgram);
    volumeUpload.close();
    glDeleteFramebuffers(volumeCopyRead);
    glDeleteFramebuffers(volumeCopyDraw);
    glDeleteBuffers(vbo);
    glDeleteVertexArrays(vao);
    glDeleteTextures(texture);
    glDeleteTextures(palette);
    glDeleteRenderbuffers(depth);
    glDeleteFramebuffers(framebuffer);
  }
}
