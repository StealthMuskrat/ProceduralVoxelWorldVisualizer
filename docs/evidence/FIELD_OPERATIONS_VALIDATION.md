# Field calculus and distance-shape validation

Observed 2026-10-25 at implementation head `fcb7971354debfc1a980b83a769ba4f0336bc75c`. [Actions run 40231322715](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40231322715) completed successfully for Linux, Windows and cross-platform binary reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1.

Raw Linux JUnit XML was independently counted: **502 tests / 66 suites / 0 failures / 0 errors / 0 skipped**. The batch adds 32 core mathematical/ownership regressions and 6 editor creation/default/transaction regressions.

- `clean test build dependencyNotices releaseJars`: PASS on both platforms.
- Gradients/Laplacians: independent quadratic and affine vectors, active axis order, explicit coordinates/slices, transform-once behavior, Scalar output rejection and denominator validation: PASS.
- Min/max/median neighborhoods: independent sorted values `[13,16,21,28,31,36,49,52,57]`, exact blend endpoints, lazy zero strength/radius, nested work admission, immutable snapshots and concurrent primitive scratch: PASS.
- Plane/capsule/cylinder/torus/rounded-box primitives: exact signed-distance vectors, cap/corner/endpoint/axis/center cases, explicit Coordinates, dimension restrictions and control diagnostics: PASS. Smooth intersection/subtraction tie, support and symmetry vectors: PASS.
- `FieldOperationsProbe`: PASS on both platforms. The actual production Properties controls render **22** dimension-specific item details; 2D excludes the two 3D-only shapes and advertises Vector2 gradients. Real mouse/keyboard input changes filter strength to 0.25 in a private draft; parent Apply and one Undo/Redo produce **57 → 37.5 → 57 → 37.5**. [Raw report](field-operations-reference.json).
- `FieldOperationsConsumer`: PASS, compiled and executed with the engine artifact alone, against both the saved native project and checked-in portable fixture. Independent gradients, Laplacians and signed-distance center expectations match. [Raw values](independent-field-values.json).
- Independent Python: PASS for the full odd-neighborhood vector, exact blend arithmetic, generated schema/registry signatures, dimension membership, bounded active controls and preserved disabled experimental shapes.
- Canonical generated project/app schemas, processing/control registries and app defaults were exported from the authoritative Java model and synchronized into the repository. Final CI checks reject drift without rewriting files. Local schema-family and generated-contract checks passed after synchronization.
- Existing independent consumers, exact voxel hashes, all prior native UI references, desktop volume/slice/stage/streaming smoke, strict OpenGL 3.0 renderer probes, Windows packaging and packaged launcher: PASS.
- Linux/Windows desktop and engine JAR bytes: identical, PASS.

Validation corrected an erroneous cylinder test coordinate to its intended independent 3–4–5 corner vector and refreshed the stale Application Settings schema before the successful run. No runtime shape formula was changed in response to that test-vector error.

These scoped checks do not certify the complete Tree 6 gesture matrix, all A01–A36 items, Windows 11 hardware, representative throughput, GPU timing or production release readiness. See [field operation contracts](../FIELD_CALCULUS_AND_DISTANCE.md).
