package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;

import java.nio.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

/** All methods belong exclusively to the render thread. CPU work never touches OpenGL. */
public final class GlViewport implements AutoCloseable {
  private int texture, framebuffer, depth, width, height, vao, vbo, program, palette;
  private Mesh mesh = new Mesh(new float[0], false);
  private boolean image = false;

  public GlViewport() {
    texture = glGenTextures();
    framebuffer = glGenFramebuffers();
    depth = glGenRenderbuffers();
    vao = glGenVertexArrays();
    vbo = glGenBuffers();
    palette = glGenTextures();
    program = link();
  }

  public int texture() {
    return texture;
  }

  public void setImage(int w, int h, int[] rgba) {
    image = true;
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
    glDeleteBuffers(vbo);
    glDeleteVertexArrays(vao);
    glDeleteTextures(texture);
    glDeleteTextures(palette);
    glDeleteRenderbuffers(depth);
    glDeleteFramebuffers(framebuffer);
  }
}
