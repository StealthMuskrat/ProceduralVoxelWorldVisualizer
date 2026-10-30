# Typed noise authoring workflows

This batch extends the existing Properties panel and canonical noise definition. It introduces no second evaluator, graph workspace, project-format bump or automatic project commit.

## Bounded summaries and typed controls

Transforms, processing and shared parameters have searchable ordered summaries with stored indices and six-type filtering. The summaries use fixed-height native clipping and show one selected item's expanded controls. Item rows retain enabled checkboxes; move, duplicate, delete and pin actions remain beside the expanded property item. Search/navigation opens the exact UUID item, including curve owners. Filtering and selection never change processing order.

Input controls, shared-parameter sources, enabled earlier results, sampled prefixes and Scalar outputs use searchable clipped UUID selectors. Expression items show one chosen input's controls at a time and permit explicitly renaming/removing named bindings. Renaming a binding does not rewrite expression text; its uses must be updated before the parent Apply succeeds.

Earlier-result choices require an enabled result of exactly the declared type, strictly before the requesting item in the same stage. Coordinate-stage choices never include post-source results. Source Result, Ambient Value and Noise Generator Reference are offered for Scalar bindings; value vectors and coordinates retain distinct types. A sampled field selects Source Field, Earlier Field Prefix or Noise Generator Field. Selecting another source or explicitly changing type clears inactive reference UUIDs and uses a documented typed constant reset rather than an implicit conversion.

Algorithm and operation menus search canonical keys, presentation labels, categories and default result signatures. Existing presentation aliases remain visible. Polymorphic items still require compatible authored input/result signatures; the full compiler is authoritative on Apply.

Immutable draft metadata and choice projections are cached by draft identity and explicit UI edit version. Source/processing/parameter indices are independent of committed resource browser metadata. Ordinary draw/scroll/filter does not serialize or recompile the project. Choice contexts are capped at 128, and item switches clear provisional text-widget caches. Detailed dependency and structural-error lists use native clipping.

## Structural edits and explicit resolution

`NoiseAuthoring.plan(project, draft, request)` works on an owned candidate and validates the complete replacement through `NoiseCompiler`. Reorder rejects forward/self, disabled, wrong-type, wrong-stage and invalid-output dependencies without changing the draft. Boundary moves are no-ops. Duplicate changes the item UUID, internal curve identities and collision-safe ID; external/shared bindings remain references to their existing sources. Shared-parameter duplicate creates a separate value and does not redirect existing users.

Delete/Disable initially retain existing bindings. If resolution is required, a private dialog offers explicit replacement, Raw Source for Scalar post-source uses, or Inline Current Value for a deleted shared parameter. Raw Source distinguishes numeric Source Result from a resampled Source Field. Replacement retains a live reference and must pass type, stage and order validation for every use. Inline is an explicit value copy; it never happens automatically.

Deleting a selected output requires a valid explicit output replacement in the same candidate. Item/parameter pin references are retargeted to an explicit replacement or removed with the deleted target. Disabling retains the authored item. Invalid disabled expression text stays preserved and cannot be enabled until full validation succeeds. Deleting an invalid item can repair a provisional draft when its remaining definition validates.

A plan owns its candidate and diagnostics. `candidate()` returns a copy. `applyTo(draft)` rejects a changed draft base before mutation and publishes the lists, bindings, parameters and pins together. No document history is created by private browsing, preview, resolution or Cancel. Apply in Properties commits the complete resolved noise definition as one parent document transaction; Undo/Redo includes its output and all rewrites.

The UI dialog also checks the selected owner and source project. Exact draft/project snapshots are checked on Apply; stale publication rejects before mutation. Drawing the modal uses identity checks and cached diagnostics rather than serializing a large draft every frame.

## Shared parameters, pins and source navigation

Parameters support create, ID/value editing, duplicate, dependency inspection, explicit delete resolution and pinning. Shared parameter values remain canonical. Editing a pinned parameter edits that original value; no secondary numeric copy is stored. Pin labels, groups, order and removal remain presentation metadata and preserve computation identity. Item pins navigate to their original input/settings controls and show no misleading editable value.

Used By lists identify the exact input, Output or pin. Local Go To Control preserves the current draft. Referenced resource controls offer Open Referenced Noise Generator, Find References and Select Resource in Tree. Leaving through the reference navigation actions uses the existing Apply/Discard/Cancel draft guard, including invalid-Apply preservation and stale-target checks. Find References opens the committed Find index without committing the current draft.

## Contract and validation scope

