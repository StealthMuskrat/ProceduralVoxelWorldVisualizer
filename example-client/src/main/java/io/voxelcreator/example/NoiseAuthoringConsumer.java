package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import java.nio.file.*;

/** Engine-only arithmetic and snapshot oracle for the native authoring output. */
public final class NoiseAuthoringConsumer {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the native authoring project");
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    require(loaded.success(), loaded.diagnostics().toString());
    var project = loaded.project();
    var noise = project.noise2D.getFirst();
    require(noise.id.equals("authoring"), "Draft guard changed committed ID");
    require(
        noise.processing.size() == 2002 && noise.sharedParameters.size() == 1999,
        "Authoring membership");
    require(
        noise.pinnedControls.size() == 1
            && noise.pinnedControls.getFirst().label.equals("Add settings"),
        "Canonical pin cleanup");
    var immutable = new CompiledProject(project);
    require(immutable.noise2D("authoring", 0).sample(-17, 23) == 12, "Independent (1+2)*4 oracle");
    var add = noise.processing.get(noise.processing.size() - 2);
    require(
        add.inputs.get("b").sourceKind.equals("Constant")
            && add.inputs.get("b").value.doubleValue() == 2,
        "Explicit inline resolution");
    var rejected = NoiseAuthoring.plan(project, noise, Request.simple(Action.DOWN, add.uuid));
    require(!rejected.valid(), "Invalid forward reorder accepted");
    var plan =
        NoiseAuthoring.plan(
            project, noise, new Request(Action.DELETE, add.uuid, Resolution.SOURCE, null));
    require(plan.valid(), plan.diagnostics().toString());
    plan.applyTo(noise);
    require(
        new CompiledProject(project).noise2D("authoring", 0).sample(-17, 23) == 4,
        "Independent raw-source replacement oracle");
    require(immutable.noise2D("authoring", 0).sample(-17, 23) == 12, "Compiled snapshot mutated");
    System.out.println(
        "NOISE_AUTHORING_ENGINE_ONLY_PASS private resolution / disabled preservation / exact 12 and"
            + " 4 expectations / immutable source / dependency order rejection");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
