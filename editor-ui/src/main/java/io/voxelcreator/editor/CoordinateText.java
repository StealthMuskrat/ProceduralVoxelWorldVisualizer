package io.voxelcreator.editor;

import io.voxelcreator.model.BookmarkTransactions.*;
import java.util.*;

/** Complete raw coordinates are parsed together; invalid input never falls back to an old cell. */
final class CoordinateText {
  static final int MAX_CHARACTERS = 128;
  private final List<String> axes;
  private final Map<String, String> text = new LinkedHashMap<>();

  CoordinateText(Tab tab, Coordinates coordinates) {
    if (tab.threeDimensional() != (coordinates.y() != null))
      throw new IllegalArgumentException("Coordinates must match the owning tab");
    axes = tab.threeDimensional() ? List.of("X", "Y", "Z") : List.of("X", "Z");
    text.put("X", Integer.toString(coordinates.x()));
    if (coordinates.y() != null) text.put("Y", Integer.toString(coordinates.y()));
    text.put("Z", Integer.toString(coordinates.z()));
  }

  List<String> axes() {
    return axes;
  }

  String text(String axis) {
    if (!text.containsKey(axis))
      throw new IllegalArgumentException("Unknown coordinate axis " + axis);
    return text.get(axis);
  }

  void edit(String axis, String value) {
    text(axis);
    if (Objects.requireNonNull(value).length() > MAX_CHARACTERS)
      throw new IllegalArgumentException("Coordinate text limit " + MAX_CHARACTERS);
    text.put(axis, value);
  }

  Coordinates parse() {
    var values = new HashMap<String, Integer>();
    for (var axis : axes) {
      String raw = text.get(axis);
      try {
        if (!raw.matches("[+-]?[0-9]+")) throw new NumberFormatException();
        values.put(axis, Integer.parseInt(raw));
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException(axis + " requires a signed 32-bit decimal integer");
      }
    }
    return new Coordinates(values.get("X"), values.get("Y"), values.get("Z"));
  }

  Coordinates parse(Map<String, String> displayed) {
    if (!displayed.keySet().equals(text.keySet()))
      throw new IllegalStateException("All coordinate fields for the owning tab are required");
    for (var axis : axes)
      if (!Objects.equals(text.get(axis), displayed.get(axis)))
        throw new IllegalStateException(
            "Displayed " + axis + " differs from the private coordinate input");
    return parse();
  }
}
