package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Deferred native presentation/exact-input replay using the actual shared scalar drawers. */
public final class ScalarDisplayProbe {
  private static final UiFields properties = new UiFields();
  private static final ViewportFields view = new ViewportFields();
  private static final Map<String, Double> propertyValues = initial(), viewValues = initial();
  private static final Map<String, UiFields.NumericRow> rows = new LinkedHashMap<>();
  private static int form, width = 1000, height = 900, writes;
  private static float scale = 1;

  private static Map<String, Double> initial() {
    var values = new LinkedHashMap<String, Double>();
    values.put("Small", 1e-9);
    values.put("Negative", -1e-9);
    values.put("Large", 1e10);
    values.put("Precise", Math.nextUp(1.0));
    values.put("Signed zero", -0.0);
    values.put("Invalid", Double.NaN);
    return values;
  }

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      var cases = new ArrayList<Map<String, Object>>();
      for (form = 0; form < 2; form++) {
        var before = bits(values());
        for (int scenario = 0; scenario < 3; scenario++) {
          width = scenario == 1 ? 360 : 1000;
          height = scenario == 2 ? 320 : 900;
          scale = scenario == 1 ? 1.5f : 1;
          frames(5);
          var ids = new HashSet<Integer>();
          for (var row : rows.values()) {
            require(
                ids.add(row.inputId()) && ids.add(row.sliderId()), "Aliased scalar input/slider");
            require(row.input().width() > 0 && row.slider().width() > 0, "Empty pair geometry");
            require(
                row.input().x() >= 16
                    && row.slider().x() >= 16
                    && row.input().x() + row.input().width() <= width + 16
                    && row.slider().x() + row.slider().width() <= width + 16,
                "Pair exceeded horizontal bounds");
          }
          require(
              before.equals(bits(values())) && writes == 0, "Idle readout rewrote an exact value");
          cases.add(
              Map.of(
                  "form",
                  form,
                  "scenario",
                  scenario,
                  "rows",
                  Map.copyOf(rows),
                  "bits",
                  before,
                  "labels",
                  labels(values())));
        }
      }
      width = 1000;
      height = 900;
      scale = 1;
      form = 0;
      frames(5);
      enter("Small", "-1.2345678901234567e-10");
      sameBits(Double.parseDouble("-1.2345678901234567e-10"), values().get("Small"));
      enter("Large", "-12345678901.25");
      sameBits(-12345678901.25, values().get("Large"));
      enter("Signed zero", "-0");
      sameBits(-0.0, values().get("Signed zero"));
      form = 1;
      frames(5);
      enter("Small", "1e-300");
      sameBits(1e-300, values().get("Small"));
      enter("Invalid", "0.25");
      sameBits(.25, values().get("Invalid"));
      boolean retainedPrecision = false;
      for (double fraction : new double[] {.37123456, .61345678, .82712345}) {
        click(rows.get("Small").slider(), fraction);
        double actual = values().get("Small");
        require(Double.isFinite(actual) && actual != 0, "Tiny slider collapsed to zero");
        retainedPrecision |=
            Double.doubleToRawLongBits(actual)
                != Double.doubleToRawLongBits(Double.parseDouble(ScalarDisplay.compact(actual)));
      }
      require(retainedPrecision, "Slider publication rounded to its display format");
      var finalBits = bits(values());
      int finalWrites = writes;
      frames(20);
      require(
          finalBits.equals(bits(values())) && finalWrites == writes,
          "Idle redraw rounded an edited value");
      Files.writeString(
          out.resolve("readouts.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "cases",
                  cases,
                  "properties",
                  labels(propertyValues),
                  "view",
                  labels(viewValues),
                  "writes",
                  writes)));
      System.out.println("SCALAR_DISPLAY_NATIVE_PASS");
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Map<String, Double> values() {
    return form == 0 ? propertyValues : viewValues;
  }

  private static Map<String, Long> bits(Map<String, Double> values) {
    var result = new LinkedHashMap<String, Long>();
    values.forEach((key, value) -> result.put(key, Double.doubleToRawLongBits(value)));
    return result;
  }

  private static Map<String, Object> labels(Map<String, Double> values) {
    var result = new LinkedHashMap<String, Object>();
    values.forEach(
        (key, value) ->
            result.put(
                key,
                Map.of(
                    "compact",
                    ScalarDisplay.compact(value),
                    "exact",
                    ScalarDisplay.exact(value),
                    "format",
                    ScalarDisplay.sliderFormat(value),
                    "bits",
                    Double.doubleToRawLongBits(value))));
    return result;
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(scale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    boolean open = ImGui.begin("Shared scalar readouts", ImGuiWindowFlags.NoSavedSettings);
    try {
      if (open) {
        ImGui.pushID(form);
        try {
          rows.clear();
          view.reset();
          for (var entry : values().entrySet()) {
            String key = entry.getKey();
            double value = entry.getValue();
            double extent = key.equals("Large") ? 2e10 : key.equals("Precise") ? 2 : 2e-9;
            java.util.function.DoubleConsumer update =
                next -> {
                  values().put(key, next);
                  writes++;
                };
            if (form == 0)
              rows.put(
                  key,
                  properties.number(key, value, -extent, extent, false, update, Runnable::run));
            else {
              view.number(key, value, -extent, extent, update);
              rows.put(
                  key,
                  new UiFields.NumericRow(
                      view.sliders().get(key),
                      view.bounds(key),
                      view.sliderIds().get(key),
                      view.ids().get(key),
                      view.inline().get(key),
                      -extent,
                      extent,
                      false));
            }
          }
          ViewportSampleStatus.draw(
              "sample-status",
              "Raw " + ScalarDisplay.compact(values().get("Small")),
              "Exact raw " + ScalarDisplay.exact(values().get("Small")),
              ImGui.getContentRegionAvailX(),
              ImGui.getFrameHeight());
        } finally {
          ImGui.popID();
        }
      }
    } finally {
      ImGui.end();
    }
    ImGui.render();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void click(DialogBounds.Rect rect, double fraction) {
    var io = ImGui.getIO();
    io.addMousePosEvent(rect.x() + (float) (rect.width() * fraction), rect.y() + rect.height() / 2);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void enter(String key, String text) {
    click(rows.get(key).input(), .5);
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void sameBits(double expected, double actual) {
    require(
        Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual),
        "Exact input bits changed");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
