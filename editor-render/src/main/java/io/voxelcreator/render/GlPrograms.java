package io.voxelcreator.render;

import static org.lwjgl.opengl.GL20.*;

import java.util.Objects;

/** Own every shader/program until a fully linked program transfers to its render-thread caller. */
public final class GlPrograms {
  private GlPrograms() {}

  interface Driver {
    int createShader(int kind);

    void compile(int shader, String source);

    boolean compiled(int shader);

    String shaderLog(int shader);

    int createProgram();

    void attach(int program, int shader);

    void attribute(int program, int index, String name);

    void link(int program);

    boolean linked(int program);

    String programLog(int program);

    void deleteShader(int shader);

    void deleteProgram(int program);
  }

  private static final Driver NATIVE =
      new Driver() {
        public int createShader(int kind) {
          return glCreateShader(kind);
        }

        public void compile(int shader, String source) {
          glShaderSource(shader, source);
          glCompileShader(shader);
        }

        public boolean compiled(int shader) {
          return glGetShaderi(shader, GL_COMPILE_STATUS) != 0;
        }

        public String shaderLog(int shader) {
          return glGetShaderInfoLog(shader);
        }

        public int createProgram() {
          return glCreateProgram();
        }

        public void attach(int program, int shader) {
          glAttachShader(program, shader);
        }

        public void attribute(int program, int index, String name) {
          glBindAttribLocation(program, index, name);
        }

        public void link(int program) {
          glLinkProgram(program);
        }

        public boolean linked(int program) {
          return glGetProgrami(program, GL_LINK_STATUS) != 0;
        }

        public String programLog(int program) {
          return glGetProgramInfoLog(program);
        }

        public void deleteShader(int shader) {
          glDeleteShader(shader);
        }

        public void deleteProgram(int program) {
          glDeleteProgram(program);
        }
      };

  public static int link(String label, String vertex, String fragment, String... attributes) {
    return link(NATIVE, label, vertex, fragment, attributes);
  }

  static int link(
      Driver driver, String label, String vertex, String fragment, String... attributes) {
    Objects.requireNonNull(driver);
    Objects.requireNonNull(label);
    Objects.requireNonNull(vertex);
    Objects.requireNonNull(fragment);
    var names = attributes.clone();
    int vs = 0, fs = 0, program = 0;
    Throwable failure = null;
    try {
      vs = driver.createShader(GL_VERTEX_SHADER);
      if (vs == 0) throw new IllegalStateException(label + ": vertex shader allocation failed");
      driver.compile(vs, vertex);
      if (!driver.compiled(vs)) throw rejected(label, "vertex compile", driver.shaderLog(vs));
      fs = driver.createShader(GL_FRAGMENT_SHADER);
      if (fs == 0) throw new IllegalStateException(label + ": fragment shader allocation failed");
      driver.compile(fs, fragment);
      if (!driver.compiled(fs)) throw rejected(label, "fragment compile", driver.shaderLog(fs));
      program = driver.createProgram();
      if (program == 0) throw new IllegalStateException(label + ": program allocation failed");
      driver.attach(program, vs);
      driver.attach(program, fs);
      for (int i = 0; i < names.length; i++)
        if (names[i] != null) driver.attribute(program, i, names[i]);
      driver.link(program);
      if (!driver.linked(program)) throw rejected(label, "link", driver.programLog(program));
      driver.deleteShader(vs);
      vs = 0;
      driver.deleteShader(fs);
      fs = 0;
      int accepted = program;
      program = 0;
      return accepted;
    } catch (RuntimeException | Error rejected) {
      failure = rejected;
      throw rejected;
    } finally {
      int v = vs, f = fs, p = program;
      cleanup(
          failure,
          () -> {
            if (v != 0) driver.deleteShader(v);
          },
          () -> {
            if (f != 0) driver.deleteShader(f);
          },
          () -> {
            if (p != 0) driver.deleteProgram(p);
          });
    }
  }

  private static IllegalStateException rejected(String label, String stage, String log) {
    String detail =
        label + " GLSL " + stage + ": " + Objects.requireNonNullElse(log, "No driver diagnostic");
    if (detail.length() > 8192) {
      int end = 8191;
      if (Character.isHighSurrogate(detail.charAt(end - 1))) end--;
      detail = detail.substring(0, end) + "…";
    }
    return new IllegalStateException(detail);
  }

  private static void cleanup(Throwable primary, Runnable... releases) {
    Throwable failure = primary;
    for (var release : releases)
      try {
        release.run();
      } catch (RuntimeException | Error cleanup) {
        if (failure == null) failure = cleanup;
        else if (failure != cleanup) failure.addSuppressed(cleanup);
      }
    if (primary == null && failure != null) {
      if (failure instanceof RuntimeException runtime) throw runtime;
      throw (Error) failure;
    }
  }
}
