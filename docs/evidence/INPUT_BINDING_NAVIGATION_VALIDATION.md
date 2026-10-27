# Input binding navigation and keyboard repair

Validated implementation: `ceba41e9654b6a6449da65ecdbc53b0304eb42bf`.

[Actions run 40563680844](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40563680844) passed Linux, Windows and cross-platform JAR reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1. Both platform builds passed `clean test build dependencyNotices releaseJars`, prior native workflows, packaged launcher checks and INPUT_BINDING_NATIVE_PASS / INPUT_BINDING_PASS. Raw Linux JUnit contains **816 tests across 105 suites, zero failures/errors/skips**; see [summary](input-binding-junit.json).

Input links previously selected their named binding every frame, overriding later manual choices. The selected input key also belonged to the whole panel rather than each item. Input navigation scrolled to a header without focusing its editor. It now selects once into bounded, draft-local per-item state and focuses the binding's exact editor. See [interaction contract](../INPUT_BINDING_NAVIGATION.md).

Six new selection tests cover per-item independence and later user choice, unavailable names/foreign items, draft replacement with identical UUIDs, input removal/fallback, reorder/coordinate ownership and the 128-entry bound. Three diagnostic tests cover exact decoded input names, all recognized serialized leaves, vector/coordinate component indices, unrelated-path rejection and source-dependent default destinations. Existing expression-text/expanded editing and navigation tests remain passing.

The native InputBindingProbe uses the full production PropertyPanel, rather than only its expression subpanel. Real ImGui mouse/key/text events and controlled native/ImGui focus verify:

- A malformed constant is rejected by Apply without replacing the current Properties draft. Its actual diagnostic resolves `inputs/bias`, focuses Value and accepts keyboard-only repair to 3.25 after several idle frames, with no click on the numeric field.
- Changing the first item's input to `extra` remains selected after eight frames. The second item independently switches to `alpha`; revisiting both items retains `extra` / `alpha`. A prior navigation target cannot force the selector back.
- Thirteen focus destinations acknowledge actual widget focus: scalar Value, another named Value, Type, Source, Boolean Value, Shared Parameter picker, Earlier Result picker, Ambient selector, Noise Generator Reference picker, Projection, Slice coordinate, transform X and the second explicit coordinate component. Custom UUID picker focus is captured on its button before its descriptive label is drawn.
- Transform X accepts keyboard-only edit to 4. The explicit Coordinates2D second component accepts 8 while the first stays 0. All navigation and private repairs retain committed JSON, revision, Undo bytes and the same rejected draft object. A foreign-project link is rejected.
- One ordinary Apply publishes all repairs. The engine samples 26.25, one Undo returns 5, and one Redo returns 26.25.

The [raw native report](input-binding-native.json) retains the exact focus/selection sequence and before/after samples. The [saved project](input-binding-project.json) retains both processing items, shared/external/earlier/ambient references and the edited explicit coordinate/transform bindings. Independent Python resolves those saved identities and recomputes `3.25 + 3 + (0 + 4) + 2*8 = 26.25`; unrelated input values remain 7 and 11. Local replay of all downloaded picking/navigation/project-file reports and the new binding check passed.

The initial Linux run passed. It was superseded while the fixture expanded from four value/component focus destinations to thirteen, covering custom reference pickers and enum controls as well. The final run passes all original checks and the additional assertions; no assertion was weakened. Existing sampled-prefix explanatory text was retained during the control refactor.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local formatting, schema/fixture validation, Python syntax, diff checks and downloaded-report replay passed. Pinned CI supplies compilation and automated/native/package evidence.

This is controlled native ImGui/Properties evidence. Reference/enum/Boolean controls acknowledge focus; the fixture does not claim every keyboard activation/edit gesture for those controls. Hidden specialized controls, physical mouse/keyboard/OS focus, full GUI/DPI and Windows 11 hardware acceptance remain separate. No engine API, schema, archive, project format or numerical semantics changed. A03/A11 remain PARTIAL.
