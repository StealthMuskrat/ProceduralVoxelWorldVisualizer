package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;

/** Native mesh binding adapter; vertex/element attributes remain properties of each owning VAO. */
final class GlMeshUploadState implements MeshUploadState.Backend {
  public int read(MeshUploadState.Item item) {
    return glGetInteger(item == MeshUploadState.Item.VERTEX_ARRAY
        ? GL_VERTEX_ARRAY_BINDING : GL_ARRAY_BUFFER_BINDING);
  }
  public void write(MeshUploadState.Item item, int value) {
    if (item == MeshUploadState.Item.VERTEX_ARRAY) glBindVertexArray(value);
    else glBindBuffer(GL_ARRAY_BUFFER, value);
  }
}
