package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred shared lexical contract; names are validated rather than repaired. */
class ResourceIdsTest {
  @Test
  void canonicalNamesRetainUnicodeCaseInternalSpacesAndGenericEmptySpelling() {
    for (String id : List.of("stone", "Stone", "é", "石", "🌋", "with space", "empty", "EMPTY"))
      assertTrue(ResourceIds.isCanonical(id), id);
    for (String id :
        Arrays.asList(
            null,
            "",
            " ",
            " leading",
            "trailing ",
            "a\nb",
            "a\tb",
            "a\0b",
            "a\u007fb",
            "a\u0085b",
            "\u2003id",
            "id\u2003",
            "e\u0301")) assertFalse(ResourceIds.isCanonical(id), String.valueOf(id));
  }

  @Test
  void projectValidationRetainsExactIdDiagnosticsAndDoesNotRepairInputs() {
    for (String id : List.of(" leading", "trailing ", "a\nb", "a\0b", "a\u0085b", "e\u0301")) {
      var project = new ProjectData();
      var type = new VoxelType();
      type.id = id;
      project.voxelTypes.add(type);
      var before = ProjectIO.JSON.valueToTree(project);
      var issues = Validator.validate(project);
      assertEquals(1, issues.size());
      assertEquals("INVALID_ID", issues.getFirst().code());
      assertEquals("/voxelTypes/0/id", issues.getFirst().jsonPointer());
      assertEquals(before, ProjectIO.JSON.valueToTree(project));
      assertEquals(id, type.id);
    }
  }

  @Test
  void namespaceUniquenessAndReservedEmptyRemainSeparateFromLexicalShape() {
    var project = new ProjectData();
    for (String id : List.of("Stone", "stone", "empty", "é", "with space")) {
      var type = new VoxelType();
      type.id = id;
      project.voxelTypes.add(type);
    }
    assertTrue(Validator.validate(project).isEmpty());
    project.voxelTypes.getLast().id = "Stone";
    assertEquals("DUPLICATE_ID", Validator.validate(project).getFirst().code());
    project.voxelTypes.getLast().id = "EMPTY";
    var issues = Validator.validate(project);
    assertEquals(1, issues.size());
    assertEquals("RESERVED_ID", issues.getFirst().code());
    assertEquals("/voxelTypes/4/id", issues.getFirst().jsonPointer());
  }
}
