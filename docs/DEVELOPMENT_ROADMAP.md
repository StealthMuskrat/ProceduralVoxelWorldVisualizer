# Completion against the original assignment

The goal remains the complete application in [SPECIFICATION.md](SPECIFICATION.md): one Java 25 Windows desktop program, a bundled Windows runtime/launcher, a separately usable headless engine JAR, an independent Java client, source/fixtures/notices/instructions, executed acceptance evidence and a checksum-verified release ZIP. A collection of implementation notes or deferred test definitions is not the final deliverable.

The owner's later instructions govern the current work phase: implement in coherent batches and defer testing until the program is fully developed. Keep the complete current project on `main`. Temporary branches may be used for development stages, then must be integrated into main and deleted. The previous development-only branch rule is superseded. GitHub workflow execution remains paused: the workflow is manual-only and must not be dispatched during the pause. The specification's frequent-testing process is deferred, while its final correctness and delivery gates remain required.

Continue source development by checking required behavior across the numbered trees and closing concrete end-to-end integration gaps. Prioritize application functionality and failure containment; group related control/gesture fixes instead of treating every isolated dialog edge case as a separate product milestone. Do not expand optional renderer features merely because their absence is listed as a limitation. Current [renderer recovery](RENDERER_RECOVERY.md) closes a specified startup/failure boundary in the real three-tab editor.

The remaining sequence is:

1. Finish the implementation audit across required noise authoring, world inheritance/actions, exact storage/API/archive, all three visualizers, project/resource workflows, utilities and settings. A source path is implementation coverage; it is not passing evidence.
2. At the final validation stage, compile the complete repository, run the prepared deterministic/native/integration checks, repair failures and update the acceptance matrix from observed results. Preserve private/project/preference separation and headless dependency isolation throughout.
3. Complete the specified Windows 11 interactive/hardware, restart/DPI, representative performance and independent-client checks. Existing Linux/Windows Server software-GL evidence must not be relabeled as those results.
4. Build and verify reproducible desktop/engine JARs and the bundled Windows launcher outside Gradle/IDE; complete dependency/owner-notice review; produce the prescribed source/binary/examples/fixtures/reports/docs layout, SHA256SUMS and named release ZIP.

The [acceptance matrix](REQUIREMENTS_AND_TESTS.md) remains authoritative for observed evidence and pending scope. The new development batches are uncompiled/untested, and the project is not being declared fully developed or release-ready here. Resume final execution only when the owner's development-phase condition is met or the owner changes it.

Implementation audit follow-up: [initial noise algorithm choice](NOISE_CREATION_CHOICES.md) closes the missing optional creation selector in 5.2.2, using the existing registry/profile/compiler path. Source implementation and deferred coverage do not advance acceptance status.

Numeric-control audit follow-up: the [viewport numeric controls](VIEWPORT_CONTROL_PANES.md) now pair all existing view numeric inputs with contextual sliders and preserve exact text entry. The broader Properties/shared numeric-control audit and five-place display versus exact editing presentation remain outstanding; this scoped implementation does not certify universal Tree 4 conformance.

The subsequent [noise authoring numeric controls](NOISE_NUMERIC_CONTROLS.md) unify source/warp/remapping rows and add paired processing, transform, shared-parameter and literal controls with exact input focus. World/curve/remaining shared numeric callers and universal five-place presentation remain in the implementation audit; these source changes and deferred definitions do not advance acceptance evidence.

The [World Properties numeric follow-up](WORLD_NUMERIC_CONTROLS.md) adds paired default/override, driver/gate/interval and sampling-transform controls, preserving sparse inheritance and exact integer input. Shared non-finite draft repair and explicit reset/live-inheritance replay remain unvalidated. Curve/remaining numeric callers and universal five-place presentation remain in the audit. Work stays on main under the owner's branch policy and manual-only testing pause.

The [Curve numeric follow-up](CURVE_NUMERIC_CONTROLS.md) adds paired curve controls with owned one-gesture local history, exact input focus, held cancellation and unchanged parent publication. Remaining shared numeric callers, universal five-place presentation and the wider required-feature integration audit remain outstanding. No deferred definitions have been executed and no acceptance status advances from these source changes.

The [application profile numeric follow-up](PROFILE_NUMERIC_CONTROLS.md) removes native display-format quantization from creation-default sliders and adds paired presentation-order editing with private-owner numeric IDs. Raw validation and explicit profile publication remain authoritative. The broader integration audit and universal five-place exact-edit presentation remain pending; main is still the only branch and all execution stays deferred.

The [Find typing debounce follow-up](FIND_TYPING_DEBOUNCE.md) implements the 4.4.1 costly-search requirement for the unified committed Find index. Typed queries in large indexes coalesce while explicit scopes and document refreshes remain immediate; pending rows cannot navigate. The wider integration audit, unmeasured index/scan cost and all final acceptance gates remain outstanding. Definitions are deferred and the owner main/manual-only policy is unchanged.

The [camera screen-geometry follow-up](CAMERA_SCREEN_GEOMETRY.md) corrects the 16.1.2 right-pan integration and removes float far-plane inversion from 3D/World picking rays while preserving rendered near clipping. Central Help, native orbit/picking definitions and independent source/engine replay follow the same behavior. This closes a concrete source mismatch; execution and final acceptance remain deferred on main under the unchanged manual-only pause.
