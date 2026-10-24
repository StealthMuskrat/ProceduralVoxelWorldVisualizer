package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Independent consumer importing only the published headless engine artifact. */
public final class Example {
  public static ProjectData demo() {
    var p = new ProjectData();
    p.project.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000001");
    p.worldGenerator.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000002");
    var t = new VoxelType();
    t.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000003");
    t.id = "stone";
    p.voxelTypes.add(t);
    var n = new Noise();
    n.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000004");
    n.id = "terrain";
    n.algorithm.parameters.put("frequency", .03);
    p.noise2D.add(n);
    var volume = new Noise();
    volume.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000005");
    volume.id = "density";
    volume.dimension = 3;
    p.noise3D.add(volume);
    var layer = new Layer();
    layer.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000006");
    layer.id = "terrain";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = t.uuid;
    layer.defaults.baseHeight = 16;
    layer.defaults.heightScale = 12;
    var op = new Operation();
    op.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000007");
    op.id = "surface";
    op.noiseGeneratorUuid = n.uuid;
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    return p;
  }

  public static ProjectData sampledFieldsDemo() {
    var p = new ProjectData();
    p.project.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000020");
    p.worldGenerator.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000021");
    var n = new Noise();
    n.id = "sampled-derivative";
    n.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000022");
    n.algorithm.key = "CONSTANT";
    var translate = new Item();
    translate.id = "translate";
    translate.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000023");
    translate.kind = "TRANSLATE";
    translate.resultType = "Coordinates2D";
    translate.inputs.put("x", Binding.constant(3));
    n.coordinateTransforms.add(translate);
    var quadratic = ProcessingRegistry.create("EXPRESSION", "quadratic");
    quadratic.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000024");
    quadratic.parameters.put("expression", ProjectIO.JSON.valueToTree("x*x+z*z"));
    n.processing.add(quadratic);
    var derivative = ProcessingRegistry.create("DERIVATIVE", "derivative");
    derivative.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000025");
    derivative.inputs.put("field", Binding.earlierField(quadratic.uuid));
    derivative.parameters.put("spacing", ProjectIO.JSON.valueToTree(.25));
    n.processing.add(derivative);
    n.outputBinding = Binding.earlier(derivative.uuid);
    p.noise2D.add(n);
    return p;
  }

  public static ProjectData worldUseDemo() {
    var p = demo();
    p.project.minY = -8;
    p.project.maxY = 8;
    var a = p.voxelTypes.getFirst();
    var b = new VoxelType();
    b.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000041");
    b.id = "clay";
    b.color = new Color(184, 130, 89);
    p.voxelTypes.add(b);
    var n = p.noise2D.getFirst();
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    var expression = ProcessingRegistry.create("EXPRESSION", "coordinate");
    expression.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000042");
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x"));
    n.processing.add(expression);
    n.outputBinding = Binding.earlier(expression.uuid);
    var s = p.worldGenerator.layers.getFirst().defaults;
    s.axisX = "Y";
    s.typeSource = "INTERVALS";
    s.voxelTypeUuid = null;
    var translate = new SamplingTransform();
    translate.x = 2;
    s.samplingTransforms.add(translate);
    var left = new TypeInterval();
    left.min = -100;
    left.max = 0;
    left.voxelTypeUuid = a.uuid;
    var right = new TypeInterval();
    right.min = 0;
    right.max = 100;
    right.includeMax = true;
    right.voxelTypeUuid = b.uuid;
    s.typeIntervals.addAll(java.util.List.of(left, right));
    s.probabilityDriver.enabled = true;
    s.probabilityDriver.inputMin = -100;
    s.probabilityDriver.inputMax = 100;
    s.probabilityDriver.outputMin = 1;
    s.probabilityDriver.outputMax = 1;
    s.fieldGates.kind = "NOT";
    var never = new Gate();
    never.kind = "PROBABILITY";
    never.probability = 0;
    s.fieldGates.children.add(never);
    p.worldGenerator.layers.getFirst().operations.getFirst().interpretation = "TYPE_SELECTION";
    return p;
  }

