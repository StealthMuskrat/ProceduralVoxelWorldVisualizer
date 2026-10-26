package io.voxelcreator.editor;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/**
 * Only user creation paths use metadata. No project reader/compiler/duplicate/import uses this
 * class.
 */
public final class EditorCreations {
  private EditorCreations() {}

  public static Noise noise(AppMetadata metadata, int dimension, String id, UUID parent) {
    metadata.validate();
    return noiseUnchecked(metadata, dimension, id, parent);
  }

  static Noise noiseUnchecked(AppMetadata metadata, int dimension, String id, UUID parent) {
    if (dimension != 2 && dimension != 3) throw new IllegalArgumentException("Noise dimension");
    var noise = new Noise();
    noise.id = id;
    noise.dimension = dimension;
    noise.parentFolderUuid = parent;
    String root = "noise" + dimension + "D";
    noise.seedOffset = CreationRegistry.value(metadata, root + ".seedOffset").longValue();
    noise.algorithm =
        algorithm(
            metadata, dimension, CreationRegistry.value(metadata, root + ".algorithm").textValue());
    return noise;
  }

  private static Algorithm algorithm(AppMetadata metadata, int dimension, String key) {
    var algorithm = new Algorithm();
    algorithm.key = key;
    algorithm.parameters.clear();
    String prefix = "noise" + dimension + "D.algorithm." + key + ".";
    for (var spec : CreationRegistry.CONTROLS.values())
      if (spec.id().startsWith(prefix))
        algorithm.parameters.put(
            spec.id().substring(prefix.length()),
            CreationRegistry.value(metadata, spec.id()).doubleValue());
    return algorithm;
  }

  public static VoxelType voxel(AppMetadata metadata, String id) {
    metadata.validate();
    var type = new VoxelType();
    type.id = id;
    type.color =
        new Color(
            CreationRegistry.value(metadata, "voxel.color.r").intValue(),
            CreationRegistry.value(metadata, "voxel.color.g").intValue(),
            CreationRegistry.value(metadata, "voxel.color.b").intValue());
    return type;
  }

  public static Item processing(AppMetadata metadata, String key, String id) {
    metadata.validate();
    return processingUnchecked(metadata, key, id);
  }

  public static Item processing(AppMetadata metadata, String key, String id, int dimension) {
    metadata.validate();
    var item = processingUnchecked(metadata, key, id);
    item.resultType = ProcessingExtensions.resultType(key, dimension);
    if (key.equals("WARP_SAMPLE")
        && dimension == 2
        && !WarpRegistry.orders(2).contains(item.parameters.get("warpOrder").asText()))
      item.parameters.put(
          "warpOrder",
          ProjectIO.JSON.valueToTree(item.parameters.get("warpOrder").asText().replace("Y", "")));
    return item;
  }

  static Item processingUnchecked(AppMetadata metadata, String key, String id) {
    var item = ProcessingRegistry.create(key, id);
    String prefix = "processing." + key + ".";
    for (var spec : CreationRegistry.CONTROLS.values())
      if (spec.id().startsWith(prefix)) {
        String path = spec.id().substring(prefix.length());
        var value = CreationRegistry.value(metadata, spec.id());
        if (path.startsWith("input.")) item.inputs.get(path.substring(6)).value = value;
        else item.parameters.put(path.substring(10), value);
      }
    if (key.equals("CURVE"))
      item.parameters.put("curve", ProjectIO.JSON.valueToTree(curveUnchecked(metadata)));
    return item;
  }

  public static Item transform(AppMetadata metadata, int dimension, String key, String id) {
    metadata.validate();
    if (dimension != 2 && dimension != 3 || !CreationRegistry.TRANSFORMS.contains(key))
      throw new IllegalArgumentException("Transform signature");
    var item = new Item();
    item.id = id;
    item.kind = key;
    item.resultType = dimension == 2 ? "Coordinates2D" : "Coordinates3D";
    String prefix = "transform" + dimension + "D." + key + ".";
    for (var spec : CreationRegistry.CONTROLS.values())
      if (spec.id().startsWith(prefix))
        item.parameters.put(
            spec.id().substring(prefix.length()), CreationRegistry.value(metadata, spec.id()));
    return item;
  }

  public static Curve curve(AppMetadata metadata) {
    metadata.validate();
    return curveUnchecked(metadata);
  }

