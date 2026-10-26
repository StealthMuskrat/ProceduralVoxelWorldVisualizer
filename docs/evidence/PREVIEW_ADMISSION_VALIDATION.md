# Per-view fixed preview admission validation

Validated implementation: `9c0a4368d69e9fb398d815065515f3db51eca295`.

[Actions run 40422186877](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40422186877) passed Linux, Windows and reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit contains 734 tests across 92 suites with zero failures, errors or skips; see [JUnit summary](preview-admission-junit.json). Five PreviewAdmissionTest cases cover independent rejected/eligible views, retry after budget changes, 2D versus 3D dimension/cell boundaries, exact inclusive working-byte limits across all fixed modes, retained queue rejection across polling, maximum-size long arithmetic and invalid-request rejection.

The shared fixed-preview pause flag was removed. Cell/dimension/CPU or queue rejection now belongs only to its view. A blocked view retains its reason without resubmitting every frame; marking a changed request resets its admission state. Both toolbar and context-menu Regenerate already mark that request, so neither is defeated by a leftover shared pause. Raising budgets alone does not silently retry: a new request or explicit Regenerate rearms admission.

The conservative historical byte formulas are unchanged: 64 bytes per 2D cell, 12 bytes per Volume cell, 96 bytes per Point Cloud cell and 3024 bytes per other fixed 3D cell. Tests check exact budget equality admits and one byte/cell below rejects, with a distinct 2D max-dimension exemption. Rejected previews retain their accepted display and set failed/outdated status so picking does not treat it as current. Streaming pressure controls remain separate.

The workflow also passes native editor/bundled/packaged smokes, input/geometry/cancellation checks, independent image digests and 524,288 engine-only sample comparisons. This batch does not inject a saturated real editor queue or click both retry controls under native budget failure; those manual/GUI pressure cases and representative hardware stress remain separate. Complete specification acceptance and production sign-off remain pending.
