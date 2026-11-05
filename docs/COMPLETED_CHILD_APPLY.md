# Complete-worker gates for Membership and Curve Apply

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, real update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. A03/A08/A09/A13/A22/A32, native/Windows 11 and final release acceptance do not advance.

## Complete posting and successful admission before publication

MembershipEditSession and CurveApplySession already retained worker success or captured preparation failure until the current dialog controls permitted acceptance. Their readiness callbacks still dropped the task handle. Acceptance could consequently publish inside unfinished posting or before executor.execute returned successfully, hiding a later handoff/admission fault behind a committed member transaction or privately staged curve.

Both paths now retain their task handles. Readiness callbacks only mark the receipt; acceptance additionally requires normal complete worker return and successful executor admission. Membership also keeps its existing admission-in-progress guard. Inline acceptance during posting or between inline task return and executor admission leaves the same work version, project, private input, local choices/curve, raw seed ledger, preferences and history intact. Later ordinary owner acceptance completes the original Apply action without another confirmation or automatic retry.

Captured preparation failures and rejected member diagnostics wait for that complete boundary. A later poster/admission failure cannot hide behind an unaccepted success or captured failure. Operational Future status/get faults retain their existing refresh recovery policy; not every operational fault waits for Cancel. The shared nonblocking helper contains terminal status/get failures and restores interruption from interrupted reads.

## Retiring one request cannot settle another

Membership acceptance and both refresh paths capture their original request before Future observation. Each terminal check rechecks that request before taking a mailbox or reporting failure. An obsolete terminal read that creates a ready replacement and returns normally, throws Error or reports cancellation cannot consume or fail the replacement.

Retirement returns the identity reserved before cancellation callbacks, after clearing the retiring work's active flags. Scheduling, cancellation/suspension, changed choices/local input, rejection diagnostics, failure feedback and Curve staging check that exact identity before continuing. Failure descriptions check ownership again before assigning/reporting feedback. A Throwable message getter can retry or close without obsolete feedback overwriting the outcome. Curve staging rejection also guards its description, and its acceptance finally block only clears the applying flag for its own identity.

Membership Cancel now marks the owner closed before task cancellation. Successful Membership publication likewise closes immediately after the single-use document transaction, before retiring its completed task. Cancellation callbacks cannot apply another selection to a closed or already-consumed owner. Curve final cancellation already closes first and retains that ordering. Successful Curve staging still consumes the editor before its ordinary follow-up callback; accepted follow-up failures retain their existing consumed behavior.

## Publication domains stay distinct

| Path | Complete acceptance publishes |
| --- | --- |
| Set or Group Apply Members | One member-only document transaction, followed by copying just the member list into the existing private Properties draft. Private ID/raw seed input and explicit Save remain separate. |
| Curve Apply | Only the exact curve parameter into its opening Properties draft. Parent Properties Apply remains the separate document transaction with Undo/Redo; Save remains explicit. |

Opening owner/source/project/lifetime, local curve UUID/node/value, member UUID/order, private/raw input, feedback, checkpoint/history and validation-policy checks remain authoritative. Cancel Apply, suspension and edits keep their existing explicit-retry policies. Groups remain organizational, sets retain generation-predicate meaning, and unrelated private input survives successful child publication. Public synchronous constructors, model/API/schema/registry/project/package shapes and deterministic field/voxel mathematics are unchanged. Engine/model/storage remain independent of editor/native dependencies.

## Deferred definitions and available review

Eighteen ChildApplyCompletionTest definitions share Set, Group and Curve fixtures where applicable. They define real posting held after a readiness receipt, late posting faults with explicit retry, inline posting/admission with success or captured failure, later poster/executor faults, closing held success/failure, and complete work without a receipt. They also define contained terminal status/get/interrupted reads; normal/Error/cancelled terminal reads creating ready successors; failure descriptions retrying/closing; cancellation-created successors; closure before cancellation-driven Apply; consumed Membership closure; complete rejected-member feedback and retry; rejected-plan cleanup creating a successor; accepted Curve retirement creating a successor; reentrant Curve stage-failure feedback; and changed member/curve retirement preserving ready replacements.

Success defines one member-only transaction or separate private Curve staging and parent Apply, full project Undo/Redo comparisons and independent clamped-linear .75/1.5/3 samples. All prepublication retention checks compare complete project/private/preferences/local snapshots and history/revision/raw ownership. Real-worker fixtures use explicit executor completion barriers and release latches in finally blocks. The existing BackgroundMembershipTest and BackgroundCurveApplyTest real-worker definitions now wait for complete worker return before acceptance. Existing native Membership and Curve probes already use queued-task executor barriers. No definition or probe ran.

Existing native case/receipt counts, saved-model/schema comparisons, public formats/APIs, workflow wiring and independent replay dimensions remain unchanged. No new native case or generated-world fixture is added. Source review, git whitespace/content checks and repository metadata review are the available checks. Owner capture/comparison/staging/history/refresh costs, remaining worker and required-feature audit, measured native/Windows 11 and final release acceptance remain outstanding. This follow-up does not certify repository-wide completion.
