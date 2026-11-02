package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorldListEditsTest {
  private static <T> boolean edit(
      List<T> rows, int index, WorldListEdits.Action action, int limit, Class<T> type) {
    return WorldListEdits.apply(rows, WorldListEdits.request(rows, index, action), limit, type);
  }

  @Test
  void movesPreserveEveryRowAndAnIntentCanOnlyBeDeliveredOnce() {
    var a = new SamplingTransform();
    var b = new SamplingTransform();
    var c = new SamplingTransform();
    var rows = new ArrayList<>(List.of(a, b, c));
    var request = WorldListEdits.request(rows, 0, WorldListEdits.Action.DOWN);
    assertTrue(WorldListEdits.apply(rows, request, 64, SamplingTransform.class));
    assertEquals(List.of(b, a, c), rows);
    assertFalse(WorldListEdits.apply(rows, request, 64, SamplingTransform.class));
    assertEquals(List.of(b, a, c), rows);
    assertTrue(edit(rows, 2, WorldListEdits.Action.UP, 64, SamplingTransform.class));
    assertEquals(List.of(b, c, a), rows);
    assertTrue(edit(rows, 1, WorldListEdits.Action.REMOVE, 64, SamplingTransform.class));
    assertEquals(List.of(b, a), rows);
  }

  @Test
  void boundaryAndInvalidIndicesAreNoOpsAndCannotBeReplayedAfterLaterChanges() {
    var rows = new ArrayList<>(List.of(new TypeInterval()));
    String before = ProjectIO.JSON.valueToTree(rows).toString();
    assertFalse(edit(rows, 0, WorldListEdits.Action.UP, 256, TypeInterval.class));
    var down = WorldListEdits.request(rows, 0, WorldListEdits.Action.DOWN);
    assertFalse(WorldListEdits.apply(rows, down, 256, TypeInterval.class));
    assertFalse(edit(rows, -1, WorldListEdits.Action.REMOVE, 256, TypeInterval.class));
    assertFalse(edit(rows, 1, WorldListEdits.Action.REMOVE, 256, TypeInterval.class));
    assertEquals(before, ProjectIO.JSON.valueToTree(rows).toString());
    rows.add(new TypeInterval());
    assertFalse(WorldListEdits.apply(rows, down, 256, TypeInterval.class));
    assertEquals(2, rows.size());
  }

  @Test
  void deliveryRequiresTheExactListAndCompleteOriginalRowStructure() {
    var a = new SamplingTransform();
    var b = new SamplingTransform();
    var c = new SamplingTransform();
    var rows = new ArrayList<>(List.of(a, b, c));
    var request = WorldListEdits.request(rows, 0, WorldListEdits.Action.REMOVE);
    var equivalent = new ArrayList<>(rows);
    assertFalse(WorldListEdits.apply(equivalent, request, 64, SamplingTransform.class));
    assertEquals(List.of(a, b, c), equivalent);
    assertFalse(WorldListEdits.apply(rows, request, 64, SamplingTransform.class));
    request = WorldListEdits.request(rows, 0, WorldListEdits.Action.REMOVE);
    Collections.swap(rows, 1, 2);
    assertFalse(WorldListEdits.apply(rows, request, 64, SamplingTransform.class));
    Collections.swap(rows, 1, 2);
    assertFalse(WorldListEdits.apply(rows, request, 64, SamplingTransform.class));
    assertEquals(List.of(a, b, c), rows);
    request = WorldListEdits.request(rows, 0, WorldListEdits.Action.REMOVE);
    rows.set(2, new SamplingTransform());
    assertFalse(WorldListEdits.apply(rows, request, 64, SamplingTransform.class));
    assertSame(a, rows.getFirst());
  }

  @Test
  void duplicationOwnsAllFieldsAndUsesTheCurrentValuesOfTheStillOwnedRow() {
    var row = new NumericDriver();
    row.enabled = true;
    row.destination = "transformPivotX";
    row.transformIndex = 3;
    row.combination = "MULTIPLY";
    row.noiseGeneratorUuid = UUID.randomUUID();
    row.inputMin = -9;
    row.inputMax = 17;
    row.outputMin = -2.123456789012345;
    row.outputMax = 5;
    row.mapping = "SMOOTHSTEP";
    var rows = new ArrayList<>(List.of(row));
    var request = WorldListEdits.request(rows, 0, WorldListEdits.Action.DUPLICATE);
    row.outputMax = 7;
    String before = ProjectIO.JSON.valueToTree(row).toString();
    assertTrue(WorldListEdits.apply(rows, request, 32, NumericDriver.class));
    var copy = rows.getLast();
    assertNotSame(row, copy);
    assertEquals(before, ProjectIO.JSON.valueToTree(copy).toString());
    copy.outputMin = 99;
    copy.transformIndex = 0;
    assertEquals(before, ProjectIO.JSON.valueToTree(row).toString());
  }

  @Test
  void exactCollectionLimitsRejectCopiesAndAddsWithoutChangingExistingRows() {
    var transforms = new ArrayList<SamplingTransform>();
    for (int i = 0; i < 64; i++)
      assertTrue(WorldListEdits.add(transforms, new SamplingTransform(), 64));
    String before = ProjectIO.JSON.valueToTree(transforms).toString();
    assertFalse(edit(transforms, 0, WorldListEdits.Action.DUPLICATE, 64, SamplingTransform.class));
    assertFalse(WorldListEdits.add(transforms, new SamplingTransform(), 64));
    assertEquals(before, ProjectIO.JSON.valueToTree(transforms).toString());
    assertTrue(edit(transforms, 63, WorldListEdits.Action.REMOVE, 64, SamplingTransform.class));
    assertTrue(edit(transforms, 0, WorldListEdits.Action.DUPLICATE, 64, SamplingTransform.class));
    var drivers = new ArrayList<NumericDriver>();
    for (int i = 0; i < 32; i++) WorldListEdits.add(drivers, new NumericDriver(), 32);
    assertFalse(edit(drivers, 0, WorldListEdits.Action.DUPLICATE, 32, NumericDriver.class));
    var intervals = new ArrayList<TypeInterval>();
    for (int i = 0; i < 256; i++) WorldListEdits.add(intervals, new TypeInterval(), 256);
    assertFalse(WorldListEdits.add(intervals, new TypeInterval(), 256));
    assertEquals(32, drivers.size());
    assertEquals(256, intervals.size());
  }

  @Test
  void transformEditsKeepNumericRowTargetsAndSparseOverridesExplicit() {
    var layer = new Layer();
    var translate = new SamplingTransform();
    translate.x = 3;
    var scale = new SamplingTransform();
    scale.kind = "SCALE";
    scale.x = scale.y = scale.z = 2;
    layer.defaults.samplingTransforms.addAll(List.of(translate, scale));
    var driver = new NumericDriver();
    driver.destination = "transformX";
    driver.transformIndex = 1;
    layer.defaults.parameterDrivers.add(driver);
    var op = new Operation();
    var inherited = SettingsResolver.resolve(layer, op);
    edit(inherited.samplingTransforms, 0, WorldListEdits.Action.DOWN, 64, SamplingTransform.class);
    assertSame(translate, layer.defaults.samplingTransforms.getFirst());
    assertTrue(op.overrides.isEmpty());
    op.overrides.put(
        "samplingTransforms", ProjectIO.JSON.valueToTree(inherited.samplingTransforms));
    assertEquals(Set.of("samplingTransforms"), op.overrides.keySet());
    var resolved = SettingsResolver.resolve(layer, op);
    assertEquals("SCALE", resolved.samplingTransforms.getFirst().kind);
    assertEquals(1, resolved.parameterDrivers.getFirst().transformIndex);
    assertEquals("TRANSLATE", resolved.samplingTransforms.get(1).kind);
    op.overrides.remove("samplingTransforms");
    assertEquals(
        "TRANSLATE", SettingsResolver.resolve(layer, op).samplingTransforms.getFirst().kind);
    assertEquals(1, driver.transformIndex);
  }

  @Test
  void privateListEditsApplyOnceAndInvalidTargetDeletionRetainsTheDraft() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var layer = new Layer();
                layer.id = "layer";
                layer.defaults.samplingTransforms.add(new SamplingTransform());
                var driver = new NumericDriver();
                driver.destination = "transformX";
                driver.transformIndex = 0;
                var noise = new Noise();
                noise.id = "driver-field";
                p.noise2D.add(noise);
                driver.noiseGeneratorUuid = noise.uuid;
                layer.defaults.parameterDrivers.add(driver);
                p.worldGenerator.layers.add(layer);
              }));
      state.select("layer", state.project().worldGenerator.layers.getFirst().uuid);
      String before = ProjectIO.JSON.valueToTree(state.project()).toString();
      long revision = state.revision;
      var draft = (Layer) state.draft;
      edit(
          draft.defaults.samplingTransforms,
          0,
          WorldListEdits.Action.DUPLICATE,
          64,
          SamplingTransform.class);
      draft.defaults.samplingTransforms.getLast().x = 2.123456789012345;
      edit(
          draft.defaults.samplingTransforms,
          1,
          WorldListEdits.Action.UP,
          64,
          SamplingTransform.class);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()).toString());
      assertEquals(revision, state.revision);
      assertTrue(state.applyDraft());
      String applied = ProjectIO.JSON.valueToTree(state.project()).toString();
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()).toString());
      state.document.redo();
      assertEquals(applied, ProjectIO.JSON.valueToTree(state.project()).toString());
      state.refreshDraft();
      draft = (Layer) state.draft;
      edit(
          draft.defaults.samplingTransforms,
          0,
          WorldListEdits.Action.REMOVE,
          64,
          SamplingTransform.class);
      edit(
          draft.defaults.samplingTransforms,
          0,
          WorldListEdits.Action.REMOVE,
          64,
          SamplingTransform.class);
      String invalid = ProjectIO.JSON.valueToTree(draft).toString();
      assertFalse(state.applyDraft());
      assertTrue(state.issues.stream().anyMatch(d -> d.code().equals("INVALID_DRIVER_TRANSFORM")));
      assertEquals(invalid, ProjectIO.JSON.valueToTree(state.draft).toString());
      assertEquals(applied, ProjectIO.JSON.valueToTree(state.project()).toString());
    }
  }
}
