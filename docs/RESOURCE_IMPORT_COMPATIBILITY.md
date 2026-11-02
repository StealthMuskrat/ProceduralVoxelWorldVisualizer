# Import compatibility and input names — unvalidated development

This main-branch source follow-up addresses specification 3.3.2 and 3.4.2/3.4.3/3.4.6, with A06/A07/A08/A22 coverage definitions. No compilation, JUnit, independent consumer, checker, native probe or workflow ran. The owner testing pause and last observed 922-test / 120-suite validated baseline remain.

## Preserve binding definitions during Reuse review

ResourceImport's conservative compatibility comparison strips mutable/local identity metadata from owned JSON before comparing definitions. It previously treated any object with a uuid key as an identity declaration. An expression's inputs map can legitimately contain a Binding named uuid. In that case, normalization removed that binding and also inputs named id or parentFolderUuid. Different field definitions could therefore compare equal and incorrectly offer REUSE, mapping incoming dependents to an existing field with different results.

Identity collection and normalization now recognize metadata only when uuid contains the full valid textual UUID spelling. An object-valued uuid input remains part of the input map; id and parentFolderUuid bindings remain alongside it. Actual resource, item, parameter, curve and point UUID metadata still normalize, and internal references still compare through their local identity paths. Existing conservative external-reference and seed-sensitive rules remain unchanged.

The resulting compatibility flag feeds the existing import panel: incompatible REUSE is not offered as a new choice, and a retained/API-requested incompatible choice produces INCOMPATIBLE_REUSE and blocks publication. Users can choose COPY or SKIP through the existing workspace. COPY keeps its stable preview UUIDs and collision-safe IDs; successful Apply remains one complete document transaction. No project or package version, schema, public signature, editor layout or stored field value changes. The destination's existing definitions, project settings and separate private draft remain owned by their existing workflows.

## Deferred definitions

Eight ResourceImportCompatibilityTest definitions specify differences in five identity-like input names through both UUID-authoritative and visible-ID matching (ten cases), valid Reuse across local identity/organization changes, local shared-parameter references, conservative external UUID comparison, retained unused input definitions, actual curve/point normalization, rejected-Reuse-to-stable-Copy publication with exact Undo/Redo, and immutable cached compatibility previews. Input source/destination models must remain unchanged by comparison.

ImportCompatibilityConsumer compiles separately against only the engine JAR. A changed uuid binding raises an existing field from x+15 to incoming x+25. REUSE must reject without changing the active document, saved checkpoint, lifetime or history. Switching through COPY/SKIP/COPY must retain the copied UUID, keep field-copy naming, reuse the existing voxel type and preserve sparse placement overrides. Apply must match the complete preview, keep destination definitions/settings, save/reload exactly and move through one complete Undo/Redo transaction.

Independent expectations require 63 exact scalar observations and 588 exact signed-position voxels at roots 2/4 across the before/after snapshots. The original NO_CHANGE world is EMPTY; the imported SET layer selects X>=1 within inclusive project-Y bounds. Wrongly reusing x+15 with the incoming threshold would instead leave the sampled region EMPTY. Original/incoming definitions and saved bytes must stay unchanged. Project JSON and a report are future artifacts.

Future Linux/Windows commands and artifact paths are added to the existing manual-only workflow without dispatch. Broad semantic compatibility, full import gestures/private-draft/native/platform/Windows 11 and final acceptance/release gates remain pending. Main remains authoritative; no development branch was created.
