package io.voxelcreator.editor;

import static org.lwjgl.opengl.GL33.*;

import io.voxelcreator.render.*;
import java.util.Arrays;
import org.lwjgl.BufferUtils;

/** Deferred native fixed-upload sentinels, followed by CameraGeometryProbe's raster expectations. */
final class FixedUploadProbe {
  private static final int[] STORES = {GL_UNPACK_ALIGNMENT, GL_UNPACK_SWAP_BYTES,
    GL_UNPACK_ROW_LENGTH, GL_UNPACK_IMAGE_HEIGHT, GL_UNPACK_SKIP_PIXELS,
    GL_UNPACK_SKIP_ROWS, GL_UNPACK_SKIP_IMAGES};
  private static final int[] HOSTILE = {8, 1, 11, 13, 2, 3, 1};

  static void run(GlViewport view, Mesh mesh) {
    view.setVolume(new VolumeData(2, new float[] {1, 2, 3, 4, 5, 6, 7, 8}));
    var resident = view.volumeResident();
    int active = glGetInteger(GL_ACTIVE_TEXTURE), array = glGetInteger(GL_ARRAY_BUFFER_BINDING),
        vao = glGetInteger(GL_VERTEX_ARRAY_BINDING), unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
    int[] stores = Arrays.stream(STORES).map(value -> glGetInteger(value)).toArray();
    glActiveTexture(GL_TEXTURE2);
    int binding = glGetInteger(GL_TEXTURE_BINDING_2D);
    int texture = glGenTextures(), pbo = glGenBuffers(), foreignVao = glGenVertexArrays(),
        vbo = glGenBuffers(), elements = glGenBuffers();
    int[] rgba = {0xff0000ff, 0xff00ff00, 0xffff0000, 0x7f010203, 0x00112233, 0x809abcde};
    try {
      glBindTexture(GL_TEXTURE_2D, texture);
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
      glBufferData(GL_PIXEL_UNPACK_BUFFER, 64, GL_STATIC_DRAW);
      for (int i = 0; i < STORES.length; i++) glPixelStorei(STORES[i], HOSTILE[i]);
      glBindVertexArray(foreignVao);
      glBindBuffer(GL_ARRAY_BUFFER, vbo);
      glBufferData(GL_ARRAY_BUFFER, new float[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, GL_STATIC_DRAW);
      glEnableVertexAttribArray(0);
      glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
      glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elements);
      glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[] {0, 1, 2}, GL_STATIC_DRAW);
      require(glGetError() == GL_NO_ERROR, "Fixed upload sentinel setup");
      glEnable(-1);
      boolean rejected = false;
      try { view.setImage(3, 2, rgba); }
      catch (IllegalStateException failure) {
        require(failure.getMessage().startsWith("Image upload GL error "), "Unexpected image failure");
        rejected = true;
      }
      require(rejected && view.volumeResident() == resident, "Failed image retired prior volume");
      state(texture, pbo, foreignVao, vbo, elements);
      require(glGetError() == GL_NO_ERROR, "Image completion error not consumed");
      view.setImage(3, 2, rgba);
      require(view.volumeResident() == null, "Healthy image retained hidden volume");
      state(texture, pbo, foreignVao, vbo, elements);
      var pixels = BufferUtils.createIntBuffer(6);
      glBindTexture(GL_TEXTURE_2D, view.texture());
      try {
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        int[] actual = new int[6]; pixels.get(actual);
        require(Arrays.equals(rgba, actual), "Fixed image pixel words differ");
      } finally { glBindTexture(GL_TEXTURE_2D, texture); }
      glEnable(-1);
      rejected = false;
      try { view.setMesh(mesh); }
      catch (IllegalStateException failure) {
        require(failure.getMessage().startsWith("Mesh upload GL error "), "Unexpected mesh failure");
        rejected = true;
      }
      require(rejected, "Failed mesh accepted");
      state(texture, pbo, foreignVao, vbo, elements);
      require(glGetError() == GL_NO_ERROR, "Mesh completion error not consumed");
      view.setMesh(mesh);
      state(texture, pbo, foreignVao, vbo, elements);
      require(glGetError() == GL_NO_ERROR, "Fixed upload recovery GL error");
    } finally {
      glBindVertexArray(vao);
      glBindBuffer(GL_ARRAY_BUFFER, array);
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
      for (int i = 0; i < STORES.length; i++) glPixelStorei(STORES[i], stores[i]);
      glActiveTexture(GL_TEXTURE2);
      glBindTexture(GL_TEXTURE_2D, binding);
      glActiveTexture(active);
      glDeleteVertexArrays(foreignVao);
      glDeleteBuffers(vbo); glDeleteBuffers(elements); glDeleteBuffers(pbo); glDeleteTextures(texture);
    }
  }

  private static void state(int texture, int pbo, int vao, int buffer, int elements) {
    require(glGetInteger(GL_ACTIVE_TEXTURE) == GL_TEXTURE2
            && glGetInteger(GL_TEXTURE_BINDING_2D) == texture
            && glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING) == pbo,
        "Fixed upload texture/unpack bindings changed");
    for (int i = 0; i < STORES.length; i++)
      require(glGetInteger(STORES[i]) == HOSTILE[i], "Fixed upload unpack value changed");
    require(glGetInteger(GL_VERTEX_ARRAY_BINDING) == vao
            && glGetInteger(GL_ARRAY_BUFFER_BINDING) == buffer
            && glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING) == elements
            && glGetVertexAttribi(0, GL_VERTEX_ATTRIB_ARRAY_ENABLED) == GL_TRUE
            && glGetVertexAttribi(0, GL_VERTEX_ATTRIB_ARRAY_SIZE) == 3
            && glGetVertexAttribi(0, GL_VERTEX_ATTRIB_ARRAY_STRIDE) == 12
            && glGetVertexAttribi(0, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING) == buffer,
        "Fixed upload caller VAO/buffer/attribute changed");
  }
  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