  public static ProjectData parameterDriversDemo() {
    var p = demo();
    p.project.minY = -8;
    p.project.maxY = 8;
    var n = p.noise2D.getFirst();
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", .5);
    var source = new Noise();
    source.id = "height-control";
    source.dimension = 3;
    source.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000061");
    source.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "coordinate");
    expression.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000062");
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x"));
    source.processing.add(expression);
    source.outputBinding = Binding.earlier(expression.uuid);
    p.noise3D.add(source);
    var s = p.worldGenerator.layers.getFirst().defaults;
    s.baseHeight = 0;
    s.heightScale = 0;
    var d = new NumericDriver();
    d.enabled = true;
    d.destination = "baseHeight";
    d.noiseGeneratorUuid = source.uuid;
    d.inputMin = -4;
    d.inputMax = 4;
    d.outputMin = -4;
    d.outputMax = 4;
    s.parameterDrivers.add(d);
    var add = new NumericDriver();
    add.enabled = true;
    add.destination = "baseHeight";
    add.combination = "ADD";
    add.outputMin = 1;
    add.outputMax = 1;
    s.parameterDrivers.add(add);
    return p;
  }

  public static ProjectData compiledExpressionsDemo() {
    var p = sampledFieldsDemo();
    var n = p.noise2D.getFirst();
    n.id = "compiled-expression";
    n.processing.clear();
    n.coordinateTransforms.clear();
    var parameter = new Parameter();
    parameter.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000051");
    parameter.id = "bias";
    parameter.type = "Scalar";
    parameter.value = ProjectIO.JSON.valueToTree(3);
    n.sharedParameters.add(parameter);
    var expression = ProcessingRegistry.create("EXPRESSION", "optimized");
    expression.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000052");
    expression.parameters.put(
        "expression", ProjectIO.JSON.valueToTree("(x+z)*(x+z)+bias+(true ? 2^3 : 1/0)"));
    var binding = Binding.constant(0);
    binding.sourceKind = "Shared Parameter";
    binding.parameterUuid = parameter.uuid;
    expression.inputs.put("bias", binding);
    n.processing.add(expression);
    n.outputBinding = Binding.earlier(expression.uuid);
    return p;
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--contracts")) {
      ContractGenerator.write(Path.of(args.length > 1 ? args[1] : "."));
      return;
    }
    Path working = Path.of(args.length > 0 ? args[0] : "example-output");
    Files.createDirectories(working);
    var sampled = sampledFieldsDemo();
    Path fieldFile = working.resolve("sampled-fields.pvproj.json");
    new ProjectIO().save(fieldFile, sampled);
    var fieldLoad = new ProjectLoader().load(fieldFile);
    if (!fieldLoad.success()) throw new IllegalStateException(fieldLoad.diagnostics().toString());
    var sampledProject = new ProjectCompiler().compileWorld(fieldLoad.project()).compiled();
    var derivative = sampledProject.noise2D("sampled-derivative", 42);
    for (int x = -33; x <= 33; x++)
      if (derivative.sample(x, 31) != 2. * (x + 3))
        throw new AssertionError("Field-prefix derivative mismatch at " + x);
    System.out.println("Independent sampled-field prefix PASS");
    var authoring = worldUseDemo();
    Path authoringFile = working.resolve("world-use.pvproj.json");
    new ProjectIO().save(authoringFile, authoring);
    var authoringLoad = new ProjectLoader().load(authoringFile);
    if (!authoringLoad.success())
      throw new IllegalStateException(authoringLoad.diagnostics().toString());
    try (var authoringEngine = new NoiseGeneratorEngine();
        var authoringWorld =
            authoringEngine.openWorld(
                new CompiledProject(authoringLoad.project()),
                new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      int stone = authoringWorld.registry().runtimeId(authoring.voxelTypes.getFirst().uuid),
          clay = authoringWorld.registry().runtimeId(authoring.voxelTypes.getLast().uuid);
      for (int y = -7; y <= 7; y++)
        if (authoringWorld.getVoxelTypeId(new BlockPos(-1, y, 3)) != (y + 2 < 0 ? stone : clay))
          throw new AssertionError("World-use type/axis/column mismatch at " + y);
      var details = authoringWorld.trace(new BlockPos(-1, -2, 3)).steps().getFirst().details();
      if (details.matchedInterval() != 1
          || details.drivers().size() != 1
          || details.sampleCoordinates().x() != 0)
        throw new AssertionError("World-use trace mismatch");
      try (var collected =
          authoringWorld.collectRegion(
              RegionRequest.of(new RegionBounds(new BlockPos(-1, -4, 0), new BlockPos(0, 3, 3))))) {
        if (collected.chunks().size() != 4) throw new AssertionError("Owned collection");
      }
    }
    System.out.println(
        "Independent world-use intervals / driver / sampling / NOT probability gate / owned"
            + " collection PASS");
    var parameterData = parameterDriversDemo();
    Path parameterFile = working.resolve("parameter-drivers.pvproj.json");
    new ProjectIO().save(parameterFile, parameterData);
    var loadedParameters = new ProjectLoader().load(parameterFile);
    if (!loadedParameters.success()) throw new AssertionError(loadedParameters.diagnostics());
    try (var parameterEngine = new NoiseGeneratorEngine();
        var parameterWorld =
            parameterEngine.openWorld(
                new CompiledProject(loadedParameters.project()),
                new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      for (int x = -4; x <= 4; x++)
        for (int y = -6; y <= 6; y++)
          if (parameterWorld.getVoxelTypeId(new BlockPos(x, y, 0)) != (y <= x + 1 ? 1 : 0))
            throw new AssertionError("Driven height mismatch");
      var report =
          parameterWorld.trace(new BlockPos(2, 3, 0)).steps().getFirst().details().parameters();
      if (report.size() != 2 || report.getLast().effective() != 3)
        throw new AssertionError("Numeric driver trace");
    }
    System.out.println(
        "Independent ordered numeric parameter drivers / trace / JSON roundtrip PASS");
    var expressionData = compiledExpressionsDemo();
    Path expressionsFile = working.resolve("compiled-expressions.pvproj.json");
    new ProjectIO().save(expressionsFile, expressionData);
    var expressionLoad = new ProjectLoader().load(expressionsFile);
    if (!expressionLoad.success())
      throw new IllegalStateException(expressionLoad.diagnostics().toString());
    var expressionCompiled = new CompiledProject(expressionLoad.project());
    var expressionSampler = expressionCompiled.noise2D("compiled-expression", 17);
    double[] expressionGrid = new double[35];
    java.util.Arrays.fill(expressionGrid, -999);
    expressionSampler.sampleGrid(
        -2, -1, 4, 3, .5, -2, expressionGrid, 2, 7, new CancellationToken());
    for (int z = 0; z < 3; z++)
      for (int x = 0; x < 4; x++) {
        double v = (-2 + x * .5) + (-1 - z * 2);
        if (expressionGrid[2 + z * 7 + x] != v * v + 11)
          throw new AssertionError("Compiled expression/grid mismatch");
      }
    double[] xs = {-3, -1, 2}, zs = {2, 4, -5}, pointResults = new double[3];
    expressionSampler.samplePoints(xs, zs, 0, 3, pointResults, 0, new CancellationToken());
    for (int i = 0; i < 3; i++)
      if (pointResults[i] != (xs[i] + zs[i]) * (xs[i] + zs[i]) + 11)
        throw new AssertionError("Primitive point batch mismatch");
    var report =
        new io.voxelcreator.core.NoiseCompiler(expressionLoad.project(), 17)
            .statistics(expressionData.noise2D.getFirst().uuid);
    if (report.expressionInstructions() < 1)
      throw new AssertionError("Missing compiler statistics");
    System.out.println(
        "Independent compiled-expression / strided grid / primitive points PASS " + report);
    var p = demo();
    Path file = working.resolve("terrain.pvproj.json");
    new ProjectIO().save(file, p);
    var loaded = new ProjectLoader().load(file);
    if (!loaded.success()) throw new IllegalStateException(loaded.diagnostics().toString());
    var compiled = new ProjectCompiler().compileWorld(loaded.project()).compiled();
    try (var engine = new NoiseGeneratorEngine(2, 16, 256L << 20);
        var world =
            engine.openWorld(
                compiled,
                new WorldOptions(
                    42L, 16, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, 16L << 20))) {
      var sampler = compiled.noise2D("terrain", 42);
      double[] grid = new double[64];
      sampler.sampleGrid(-4, -4, 8, 8, 1, grid);
      System.out.println("Direct sample: " + sampler.sample(-1, 3));
      VoxelChunk chunk = world.generateChunk(new ChunkPos(-1, 0, 0));
      var stage =
          world.generateStageChunk(chunk.position(), p.worldGenerator.layers.getFirst().uuid);
      for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++)
            if (stage.before().getVoxelTypeId(x, y, z) != 0
                || stage.after().getVoxelTypeId(x, y, z) != chunk.getVoxelTypeId(x, y, z))
              throw new AssertionError("Independent stage snapshot mismatch");
      System.out.println("Independent exact stage snapshots PASS");
      var asynchronous =
          world
              .generateChunkAsync(new ChunkPos(0, 0, 0), GenerationPriority.INTERACTIVE)
              .get(5, TimeUnit.SECONDS);
      System.out.println("Async chunk: " + asynchronous.representation());
      var request =
          new RegionRequest(
              new RegionBounds(new BlockPos(-16, 0, 0), new BlockPos(15, 15, 15)),
              RegionRequest.FailurePolicy.CONTINUE,
              RegionRequest.DeliveryOrder.COORDINATE,
              2,
              GenerationPriority.BACKGROUND);
      var stream =
          world.streamRegion(
              request,
              c -> {
                System.out.println("Delivered " + c.position());
                return CompletableFuture.completedFuture(null);
              });
      var summary = stream.completion().get(10, TimeUnit.SECONDS);
      if (summary.progress().completed() != 2) throw new IllegalStateException(summary.toString());
      var cancellation =
          world.streamRegion(
              RegionRequest.of(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(1023, 15, 15))),
              c -> new CompletableFuture<>());
      cancellation.cancel();
      cancellation.completion().get(5, TimeUnit.SECONDS);
      Path archive = working.resolve("exact-world.pvw");
      VoxelArchive.writeSingleChunk(archive, chunk, compiled.registry());
      var roundtrip = VoxelArchive.readSingleChunk(archive, VoxelRegistry.semantic("stone"));
      for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++)
            if (chunk.getVoxelTypeId(x, y, z) != roundtrip.getVoxelTypeId(x, y, z))
              throw new AssertionError("Archive mismatch");
      var edited = chunk.withFilledBox(0, 0, 0, 7, 15, 15, 0);
      if (edited.getVoxelTypeId(0, 0, 0) != 0
          || chunk.getVoxelTypeId(0, 0, 0) != roundtrip.getVoxelTypeId(0, 0, 0))
        throw new AssertionError("Immutable bulk edit failed");
      Path legacy = working.resolve("legacy-world.pvw");
      try (var writer =
          VoxelArchive.openWorldWriter(
              legacy, compiled.registry(), VoxelArchive.Format.LEGACY_V1)) {
        writer.append(edited);
        writer.finish();
      }
      try (var reader = VoxelArchive.openWorldReader(archive, compiled.registry())) {
        if (reader.version() != 2) throw new AssertionError("Expected packed archive version 2");
      }
      try (var reader = VoxelArchive.openWorldReader(legacy, compiled.registry())) {
        if (reader.version() != 1
            || reader.read(chunk.position()).orElseThrow().getVoxelTypeId(0, 0, 0) != 0)
          throw new AssertionError("Legacy archive compatibility failed");
      }
      System.out.println("Palette storage, immutable bulk edits and v1/v2 archives PASS");
      System.out.println("Archive and independent engine consumer PASS; " + world.cacheStats());
      System.out.println(
          "Independent generation priorities / scheduler stats PASS " + engine.schedulerStats());
    }
  }
}
