package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred independent source order, immutable labels and explicit missing-boundary behavior. */
class StageBoundaryCatalogTest {
  @Test
  void followsAuthoredPipelineRatherThanTreeOrderAndRetainsDisabledStages() {
    var p = new ProjectData();
    var a = new Layer();
    a.id = "a";
    var b = new Layer();
    b.id = "b";
    b.enabled = false;
    var x = new Operation();
    x.id = "x";
    var y = new Operation();
    y.id = "y";
    y.enabled = false;
    var z = new Operation();
    z.id = "z";
    a.operations.addAll(List.of(x, y));
    b.operations.add(z);
    p.worldGenerator.layers.addAll(List.of(a, b));
    p.project.rootOrder.put("layer", new ArrayList<>(List.of(b.uuid, a.uuid)));
    var index = new StageBoundaryCatalog(p);
    assertEquals(
        List.of(a.uuid, x.uuid, y.uuid, b.uuid, z.uuid),
        index.rows().stream().map(StageBoundaryCatalog.Row::uuid).toList());
    assertTrue(index.get(x.uuid).enabled());
    assertFalse(index.get(y.uuid).enabled());
    assertFalse(index.get(z.uuid).enabled());
    assertEquals(a.uuid, index.get(x.uuid).layerUuid());
    assertEquals("Layer a / enabled", index.get(x.uuid).detail());
    assertEquals("Layer b / disabled", index.get(z.uuid).detail());
  }

  @Test
  void rowsAndChoiceDetailsStayFrozenAfterSourceMutation() {
    var p = new ProjectData();
    var layer = new Layer();
    layer.id = "original";
    var op = new Operation();
    op.id = "operation";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    var index = new StageBoundaryCatalog(p);
    layer.id = "later";
    layer.enabled = false;
    op.id = "changed";
    layer.operations.clear();
    assertEquals("original", index.get(layer.uuid).id());
    assertEquals("operation", index.get(op.uuid).id());
    assertEquals("Layer original / enabled", index.choices().get(1).detail());
    assertThrows(UnsupportedOperationException.class, () -> index.rows().clear());
    assertThrows(UnsupportedOperationException.class, () -> index.choices().clear());
  }

  @Test
  void onlyUnselectedBoundaryReceivesDefaultAndFinalNeedsNoBoundary() {
    var p = new ProjectData();
    var layer = new Layer();
    layer.id = "first";
    var op = new Operation();
    op.id = "preferred";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    var index = new StageBoundaryCatalog(p);
    var missing = UUID.randomUUID();
    assertEquals(op.uuid, index.initial(null, op.uuid));
    assertEquals(layer.uuid, index.initial(null, missing));
    assertEquals(missing, index.initial(missing, op.uuid));
    for (var mode : List.of("Before", "After", "Only Changes")) {
      assertFalse(index.available(mode, missing));
      assertFalse(index.available(mode, null));
      assertTrue(index.available(mode, op.uuid));
    }
    assertTrue(index.available("Final", missing));
    assertTrue(new StageBoundaryCatalog(new ProjectData()).available("Final", null));
    assertNull(new StageBoundaryCatalog(new ProjectData()).initial(null, op.uuid));
  }

  @Test
  void cacheReusesOnlySamePublicationIncludingSameUuidCopies() {
    var p = new ProjectData();
    var layer = new Layer();
    layer.id = "first";
    p.worldGenerator.layers.add(layer);
    var cache = new StageBoundaryCatalog.Cache();
    var first = cache.get(p, 3);
    assertSame(first, cache.get(p, 3));
    assertNotSame(first, cache.get(p, 4));
    var copy = ProjectIO.copy(p);
    assertEquals(p.project.uuid, copy.project.uuid);
    assertNotSame(cache.get(p, 4), cache.get(copy, 4));
    assertEquals(3, cache.builds());
  }
}
