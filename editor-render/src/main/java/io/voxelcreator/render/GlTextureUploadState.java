package io.voxelcreator.render;

import static org.lwjgl.opengl.GL33.*;

/** Native unpack adapter for the selected 2D/3D target on the captured active texture unit. */
final class GlTextureUploadState implements TextureUploadState.Backend {
  private final int target, binding;
  GlTextureUploadState(int target) {
    if (target != GL_TEXTURE_2D && target != GL_TEXTURE_3D)
      throw new IllegalArgumentException("Host texture target");
    this.target = target;
    binding = target == GL_TEXTURE_2D ? GL_TEXTURE_BINDING_2D : GL_TEXTURE_BINDING_3D;
  }
  public int read(TextureUploadState.Item item) {
    return glGetInteger(switch (item) {
      case ACTIVE_TEXTURE -> GL_ACTIVE_TEXTURE;
      case BINDING -> binding;
      case PIXEL_UNPACK_BUFFER -> GL_PIXEL_UNPACK_BUFFER_BINDING;
      case ALIGNMENT -> GL_UNPACK_ALIGNMENT;
      case SWAP_BYTES -> GL_UNPACK_SWAP_BYTES;
      case ROW_LENGTH -> GL_UNPACK_ROW_LENGTH;
      case IMAGE_HEIGHT -> GL_UNPACK_IMAGE_HEIGHT;
      case SKIP_PIXELS -> GL_UNPACK_SKIP_PIXELS;
      case SKIP_ROWS -> GL_UNPACK_SKIP_ROWS;
      case SKIP_IMAGES -> GL_UNPACK_SKIP_IMAGES;
    });
  }
  public void write(TextureUploadState.Item item, int value) {
    switch (item) {
      case ACTIVE_TEXTURE -> glActiveTexture(value);
      case BINDING -> glBindTexture(target, value);
      case PIXEL_UNPACK_BUFFER -> glBindBuffer(GL_PIXEL_UNPACK_BUFFER, value);
      case ALIGNMENT -> glPixelStorei(GL_UNPACK_ALIGNMENT, value);
      case SWAP_BYTES -> glPixelStorei(GL_UNPACK_SWAP_BYTES, value);
      case ROW_LENGTH -> glPixelStorei(GL_UNPACK_ROW_LENGTH, value);
      case IMAGE_HEIGHT -> glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, value);
      case SKIP_PIXELS -> glPixelStorei(GL_UNPACK_SKIP_PIXELS, value);
      case SKIP_ROWS -> glPixelStorei(GL_UNPACK_SKIP_ROWS, value);
      case SKIP_IMAGES -> glPixelStorei(GL_UNPACK_SKIP_IMAGES, value);
    }
  }
}
