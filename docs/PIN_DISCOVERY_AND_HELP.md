# Pin discovery, exact routes and Help — unvalidated development

This connected editor batch supports specification 6.11.4, 18.2.1–18.2.3 and 18.6.1–18.6.3. The owner testing pause remains active: no compilation, JUnit, native replay, independent consumer, checker, schema generator or workflow ran. The last observed validated baseline remains 922 tests / 120 suites. Main is authoritative.

## Committed Find and private Used By

Unified Find now includes a `pinned control` kind. Each committed pin has its own row with its label, authored group, control name, owning noise/root/directory breadcrumb and current JSON location. A row follows its target item's enabled state; shared-parameter pins remain enabled. Search projections own immutable text/reference metadata until the next committed index publication. Provisional pin edits stay in Properties and do not enter committed Find before Apply.

Find References reports each actual pin reference to the original item/shared parameter. A pin's label or group containing UUID text does not create a reference. The direct noise-container projection now excludes its nested pins, matching its existing exclusion of processing/shared/input collections; direct-only reference results do not duplicate a pin as an aggregate noise resource. Include Containers still exposes that noise resource's complete reference set. Pin rows remain references to their original control, not transitive references to whatever field that control currently uses.

Private Used By metadata now carries a pin's logical control identity as well as its item/shared-parameter UUID. Find pin rows carry the same logical route. Properties resolves that pair against its current draft and reveals the matching pin's current group and label editor. Reordering, changing presentation labels/groups, and moving unrelated rows do not change the route. The stored JSON pointer describes the indexed snapshot for display; navigation does not replay its pin array position.

Each Find row has a distinct snapshot key, including when manually authored duplicate pins have the same target/path. A duplicate occurrence suffix distinguishes rows for selection/rendering only and never chooses a duplicate for navigation. If the pair is missing or identifies several current pins, Properties reveals the full picker with guidance and retains its previous selection without guessing a neighbor. Legacy UUID-only links keep their existing ambiguity policy. Pending precise metadata focus also retires if its selected pin is retargeted to a different control path.

Logical navigation strings are transient editor targets; they do not add persisted Pin fields or new public engine/schema formats. Find keeps its existing draft-protection guard and committed revision ownership. Used By remains a snapshot of the current private draft version. Neither searching nor routing applies, saves, reorders or evaluates procedural data.

## Central guidance and context entry points

The central Help catalogue adds Pinned controls and live sources, linked from Noise, Processing and Find. Search and complete-topic Copy include guidance on original literal editing, bound-source navigation, group filtering, logical links, duplicate/missing pins and explicit Apply/Undo/Save. Its related topics include scalar readouts and persistence. The Pinned Controls header, item Pin Control picker, Pin Item button and shared-parameter Pin Control button expose the existing right-click Open Help action. The header supports the same action while collapsed.

Context Help uses the existing bounded popup and explicit HelpRequests mailbox; it does not consume an authoring action or publish a draft. The existing Help/Back/Copy presentation remains authoritative. No source is copied into a pin and no generation arithmetic, seed, preview setting or user preference is changed by discovery or guidance.

## Deferred definitions

Ten PinDiscoveryTest definitions cover labels/groups/control text and actual pointers; direct versus aggregate references and UUID-like labels; enabled/root/directory filtering; immutable index metadata; logical keys and navigation through reorder/rename; duplicate row keys and ambiguous routes; removed routes; exact immutable Used By paths; whole-item/Unicode/slash route strings; and searchable/linked/read-only central Help.

PinWorkflowProbe retains its production pin-picker creation/editing and one Apply/Undo/Redo sequence. Its additional definition first requires committed Find to exclude a private specific pin, then routes an applied Find hit through the production SearchNavigation and Properties path. It temporarily moves that pin through the actual Up/Down controls and requires the old hit and current Used By link to reveal the same pin rather than the historical array position. It restores the order, uses actual right-click/Open Help input, checks the exact help topic and unchanged draft/document/preferences, and records the complete guidance text. This composes committed Find metadata with production navigation; it does not add a new native Find-window row-click scenario.

The future pin replay checker requires those receipts and `pin-help.txt`. Existing manual-only commands/artifact directories already include the extended replay and the engine-JAR-only 162-observation `x+5`/pin-free consumer; no workflow change or dispatch was needed. Full native search/guard combinations, all context entry points, huge catalogues, physical keyboard/IME/DPI, Windows 11 and final GUI/release acceptance remain pending.
