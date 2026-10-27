package io.voxelcreator.editor;

import static org.lwjgl.opengl.GL33.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;

/** Native normal/lighting reference and saved canonical field geometry for independent replay. */
final class IsosurfaceProbe {
  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var project = new ProjectData();
    for (String expression :
        List.of(
            "z - 2.25", "2.25 - z", "z < 3 ? -1.7976931348623157e308 : 1.7976931348623157e308")) {
      var n = new Noise();
      n.id = "surface" + project.noise3D.size();
      n.dimension = 3;
      n.algorithm.key = "CONSTANT";
      var item = ProcessingRegistry.create("EXPRESSION", "field", 3);
      item.parameters.put("expression", ProjectIO.JSON.valueToTree(expression));
      n.processing.add(item);
      n.outputBinding = Binding.earlier(item.uuid);
      project.noise3D.add(n);
    }
    require(NoiseCompiler.validate(project).isEmpty(), "Isosurface canonical fixture");
    var rows = new ArrayList<Map<String, Object>>();
    var compiler = new NoiseCompiler(project, project.project.seed);
    try (var viewport = new GlViewport()) {
      var palette = new int[256];
      Arrays.fill(palette, 0xff0000ff);
      viewport.setPalette(palette);
      var camera = new Camera();
      camera.flying = true;
      camera.position.set(2, 2, 8);
      camera.yaw = 0;
      camera.pitch = 0;
      for (int i = 0; i < 3; i++) {
        var field = compiler.compile(project.noise3D.get(i).uuid);
        var samples = new AtomicInteger();
        var mesh =
            IsosurfaceMesh.build(
                4,
                (x, y, z) -> {
                  samples.incrementAndGet();
                  return field.sample(x, y, z);
                },
                0,
                new CancellationToken(),
                IsosurfaceMesh.maximumMeshBytes(4));
        require(samples.get() == 125 && mesh.vertexCount() > 0, "Isosurface sampling/publication");
        double nz = i == 1 ? 1 : -1;
        viewport.setImage(2, 2, new int[4]);
        viewport.setMesh(mesh);
        viewport.render(65, 65, camera.matrix(1, new Vector3d()), false, 1);
        var pixels = BufferUtils.createByteBuffer(65 * 65 * 4);
        glBindTexture(GL_TEXTURE_2D, viewport.texture());
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        var observed = new ArrayList<List<Integer>>();
        double light = .35 + .65 * Math.max(0, nz * .3 / Math.sqrt(1.25));
        for (int y : new int[] {24, 28, 32, 36, 40})
          for (int x : new int[] {24, 28, 32, 36, 40}) {
            int at = (y * 65 + x) * 4;
            var rgba = new ArrayList<Integer>();
            for (int c = 0; c < 4; c++) rgba.add(Byte.toUnsignedInt(pixels.get(at + c)));
            require(
                Math.abs(rgba.get(0) - 255 * light) <= 2
                    && rgba.get(1) == 0
                    && rgba.get(2) == 0
                    && rgba.get(3) == 255,
                "Isosurface lighting "
                    + i
                    + " at "
                    + x
                    + ","
                    + y
                    + ": "
                    + rgba
                    + " expected "
                    + 255 * light);
            observed.add(rgba);
          }
        var row = geometry(i == 0 ? "positive" : i == 1 ? "negative" : "extreme", mesh);
        row.put("normalZ", nz);
        row.put("samples", samples.get());
        row.put("pixels", observed);
        rows.add(row);
      }
    }
    MeshBuilder.Scalar f = (x, y, z) -> y + .37 * z + .23 * x - .8;
    var left = geometry("left", MeshBuilder.isosurface(2, (x, y, z) -> f.sample(x - 2, y, z), 0));
    left.put("originX", -2);
    rows.add(left);
    var right = geometry("right", MeshBuilder.isosurface(2, f, 0));
    right.put("originX", 0);
    rows.add(right);
    rows.add(
        geometry(
            "sphere",
            MeshBuilder.isosurface(
                4,
                (x, y, z) -> 1.69 - (x - 2) * (x - 2) - (y - 2) * (y - 2) - (z - 2) * (z - 2),
                0)));
    require(glGetError() == GL_NO_ERROR, "Isosurface OpenGL error");
    new ProjectIO().save(out.resolve("isosurface-project.json"), project);
    Files.writeString(
        out.resolve("isosurface-native.json"),
        ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "meshes", rows)));
    System.out.println(
        "ISOSURFACE_NATIVE_PASS canonical positive-complement-extreme fields / 75 analytical"
            + " lighting pixels / image-mesh transitions / shared face and closed sphere replay");
  }

  private static Map<String, Object> geometry(String name, Mesh mesh) {
    var row = new LinkedHashMap<String, Object>();
    row.put("name", name);
    row.put("vertices", mesh.vertices());
    row.put("bytes", mesh.bytes());
    return row;
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
