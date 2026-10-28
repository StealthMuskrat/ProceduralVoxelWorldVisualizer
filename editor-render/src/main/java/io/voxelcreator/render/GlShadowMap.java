package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;

/** One render-thread-owned depth texture. Optional shadows never require a newer GL context. */
public final class GlShadowMap implements AutoCloseable {
  public interface Draw {
    int render(int program, Matrix4f matrix);
  }

  public record Sampling(int texture, int resolution, DirectionalShadow.Projection projection) {}

  private int texture, framebuffer, program, resolution;

  public int texture() {
    return texture;
  }

  public int framebuffer() {
    return framebuffer;
  }

  public long bytes() {
    return DirectionalShadow.bytes(resolution);
  }

  public int resolution() {
    return resolution;
  }

  public void allocate(int size) {
    DirectionalShadow.bytes(size);
    if (size == 0) throw new IllegalArgumentException("Empty shadow map");
    if (size == resolution) return;
    // Retire before replacement: never hold two complete shadow maps.
    close();
    if (size > glGetInteger(GL_MAX_TEXTURE_SIZE))
      throw new IllegalStateException("Shadow map exceeds device texture limit");
    int active = glGetInteger(GL_ACTIVE_TEXTURE),
        binding = glGetInteger(GL_TEXTURE_BINDING_2D),
        unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
    int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
        draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    try {
      texture = glGenTextures();
      framebuffer = glGenFramebuffers();
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
      glBindTexture(GL_TEXTURE_2D, texture);
      glTexImage2D(
          GL_TEXTURE_2D,
          0,
          GL_DEPTH_COMPONENT24,
          size,
          size,
          0,
          GL_DEPTH_COMPONENT,
          GL_UNSIGNED_INT,
          (ByteBuffer) null);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
      glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texture, 0);
      glDrawBuffer(GL_NONE);
      glReadBuffer(GL_NONE);
      if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        throw new IllegalStateException("Incomplete shadow framebuffer");
      program = link();
      resolution = size;
    } catch (RuntimeException failure) {
      close();
      throw failure;
    } finally {
      glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
      glBindTexture(GL_TEXTURE_2D, binding);
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
      glActiveTexture(active);
    }
  }

  /** Restore every raster state changed by the depth pass, including split read/draw FBOs. */
  public int render(DirectionalShadow.Projection projection, Draw drawCommands) {
    if (program == 0) throw new IllegalStateException("Shadow map not allocated");
    int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
        draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    int currentProgram = glGetInteger(GL_CURRENT_PROGRAM),
        vao = glGetInteger(GL_VERTEX_ARRAY_BINDING),
        depthFunc = glGetInteger(GL_DEPTH_FUNC);
    int[] viewport = new int[4];
    glGetIntegerv(GL_VIEWPORT, viewport);
    boolean depthTest = glIsEnabled(GL_DEPTH_TEST),
        cull = glIsEnabled(GL_CULL_FACE),
        scissor = glIsEnabled(GL_SCISSOR_TEST),
        offset = glIsEnabled(GL_POLYGON_OFFSET_FILL),
        discard = glIsEnabled(GL_RASTERIZER_DISCARD),
        mask = glGetBoolean(GL_DEPTH_WRITEMASK);
    double clear = glGetDouble(GL_DEPTH_CLEAR_VALUE);
    double[] range = new double[2];
    glGetDoublev(GL_DEPTH_RANGE, range);
    float[] attribute = new float[4];
    glGetVertexAttribfv(4, GL_CURRENT_VERTEX_ATTRIB, attribute);
    try {
      glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
      glViewport(0, 0, resolution, resolution);
      glEnable(GL_DEPTH_TEST);
      glDepthMask(true);
      glDepthFunc(GL_LESS);
      glClearDepth(1);
      glDepthRange(0, 1);
      glDisable(GL_CULL_FACE);
      glDisable(GL_SCISSOR_TEST);
      glDisable(GL_POLYGON_OFFSET_FILL);
      glDisable(GL_RASTERIZER_DISCARD);
      glClear(GL_DEPTH_BUFFER_BIT);
      return drawCommands.render(program, projection.matrix());
    } finally {
      glBindVertexArray(vao);
      glVertexAttrib4f(4, attribute[0], attribute[1], attribute[2], attribute[3]);
      glUseProgram(currentProgram);
      glDepthMask(mask);
      glDepthFunc(depthFunc);
      glClearDepth(clear);
      glDepthRange(range[0], range[1]);
      enable(GL_DEPTH_TEST, depthTest);
      enable(GL_CULL_FACE, cull);
      enable(GL_SCISSOR_TEST, scissor);
      enable(GL_POLYGON_OFFSET_FILL, offset);
      enable(GL_RASTERIZER_DISCARD, discard);
      glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
      glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }
  }

  private static void enable(int capability, boolean value) {
    if (value) glEnable(capability);
    else glDisable(capability);
  }

  /** Caller owns texture unit 3 binding for the duration of the color pass. */
  public static void uniforms(int program, Sampling sampling) {
    glUniform1i(glGetUniformLocation(program, "shadowEnabled"), sampling == null ? 0 : 1);
    glUniform1i(glGetUniformLocation(program, "shadowDepth"), 3);
    if (sampling == null) return;
    glUniformMatrix4fv(
        glGetUniformLocation(program, "shadowMatrix"),
        false,
        sampling.projection.matrix().get(new float[16]));
    glUniform1f(glGetUniformLocation(program, "shadowTexel"), 1f / sampling.resolution);
    glUniform1f(glGetUniformLocation(program, "shadowBias"), sampling.projection.bias());
  }

  public static String fragment() {
    return """
in vec4 lightPosition;
uniform sampler2D shadowDepth;uniform int shadowEnabled;uniform float shadowTexel,shadowBias;
float shadowVisibility(vec3 normal){
  if(shadowEnabled==0)return 1.0;
  vec3 p=lightPosition.xyz/lightPosition.w*.5+.5;
  if(any(lessThan(p,vec3(0)))||any(greaterThan(p,vec3(1))))return 1.0;
  float bias=shadowBias*(1.0+2.0*(1.0-max(dot(normalize(normal),normalize(vec3(.4,1,.3))),0.0)));
  float visible=0.0;
  for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
    vec2 uv=p.xy+vec2(x,y)*shadowTexel;
    visible+=(any(lessThan(uv,vec2(0)))||any(greaterThan(uv,vec2(1)))||p.z-bias<=texture(shadowDepth,uv).r)?1.0:0.0;
  }
  return visible/9.0;
}
""";
  }

  private static int link() {
    String vs =
        "#version 130\n"
            + "in vec3 position;in vec3 chunkOffset;uniform mat4 matrix;uniform float"
            + " positionScale;void"
            + " main(){gl_Position=matrix*vec4(position*positionScale+chunkOffset,1);}";
    int vertex = 0, fragment = 0, result = 0;
    try {
      vertex = shader(GL_VERTEX_SHADER, vs);
      fragment = shader(GL_FRAGMENT_SHADER, "#version 130\nvoid main(){}");
      result = glCreateProgram();
      glAttachShader(result, vertex);
      glAttachShader(result, fragment);
      glBindAttribLocation(result, 0, "position");
      glBindAttribLocation(result, 4, "chunkOffset");
      glLinkProgram(result);
      if (glGetProgrami(result, GL_LINK_STATUS) == 0)
        throw new IllegalStateException("Shadow GLSL link: " + glGetProgramInfoLog(result));
      int success = result;
      result = 0;
      return success;
    } finally {
      if (vertex != 0) glDeleteShader(vertex);
      if (fragment != 0) glDeleteShader(fragment);
      if (result != 0) glDeleteProgram(result);
    }
  }

  private static int shader(int type, String source) {
    int shader = glCreateShader(type);
    glShaderSource(shader, source);
    glCompileShader(shader);
    if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
      String message = glGetShaderInfoLog(shader);
      glDeleteShader(shader);
      throw new IllegalStateException("Shadow GLSL compile: " + message);
    }
    return shader;
  }

  @Override
  public void close() {
    if (texture != 0) glDeleteTextures(texture);
    if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
    if (program != 0) glDeleteProgram(program);
    texture = framebuffer = program = resolution = 0;
  }
}
