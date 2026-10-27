# 3D, clear and missing-source viewport recovery

Validated implementation: `4c49a82cd27d6185cd71e542f7b0160dc724d0e1`.

[Actions run 40427699246](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40427699246) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit remains 735 tests across 92 suites with zero failures, errors or skips; see [JUnit summary](viewport-recovery-junit.json). This batch expands native production-viewport coverage.

ResourcePicker exposes cached dimension/UUID availability. Viewports checks it before fixed or streaming submission. A missing selection retains its UUID for Undo, reports a missing-source status, clears picked coordinates/pan capture and hides stale hover/picking. Empty/missing sources submit no compilation job. The accepted observation remains owned internally; restoring or selecting an existing resource resumes publication.

The native GL/ImGui probe now selects a 3D generator and awaits a real Volume preview. Its picker initially shows both 3D choices rather than inheriting the 2D query; after filtering/selecting 3D, the original 2D query still returns one row. Native clicks clear the 2D selection without altering the 3D UUID; submission timestamps remain unchanged while empty. Reselecting the 2D UUID publishes another valid image. Existing budget/full-queue retries still pass.

Selector/retry controls are checked against unchanged authored JSON/revision/history before the separate deletion fixture. The fixture deletes the selected 2D resource through a document transaction, draws several frames and requires the UUID/missing status, unchanged submission timestamp and retained accepted observation. Document Undo plus the normal revision increment restores authored JSON and the same selected UUID; generation resumes. The deliberate deletion/Undo changes history state and is distinct from view-only selection.

See [raw format-3 native observations](viewport-recovery-native.json). Python checks 3D choice count, independent-filter/clear/missing/Undo flags and UUID shape alongside previous row bounds and exact 0.75 field output. VIEWPORT_SELECTION_RECOVERY_PASS identifies the added evidence. Tab selection uses the probe's tab flag; generator, filter and empty-option actions use injected native mouse/text events.

Both platform workflows run the probe with existing native/editor/rendering/input/image/engine references. This does not certify every streaming-mode removal, physical OS-window/mouse/DPI scenario, arbitrary load or Windows 11 hardware. Complete A27 and production sign-off remain separate.
