# Pointer-anchored 2D navigation validation

Validated implementation: `6805884e8719bbfc924eb8e7843680aa7109354e`.

[Actions run 40414811655](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40414811655) passed Linux, Windows and reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; schemas and diff checks passed locally.

Raw Linux JUnit contains 722 tests across 90 suites with zero failures, errors or skips; see [JUnit summary](navigation-2d-junit.json). Five new NoiseImageNavigationTest cases verify pointer-world invariance across 100 anchor/wheel combinations (1e-10 absolute tolerance), explicit pan distances and center zoom, zero/saturated identity, extreme finite wheels, rejected gestures without mutation, and inverse zoom/pan within rounding tolerance.

Production 2D input uses the same immutable navigation calculation. Pan/zoom publish both coordinates and scale together after validation; invalid or overflowing input preserves the prior pose. Zoom adjusts the center by the finite old/new scale difference, avoiding construction of a huge intermediate world anchor. Step limits remain 0.0001 to 10000. Equality suppresses dirty work at zero input, saturated zoom and representationally unchanged centers. Accepted image geometry still changes only after preview publication, and navigation remains outside project history.

The workflow also passes existing native editor/bundled/packaged launcher smokes, image row/overlap/accepted-geometry checks, eight independent raw/color/histogram digests and 524,288 engine-only sample comparisons. These are regression checks; this batch adds no native wheel-event injection. Full manual pointer/drag/DPI behavior, Windows 11 hardware and complete A27 acceptance remain pending.
