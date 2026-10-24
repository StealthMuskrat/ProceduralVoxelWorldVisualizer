package io.voxelcreator.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Editable authoring data. UUIDs carry references; array order carries execution order. */
public final class ProjectData {
  public int formatVersion = 2;
  public Project project = new Project();
  public List<VoxelType> voxelTypes = new ArrayList<>();
  public List<Membership> voxelGroups = new ArrayList<>(), voxelSets = new ArrayList<>();
  public List<Noise> noise2D = new ArrayList<>(), noise3D = new ArrayList<>();
  public World worldGenerator = new World();
  public List<Folder> folders = new ArrayList<>();
  public Bookmarks bookmarks = new Bookmarks();

  public static class Identity {
    public UUID uuid = UUID.randomUUID();
    public String id = "";
  }

  public static final class Project {
    public UUID uuid = UUID.randomUUID();
    public long seed = 0;
    public int minY = -64, maxY = 319;
  }

  public static final class Color {
    public int r = 200, g = 200, b = 200;

    public Color() {}

    public Color(int r, int g, int b) {
      this.r = r;
      this.g = g;
      this.b = b;
    }
  }

  public static final class VoxelType extends Identity {
    public Color color = new Color();
  }

  public static final class Membership extends Identity {
    public List<UUID> voxelTypeUuids = new ArrayList<>();
  }

  public static final class Folder extends Identity {
    public String rootKind = "noise2D";
    public UUID parentFolderUuid;
    public List<UUID> childOrder = new ArrayList<>();
  }

  public static final class Algorithm {
    public String key = "OPEN_SIMPLEX_2";
    public Map<String, Double> parameters = new LinkedHashMap<>(Map.of("frequency", 0.01));
  }

  public static final class Noise extends Identity {
    public int dimension = 2;
    public UUID parentFolderUuid;
    public Algorithm algorithm = new Algorithm();
    public long seedOffset = 0;
    public List<Item> coordinateTransforms = new ArrayList<>(), processing = new ArrayList<>();
    public List<Parameter> sharedParameters = new ArrayList<>();
    public List<Pin> pinnedControls = new ArrayList<>();
    public Binding outputBinding = Binding.source();
  }

  public static final class Item extends Identity {
    public String kind = "ADD", resultType = "Scalar";
    public boolean enabled = true;
    public Map<String, JsonNode> parameters = new LinkedHashMap<>();
    public Map<String, Binding> inputs = new LinkedHashMap<>();
  }

  public static final class Parameter extends Identity {
    public String type = "Scalar";
    public JsonNode value;
  }

  public static final class Pin {
    public UUID targetUuid;
    public String label = "", group = "";
  }

  public static final class Binding {
    public String sourceKind = "Source Result", type = "Scalar", ambientKey;
    public JsonNode value;
    public UUID parameterUuid, itemUuid, noiseGeneratorUuid;
    public String projection = "XZ";
    public double slice = 0;

    public static Binding source() {
      return new Binding();
    }

    public static Binding constant(double x) {
      var b = new Binding();
      b.sourceKind = "Constant";
      b.value = com.fasterxml.jackson.databind.node.DoubleNode.valueOf(x);
      return b;
    }

    public static Binding earlier(UUID id) {
      var b = new Binding();
      b.sourceKind = "Earlier Result";
      b.itemUuid = id;
      return b;
    }
  }

  public static final class World {
    public UUID uuid = UUID.randomUUID();
    public List<Layer> layers = new ArrayList<>();
  }

  public static final class Layer extends Identity {
    public boolean enabled = true;
    public Settings defaults = new Settings();
    public List<Operation> operations = new ArrayList<>();
  }

  public static final class Operation extends Identity {
    public boolean enabled = true;
    public UUID noiseGeneratorUuid;
    public String interpretation = "HEIGHT_BELOW";
    public Map<String, JsonNode> overrides = new LinkedHashMap<>();
  }

  public static final class Settings {
    public double baseHeight = 0, heightScale = 64, threshold = 0, strength = 1, probability = 1;
    public int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;
    public double scaleX = 1, scaleY = 1, scaleZ = 1, offsetX = 0, offsetY = 0, offsetZ = 0;
    public long seedOffset = 0;
    public String action = "NO_CHANGE", rounding = "FLOOR";
    public UUID voxelTypeUuid;
    public Predicate voxelStatePredicate = new Predicate();
    public Gate fieldGates = new Gate();
  }

  public static final class Predicate {
    public String kind = "ANY";
    public UUID reference;
    public List<Predicate> children = new ArrayList<>();
  }

  public static final class Gate {
    public String kind = "ALWAYS", comparison = "GE";
    public double threshold = 0, max = 1;
    public UUID noiseGeneratorUuid;
    public List<Gate> children = new ArrayList<>();
  }

  public static final class Bookmarks {
    public List<Bookmark> noise2D = new ArrayList<>(),
        noise3D = new ArrayList<>(),
        world = new ArrayList<>();
  }

  public static final class Bookmark {
    public UUID uuid = UUID.randomUUID();
    public String name = "";
    public int x, z;
    public Integer y;
  }

  public static final class Curve {
    public UUID uuid = UUID.randomUUID();
    public double min = 0, max = 1;
    public String outsideDomain = "CLAMP";
    public List<Point> points = new ArrayList<>(List.of(new Point(0, 0), new Point(1, 1)));
  }

  public static final class Point {
    public UUID uuid = UUID.randomUUID();
    public double x, y, inTangent = 0, outTangent = 0;
    public String segmentInterpolation = "LINEAR", handleMode = "AUTO";

    public Point() {}

    public Point(double x, double y) {
      this.x = x;
      this.y = y;
    }
  }
}