Project format stays 2; existing Item, Binding, Parameter and Pin shapes are unchanged. `registries/noise-authoring.json` records the stage/type/structural-edit contract; the contract generator and portable `fixtures/noise-authoring.json` share it. Creation-profile defaults and algorithms are unchanged. Headless/model/storage components remain free of UI dependencies.

Core regressions cover independently known arithmetic, reorder/disable/delete safety, duplicate identity and curve remaps, output resolution, live replacements, sampled-field prefixes, parameter inline/replacement, stale/owned plans, disabled definitions, source resets and one-parent-transaction history. Editor tests cover six-type/stage eligibility, immutable cached metadata, direct references, pin presentation and private/committed separation.

The explicit native reference contains 2,002 processing items and 2,000 shared parameters. It exercises bounded production summaries and selectors, actual mouse/text UUID selection, private dependency Apply/Escape Cancel, one parent Undo/Redo, exact (1+2)*4 = 12 arithmetic and guarded invalid reference navigation. The engine-only consumer checks that an explicit Raw Source replacement produces 4 while an earlier compiled snapshot remains 12. Python validates the saved authored shape and the same independent arithmetic.

Testing runs after implementation, then any observed failures are repaired and dependent checks rerun. The complete Linux/Windows clean build, schema family, native desktop/render/input references, packaged Windows launcher, exact voxel regressions and binary reproducibility remain required. This scope does not certify every Tree 6 gesture, performance on representative hardware, all A01-A36 requirements or Windows 11 hardware acceptance.

## Development-branch pin browsing

[Exact pin selection, group filtering and source focus](PINNED_CONTROL_BROWSING.md) extend this UI on the development branch. They preserve the existing canonical Pin shape and are uncompiled/untested under the owner GitHub testing pause. The earlier authoring evidence does not validate this extension.


## Development-phase resolution ownership follow-up (unvalidated)

The dependency-resolution dialog now delegates to `NoiseEditSession`, an editor-only private transaction. Construction requires the exact selected Noise draft and an existing committed owner. It retains exact project/draft identities, project revision, selected resource/kind, ordered sibling selection, raw-seed ledger revision, and full opening project/draft values. Draw-time checks use identities/revisions; explicit choice changes and staging also compare the opening serialized values. This conservatively invalidates a pending resolution after any committed project edit, including an unrelated catalog edit. Cancel and reopen to review the new state; there is no automatic reload or retarget.

A choice change produces its new compiler-checked plan before replacing the previous resolution, replacement UUID and preview. An unchanged choice reuses the existing plan. Invalid resolutions remain editable with their diagnostics, but cannot stage. Changed draft values cannot be silently absorbed by recompiling a successor plan. The original Request's resolution/replacement are honored at construction. Replacement choice rows are prepared once from the opening draft rather than filtered anew each frame, and pending dialog ownership blocks a second structural request in Properties.

Staging consumes the session before running the dialog's selection/focus callback. A callback exception records `NOISE_EDIT_FOCUS_FAILED`, retains the accepted private edit and closes the dialog; it cannot reopen or repeat the edit. No callback rollback is promised. Cancel consumes without staging. Neither path commits the document. Raw seed text that already existed at opening stays owned by Properties and continues to block its ordinary Apply; later raw-input changes invalidate the pending resolution. The canonical `NoiseAuthoring.Plan` implementation, computation, format and parent one-transaction Apply remain unchanged.

The modal catches choice and Apply failures with non-null error messages. Native diagnostic rows remain clipped; submitted path/message strings are bounded to 1,024 UTF-16 characters, tooltips to 4,096 and dialog errors to 2,048. Full plan diagnostics remain available to the underlying session. Closed dialogs return completion on subsequent draws without reopening an ImGui popup.

Eight deferred `NoiseEditSessionTest` definitions cover private inline arithmetic/parent Undo, invalid-resolution correction, stale draft/selection/revision, raw-seed ownership, unsupported in-place project changes and consumed/offscreen sessions. The existing native `NoiseAuthoringProbe` definition now stages an unused-parameter deletion with an injected null-message focus failure, checks one callback and retained private-only changes over subsequent frames, then explicitly restores its fixture draft. Its independent report checker requires the new consumption fields. These definitions have not been executed; implementation is uncompiled and untested under the owner GitHub testing pause. Native stale-choice, broader focus recovery, keyboard/DPI and complete GUI acceptance remain pending. Earlier authoring evidence does not validate this follow-up.

The unvalidated [resource forms layout follow-up](RESOURCE_FORMS_LAYOUT.md) gives NoiseEditDialog retained Resolution/Issues pages and persistent cancellation-first actions. Staging remains private to Properties with exact-owner checks and one consumed focus callback. Thirty shared layout cases, native staging/retry/ownership phases and independent equivalent-arithmetic replay remain deferred.
