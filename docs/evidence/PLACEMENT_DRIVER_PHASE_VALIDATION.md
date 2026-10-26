# Phased placement-driver validation

Validated implementation: `4788b76c53fc16ba3b780d45d0214a686b43f415`, [Actions run 40339649459](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40339649459). Pinned Temurin 25.0.4+7 / Gradle 9.2.1 jobs on Ubuntu 24.04 and Windows Server 2025 both succeeded.

Raw Linux JUnit XML independently counts **655 tests / 79 suites / 0 failures / 0 errors / 0 skipped**. This batch adds 17 API, 3 model and 2 editor regressions. The required `clean test build dependencyNotices releaseJars` command passes on both platforms. Local schemas and whitespace checks pass; the local Java launcher lacks its shared launcher library, so Java/native evidence comes from pinned CI.

## Observed workflow and independent replay

The native probe draws the production numeric-driver controls in an isolated native window. Real mouse/text input changes the first sampling driver's output maximum from 2 to **4**, with its output minimum fixed at 2. At world (0,0,0), baseline field y=0 maps to offsetX=3, moving primary x from 2 to **3**. Against threshold 2.5, the committed selection changes from **EMPTY (0)** to **solid (1)**. The edit remains private until parent Apply; one Undo and Redo restore the exact states. Removing the required coordinate-driver field rejects Apply with `MISSING_DRIVER_FIELD` while preserving the invalid draft. The committed saved file retains the valid field.

- [Native editing report](placement-driver-native.json)
- [Committed saved project](placement-driver-project.json)
- [Engine-only replay report](placement-driver-engine.json)
- [Exact generation trace](placement-driver-trace.json)
- [Raw JUnit summary](placement-driver-junit.json)

The external consumer compiles against the headless engine JAR only. For 49 signed X/Y cells at each of root sizes 2, 4 and 8 (**147 cells**), it independently checks `x + 2 + (outputMax-2)*(y+4)/8 >= 2.5`, including exact ties, voxel reads and trace agreement. It separately loads the original portable fixture with outputMax=2. Python checks the native saved project against the structural schema, recomputes the baseline/final coordinate arithmetic, verifies authored row order **0,2,1** across SAMPLING/INTERPRETATION/ACCEPTANCE, and rejects a coordinate row with a null source. All these checks pass on both platforms.

## Regression scope

API coverage includes fixed-baseline sampling rather than feedback; phase partition and stable authored indices; scale/offset before ordered transforms; 3D Y drivers bypassing 2D column prefetch; final driven coordinates reaching interpretation and gate fields; signed axes and rotation; composition with existing mapped probability/strength factors; immediate unit-range rejection before later replacement; signed valid acceptance mappings; lazy acceptance after interpretation/type rejection; disabled/out-of-Y laziness; overflow without partial chunk publication; worker/root/stage/trace agreement; owned compiled settings; live override reset; and the historical eight-argument ParameterEvaluation constructor.

Model/editor coverage checks explicit sources even on disabled sampling rows, immutable registry/phase definitions, signed acceptance mappings, private Apply/Undo/rejected-draft preservation and owned export dependency closure. The initial run found an invalid test fixture: an empty type-interval list was correctly rejected before it could test unmatched selection. A valid nonmatching interval repaired that fixture; the final run passes the intended lazy acceptance test.

All existing source C++/analytic/seed vectors, exact voxel hashes, previous independent consumers, native resource/noise/trace workflows, volume and OpenGL 4.5/strict 3.0 streaming probes pass. Windows runtime packaging and packaged-launcher smoke pass. Cross-platform JAR byte hashes and all 20 pinned source/dimension raw-bit digests match. Generated schemas and the world-operation registry are semantically checked against canonical Java output; checked-in contracts use generated formatting.

## Compatibility and remaining scope

Historical parameter lists keep their ordered interpretation semantics; all newly supported destinations are optional. SAMPLING rows require explicit fields and use one fixed resolved baseline sampling program. Final sampling controls affect subsequent primary and referenced fields. ACCEPTANCE rows execute only after interpretation/type selection and validate every effective strength/probability intermediate in [0,1]. Existing factor multiplication and seeds remain unchanged. Sampling drivers conservatively disable primary column prefetch. Project format remains 2; world-operation registry is version 4.

ParameterEvaluation now includes phase and query coordinates, with its historical constructor retained. Record-pattern/reflection clients must account for the added components. Native evidence covers the production controls and private transaction, not every full Properties navigation/inheritance gesture. Complete GUI/DPI/hardware acceptance, arbitrary transform-row destinations and representative performance remain outstanding. No project-owner licensing or redistribution grant is invented.
