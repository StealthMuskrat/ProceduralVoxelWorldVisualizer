package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;

/** Fixed dispatch; numeric collisions across GL namespaces remain independent resources. */
final class GlHandleDeletion {
  private GlHandleDeletion() {}

  static void delete(RenderRetirement.Handle handle) {
    switch (handle.kind()) {
      case PROGRAM -> glDeleteProgram(handle.id());
      case FRAMEBUFFER -> glDeleteFramebuffers(handle.id());
      case BUFFER -> glDeleteBuffers(handle.id());
      case VERTEX_ARRAY -> glDeleteVertexArrays(handle.id());
      case TEXTURE -> glDeleteTextures(handle.id());
      case RENDERBUFFER -> glDeleteRenderbuffers(handle.id());
    }
  }
}
