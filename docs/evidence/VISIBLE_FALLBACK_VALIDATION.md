# Visible fixed fallback and native camera input

Validated implementation: `6f596bb52b038c3fab961be2bccf8055bcf0e080`.

[Actions run 40504693575](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40504693575) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit remains 742 tests across 93 suites with zero failures, errors or skips; see [JUnit summary](visible-fallback-junit.json). This batch adds production native GL pixel/input observations.

The probe switches the constant 0.5 source to threshold 0.25, sets a 60-block camera distance, awaits a new stream, then injects renderer startup failure. The resulting fixed 16-cube mesh is fully occupied and must produce more than 100 pixels different from the known clear color (RGBA byte tolerance six). The actual viewport texture is read with glGetTexImage; raw RGBA and vertically corrected PNG files are retained in the platform evidence artifact.

Native left-button orbit changes the texture hash while the procedural submission timestamp stays unchanged. A wheel event zooms in and must increase visible mesh coverage, again with an unchanged submission timestamp at that observation. A middle-button pan crosses the 16-block fixed sampling grid; the probe waits for another fixed preview publication with a later submission timestamp and a changed, still-visible texture. Authored JSON, revision and history size stay unchanged across camera gestures. Previous source/renderer faults, explicit recovery and lifecycle checks still run.

| Linux observation | Foreground pixels |
|---|---:|
| Before camera input | 29,030 |
| After orbit | 28,008 |
| After zoom | 45,537 |
| After pan/rebuild | 43,703 |

Python independently reads the raw files, recomputes every SHA-256 and foreground count, checks dimensions/byte lengths, distinct hashes, increasing zoom coverage and native view-only/work observations. See [raw format-7 report](visible-fallback-native.json). VISIBLE_FALLBACK_CAMERA_PASS identifies the checks. The zoom and pan PNGs were visually inspected: a visible shaded cube remains in frame on the viewport background. The PNGs/raw pixels are in build/preview-admission/ in the CI evidence artifact, not part of the engine data format.

Pixel hashes/counts are observed per platform, not required to match across different software-GL drivers. This is one solid mesh, camera path and scale under software GL/native ImGui; it does not certify physical OS-window input, actual driver faults, fallback raycast gestures, arbitrary mesh/DPI/load cases or Windows 11 hardware. Complete acceptance and production sign-off remain separate.
