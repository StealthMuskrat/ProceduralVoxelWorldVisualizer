package io.voxelcreator.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.ProjectData.Bookmark;
import java.util.*;

/** Coordinate-only, tab-scoped bookmark edits. Navigation is deliberately outside this API. */
public final class BookmarkTransactions {
  private BookmarkTransactions() {}

  public static Map<String, Object> registry() {
    var registry = new LinkedHashMap<String, Object>();
    registry.put("registryVersion", 1);
    registry.put("projectFormatVersion", 2);
    registry.put("collections", List.of("noise2D", "noise3D", "world"));
    registry.put("coordinateType", "signed 32-bit integer cells");
    registry.put("captureRule", "floor; reject nonfinite or out-of-range coordinates");
    registry.put("coordinatesOnly", true);
    registry.put("duplicateNamesAllowed", true);
    registry.put("navigationAffectsProject", false);
    return Collections.unmodifiableMap(registry);
  }

  public enum Tab {
    NOISE_2D("noise2D", false), NOISE_3D("noise3D", true), WORLD("world", true);

    private final String key;
    private final boolean threeDimensional;

    Tab(String key, boolean threeDimensional) {
      this.key = key;
      this.threeDimensional = threeDimensional;
    }

    public String key() { return key; }
    public boolean threeDimensional() { return threeDimensional; }
  }

  public record Coordinates(int x, Integer y, int z) {
    public String label() {
      return "X " + x + (y == null ? "" : "  Y " + y) + "  Z " + z;
    }
  }

  public static List<Bookmark> list(ProjectData project, Tab tab) {
    return switch (tab) {
      case NOISE_2D -> project.bookmarks.noise2D;
      case NOISE_3D -> project.bookmarks.noise3D;
      case WORLD -> project.bookmarks.world;
    };
  }

  public static Bookmark find(ProjectData project, Tab tab, UUID uuid) {
    return list(project, tab).stream().filter(b -> b.uuid.equals(uuid)).findFirst().orElse(null);
  }

  public static Coordinates coordinates(Bookmark bookmark) {
    return new Coordinates(bookmark.x, bookmark.y, bookmark.z);
  }

  /** Capture the containing integer cell; reject overflow instead of Java's saturating casts. */
  public static Coordinates capture(Tab tab, double x, double y, double z) {
    return new Coordinates(cell(x), tab.threeDimensional() ? cell(y) : null, cell(z));
  }

  private static int cell(double value) {
    double floor = Math.floor(value);
    if (!Double.isFinite(value) || floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Bookmark coordinates must fit signed 32-bit cells");
    return (int) floor;
  }

  private static void check(Tab tab, Coordinates coordinates) {
    Objects.requireNonNull(coordinates, "Coordinates required");
    if (tab.threeDimensional() != (coordinates.y() != null))
      throw new IllegalArgumentException("Bookmark coordinates must match the owning tab");
  }

  private static Bookmark require(ProjectData project, Tab tab, UUID uuid) {
    var bookmark = find(project, tab, uuid);
    if (bookmark == null) throw new IllegalStateException("Bookmark no longer exists in this tab");
    return bookmark;
  }

  public static void update(ProjectData project, Tab tab, UUID uuid, Coordinates coordinates) {
    check(tab, coordinates);
    var bookmark = require(project, tab, uuid);
    bookmark.x = coordinates.x();
    bookmark.y = coordinates.y();
    bookmark.z = coordinates.z();
  }

  public static void delete(ProjectData project, Tab tab, UUID uuid) {
    var bookmark = require(project, tab, uuid);
    list(project, tab).remove(bookmark);
  }

  /** Owned Add/Rename draft. Unrelated project edits are merged; changed targets are rejected. */
  public static final class Draft {
    private final UUID projectUuid, worldUuid, uuid;
    private final Tab tab;
    private final JsonNode original;
    private String name;
    private Coordinates coordinates;

    private Draft(ProjectData project, Tab tab, Bookmark bookmark, Coordinates coordinates) {
      projectUuid = project.project.uuid;
      worldUuid = project.worldGenerator.uuid;
      this.tab = Objects.requireNonNull(tab);
      uuid = bookmark == null ? UUID.randomUUID() : bookmark.uuid;
      original = bookmark == null ? null : ProjectIO.JSON.valueToTree(bookmark);
      name = bookmark == null ? "" : bookmark.name;
      check(tab, coordinates);
      this.coordinates = coordinates;
    }

    public static Draft add(ProjectData project, Tab tab, Coordinates coordinates) {
      return new Draft(project, tab, null, coordinates);
    }

    public static Draft rename(ProjectData project, Tab tab, UUID uuid) {
      var bookmark = require(project, tab, uuid);
      return new Draft(project, tab, bookmark, BookmarkTransactions.coordinates(bookmark));
    }

    public Tab tab() { return tab; }
    public UUID uuid() { return uuid; }
    public boolean adding() { return original == null; }
    public String name() { return name; }
    public Coordinates coordinates() { return coordinates; }
    public void name(String value) { name = value; }
    public void coordinates(Coordinates value) {
      if (!adding()) throw new IllegalStateException("Rename changes only the name");
      check(tab, value);
      coordinates = value;
    }

    public void checkCurrent(ProjectData project) {
      if (!projectUuid.equals(project.project.uuid) || !worldUuid.equals(project.worldGenerator.uuid))
        throw new IllegalStateException("Bookmark draft belongs to another project");
      if (name == null || name.isBlank()) throw new IllegalArgumentException("Bookmark name required");
      check(tab, coordinates);
      if (adding()) {
        for (Tab scope : Tab.values())
          if (find(project, scope, uuid) != null)
            throw new IllegalStateException("Bookmark draft has already been applied");

      } else {
        var bookmark = require(project, tab, uuid);
        if (!original.equals(ProjectIO.JSON.valueToTree(bookmark)))
          throw new IllegalStateException("Bookmark changed; reopen Rename");
      }
    }

    public void applyTo(ProjectData project) {
      checkCurrent(project);
      if (adding()) {
        var bookmark = new Bookmark();
        bookmark.uuid = uuid;
        bookmark.name = name;
        bookmark.x = coordinates.x();
        bookmark.y = coordinates.y();
        bookmark.z = coordinates.z();
        list(project, tab).add(bookmark);
      } else require(project, tab, uuid).name = name;
    }
  }
}
