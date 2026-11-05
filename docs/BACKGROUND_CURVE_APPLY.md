# Background Curve Apply

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, consumer, Python checker or GitHub workflow ran. The validated baseline remains **922 tests / 120 suites**. This is not completed A03/A08/A13, GUI, Windows 11 hardware or release acceptance evidence.

## Preparation and action decision

Production Properties passes its existing bounded editor executor and UI queue into CurveDialog. An explicit Curve Apply captures the complete local curve value on the owner thread. A worker reconstructs a separate candidate, runs the canonical ScalarCurve validation and freezes a prepared value without a mutable payload accessor. The production publication path no longer recompiles that prepared curve on the UI thread. Legacy direct dialog/session constructors retain synchronous behavior.

Delivery checks request identity, the exact local curve and the opening owner, then makes the result available. It does not stage or run UI refresh. The real dialog processes its current input before accepting a ready result, so Cancel Apply, Cancel and Escape can win on the completion-ready frame. A normal successful request still requires only the original Apply action. Native Apply/Retry/Cancel Apply identities include the request version, fencing held releases across request changes.

Curve edits or local Undo/Redo to different values retire pending preparation and require another explicit Apply. Selection, grid, snap settings, view navigation and unchanged frames do not submit new work. Cancel Apply and Escape retain the local curve/history and parent draft; Cancel/window close/Properties closure also consume the opening source token and discard the local editor. Retired work may finish, but its queued or duplicate result cannot stage into this opening or a successor. Queue removal/interruption is best effort, not a hard cancellation latency guarantee for ScalarCurve construction.

Scheduler rejection, worker exceptions/Errors, external Future cancellation and UI handoff failure end pending state and expose bounded feedback. Retry Apply is explicit; unchanged frames do not retry. Invalid candidates and publication rejection preserve private correction. Accepted staging is consumed before the existing refresh callback; refresh failure remains a separate CURVE_EDIT_REFRESH_FAILED event and cannot repeat staging.

## Publication authority

CurveDraftEdit retains the opening document session token in addition to its existing exact EditorState, project object/revision/UUID, draft, item object/UUID/kind, ordered selection and curve-parameter presence/node guards. Final staging repeats complete opening project and source-value checks. Equal-value node replacement still requires reopening; in-place source/project mutation cannot be overwritten. Existing curve UUIDs cannot be replaced by a prepared candidate.

Only the curve parameter is copied into the current private Properties draft. Unrelated private ID fields and provisional seed text survive, including changes made after preparation begins. No project/history/preference state is published. Invalid raw seed input still blocks parent Properties Apply. That explicit parent transaction supplies the single project Undo/Redo boundary, and explicit project Save persists it.

ScalarCurve mathematics, cache/preview observations, headless public signatures, schema shapes, registry identifiers and serialized versions are unchanged. No new project/configuration fields are added. Opening snapshots, local edit validation/cache misses, UI value projection/comparison, prepared-node copying and parent refresh still have owner-thread cost. This change is not a measured large-curve responsiveness guarantee.

## Deferred verification

Fourteen BackgroundCurveApplyTest definitions cover a real worker and held UI delivery, private/raw-input retention with independent linear expectations and one parent Undo/Redo, local supersession with and without a frame, cancellation/closure/duplicate delivery, queue rejection/external cancellation, worker/handoff Error recovery, invalid-curve correction, exact source/document/selection checks, owned prepared nodes, consumed refresh failure, ready-result rechecks, completion-ready cancellation and curve UUID correction/retry. Existing synchronous publication and source-token definitions remain applicable. None ran.

BackgroundCurveApplyProbe adds nine cases within the existing CurveGestureProbe: successful Apply, Cancel Apply, completion-ready Escape and held Cancel release, Cancel, scheduler Retry, local Undo supersession, equal-value source replacement and Properties closure. Each case uses production PropertyPanel/CurveDialog controls, actual exact Y text input, twelve queued frames, twelve undelivered frames and the real editor executor. Successful cases retain private/raw/preference input, reject an invalid parent seed, then explicitly correct it and define one parent Undo/Redo. Every case checks unchanged original checkpoint bytes and saves before/after files.

The existing manual-only Linux/Windows curve gesture steps pass those files to the headless CurveGestureConsumer and Python receipt replay. They define exact full-model comparisons for eighteen saved projects and **378 independent clamped-linear observations**, using seven X values and three signed Z values per file. Synthetic ImGui input is not physical keyboard/mouse, DPI/docking/focus or Windows 11 evidence. Full compile/runtime/concurrency/native/performance/acceptance and final artifact validation remain pending.


Follow-up: [ready Membership/Curve ownership](READY_CHILD_APPLY.md) places successful and failed Curve preparation in a retirement-aware CPU mailbox, fences shown-work acceptance after window unwind and suspends pending Apply on collapse. The expanded definitions remain uncompiled/untested.
