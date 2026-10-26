# Canonical source algorithms and reference vectors

The source registry is now an immutable headless contract shared by compilation, Properties and future-item creation profiles. It declares all ten stable source IDs, 2D/3D support, parameter types/defaults, numeric ranges, named enum indices, visibility, sampling semantics and recommended creation ranges. Source formulas and seed mixing remain unchanged.

## Parameters and compatibility

`NoiseAlgorithmRegistry.parameters(key,dimension)` returns the supported dimensional signature. `validate(algorithm,dimension)` rejects unknown keys, invalid dimensions, null/nonfinite values, fractional enum/integer controls and invalid supported ranges. Source issues carry `algorithm/parameters/<key>` for exact control navigation. Source algorithm identity errors retain the algorithm chooser destination.

FastNoiseLite controls narrow to float and its noise result widens from float to double. Source frequency remains in `(0,1e6]` and retains its historical global validity check, including stored analytic-source frequency. Octaves remain 1..16; fractal and Cellular modes retain their historical numeric indices. Cellular indices retain their historical bounds on all FastNoiseLite families, even when unused. Signed finite float-compatible gain, lacunarity, ping-pong strength and jitter remain supported; no hidden clamp or 0..1 normalization is added. Parameters that would narrow to nonfinite float and negative active SDF radii/extents now reject before evaluation. Extremely large supported controls/coordinates can still fail at the existing executed nonfinite-result boundary.

Known inactive parameters remain finite/typed, stored and numerically ignored. For example switching from Perlin to White Hash does not destroy old fractal settings. Inactive parameters never become automatically baked defaults or implicit processing items. In 2D Box SDF, stored Y extent is inactive; the 3D signature validates it. New creation profiles retain their established narrower preset ranges, which are declared separately from the engine's saved-definition ranges. Existing project values never consult a current creation profile.

Project format remains 2. The source registry advances to version 2 and generated project/app schemas reflect its signatures. Existing valid ordinary source definitions retain their raw numerical behavior; previously accepted invalid active source sizes or float-narrowing overflow may now require explicit repair. Unknown/incompatible data rejects transactionally without replacing the active project.

## Properties and Application Settings

Properties renders the selected source's supported controls, with numeric slider/text pairs and searchable named enum choices. Source fractal mode NONE hides octave/lacunarity/gain; ping-pong strength appears only for PING_PONG. White Hash shows frequency only. Cellular exposes named distance and return variants. 2D sources omit Y extent. A targeted validation issue can reveal a stored inactive control for explicit legacy repair.

Numeric enum values persist as the same numeric indices. Display aliases and help are application metadata; they do not affect procedural semantics. Application Settings uses the same named options while storing integer creation-default values. Changing sliders, numeric text or enum choices edits the existing private noise draft; parent Apply is one document transaction, and invalid Apply preserves exact draft data.

## Independent references and repeatability

The repository pins the unmodified upstream FastNoiseLite 1.1.1 C++ header with its MIT notice and SHA-256. A separate C++20 harness generates 3,264 double-coordinate vectors covering all five upstream families, both dimensions, full-width seeds, signed/boundary coordinates, source fractals and all Cellular distance/return pairs. It does not call the Java engine. Compiler flags define wrapping hash arithmetic and disable contraction/fast-math. See [reference provenance](../reference/fastnoiselite/README.md).

The engine-only consumer checks each independent C++ result under the explicit cross-language policy `abs(error) <= 2e-6 + 2e-6*abs(reference)`. Exact C++ bit matches and maximum error are reported separately; numerical agreement is not a claim of cross-language bit identity. Independent Python formulas also supply 240 exact-bit analytic/White Hash vectors and 32 exact 64-bit seed-mixing vectors.

All Java results contribute raw double bits to 20 ordered per-source/dimension digests. Linux and Windows pinned-JVM digests must match, and the recorded baseline detects later drift. This evidence concerns the observed x64/JVM/OS configurations; a certified JVM/CPU matrix and full A09 hardware sign-off remain incomplete.

Testing follows the completed implementation batch: source contract/math/ownership regressions, generated-schema checks, native named-choice/numeric/Apply/Undo/invalid-draft workflows, independent consumers, existing voxel regressions, both platform packages and JAR reproducibility. Full GUI/DPI acceptance and representative throughput remain separate.
