package io.voxelcreator.render;

public record Mesh(float[] vertices, boolean points) {
  public int vertexCount() {
    return vertices.length / 7;
  }

  public long bytes() {
    return vertices.length * 4L;
  }
}