  static Curve curveUnchecked(AppMetadata metadata) {
    var curve = new Curve();
    curve.min = CreationRegistry.value(metadata, "curve.min").doubleValue();
    curve.max = CreationRegistry.value(metadata, "curve.max").doubleValue();
    curve.displayMin = CreationRegistry.value(metadata, "curve.displayMin").doubleValue();
    curve.displayMax = CreationRegistry.value(metadata, "curve.displayMax").doubleValue();
    curve.outsideDomain = CreationRegistry.value(metadata, "curve.outsideDomain").textValue();
    var a =
        new Point(curve.min, CreationRegistry.value(metadata, "curve.startValue").doubleValue());
    var b = new Point(curve.max, CreationRegistry.value(metadata, "curve.endValue").doubleValue());
    a.segmentInterpolation =
        b.segmentInterpolation =
            CreationRegistry.value(metadata, "curve.interpolation").textValue();
    curve.points = new ArrayList<>(List.of(a, b));
    return curve;
  }

  static void validateDefaults(AppMetadata metadata) {
    new ScalarCurve(curveUnchecked(metadata));
    if (CreationRegistry.value(metadata, "processing.REMAP.parameter.inputMin").doubleValue()
        == CreationRegistry.value(metadata, "processing.REMAP.parameter.inputMax").doubleValue())
      throw new IllegalArgumentException("Remap default input endpoints must differ");
    if (CreationRegistry.value(metadata, "processing.CLAMP.parameter.min").doubleValue()
        > CreationRegistry.value(metadata, "processing.CLAMP.parameter.max").doubleValue())
      throw new IllegalArgumentException("Clamp default minimum exceeds maximum");
    for (int dimension : new int[] {2, 3})
      for (String key : NoiseAlgorithms.KEYS)
        NoiseAlgorithms.compile(algorithm(metadata, dimension, key), dimension, 0);
    for (var entry : ProcessingExtensions.DETAILS.entrySet()) {
      String prefix = "processing." + entry.getKey() + ".";
      if (metadata.creationDefaults.keySet().stream().noneMatch(key -> key.startsWith(prefix)))
        continue;
      for (int dimension : entry.getValue().dimensions()) {
        var project = new ProjectData();
        var noise = new Noise();
        noise.id = "extension-defaults";
        noise.dimension = dimension;
        noise.algorithm.key = "CONSTANT";
        (dimension == 2 ? project.noise2D : project.noise3D).add(noise);
        var item = processingUnchecked(metadata, entry.getKey(), "extension");
        item.resultType = ProcessingExtensions.resultType(item.kind, dimension);
        noise.processing.add(item);
        if (item.resultType.equals("Scalar")) noise.outputBinding = Binding.earlier(item.uuid);
        new NoiseCompiler(project, 0).compile(noise.uuid);
      }
    }
    for (int dimension : new int[] {2, 3}) {
      var p = new ProjectData();
      var n = new Noise();
      n.id = "warp-defaults";
      n.dimension = dimension;
      n.algorithm.key = "CONSTANT";
      (dimension == 2 ? p.noise2D : p.noise3D).add(n);
      var processing = processingUnchecked(metadata, "WARP_SAMPLE", "warp");
      n.processing.add(processing);
      n.outputBinding = Binding.earlier(processing.uuid);
      if (dimension == 2)
        processing.parameters.put(
            "warpOrder",
            ProjectIO.JSON.valueToTree(
                processing.parameters.get("warpOrder").asText().replace("Y", "")));
      WarpPlan.compile(processing, dimension, 0);
      var transform = new Item();
      transform.kind = "DOMAIN_WARP";
      transform.id = "warp-transform";
      transform.resultType = dimension == 2 ? "Coordinates2D" : "Coordinates3D";
      String prefix = "transform" + dimension + "D.DOMAIN_WARP.";
      for (var spec : CreationRegistry.CONTROLS.values())
        if (spec.id().startsWith(prefix))
          transform.parameters.put(
              spec.id().substring(prefix.length()), CreationRegistry.value(metadata, spec.id()));
      WarpPlan.compile(transform, dimension, 0);
    }
    if (metadata.creationDefaults.containsKey("processing.EXPRESSION.parameter.expression"))
      for (int dimension : new int[] {2, 3}) {
        var project = new ProjectData();
        var noise = new Noise();
        noise.id = "creation-validation";
        noise.dimension = dimension;
        noise.algorithm.key = "CONSTANT";
        noise.algorithm.parameters.clear();
        var expression = processingUnchecked(metadata, "EXPRESSION", "expression");
        noise.processing.add(expression);
        noise.outputBinding = Binding.earlier(expression.uuid);
        (dimension == 2 ? project.noise2D : project.noise3D).add(noise);
        new NoiseCompiler(project, 0).compile(noise.uuid);
      }
  }
}
