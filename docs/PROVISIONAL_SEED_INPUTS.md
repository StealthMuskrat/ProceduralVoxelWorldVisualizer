# Provisional Properties seed inputs

Status: **implemented, awaiting validation** on `codex/development-phase`. The owner has paused GitHub testing until the program is fully developed. This source and its regression/native/independent replay definitions have not been compiled or executed.

Source noise seed offsets, layer/placement seed offsets and probability-gate seed offsets now retain incomplete or invalid private text. A lone sign, overflow, whitespace, exponent/hex syntax or non-ASCII digits cannot silently apply the previous model value. Complete signed ASCII decimal input parses directly to a long, including both signed 64-bit endpoints. No floating-point intermediate or implicit rounding is used.

A draft-owned ledger records invalid inputs separately from the canonical model. Each input has a logical control path, bounded label and up to 128 UTF-16 characters of raw text. Up to 1,024 live/detached problems are retained; capacity rejects a further invalid edit without evicting an existing commit blocker. The UI's independent seed-buffer cache is also capped at 1,024 entries. Buffer eviction can recreate invalid text from the ledger. Ordinary Properties text-cache resets and collapsed sections do not erase a pending seed input.

`EditorState.applyDraft()` checks this ledger before opening a document transaction. Rejection keeps the exact private draft, canonical long values, committed project, revision and history. It publishes `INVALID_SEED_TEXT` diagnostics under **Rejected Properties seed input**. Their `/editor/draft/...` paths describe provisional editor data, not a falsely invalid serialized project value. Source UUIDs and logical parameter keys route to the current source/root setting or aggregate gate section through the existing navigation rules.

## Explicit correction, restoration and tree changes

Properties shows the problem count, up to eight correction/restoration rows at a time and **Restore All Seed Text**. Correcting a field removes only that field's live problem and updates its canonical long. Restore explicitly discards the raw invalid text and displays the current valid model value; it does not reset other valid edits, remove an override, alter a layer or save a project. Removing/resetting an entire placement override discards that property's pending raw input as part of the explicit removal.

Gate children have positional identities. Adding/removing a child, or automatically adding the child for NOT, detaches affected invalid seed inputs before an index can identify another gate. Their old text remains a `STALE_SEED_TEXT` commit blocker with an aggregate gate destination until explicitly restored. It is never placed into the new row. Nested raw-buffer paths distinguish current children, while historical diagnostic navigation continues to avoid row-index replay.

Ledger ownership follows the project UUID, selected resource and exact Properties draft object. Revert, replacement with the same UUID, another selection and New clear the old ownership. An unrelated committed project edit preserves inputs belonging to the same private resource draft. Valid Apply/Undo/Redo use the existing parent transaction and canonical persistence; seeds, schemas, engine APIs and generated numerical semantics are unchanged. This is transient editor state and creates no recovery/autosave file.

## Guarded navigation

Search/source-reference Apply/Discard/Cancel protection now sees pending seed text even when the canonical draft equals the committed resource. Apply remains open on seed rejection, Cancel retains the raw text and Discard changes resource through the existing explicit draft-discard path. An invalid-text revision participates in stale-decision checks, so changing `-` to `--` cannot let an older Discard decision discard the newer input. This extends those existing guarded entry points; it does not redefine every resource-tree selection gesture.

## Deferred verification definitions

`DraftSeedInputsTest` defines nine contracts for lexical/overflow retention and Apply rejection, exact long endpoints and per-field correction, detached gate positions, prefix boundaries and explicit restoration, replacement/selection/New ownership, unrelated-document preservation, bounded storage without blocker eviction, guarded Apply/Cancel/Discard and stale raw-text decisions.

`SeedTextProbe` defines a full production Properties workflow. It rejects a source sign, follows its diagnostic, exercises guarded Apply/Cancel, commits source offset 17, enters layer offset Long.MIN_VALUE, rejects gate overflow and repairs it to Long.MAX_VALUE. It then enables a placement override explicitly, rejects `+`, restores its last valid text without removing override presence, and commits 43. Every parent Apply is followed by one Undo/Redo; project/history/private ownership and User Config isolation are checked. Native positional gate mutation/retention remains separate from the pure ledger definitions.

`SeedTextConsumer` defines engine-JAR replay of those four exact persisted longs, 81 coordinate-hash samples and 144 occupied voxels over roots 2/4 and signed chunk coordinates with a probability-one gate. `verify_seed_text.py` defines an independent masked unsigned-64 SplitMix/hash reference for all 81 raw-bit samples plus schema, seed-presence and native-report checks. Future Linux/Windows workflow entries and artifact retention reference these definitions without changing development-branch trigger exclusions. None has been executed during this batch.

Physical keyboard/IME/DPI behavior, all nested gate edits and override reset gestures, capacity behavior in a native host, every numeric property editor and full GUI/hardware acceptance remain pending. Earlier evidence stays scoped to its recorded source.

The subsequent [resource selection guard](RESOURCE_SELECTION_GUARDS.md) extends raw-text protection to browser/manager row clicks, Ctrl/Shift selection and context-menu Properties. It is also unvalidated; structural browser mutations and file transitions retain their separately documented policies.

The subsequent [resource browser transactions](RESOURCE_BROWSER_TRANSACTIONS.md) also prevent structural actions from silently refreshing invalid seed text. Explicit Discard disposes of it only after the requested action succeeds; explicit Apply remains blocked by it. This source is unvalidated.

[Project draft transitions](PROJECT_DRAFT_TRANSITIONS.md) subsequently extend raw-seed protection to New/Open/Exit and shared native-close routing, including clean committed documents. Discard is deferred through failed/canceled paths, and standalone Save remains committed-only. This implementation is also unvalidated.
