package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;

import java.nio.ByteBuffer;
import java.util.Objects;
import org.joml.Matrix4f;

/** One render-thread-owned depth texture. Optional shadows never require a newer GL context. */
public final class GlShadowMap implements AutoCloseable {
  public interface Draw {
    int render(int program, Matrix4f matrix);
  }

  public record Sampling(int texture, int resolution, DirectionalShadow.Projection projection) {}

  private final ShadowMapWork work = new ShadowMapWork(GlHandleDeletion::delete);
  private final ShadowResources resources = work.resources;

  public int texture() {
    return resources.texture;
  }

  public int framebuffer() {
    return resources.framebuffer;
  }

  public long bytes() {
    return DirectionalShadow.bytes(resources.resolution);
  }

  public int resolution() {
    return resources.resolution;
  }

  public void allocate(int size) {
    work.allocate(size, () -> glGetInteger(GL_MAX_TEXTURE_SIZE),
        () -> ShadowAllocationState.capture(new GlShadowAllocationState()), ALLOCATION);
  }

  private static final ShadowMapWork.Allocation ALLOCATION = new ShadowMapWork.Allocation() {
    public int texture() { return glGenTextures(); }
    public int framebuffer() { return glGenFramebuffers(); }
    public void storage(int texture, int framebuffer, int size) {
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
    }
    public int program() { return link(); }
    public void complete() {
      if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("Shadow allocation failed");
    }
  };

  /** Restore every raster state changed by the depth pass, including split read/draw FBOs. */
  public int render(DirectionalShadow.Projection projection, Draw drawCommands) {
    work.requireIdle();
    Objects.requireNonNull(projection);
    Objects.requireNonNull(drawCommands);
    return work.render(() -> ShadowRasterState.capture(new GlShadowRasterState()),
        program -> drawCommands.render(program, projection.matrix()), () -> {
      if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("Shadow raster failed");
    });
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
    return GlPrograms.link(
        "Directional shadows",
        vs,
        "#version 130\nvoid main(){}",
        "position",
        null,
        null,
        null,
        "chunkOffset");
  }

  @Override
  public void close() {
    work.close();
  }
}
