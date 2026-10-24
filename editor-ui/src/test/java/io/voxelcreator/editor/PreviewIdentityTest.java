package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.model.ProjectData.*;
import org.junit.jupiter.api.Test;

public class PreviewIdentityTest {
  @Test
  void paletteBookmarksAndFoldersDoNotChangeWorldGeometryIdentity() {
    var p = new ProjectData();
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    String key = PreviewIdentity.of(p, 2, null);
    type.color.r = 17;
    var bookmark = new Bookmark();
    bookmark.name = "view";
    p.bookmarks.world.add(bookmark);
    var folder = new Folder();
    folder.id = "organize";
    p.folders.add(folder);
    assertEquals(key, PreviewIdentity.of(p, 2, null));
    p.project.seed++;
    assertNotEquals(key, PreviewIdentity.of(p, 2, null));
  }

  @Test
  void unrelatedFieldsDoNotInvalidateSelectedNoise() {
    var p = new ProjectData();
    var selected = new Noise();
    selected.id = "selected";
    p.noise2D.add(selected);
    String key = PreviewIdentity.of(p, 1, selected.uuid);
    var other = new Noise();
    other.id = "other";
    p.noise2D.add(other);
    assertEquals(key, PreviewIdentity.of(p, 1, selected.uuid));
    selected.seedOffset++;
    assertNotEquals(key, PreviewIdentity.of(p, 1, selected.uuid));
  }
}
