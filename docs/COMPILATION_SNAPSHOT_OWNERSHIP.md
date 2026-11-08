# Compiled snapshot admission

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, independent consumer, checker, packaging operation or workflow ran. The last observed baseline remains **922 tests / 120 suites**. No A01–A36, Windows 11 or release gate advances.

## Validate the definition that is retained

CompiledProject previously validated the caller's editable ProjectData and then captured a separate serialized copy. NoiseCompiler validation itself uses a temporary copy for field compilation. Neither that temporary copy nor earlier structural validation proves that the later retained definition has valid IDs, UUIDs, bindings and enabled algorithms. Mutable caller data or a custom collection serializer can produce a different candidate at the later capture. VoxelRegistry construction alone does not validate the complete project.

All public compilation routes now share a private preparation boundary:

1. Run the existing full caller validation first. A rejected candidate returns its diagnostics without an additional capture or traversal.
2. Capture the candidate that will belong to CompiledProject.
3. Run full structural and noise validation against that owned candidate.
4. Adopt that exact candidate without another caller capture. An opaque package-private preparation value exposes diagnostics to ProjectCompiler but no editable model accessor.

compileWorld returns a failed CompileResult with immutable owned-candidate diagnostics when the retained candidate is invalid. The public constructor and selected-noise convenience compilers reject with IllegalArgumentException. Existing failures during serialization retain their propagation; this does not introduce a universal diagnostic wrapper. Successful compiled snapshots, sampler plans and chunks retain their existing isolation from later caller edits.

Caller validation deliberately remains first. In particular, malformed collection traversal still produces its existing one-pass INVALID_STRUCTURE diagnostic without enrichment retry or copying the failed object; see [validation-result ownership](VALIDATION_RESULT_OWNERSHIP.md). A caller rejected by initial validation cannot use a repaired serialized representation to bypass that rejection. Host serialization/collection implementations remain cooperative. This is not an atomic capture guarantee for concurrently edited inputs, callback preemption or rollback of mutations deliberately performed by a host callback.

NoiseCompiler's existing temporary copy is separate from the retained capture. A healthy invocation serializes caller data during initial noise validation and again during retained capture; subsequent validation copies the already-owned ordinary model. The direct constructor and selected-noise routes gain a full owned validation pass. compileWorld keeps two full validation passes, moving the second from the live caller to the owned candidate. Performance measurement remains deferred; this change does not claim cheaper compilation or eager retention of every executable plan.

Public method/component signatures, engine/editor dependency separation, project/schema/registry/archive formats, seed handling and field/voxel arithmetic remain unchanged. The broader W05 compilation, scheduling, caching and ownership audit remains open.

## Deferred regression definitions

Five CompilationSnapshotTest definitions cover all three public entry routes in both dimensions with invalid captured IDs, algorithms, UUID collisions and bindings; immutable rejection diagnostics; valid captured values retained after edits; disabled incomplete expressions remaining legal until enabled; invalid original IDs rejecting despite a repaired serialized representation; and one-traversal malformed caller rejection without capture. The custom collection serializer emits the original noise for the initial validation copy and the alternate noise for retained capture. No global mapper configuration or concurrent timing is involved.

CompilationSnapshotConsumer is a separate engine-JAR-only definition invoked by the existing ProjectParseConsumer command. Two dimensions, three entry routes and four invalid captured changes define **24 rejected cases**. Six valid cases sample independently specified `.25 + x + 2*z` or `.25 + x + 2*z + 4*y` fields before and after mutating caller/capture data: **162 raw-bit field observations**. The four world-capable accepted cases use roots 2/4 and retain **8 chunks / 288 exact EMPTY-cell expectations** after context/engine closure. The future report path is `project-parse/compilation-snapshots/compilation-snapshot-report.json`; no report has been generated.

Existing manual-only Linux/Windows commands compile all example sources and invoke ProjectParseConsumer. No workflow trigger or command change is needed. Prior parse, prepared-open, dependency, transfer, folder, malformed-validation and diagnostic-context replay dimensions remain unchanged. Source review and git whitespace/content/metadata checks are the available verification.
