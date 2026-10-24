package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;

import java.nio.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

/** All methods belong exclusively to the render thread. CPU work never touches OpenGL. */
public final class GlViewport implements AutoCloseable {
  private int texture, framebuffer, depth, width, height, vao, vbo, program, palette;
  private Mesh mesh = new Mesh(new float[0], false);
  private boolean image = false, volume = false;
  private int volumeTexture, volumeProgram, volumeSize;
  private float displayMin = -1, displayMax = 1, volumeOpacity = .12f, volumeThreshold = 0;
  private int volumeSteps = 192;

  public GlViewport() {
    texture = glGenTextures();
    framebuffer = glGenFramebuffers();
    depth = glGenRenderbuffers();
    vao = glGenVertexArrays();
    vbo = glGenBuffers();
    palette = glGenTextures();
    program = link();
    volumeTexture = glGenTextures();
    volumeProgram = linkVolume();
  }

  public int texture() {
    return texture;
  }

  public void setImage(int w, int h, int[] rgba) {
    image = true;
    volume = false;
    width = height = 0;
    glBindTexture(GL_TEXTURE_2D, texture);
    IntBuffer b = BufferUtils.createIntBuffer(rgba.length);
    b.put(rgba).flip();
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glBindTexture(GL_TEXTURE_2D, 0);
  }

  public void setMesh(Mesh next) {
    image = false;
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
  }

  public void setVolume(VolumeData data) {
    image = false;
    volume = true;
    volumeSize = data.size();
    glBindTexture(GL_TEXTURE_3D, volumeTexture);
    glTexImage3D(
        GL_TEXTURE_3D,
        0,
        GL_R32F,
        volumeSize,
        volumeSize,
        volumeSize,
        0,
        GL_RED,
        GL_FLOAT,
        data.copySamples());
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_3D, 0);
  }

  public void volumeAppearance(
      double min, double max, double opacity, double threshold, int steps) {
    if (!Double.isFinite(min)
        || !Double.isFinite(max)
        || min >= max
        || !Double.isFinite(opacity)
        || opacity < 0
        || !Double.isFinite(threshold)
        || steps < 16
        || steps > 512) throw new IllegalArgumentException("Volume display controls");
    displayMin = (float) Math.max(-1e30, Math.min(1e30, min));
    displayMax = (float) Math.max(-1e30, Math.min(1e30, max));
    if (displayMax <= displayMin) displayMax = Math.nextUp(displayMin);
    volumeOpacity = (float) Math.min(10, opacity);
    volumeThreshold = (float) Math.max(-Float.MAX_VALUE, Math.min(Float.MAX_VALUE, threshold));
    volumeSteps = steps;
  }

  public void setPalette(int[] colors) {
    glBindTexture(GL_TEXTURE_2D, palette);
    IntBuffer buf = BufferUtils.createIntBuffer(colors.length);
    buf.put(colors).flip();
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, colors.length, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glBindTexture(GL_TEXTURE_2D, 0);
  }

  public void render(int w, int h, Matrix4f matrix, boolean unlit, float alpha) {
    if (image) return;
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
    if (volume) {
      glDisable(GL_DEPTH_TEST);
      glDisable(GL_CULL_FACE);
      glDisable(GL_BLEND);
      glUseProgram(volumeProgram);
      glUniformMatrix4fv(
          glGetUniformLocation(volumeProgram, "inverseMatrix"),
          false,
          new Matrix4f(matrix).invert().get(new float[16]));
      glUniform1f(glGetUniformLocation(volumeProgram, "extent"), volumeSize);
      glUniform1f(glGetUniformLocation(volumeProgram, "opacity"), volumeOpacity);
      glUniform1f(glGetUniformLocation(volumeProgram, "threshold"), volumeThreshold);
      glUniform2f(glGetUniformLocation(volumeProgram, "displayRange"), displayMin, displayMax);
      glUniform1i(glGetUniformLocation(volumeProgram, "steps"), volumeSteps);
      glActiveTexture(GL_TEXTURE1);
      glBindTexture(GL_TEXTURE_3D, volumeTexture);
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
            + "in vec3 N;flat in float type;uniform sampler2D palette;uniform float unlit;uniform"
            + " float alpha;out vec4 color;void main(){vec3"
            + " base=texelFetch(palette,ivec2(int(type),0),0).rgb;float"
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
        uniform float extent,opacity,threshold;uniform vec2 displayRange;uniform int steps;
        void main(){
          vec3 background=vec3(.055,.075,.1);vec4 na=inverseMatrix*vec4(screen,-1,1),fa=inverseMatrix*vec4(screen,1,1);
          vec3 origin=na.xyz/na.w,direction=normalize(fa.xyz/fa.w-origin);
          vec3 safeDirection=vec3(abs(direction.x)<1e-7?1e-7:direction.x,abs(direction.y)<1e-7?1e-7:direction.y,abs(direction.z)<1e-7?1e-7:direction.z);
          vec3 t0=-origin/safeDirection,t1=(vec3(extent)-origin)/safeDirection;
          vec3 lo=min(t0,t1),hi=max(t0,t1);float enter=max(0.0,max(lo.x,max(lo.y,lo.z))),exit=min(hi.x,min(hi.y,hi.z));
          if(exit<=enter){color=vec4(background,1);return;}
          float stepLength=(exit-enter)/float(steps);vec3 accumulated=vec3(0);float transmittance=1.0;
          for(int i=0;i<512;i++){
            if(i>=steps||transmittance<.005)break;
            vec3 p=origin+direction*(enter+(float(i)+.5)*stepLength);float raw=texture(density,p/extent).r;
            if(raw>=threshold){float t=clamp((raw-displayRange.x)/(displayRange.y-displayRange.x),0.0,1.0),a=1.0-exp(-t*opacity*stepLength);
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
    glDeleteProgram(program);
    glDeleteProgram(volumeProgram);
    glDeleteTextures(volumeTexture);
    glDeleteBuffers(vbo);
    glDeleteVertexArrays(vao);
    glDeleteTextures(texture);
    glDeleteTextures(palette);
    glDeleteRenderbuffers(depth);
    glDeleteFramebuffers(framebuffer);
  }
}
