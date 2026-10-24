package io.voxelcreator.editor;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;

/** Explicit isolated creation/profile round trip. The resulting project is consumed headlessly. */
public final class CreationProfileProbe {
  public static AppMetadata profile() {
    var metadata = AppMetadata.defaults();
    set(metadata, "noise2D.algorithm", "CONSTANT");
    set(metadata, "noise3D.algorithm", "CONSTANT");
    set(metadata, "noise2D.algorithm.CONSTANT.value", .625);
    set(metadata, "noise3D.algorithm.CONSTANT.value", .25);
    set(metadata, "processing.MULTIPLY.input.b", 4);
    set(metadata, "processing.ADD.input.b", .75);
    set(metadata, "transform2D.TRANSLATE.z", 3);
    set(metadata, "voxel.color.r", 12);
    set(metadata, "voxel.color.g", 34);
    set(metadata, "voxel.color.b", 56);
    set(metadata, "curve.startValue", 2);
    set(metadata, "curve.endValue", 6);
    metadata.controls.get("algorithm.CONSTANT").label = "Flat field";
    metadata.controls.get("noise2D.algorithm").options.put("CONSTANT", "Flat preset");
    return metadata;
  }

  private static void set(AppMetadata metadata, String id, Object value) {
    metadata.creationDefaults.put(id, ProjectIO.JSON.valueToTree(value));
  }

  public static ProjectData created(AppMetadata metadata) {
    var project = new ProjectData();
    var multiply = EditorCreations.noise(metadata, 2, "created-multiply", null);
    var times = EditorCreations.processing(metadata, "MULTIPLY", "times");
    multiply.processing.add(times);
    multiply.outputBinding = Binding.earlier(times.uuid);
    project.noise2D.add(multiply);
    var add = EditorCreations.noise(metadata, 3, "created-add", null);
    var plus = EditorCreations.processing(metadata, "ADD", "plus");
    add.processing.add(plus);
    add.outputBinding = Binding.earlier(plus.uuid);
    project.noise3D.add(add);
    var curved = EditorCreations.noise(metadata, 2, "created-curve", null);
    var curve = EditorCreations.processing(metadata, "CURVE", "curve");
    curved.processing.add(curve);
    curved.outputBinding = Binding.earlier(curve.uuid);
    project.noise2D.add(curved);
    var gradient = EditorCreations.noise(metadata, 2, "created-gradient", null);
    gradient.algorithm.key = "GRADIENT_Y";
    gradient.algorithm.parameters.clear();
    gradient.coordinateTransforms.add(EditorCreations.transform(metadata, 2, "TRANSLATE", "shift"));
    project.noise2D.add(gradient);
    project.voxelTypes.add(EditorCreations.voxel(metadata, "created-type"));
    return project;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Pass the isolated profile probe output directory");
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toAbsolutePath().toString());
    try (var state = new EditorState(new UserConfig(), false)) {
      state.metadata = AppMetadata.defaults();
      var existing = new Noise();
      existing.id = "existing";
      existing.algorithm.key = "CONSTANT";
      existing.algorithm.parameters.clear();
      existing.algorithm.parameters.put("value", .125);
      state.change(p -> p.noise2D.add(existing));
      state.select("noise2D", existing.uuid);
      String before = ProjectIO.JSON.valueToTree(state.project()).toString();
      long revision = state.revision, history = state.document.historyBytes();
      Object draft = state.draft;
      boolean dirty = state.document.dirty();
      var session = new MetadataEditSession(state.metadata);
      var configured = profile();
      session.draft().controls = configured.controls;
      session.draft().creationDefaults = configured.creationDefaults;
      state.metadata = session.apply(state.metadata, output.resolve("app-config.json"));
      var reloaded = AppMetadata.read(output.resolve("app-config.json"));
      if (!reloaded.label("algorithm.CONSTANT", "").equals("Flat field")
          || !reloaded.option("noise2D.algorithm", "CONSTANT").equals("Flat preset"))
        throw new AssertionError("Presentation aliases did not survive restart");
      var project = created(reloaded);
      new ProjectIO().save(output.resolve("created-project.pvproj.json"), project);
      set(reloaded, "noise2D.algorithm.CONSTANT.value", .9);
      var compiled = new CompiledProject(project);
      if (compiled.noise2D("created-multiply", 42).sample(1, 2) != 2.5
          || compiled.noise3D("created-add", 42).sample(1, 2, 3) != 1
          || compiled.noise2D("created-curve", 42).sample(1, 2) != 4.5
          || compiled.noise2D("created-gradient", 42).sample(1, 2) != 5)
        throw new AssertionError("Concrete creation values changed after metadata edit");
      if (!before.equals(ProjectIO.JSON.valueToTree(state.project()).toString())
          || revision != state.revision
          || history != state.document.historyBytes()
          || dirty != state.document.dirty()
          || draft != state.draft)
        throw new AssertionError("Metadata apply changed existing project state");
      if (new CompiledProject(state.project()).noise2D("existing", 7).sample(6, 8) != .125)
        throw new AssertionError("Metadata changed existing evaluation");
      System.out.println(
          "CREATION_PROFILE_REFERENCE_PASS validated defaults / concrete values / fresh identities"
              + " / presentation aliases / atomic apply / restart / existing project and evaluation"
              + " invariance");
    }
  }
}
