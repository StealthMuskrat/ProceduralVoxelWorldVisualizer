# Typed-authoring batch validation

Observed 2026-10-25 at implementation head `62cfead09d17b0c93d9dcb31620b79972c851941`. Documentation/evidence was recorded after the implemented batch passed.

[Actions run 40228589687](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40228589687) completed successfully for Linux, Windows and binary reproducibility. Pinned toolchain: Temurin 25.0.4+7 and Gradle 9.2.1. Linux raw JUnit XML reports were independently counted: **464 tests / 64 suites / 0 failures / 0 errors / 0 skipped**, including all 36 new core/editor regressions.

- `clean test build dependencyNotices releaseJars`: PASS on both platforms.
- Formal schema family, portable authoring fixture and authoring registry: PASS locally and in CI.
- Existing exact voxel hashes, independent engine consumers and native desktop/render/input references: PASS.
- `NoiseAuthoringProbe`: PASS on both platforms. The explicit fixture includes 2,002 ordered processing items and 2,000 shared parameters. Actual mouse/text input selects a typed UUID; private resolution Apply/Escape Cancel, one parent Undo/Redo and guarded invalid referenced-source navigation preserve the declared ownership and arithmetic.
- The native fixture submitted **6 item rows**, **6 production Properties rows**, **11 parameter rows** and **1 filtered row**. One choice callback fired, and unchanged production drawing reused **1 metadata index**. [Raw report](noise-authoring-reference.json).
- `NoiseAuthoringConsumer`: PASS with the engine JAR only. Independent `(1+2)*4 = 12` and explicit Raw Source replacement `1*4 = 4` expectations hold; the earlier compiled snapshot remains 12 and invalid dependency reorder rejects.
- Python independently checked saved canonical bindings, disabled definitions, parameter/pin cleanup, six-type registry and authored arithmetic: PASS.
- Windows development packaging and packaged runtime/launcher smoke: PASS.
- Linux/Windows desktop and engine JAR comparison: PASS, byte-identical.

The native desktop screenshot was inspected: the source controls, ordered summary, enabled checkbox, typed filter and selected property item remain within the existing Properties layout. This scoped evidence does not certify the full Tree 6 gesture matrix, representative hardware performance, all A01-A36 items or Windows 11 hardware acceptance. See [workflow contracts](../NOISE_AUTHORING_WORKFLOWS.md).
