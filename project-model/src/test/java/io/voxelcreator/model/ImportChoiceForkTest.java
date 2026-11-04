package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/**
 * Deferred independent planning drafts retain allocation identity without sharing mutable choices.
 */
class ImportChoiceForkTest {
  private ProjectData incoming() {
    var p = new ProjectData();
    for (String id : List.of("first", "second")) {
      var n = new Noise();
      n.id = id;
      p.noise2D.add(n);
    }
    return p;
  }

  @Test
  void forksReplaceChoicesAndRetainTheOriginalCopyAllocationAndCachedPlan() {
    var incoming = incoming();
    var original = new ResourceImport.Session(new ProjectData(), incoming);
    var plan = original.preview();
    UUID first = incoming.noise2D.getFirst().uuid;
    var choices = new HashMap<UUID, ResourceImport.Choice>();
    choices.put(first, ResourceImport.Choice.SKIP);
    var fork = original.withChoices(choices);
    choices.clear();
    assertEquals(ResourceImport.Choice.SKIP, fork.choices().get(first));
    assertEquals(1, fork.preview().candidate().noise2D.size());
    assertEquals(plan.rows().get(1).targetUuid(), fork.preview().rows().get(1).targetUuid());
    var reset = fork.withChoices(Map.of());
    assertEquals(plan.rows(), reset.preview().rows());
    assertSame(plan, original.preview());
    assertTrue(original.choices().isEmpty());
    fork.choose(first, ResourceImport.Choice.COPY);
    assertTrue(reset.choices().isEmpty());
    assertEquals(2, reset.preview().candidate().noise2D.size());
  }

  @Test
  void parallelForksOwnCandidatesAndDoNotFollowCallerOrSiblingMutation() throws Exception {
    var incoming = incoming();
    var destination = new ProjectData();
    var original = new ResourceImport.Session(destination, incoming);
    var plan = original.preview();
    var a =
        original.withChoices(Map.of(incoming.noise2D.getFirst().uuid, ResourceImport.Choice.SKIP));
    var b = original.withChoices(Map.of());
    incoming.noise2D.clear();
    destination.project.seed = 43;
    try (var workers = Executors.newFixedThreadPool(2)) {
      var fa = workers.submit(a::preview);
      var fb = workers.submit(b::preview);
      assertEquals(1, fa.get().candidate().noise2D.size());
      assertEquals(plan.rows(), fb.get().rows());
      fa.get().candidate().noise2D.clear();
      assertEquals(2, fb.get().candidate().noise2D.size());
      assertSame(plan, original.preview());
    }
  }

  @Test
  void invalidForkChoicesFailWithoutInvalidatingTheOriginalDraft() {
    var original = new ResourceImport.Session(new ProjectData(), incoming());
    var plan = original.preview();
    assertThrows(
        IllegalArgumentException.class,
        () -> original.withChoices(Map.of(UUID.randomUUID(), ResourceImport.Choice.SKIP)));
    assertThrows(NullPointerException.class, () -> original.withChoices(null));
    assertSame(plan, original.preview());
    assertTrue(original.choices().isEmpty());
  }
}
