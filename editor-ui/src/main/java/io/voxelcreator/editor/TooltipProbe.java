package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred fractional font/tooltip replacement and actual native hover/drag input, without GL. */
public final class TooltipProbe {
  private static EditorState state;
  private static UiDpiPolicy.Frame dpi;
  private static int width = 1600, height = 1200;
  private static String mode = "text";
  private static final String SOURCE = "100% ### snow 雪 😀\n" + "full hover guidance ".repeat(1000);
  private static final List<UiTooltip.Receipt> receipts = new ArrayList<>();
  private static float[] center;
  private static boolean twice = true;
  private static float rootFont, nestedFont, afterFont, dragFont, nativeFont;
  private static DialogBounds.Rect itemBefore, itemAfter;
  private static final float[] rgb = {.2f, .4f, .6f}, rgba = {.2f, .4f, .6f, .8f};

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      var atlas = UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      UiTooltip.observe(receipts::add);
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var noise = new Noise();
                  noise.id = "committed";
                  noise.algorithm.key = "CONSTANT";
                  noise.algorithm.parameters.clear();
                  noise.algorithm.parameters.put("value", .125);
                  p.noise2D.add(noise);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var values = ProjectIO.JSON.valueToTree(draft);
        var engine = state.engine;
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var cases = new ArrayList<Map<String, Object>>();
        float[][] factors = {
          {1, 1, 1, 1},
          {2, 1, 1.25f, 1.5f},
          {2, 2, 1.25f, 1.5f},
          {3, 1, 1, 1},
          {2, 1, 2.5f, 2.5f},
          {1, 1, 1.125f, 1.25f}
        };
        for (int i = 0; i < factors.length; i++) {
          var f = factors[i];
          dpi = new UiDpiPolicy.Frame(f[0], f[1], f[2]);
          width = i == 5 ? 360 : 1600;
          height = i == 5 ? 640 : 1200;
          state.user.appearance.globalScale = f[2];
          state.user.appearance.windowScales.put("Resources", f[3]);
          var user = ProjectIO.JSON.valueToTree(state.user);
          for (String form : List.of("text", "button", "edit3", "edit4", "drag")) {
            mode = form;
            dragFont = 0;
            ImGui.getIO().addMousePosEvent(80, 150);
            frames(5);
            require(center != null, "Missing hover source");
            var io = ImGui.getIO();
            io.addMousePosEvent(center[0], center[1]);
            frames(3);
            if (form.equals("drag")) {
              io.addMouseButtonEvent(0, true);
              frame();
              io.addMousePosEvent(center[0] + 40, center[1] + 25);
              frames(4);
            }
            require(!receipts.isEmpty(), "Native hover/drag tooltip missing: " + form);
            var shown = receipts.getLast();
            float expected = 13 * f[2] * f[3] * f[0] / f[1];
            for (float font :
                new float[] {rootFont, nestedFont, afterFont, shown.parentFont(), shown.font()})
              require(
                  Math.abs(font - expected) < .001, "Fractional font inheritance failed: " + form);
            require(itemBefore.equals(itemAfter), "Tooltip replaced parent last-item geometry");
            require(
                DialogBounds.contains(shown.work(), shown.bounds()),
                "Tooltip left its current work area");
            require(
                ProjectIO.JSON.valueToTree(state.user).equals(user),
                "Tooltip changed scale preferences");
            if (form.equals("text") || form.equals("drag"))
              require(
                  shown.truncated() && shown.preview().endsWith("..."),
                  "Long tooltip was not bounded");
            if (form.equals("drag"))
              require(
                  Math.abs(dragFont - expected) < .001, "Automatic drag tooltip lost font size");
            if (form.equals("text"))
              require(
                  Math.abs(nativeFont - 13 * f[2] * f[0] / f[1]) < .001,
                  "Reused native tooltip retained an owned local font scale");
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("scenario", i);
            receipt.put("form", form);
            receipt.put("content", f[0]);
            receipt.put("framebuffer", f[1]);
            receipt.put("global", f[2]);
            receipt.put("window", f[3]);
            receipt.put("rootFont", rootFont);
            receipt.put("nestedFont", nestedFont);
            receipt.put("afterFont", afterFont);
            receipt.put("dragFont", dragFont);
            receipt.put("nativeFont", nativeFont);
            receipt.put("itemBefore", itemBefore);
            receipt.put("itemAfter", itemAfter);
            receipt.put("tooltip", shown);
            cases.add(receipt);
            io.addMouseButtonEvent(0, false);
            frames(3);
          }
        }
        dpi = new UiDpiPolicy.Frame(1, 1, 1);
        width = 1600;
        height = 1200;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.clear();
        mode = "text";
        ImGui.getIO().addMousePosEvent(center[0], center[1]);
        frames(3);
        ImGui.getIO().addMousePosEvent(center[0], center[1]);
        frames(3);
        twice = false;
        frames(4);
        int singleVertices = ImGui.getDrawData().getTotalVtxCount();
        twice = true;
        frames(4);
        int replacedVertices = ImGui.getDrawData().getTotalVtxCount();
        require(
            singleVertices > 0 && replacedVertices == singleVertices,
            "Replaced tooltip remained in rendered draw data");
        require(
            Arrays.equals(rgb, new float[] {.2f, .4f, .6f})
                && Arrays.equals(rgba, new float[] {.2f, .4f, .6f, .8f}),
            "Hover mutated native color data");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && values.equals(ProjectIO.JSON.valueToTree(draft))
                && state.draft == draft
                && state.engine == engine
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && "-".equals(state.seedInputs.text("seedOffset"))
                && engine.profiler().current().isEmpty(),
            "Tooltip input changed authoring/runtime state");
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("dpiSource", "synthetic");
        report.put("atlas", atlas);
        report.put("cases", cases);
        report.put("singleVertices", singleVertices);
        report.put("replacementVertices", replacedVertices);
        for (String flag :
            List.of(
                "projectPreserved",
                "parentItemPreserved",
                "fractionalMetrics",
                "nativeHover",
                "nativeDrag",
                "colorHoverPreserved",
                "nativeSlotRestored")) report.put(flag, true);
        for (String flag :
            List.of(
                "physicalDpiVerified",
                "physicalDetachedVerified",
                "gpuTextureVerified",
                "multilineInternalsVerified",
                "colorPickerInternalsVerified")) report.put(flag, false);
        Files.writeString(
            out.resolve("tooltip-native.json"), ProjectIO.JSON.writeValueAsString(report));
        System.out.println(
            "TOOLTIP_NATIVE_PASS fractional inherited font / replacement / bounded preview / native"
                + " hover and drag");
      }
    } finally {
      UiTooltip.observe(null);
      UiDpi.clear();
      ImGui.destroyContext();
    }
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    receipts.clear();
    nativeFont = 0;
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setDisplayFramebufferScale(dpi.framebufferScale(), dpi.framebufferScale());
    io.setFontGlobalScale(dpi.fontGlobalScale());
    UiDpi.begin(dpi);
    try {
      ImGui.newFrame();
      ImGui.setNextWindowPos(8, 8, ImGuiCond.Always);
      ImGui.setNextWindowSize(width - 16, height - 16, ImGuiCond.Always);
      ImGui.begin("Tooltip fixture");
      UiScale.window(state.user, "Resources");
      rootFont = ImGui.getTextLineHeight();
      for (int i = 0; i < 3; i++) UiScale.beginChild("tooltip-level-" + i, 0, 0, false);
      nestedFont = ImGui.getTextLineHeight();
      if (mode.equals("button"))
        UiScale.colorButton("##color-button", new ImVec4(.2f, .4f, .6f, .8f));
      else if (mode.equals("edit3")) UiScale.colorEdit3("##color-edit", rgb);
      else if (mode.equals("edit4"))
        UiScale.colorEdit4(
            "##color-edit", rgba, ImGuiColorEditFlags.NoInputs | ImGuiColorEditFlags.NoLabel);
      else ImGui.button(mode.equals("drag") ? "Drag" : "Hover");
      itemBefore = PopupLayout.itemBounds();
      center =
          new float[] {
            itemBefore.x() + itemBefore.width() / 2, itemBefore.y() + itemBefore.height() / 2
          };
      if (mode.equals("text") && ImGui.isItemHovered()) {
        if (twice) UiScale.tooltip("First tooltip must be replaced");
        UiScale.tooltip(SOURCE);
        ImGui.beginTooltip();
        try {
          nativeFont = ImGui.getTextLineHeight();
        } finally {
          ImGui.endTooltip();
        }
      }
      if (mode.equals("drag") && UiScale.beginDragDropSource()) {
        try {
          dragFont = ImGui.getTextLineHeight();
          ImGui.setDragDropPayload("tooltip-fixture", "payload");
          ImGui.textUnformatted("Drag preview");
          UiScale.tooltip(SOURCE);
        } finally {
          UiScale.endDragDropSource();
        }
      }
      itemAfter = PopupLayout.itemBounds();
      afterFont = ImGui.getTextLineHeight();
      for (int i = 0; i < 3; i++) ImGui.endChild();
      ImGui.end();
      ImGui.render();
    } finally {
      UiDpi.clear();
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
