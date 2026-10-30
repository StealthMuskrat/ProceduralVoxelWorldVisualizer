package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred production forms, native footer input and exact transaction/private staging receipts.
 */
public final class ResourceFormsLayoutProbe {
  private static EditorState state;
  private static ResourcePanel resources;
  private static NoiseEditDialog noise;
  private static Path out;
  private static String form = "Create";
  private static int width = 1200, height = 900, callbacks;
  private static UUID field, layer, used, replacement, placement;
  private static boolean noiseDone;

  public static void main(String[] args) throws Exception {
    out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(ResourceFormsLayoutProbe::fixture), "Form fixture rejected");
        field = state.project().noise2D.getFirst().uuid;
        placement = state.project().noise2D.get(1).uuid;
        layer = state.project().worldGenerator.layers.getFirst().uuid;
        used = state.project().voxelTypes.getFirst().uuid;
        replacement = state.project().voxelTypes.get(1).uuid;
        resources = new ResourcePanel(state, new PropertyPanel(state));
        privateInput();
        var draft = state.draft;
        var draftBefore = ProjectIO.JSON.valueToTree(draft);
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var engine = state.engine;
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        for (int i = 0; i < sizes.length; i++) {
          dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
          for (String mode : List.of("Create", "Placement", "Delete")) {
            form = mode;
            openForm(true);
            frames(8);
            cases.add(receipt(i, globals[i], scales[i]));
            if (i == 0 && !form.equals("Create"))
              require(
                  resources.probeFormSubmitted(form) > 0
                      && resources.probeFormSubmitted(form) < (form.equals("Delete") ? 512 : 257),
                  "Dense form rows were not clipped");
            click(control("cancel"));
            require(!pending(), "Form Cancel retained an owner");
          }
          form = "Noise";
          openNoise(true, false);
          frames(8);
          cases.add(receipt(i, globals[i], scales[i]));
          noise.choose(Resolution.REJECT, null);
          click(noise.control("issues"));
          cases.add(receipt(i, globals[i], scales[i]));
          click(control("cancel"));
          require(noiseDone, "Noise Cancel retained a dialog");
        }
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && draft == state.draft
                && draftBefore.equals(ProjectIO.JSON.valueToTree(state.draft))
                && revision == state.revision
                && history == state.document.historyBytes()
                && state.seedInputs.text("seedOffset").equals("-"),
            "Layout browsing/cancellation changed private or committed state");
        dimensions(1200, 900, 1, 1);
        for (String mode : List.of("Create", "Placement", "Delete", "Noise")) {
          form = mode;
          if (form.equals("Noise")) openNoise(true, false);
          else openForm(true);
          frames(5);
          releaseWithEscape(control(form.equals("Noise") ? "apply" : "primary"));
          require(
              !pending()
                  && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                  && draftBefore.equals(ProjectIO.JSON.valueToTree(state.draft))
                  && callbacks == 0,
              "Escape lost to form submission");
          if (form.equals("Noise")) openNoise(true, false);
          else openForm(true);
          frames(5);
          String old = id();
          press(control(form.equals("Noise") ? "apply" : "primary"));
          if (form.equals("Noise")) {
            noise.probeCancel();
            openNoise(true, false);
          } else {
            resources.reset();
            openForm(true);
          }
          frame();
          release();
          frames(4);
          require(
              pending()
                  && !old.equals(id())
                  && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                  && draftBefore.equals(ProjectIO.JSON.valueToTree(state.draft)),
              "Held predecessor submitted a successor");
          click(control("cancel"));
        }
        for (String mode : List.of("Create", "Placement", "Delete")) {
          form = mode;
          openForm(true);
          frames(5);
          state.revision++;
          click(control("primary"));
          require(
              pending()
                  && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                  && !resources.probeEditPending(),
              "Stale project form submitted");
          click(control("cancel"));
          state.revision--;
        }
        form = "Noise";
        openNoise(true, false);
        frames(5);
        ((Noise) state.draft).id = "changed-private";
        var changed = ProjectIO.JSON.valueToTree(state.draft);
        click(control("apply"));
        require(
            pending()
                && changed.equals(ProjectIO.JSON.valueToTree(state.draft))
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Stale noise stage published");
        click(control("cancel"));

        // Explicit fixture refresh releases private input before ordinary browser publication.
        state.select("noise2D", field);
        save("before");
        form = "Create";
        openForm(false);
        resources.probeFormName("");
        frames(5);
        click(control("primary"));
        require(
            pending() && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Rejected ID closed or published");
        click(center(resources.probeCreateInput()));
        ImGui.getIO().addInputCharactersUTF8("created-field");
        frames(4);
        click(control("primary"));
        require(
            !pending() && !resources.probeEditPending() && state.project().noise2D.size() == 258,
            "Corrected Create did not publish");
        recordStep("created", before);
        var created = ProjectIO.JSON.valueToTree(state.project());
        form = "Placement";
        openForm(true);
        frames(5);
        click(control("primary"));
        require(
            !pending() && state.project().worldGenerator.layers.getFirst().operations.size() == 1,
            "Placement did not publish");
        recordStep("placed", created);
        var placed = ProjectIO.JSON.valueToTree(state.project());
        form = "Delete";
        openForm(false);
        frames(5);
        var picker = resources.probeDeletePicker();
        click(picker.buttonCenter());
        ImGui.getIO().addInputCharactersUTF8("replacement");
        frames(4);
        require(
            picker.submitted() == 1,
            "Replacement picker did not filter exact compatible candidates");
        click(picker.option(replacement.toString()));
        click(control("primary"));
        require(
            !pending()
                && state.project().voxelTypes.size() == 1
                && state.project().voxelGroups.stream()
                    .allMatch(g -> g.voxelTypeUuids.equals(List.of(replacement))),
            "Reference replacement was not atomic");
        recordStep("deleted", placed);
        var deleted = ProjectIO.JSON.valueToTree(state.project());
        state.select("noise2D", field);
        form = "Noise";
        openNoise(true, true);
        frames(5);
        click(control("apply"));
        frames(4);
        require(
            noiseDone
                && callbacks == 1
                && ((Noise) state.draft).sharedParameters.isEmpty()
                && deleted.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Noise resolution did not stage privately once");
        require(
            state.events.snapshot().stream()
                    .filter(e -> e.code().equals("NOISE_EDIT_FOCUS_FAILED"))
                    .mapToLong(EditorEvents.Entry::count)
                    .sum()
                == 1,
            "Noise refresh failure repeated");
        require(state.applyDraft(), "Parent Apply rejected resolved noise");
        recordStep("resolved", deleted);
        require(
            state.engine == engine && state.engine.profiler().current().isEmpty(),
            "Resource form changed runtime ownership");
        Files.writeString(
            out.resolve("resource-forms-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("cancelBeforeMutation", true),
                    Map.entry("heldRequestsScoped", true),
                    Map.entry("staleOwnersBlocked", true),
                    Map.entry("rejectedCreateRetained", true),
                    Map.entry("replacementFiltered", true),
                    Map.entry("privateNoiseStage", true),
                    Map.entry("oneUndoRedoPerEdit", true),
                    Map.entry("callbacks", callbacks),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "RESOURCE_FORMS_LAYOUT_NATIVE_PASS persistent resource/noise forms / stale and held"
                + " requests / exact four edits with Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    var n = constant("field", 1);
    var parameter = new Parameter();
    parameter.id = "gain";
    parameter.value = ProjectIO.JSON.valueToTree(2.);
    n.sharedParameters.add(parameter);
    var add = ProcessingRegistry.create("ADD", "add");
    var binding = Binding.constant(0);
    binding.sourceKind = "Shared Parameter";
    binding.parameterUuid = parameter.uuid;
    add.inputs.put("b", binding);
    n.processing.add(add);
    n.outputBinding = Binding.earlier(add.uuid);
    p.noise2D.add(n);
    for (int i = 0; i < 256; i++)
      p.noise2D.add(constant(String.format(Locale.ROOT, "bulk-%04d", i), .125));
    var l = new Layer();
    l.id = "layer";
    p.worldGenerator.layers.add(l);
    var used = new VoxelType();
    used.id = "used";
    var replacement = new VoxelType();
    replacement.id = "replacement";
    p.voxelTypes.addAll(List.of(used, replacement));
    for (int i = 0; i < 512; i++) {
      var g = new Membership();
      g.id = "group-" + i;
      g.voxelTypeUuids.add(used.uuid);
      p.voxelGroups.add(g);
    }
  }

  private static Noise constant(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private static void privateInput() {
    state.select("noise2D", field);
    ((Noise) state.draft).id = "private-field";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
  }

  private static void openForm(boolean ready) {
    resources.probeOpenForm(form, form.equals("Placement") ? layer : used);
    if (ready) {
      if (form.equals("Create")) resources.probeFormName("created-field");
      else if (form.equals("Placement")) resources.probePlacement(placement);
      else resources.probeReplacement(replacement);
    }
  }

  private static void openNoise(boolean valid, boolean fail) {
    noiseDone = false;
    var n = (Noise) state.draft;
    noise =
        new NoiseEditDialog(
            state,
            n,
            Request.simple(Action.DELETE_PARAMETER, n.sharedParameters.getFirst().uuid),
            ignored -> {
              callbacks++;
              if (fail) throw new IllegalStateException("Injected focus failure");
            });
    if (valid) noise.choose(Resolution.INLINE_PARAMETER, null);
  }

  private static boolean pending() {
    return form.equals("Noise") ? !noiseDone : resources.probeFormOpen(form);
  }

  private static String id() {
    return form.equals("Noise") ? noise.nativeId() : resources.probeFormId(form);
  }

  private static float[] control(String key) {
    return form.equals("Noise")
        ? noise.control(key)
        : center(resources.probeFormChrome(form).actions().get(key));
  }

  private static float[] center(DialogBounds.Rect box) {
    require(box != null, "Missing form control");
    return new float[] {box.x() + box.width() / 2, box.y() + box.height() / 2};
  }

  private static Map<String, Object> receipt(int scenario, float global, float scale) {
    var layout = form.equals("Noise") ? noise.layout : resources.probeFormLayout(form);
    var chrome = form.equals("Noise") ? noise.chrome : resources.probeFormChrome(form);
    var row = new LinkedHashMap<String, Object>();
    row.put("scenario", scenario);
    row.put("form", form.equals("Noise") ? noise.page() : form);
    row.put("globalScale", global);
    row.put("windowScale", scale);
    row.put("work", layout.workArea().bounds());
    row.put("modal", layout.bounds());
    row.put("body", chrome.body());
    row.put("actions", chrome.actions());
    row.put("request", id());
    return row;
  }

  private static void save(String name) throws Exception {
    new ProjectIO().save(out.resolve(name + ".json"), state.project());
  }

  private static void recordStep(String name, JsonNode before) throws Exception {
    var after = ProjectIO.JSON.valueToTree(state.project());
    save(name);
    state.document.undo();
    state.revision++;
    require(before.equals(ProjectIO.JSON.valueToTree(state.project())), "One Undo failed: " + name);
    save(name + "-undone");
    state.document.redo();
    state.revision++;
    require(after.equals(ProjectIO.JSON.valueToTree(state.project())), "One Redo failed: " + name);
    save(name + "-redone");
    state.refreshDraft();
  }

  private static void dimensions(int w, int h, float global, float scale) {
    width = w;
    height = h;
    state.user.appearance.globalScale = global;
    state.user.appearance.windowScales.put("Resources", scale);
    state.user.appearance.windowScales.put("Properties", scale);
  }

  private static void press(float[] at) {
    require(at != null, "Missing native form action");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static void click(float[] at) {
    press(at);
    release();
    frames(4);
  }

  private static void releaseWithEscape(float[] at) {
    press(at);
    var io = ImGui.getIO();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Resource forms fixture", ImGuiWindowFlags.NoSavedSettings);
    String host = form.equals("Noise") ? "Properties" : "Resources";
    UiScale.window(state.user, host);
    DialogLayout.observe(state, host);
    if (form.equals("Noise")) {
      if (noise.draw()) noiseDone = true;
    } else resources.probeDrawForms();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
