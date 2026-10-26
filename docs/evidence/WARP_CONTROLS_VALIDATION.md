# Shared warp controls and field validation

Validated implementation: `e38af2e8b493717e61619f579bbc0790d1148d76`, [Actions run 40333759461](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40333759461). Pinned toolchain: Temurin 25.0.4+7 and Gradle 9.2.1 on Linux Ubuntu 24.04 and Windows Server 2025.

Raw Linux JUnit XML independently counts **633 tests / 76 suites / 0 failures / 0 errors / 0 skipped**. The batch adds 27 core and 7 editor regressions. Both platforms pass the required `clean test build dependencyNotices releaseJars` command. Local schema validation, reproducible Python vectors and whitespace checks pass. The local Java launcher lacks its shared launcher library; Java/native evidence comes from pinned CI.

## Independent and native evidence

The Python arithmetic oracle defines **432 vectors**, covering 2D/3D, every independent/progressive/sequential mode, all active axis orders, 1/2/3 octaves, signed/zero amplitudes and two frequencies. JUnit and an external engine-JAR-only consumer each replay every vector through both compiler stages: **864 coordinate/post-source cases** per consumer. Linux regenerates and byte-compares the checked-in corpus. Expectations use independently implemented arithmetic and affine displacement fields, rather than copying Java output.

`WarpControlsProbe` uses real native mouse/text input in production Properties controls to edit amplitude to **2**, select **SEQUENTIAL**, and select the **CONSTANT** canonical algorithm. It verifies supported source controls change from fractal to constant value while explicit component fields retain authority. With dx=z, dz=x, target x+10z and incoming (2,3), the original independent unit-amplitude result is **55**. Sequential XZ at amplitude 2 yields x=8,z=19 and **198**. Edits remain private until one Apply; one Undo and Redo restore 55 and 198. The probe visits coordinate and processing warp panels in both 2D and 3D, and rejects zero octaves with the exact `warpOctaves` key without changing the committed project or losing the invalid draft.

- [Native report](warp-native-reference.json)
- [Saved committed project](warp-project.json)
- [Engine-only replay report](warp-engine-reference.json)
- [Raw JUnit summary](warp-junit-summary.json)

The independent Python verifier validates the saved native project against the generated structural schema, recomputes 198, checks actual referenced component identities and rejects malformed enums, octave counts, source value types and coordinate-stage source fields. The engine-only consumer also loads the original portable fixture (55) separately and verifies both 3D source variants produce **403** at (2,-1,3).

## Regression coverage and whole-project validation

Core checks additionally cover all ten canonical algorithms and historical component offsets; legacy numeric bindings; resampled upstream prefixes; cross-dimensional projection/slice; stage restrictions; self/disabled/cyclic references; conflicting value/field components; 2D Y restrictions; exact control keys; supported/inactive source controls; amplitude/gain laziness; seed increment and signed-zero seed offsets; coordinate/arithmetic overflow; nested sampled and numeric-reference work limits; immutable snapshots; concurrent sampling; and unchanged passive ambient Y for historical 2D target fields. Numeric generator-reference work is counted per octave rather than assuming hidden successful-value sharing.

Editor checks cover source/creation signatures and numeric enum persistence, future-only presets, 2D axis-order projection for newly created items, invalid profiles, conditional controls, dependency-aware item deletion, private Apply/Undo/rejection and owned export dependency closure. These tests exercise the shared editor/compiler model rather than an alternate evaluation path.

Both platform jobs also pass all existing C++/analytic/seed source vectors, exact voxel hashes, native authoring/trace/resource workflows, volume and streaming references (OpenGL 4.5 and strict 3.0), Windows runtime packaging and packaged-launcher smoke. Cross-platform JAR byte hashes and all 20 pinned source/dimension raw-bit digests match. Generated schemas and registries are compared semantically to canonical Java output and retain generated formatting.

Implementation preceded automated/native validation. The first complete platform run passed 632 tests; final review then tightened conservative admission for repeated numeric generator references and added its regression before the final 633-test run.

## Compatibility and limits

Historical default algorithm, one independent octave, frequency, amplitude, IEEE-bit seed-offset interpretation and numeric stage-value semantics are retained. Post-source 2D targets keep their unmodified incoming ambient Y; coordinate stages retain Y=0 publication. Invalid/ambiguous new controls, conflicting components and unsupported WARP_SAMPLE inputs reject precisely. No runtime normalization, clamping or profile fallback was introduced. Processing registry is version 5 and warp registry version 1; project format remains 2.

This is scoped functional and platform evidence. Complete GUI/DPI interaction acceptance, representative hardware measurements, Windows 11 hardware sign-off and a certified JVM/CPU matrix remain outstanding. No owner licensing/redistribution grant is invented.
