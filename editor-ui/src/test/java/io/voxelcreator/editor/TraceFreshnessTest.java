package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.GenerationTrace;
import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.BlockPos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TraceFreshnessTest {
  private static TraceSnapshot snapshot(ProjectData project) {
    return new TraceSnapshot(project, 7, new GenerationTrace(new BlockPos(-1, 2, 3), 0, List.of()));
  }

  @Test
  void currentIdentityRequiresTheCapturedRevisionRegardlessOfRevisionOrder() {
    var project = new ProjectData();
    var trace = snapshot(project);
    assertEquals(TraceSnapshot.Freshness.CURRENT, trace.freshness(project.project.uuid, 7));
    for (long revision : new long[] {0, 6, 8, Long.MAX_VALUE})
      assertEquals(
          TraceSnapshot.Freshness.PROJECT_CHANGED, trace.freshness(project.project.uuid, revision));
  }

  @Test
  void aForeignProjectCannotBecomeCurrentByReusingARevisionNumber() {
    var trace = snapshot(new ProjectData());
    var other = UUID.randomUUID();
    for (long revision : new long[] {0, 7, 8})
      assertEquals(TraceSnapshot.Freshness.OTHER_PROJECT, trace.freshness(other, revision));
  }

  @Test
  void sourceReplacementCannotRewriteCapturedIdentityOrRevision() {
    var source = new ProjectData();
    var capturedId = source.project.uuid;
    var trace = snapshot(source);
    String details = trace.details();
    source.project.uuid = UUID.randomUUID();
    assertEquals(TraceSnapshot.Freshness.CURRENT, trace.freshness(capturedId, 7));
    assertEquals(TraceSnapshot.Freshness.OTHER_PROJECT, trace.freshness(source.project.uuid, 7));
    assertEquals(7, trace.revision());
    assertEquals(details, trace.details());
  }
}
