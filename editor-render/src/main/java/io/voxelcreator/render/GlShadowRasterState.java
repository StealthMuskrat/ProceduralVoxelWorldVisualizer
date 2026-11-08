package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;

final class GlShadowRasterState implements ShadowRasterState.Backend {
  public int binding(ShadowRasterState.Binding item) { return glGetInteger(switch (item) {
    case READ_FRAMEBUFFER -> GL_READ_FRAMEBUFFER_BINDING;
    case DRAW_FRAMEBUFFER -> GL_DRAW_FRAMEBUFFER_BINDING;
    case PROGRAM -> GL_CURRENT_PROGRAM;
    case VERTEX_ARRAY -> GL_VERTEX_ARRAY_BINDING;
    case DEPTH_FUNCTION -> GL_DEPTH_FUNC;
  }); }
  public void binding(ShadowRasterState.Binding item, int value) {
    switch (item) {
      case READ_FRAMEBUFFER -> glBindFramebuffer(GL_READ_FRAMEBUFFER, value);
      case DRAW_FRAMEBUFFER -> glBindFramebuffer(GL_DRAW_FRAMEBUFFER, value);
      case PROGRAM -> glUseProgram(value);
      case VERTEX_ARRAY -> glBindVertexArray(value);
      case DEPTH_FUNCTION -> glDepthFunc(value);
    }
  }
  private static int capability(ShadowRasterState.Flag item) { return switch (item) {
    case DEPTH_TEST -> GL_DEPTH_TEST;
    case CULL_FACE -> GL_CULL_FACE;
    case SCISSOR_TEST -> GL_SCISSOR_TEST;
    case POLYGON_OFFSET_FILL -> GL_POLYGON_OFFSET_FILL;
    case RASTERIZER_DISCARD -> GL_RASTERIZER_DISCARD;
    case DEPTH_MASK -> throw new IllegalArgumentException("Depth mask is not a capability");
  }; }
  public boolean flag(ShadowRasterState.Flag item) {
    return item == ShadowRasterState.Flag.DEPTH_MASK ? glGetBoolean(GL_DEPTH_WRITEMASK) : glIsEnabled(capability(item));
  }
  public void flag(ShadowRasterState.Flag item, boolean value) {
    if (item == ShadowRasterState.Flag.DEPTH_MASK) glDepthMask(value);
    else if (value) glEnable(capability(item)); else glDisable(capability(item));
  }
  public int[] viewport() { var value = new int[4]; glGetIntegerv(GL_VIEWPORT, value); return value; }
  public void viewport(int[] value) { glViewport(value[0], value[1], value[2], value[3]); }
  public float[] attribute() { var value = new float[4]; glGetVertexAttribfv(4, GL_CURRENT_VERTEX_ATTRIB, value); return value; }
  public void attribute(float[] value) { glVertexAttrib4f(4, value[0], value[1], value[2], value[3]); }
  public double clearDepth() { return glGetDouble(GL_DEPTH_CLEAR_VALUE); }
  public void clearDepth(double value) { glClearDepth(value); }
  public double[] depthRange() { var value = new double[2]; glGetDoublev(GL_DEPTH_RANGE, value); return value; }
  public void depthRange(double[] value) { glDepthRange(value[0], value[1]); }
  public void clear() { glClear(GL_DEPTH_BUFFER_BIT); }
}
