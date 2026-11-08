package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred production upload/data/state definitions; root GL command mapping stays source-traced. */
class ViewportUploadTest {
  private static final int[] ORIGINAL = {3, 17, 19, 8, 1, 23, 29, 2, 3, 1};
  private static final int[] PIXELS = {
    0xff0000ff, 0xff00ff00, 0xffff0000, 0x7f010203, 0x00112233, 0x809abcde
  };
  private static final float[] VERTICES = {1, 2, 3, 0, 1, 0, 5, -4, 6, 8, 1, 0, 0, 7};
  private static final List<String> MESH = List.of("bind-vao", "bind-buffer", "mesh-data",
      "enable0", "pointer0", "enable1", "pointer1", "enable2", "pointer2", "mesh-check");

  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }
  private static Throwable fault(boolean error) {
    return error ? new AssertionError() {
      @Override public String getMessage() { throw new AssertionError("Unexpected text read"); }
      @Override public String toString() { throw new AssertionError("Unexpected text read"); }
    } : new IllegalStateException() {
      @Override public String getMessage() { throw new AssertionError("Unexpected text read"); }
      @Override public String toString() { throw new AssertionError("Unexpected text read"); }
    };
  }

  private static final class Fixture {
    final ViewportUpload owner = new ViewportUpload();
    final List<String> events = new ArrayList<>();
    int vao = 11, buffer = 13, failures, publications;
    int[] unpack = ORIGINAL.clone(), pixels;
    float[] vertices;
    Mesh acceptedMesh;
    String mode = "previous";
    Consumer<String> before = event -> {}, after = event -> {};
    Runnable validator = () -> {};
    boolean restoring;
    int stores;

    void call(String event, Runnable effect) {
      events.add(event); before.accept(event); effect.run(); after.accept(event);
    }
    final MeshUploadState.Backend meshState = new MeshUploadState.Backend() {
      public int read(MeshUploadState.Item item) {
        call("read-" + item, () -> {});
        return item == MeshUploadState.Item.VERTEX_ARRAY ? vao : buffer;
      }
      public void write(MeshUploadState.Item item, int value) {
        call(item == MeshUploadState.Item.VERTEX_ARRAY ? "restore-vao" : "restore-buffer",
            () -> { if (item == MeshUploadState.Item.VERTEX_ARRAY) vao = value; else buffer = value; });
      }
    };
    final TextureUploadState.Backend imageState = new TextureUploadState.Backend() {
      public int read(TextureUploadState.Item item) {
        call("image-read-" + item, () -> {}); return unpack[item.ordinal()];
      }
      public void write(TextureUploadState.Item item, int value) {
        if (stores++ > 0 && item == TextureUploadState.Item.ACTIVE_TEXTURE) restoring = true;
        call((restoring ? "image-restore-" : "image-normal-") + item,
            () -> unpack[item.ordinal()] = value);
      }
    };
    void command(Runnable current, String event, Runnable effect) {
      current.run(); call(event, effect); current.run();
    }
    void retire() {
      RenderCleanup.run(() -> call("retire-volume", () -> {}),
          () -> call("retire-shadow", () -> {}), () -> call("retire-scene", () -> {}));
    }
    void failed() { failures++; call("failed", () -> {}); }
    void mesh(Mesh submitted) {
      owner.requireIdle();
      var owned = ViewportUploadData.mesh(submitted);
      owner.run(validator, current -> MeshUploadState.capture(meshState).run(current, () -> {
        command(current, "bind-vao", () -> vao = 101);
        command(current, "bind-buffer", () -> buffer = 103);
        command(current, "mesh-data", () -> vertices = owned.vertices().clone());
        for (var event : MESH.subList(3, MESH.size())) command(current, event, () -> {});
      }), this::retire, () -> {
        call("publish", () -> { publications++; acceptedMesh = owned; mode = "mesh"; });
      }, this::failed);
    }
    void image(int[] submitted) {
      owner.requireIdle();
      var owned = ViewportUploadData.image(3, 2, submitted);
      restoring = false; stores = 0;
      owner.run(validator, current -> TextureUploadState.capture(imageState).run(current, () -> {
        command(current, "image-bind", () -> unpack[1] = 107);
        command(current, "image-data", () -> {
          assertEquals(3, unpack[0]); assertEquals(0, unpack[2]); assertEquals(4, unpack[3]);
          for (int i = 4; i < unpack.length; i++) assertEquals(0, unpack[i]);
          pixels = owned.clone();
        });
        command(current, "image-check", () -> {});
        return null;
      }, () -> {}), this::retire, () -> {
        call("publish", () -> { publications++; mode = "image"; });
      }, this::failed);
    }
    void faultAt(String point, boolean afterEffect, Throwable failure) {
      Consumer<String> hook = event -> { if (event.equals(point)) raise(failure); };
      if (afterEffect) after = hook; else before = hook;
    }
    void repair() {
      before = event -> {}; after = event -> {}; validator = () -> {};
      vao = 11; buffer = 13; unpack = ORIGINAL.clone(); events.clear();
    }
    void restoredMesh() { assertEquals(11, vao); assertEquals(13, buffer); }
    void rejected() { assertEquals(0, publications); assertEquals("previous", mode); assertEquals(1, failures); }
  }

  @Test
  void imageAndMeshOwnTheirInputsAndRestoreStateBeforeRetirementAndAcceptance() {
    var f = new Fixture(); var image = PIXELS.clone();
    f.before = event -> {
      if (event.equals("image-data")) Arrays.fill(image, 0);
      if (event.startsWith("retire-") || event.equals("publish")) assertArrayEquals(ORIGINAL, f.unpack);
    };
    f.image(image); assertArrayEquals(PIXELS, f.pixels); assertEquals("image", f.mode);
    assertEquals(1, f.publications); assertEquals(0, f.failures);
    var values = VERTICES.clone(); f.repair();
    f.before = event -> {
      if (event.equals("mesh-data")) Arrays.fill(values, 99);
      if (event.startsWith("retire-") || event.equals("publish")) f.restoredMesh();
    };
    f.mesh(new Mesh(values, true));
    assertArrayEquals(VERTICES, f.vertices); assertArrayEquals(VERTICES, f.acceptedMesh.vertices());
    assertTrue(f.acceptedMesh.points()); assertEquals(2, f.acceptedMesh.vertexCount());
    assertEquals(56, f.acceptedMesh.bytes()); assertEquals(2, f.publications); assertEquals(0, f.failures);
  }

  @Test
  void malformedInputsRejectBeforeBackendStateOrAcceptedDataIsTouched() {
    assertThrows(NullPointerException.class, () -> ViewportUploadData.image(1, 1, null));
    for (int[] shape : new int[][] {{0, 1}, {1, -1}, {3, 3}, {Integer.MAX_VALUE, Integer.MAX_VALUE}})
      assertThrows(IllegalArgumentException.class, () -> ViewportUploadData.image(shape[0], shape[1], PIXELS));
    assertThrows(NullPointerException.class, () -> ViewportUploadData.mesh(null));
    assertThrows(NullPointerException.class, () -> ViewportUploadData.mesh(new Mesh(null, false)));
    assertThrows(IllegalArgumentException.class, () -> ViewportUploadData.mesh(new Mesh(new float[8], false)));
    var empty = ViewportUploadData.mesh(new Mesh(new float[0], false));
    assertFalse(empty.points()); assertEquals(0, empty.vertexCount());
    assertArrayEquals(new int[] {0x10203040}, ViewportUploadData.image(1, 1, new int[] {0x10203040}));
  }

  @Test
  void everyMeshCommandFaultRestoresCallerBindingsAndLeavesThePreviousAcceptance() {
    for (String point : MESH) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) {
        var f = new Fixture(); var primary = fault(error); f.faultAt(point, after, primary);
        assertSame(primary, assertThrows(Throwable.class, () -> f.mesh(new Mesh(VERTICES, true))));
        f.restoredMesh(); f.rejected(); assertFalse(f.events.contains("retire-volume"));
        assertEquals(0, primary.getSuppressed().length);
        f.repair(); f.mesh(new Mesh(VERTICES, true)); assertArrayEquals(VERTICES, f.vertices);
        assertEquals(1, f.publications);
      }
  }

  @Test
  void everyMeshRestoreFaultAttemptsTheOtherBindingAndRejectsAcceptanceUntilRetry() {
    for (String point : List.of("restore-vao", "restore-buffer"))
      for (boolean after : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true}) {
        var f = new Fixture(); var primary = fault(error); f.faultAt(point, after, primary);
        assertSame(primary, assertThrows(Throwable.class, () -> f.mesh(new Mesh(VERTICES, false))));
        assertTrue(f.events.containsAll(List.of("restore-vao", "restore-buffer")));
        assertEquals(point.equals("restore-vao") && !after ? 101 : 11, f.vao);
        assertEquals(point.equals("restore-buffer") && !after ? 103 : 13, f.buffer);
        f.rejected(); assertFalse(f.events.contains("retire-volume"));
        f.repair(); f.mesh(new Mesh(VERTICES, false)); f.restoredMesh(); assertEquals(1, f.publications);
      }
  }

  @Test
  void everySnapshotReadFaultStopsBeforeBindingUploadingOrRetiring() {
    var points = new ArrayList<String>(List.of("read-VERTEX_ARRAY", "read-ARRAY_BUFFER"));
    for (var item : TextureUploadState.Item.values()) points.add("image-read-" + item);
    for (String point : points) for (boolean error : new boolean[] {false, true}) {
      var f = new Fixture(); var primary = fault(error); f.faultAt(point, false, primary);
      assertSame(primary, assertThrows(Throwable.class, () -> {
        if (point.startsWith("image")) f.image(PIXELS); else f.mesh(new Mesh(VERTICES, false));
      }));
      f.restoredMesh(); assertArrayEquals(ORIGINAL, f.unpack); f.rejected();
      assertNull(f.vertices); assertNull(f.pixels); assertFalse(f.events.contains("retire-volume"));
    }
  }

  @Test
  void everyImageNormalizationAndRestoreFaultAttemptsAllRestoresWithoutAccepting() {
    var points = new ArrayList<String>();
    for (var item : TextureUploadState.Item.values()) {
      if (item != TextureUploadState.Item.BINDING) points.add("image-normal-" + item);
      points.add("image-restore-" + item);
    }
    for (String point : points) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) {
        var f = new Fixture(); var primary = fault(error); f.faultAt(point, after, primary);
        assertSame(primary, assertThrows(Throwable.class, () -> f.image(PIXELS)));
        for (var item : TextureUploadState.Item.values())
          assertTrue(f.events.contains("image-restore-" + item));
        if (point.startsWith("image-normal")) assertNull(f.pixels);
        f.rejected(); assertFalse(f.events.contains("retire-volume"));
        f.repair(); f.image(PIXELS); assertArrayEquals(PIXELS, f.pixels);
        assertArrayEquals(ORIGINAL, f.unpack); assertEquals(1, f.publications);
      }
  }

  @Test
  void imageBodyFailuresRestoreTenValuesAndKeepRetirementAndAcceptanceUnstarted() {
    for (String point : List.of("image-bind", "image-data", "image-check"))
      for (boolean after : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true}) {
        var f = new Fixture(); var primary = fault(error); f.faultAt(point, after, primary);
        assertSame(primary, assertThrows(Throwable.class, () -> f.image(PIXELS)));
        assertArrayEquals(ORIGINAL, f.unpack); f.rejected();
        assertFalse(f.events.contains("retire-volume")); assertEquals(0, primary.getSuppressed().length);
      }
  }

  @Test
  void sharedUnreadableWorkRestoreAndFailureObserverFaultsRetainTheOriginalPrimary() {
    var f = new Fixture(); var primary = fault(true);
    f.after = event -> {
      if (List.of("mesh-data", "restore-vao", "restore-buffer", "failed").contains(event)) raise(primary);
    };
    assertSame(primary, assertThrows(Throwable.class, () -> f.mesh(new Mesh(VERTICES, true))));
    f.restoredMesh(); f.rejected(); assertEquals(0, primary.getSuppressed().length);
    assertTrue(f.events.containsAll(List.of("restore-vao", "restore-buffer", "failed")));
  }

  @Test
  void everyRetirementFaultAttemptsPeersAfterRestorationAndPreventsMetadataPublication() {
    for (String point : List.of("retire-volume", "retire-shadow", "retire-scene"))
      for (boolean after : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true}) {
        var f = new Fixture(); var primary = fault(error); f.faultAt(point, after, primary);
        assertSame(primary, assertThrows(Throwable.class, () -> f.mesh(new Mesh(VERTICES, true))));
        f.restoredMesh(); f.rejected();
        assertTrue(f.events.containsAll(List.of("retire-volume", "retire-shadow", "retire-scene")));
        assertTrue(f.events.indexOf("retire-volume") > f.events.indexOf("restore-buffer"));
        assertTrue(f.events.indexOf("failed") > f.events.indexOf("retire-scene"));
        assertEquals(0, primary.getSuppressed().length);
      }
  }

  @Test
  void callbackInvalidationOrCloseAtWorkRestoreAndRetirementCannotAcceptOldMetadata() {
    for (String point : List.of("mesh-data", "restore-vao", "restore-buffer",
        "retire-volume", "retire-shadow", "retire-scene")) for (boolean close : new boolean[] {false, true}) {
      var f = new Fixture();
      f.after = event -> { if (event.equals(point)) { if (close) f.owner.close(); else f.owner.invalidate(); } };
      assertThrows(CancellationException.class, () -> f.mesh(new Mesh(VERTICES, true)));
      f.restoredMesh(); f.rejected();
      if (point.startsWith("retire"))
        assertTrue(f.events.containsAll(List.of("retire-volume", "retire-shadow", "retire-scene")));
      f.repair();
      if (close) {
        assertThrows(IllegalStateException.class, () -> f.mesh(new Mesh(VERTICES, true)));
        assertTrue(f.events.isEmpty());
      } else { f.mesh(new Mesh(VERTICES, true)); assertEquals(1, f.publications); }
    }
  }

  @Test
  void reentryFromValidationCaptureWorkRestorationRetirementAndPublicationRejectsBeforeWork() {
    var f = new Fixture(); var attempts = new AtomicInteger();
    Runnable nested = () -> {
      attempts.incrementAndGet();
      assertThrows(IllegalStateException.class, f.owner::requireIdle);
      assertThrows(IllegalStateException.class, () -> f.owner.run(() -> {}, current -> fail(),
          () -> fail(), () -> fail(), () -> fail()));
    };
    f.validator = nested; f.before = event -> nested.run();
    f.mesh(new Mesh(VERTICES, true));
    assertTrue(attempts.get() > MESH.size()); assertEquals(1, f.publications); assertEquals(0, f.failures);
    f.restoredMesh();
    f.owner.close(); assertThrows(IllegalStateException.class, () -> f.image(PIXELS));
  }

  @Test
  void imageClosureAtNormalizationBodyOrEveryRestoreAttemptsTheWholeCapturedTail() {
    var points = new ArrayList<String>(List.of("image-normal-PIXEL_UNPACK_BUFFER", "image-data"));
    for (var item : TextureUploadState.Item.values()) points.add("image-restore-" + item);
    for (String point : points) {
      var f = new Fixture(); f.after = event -> { if (event.equals(point)) f.owner.close(); };
      assertThrows(CancellationException.class, () -> f.image(PIXELS));
      assertArrayEquals(ORIGINAL, f.unpack); f.rejected();
      for (var item : TextureUploadState.Item.values())
        assertTrue(f.events.contains("image-restore-" + item));
      assertFalse(f.events.contains("retire-volume"));
      f.repair(); assertThrows(IllegalStateException.class, () -> f.image(PIXELS));
      assertTrue(f.events.isEmpty());
    }
  }

  @Test
  void snapshotsOwnBindingValuesAndEmptyMeshRetryRetainsAnExactIndependentLayout() {
    var f = new Fixture(); var snapshot = MeshUploadState.capture(f.meshState);
    f.vao = 41; f.buffer = 43;
    snapshot.run(() -> {}, () -> { f.vao = 101; f.buffer = 103; }); f.restoredMesh();
    f.mesh(new Mesh(new float[0], false));
    assertEquals(0, f.acceptedMesh.bytes()); assertFalse(f.acceptedMesh.points());
    f.mesh(new Mesh(VERTICES, true));
    assertEquals(1, f.vertices[0]); assertEquals(2, f.vertices[1]); assertEquals(3, f.vertices[2]);
    assertEquals(5, f.vertices[6]); assertEquals(-4, f.vertices[7]); assertEquals(7, f.vertices[13]);
    assertEquals(2, f.publications); f.restoredMesh();
  }
}
