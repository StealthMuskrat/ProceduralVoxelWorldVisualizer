package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;

final class GlShadowAllocationState implements ShadowAllocationState.Backend {
  private final GlTextureUploadState texture = new GlTextureUploadState(GL_TEXTURE_2D);
  public int read(TextureUploadState.Item item) { return texture.read(item); }
  public void write(TextureUploadState.Item item, int value) { texture.write(item, value); }
  public int readFramebuffer() { return glGetInteger(GL_READ_FRAMEBUFFER_BINDING); }
  public int drawFramebuffer() { return glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING); }
  public void readFramebuffer(int value) { glBindFramebuffer(GL_READ_FRAMEBUFFER, value); }
  public void drawFramebuffer(int value) { glBindFramebuffer(GL_DRAW_FRAMEBUFFER, value); }
}
