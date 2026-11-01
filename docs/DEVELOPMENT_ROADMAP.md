# Completion against the original assignment

The goal remains the complete application in [SPECIFICATION.md](SPECIFICATION.md): one Java 25 Windows desktop program, a bundled Windows runtime/launcher, a separately usable headless engine JAR, an independent Java client, source/fixtures/notices/instructions, executed acceptance evidence and a checksum-verified release ZIP. A collection of implementation notes or deferred test definitions is not the final deliverable.

The owner's later instructions govern the current work phase: implement in coherent batches and defer testing until the program is fully developed. Development stays on `codex/development-phase`; main, PR-triggered CI and manual GitHub workflows remain untouched. The specification's frequent-testing process is deferred by that instruction, while its final correctness and delivery gates remain required.

Continue source development by checking required behavior across the numbered trees and closing concrete end-to-end integration gaps. Prioritize application functionality and failure containment; group related control/gesture fixes instead of treating every isolated dialog edge case as a separate product milestone. Do not expand optional renderer features merely because their absence is listed as a limitation. Current [renderer recovery](RENDERER_RECOVERY.md) closes a specified startup/failure boundary in the real three-tab editor.

The remaining sequence is:

1. Finish the implementation audit across required noise authoring, world inheritance/actions, exact storage/API/archive, all three visualizers, project/resource workflows, utilities and settings. A source path is implementation coverage; it is not passing evidence.
2. At the final validation stage, compile the complete repository, run the prepared deterministic/native/integration checks, repair failures and update the acceptance matrix from observed results. Preserve private/project/preference separation and headless dependency isolation throughout.
3. Complete the specified Windows 11 interactive/hardware, restart/DPI, representative performance and independent-client checks. Existing Linux/Windows Server software-GL evidence must not be relabeled as those results.
4. Build and verify reproducible desktop/engine JARs and the bundled Windows launcher outside Gradle/IDE; complete dependency/owner-notice review; produce the prescribed source/binary/examples/fixtures/reports/docs layout, SHA256SUMS and named release ZIP.

The [acceptance matrix](REQUIREMENTS_AND_TESTS.md) remains authoritative for observed evidence and pending scope. The new development batches are uncompiled/untested, and the project is not being declared fully developed or release-ready here. Resume final execution only when the owner's development-phase condition is met or the owner changes it.
