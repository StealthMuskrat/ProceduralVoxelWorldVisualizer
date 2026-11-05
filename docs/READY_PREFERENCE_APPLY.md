# Ready preference and Application Profile decisions

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, independent consumer, Python/schema checker, packaging, update-network check or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Source definitions do not advance A11/A22/A32/A33, Windows 11 or final release acceptance.

## Current controls before preparation feedback

PreferenceApplySession and MetadataApplySession already retained successful prepared files until their owning panels processed current input. Preparation failures previously bypassed that decision: delivery reported them immediately, retired the pending request and changed Cancel identity. Successful delivery also discarded the FutureTask reference, hiding a UI handoff that ran its callback inline and then threw.

The closeable CPU mailbox now owns either a prepared file or a captured preparation failure. UI callbacks retain only request/epoch identities and mark readiness; they do not evaluate private form callbacks, report captured failures or replace files. Delivery keeps the running state, pending message and request version stable. Cancel Apply, Escape, Revert, private edits and lifecycle suspension/closure can retire a delivered preparation failure without primary Apply-error feedback. A failed preparation has already relinquished any temporary file through the existing preparer cleanup contract.

Performance, UI Scales, Update Trust, Theme Save, Binding Apply and Application Profile Apply accept using the request version shown by their current draw. A predecessor draw cannot accept a newly submitted inline request or a callback-created ready successor. Successful original Apply still finishes automatically from a later owning frame; no extra confirmation is added. Theme installation stays outside its safe palette overlay, and binding capture polling retains priority over activation. Application Profile's file boundary is injectable within the package for deferred native failure definitions; ordinary constructors keep the existing filesystem implementation.

Both sessions retain task observation through readiness. A handoff that marks readiness then throws, an external Future cancellation or executor failure cannot silently activate unaccepted staging. These operational failures and capture/guard failures retain their existing refresh/immediate recovery policy; this change defers **captured worker preparation failures**, rather than claiming that every kind of scheduler or observer failure waits for Cancel. Accepted results detach the task before consumption to avoid interrupting a worker still returning from delivery. A later external failure cannot undo already consumed publication.

Acceptance captures its original request identity before refresh. Exact input/source checks recheck that identity after callbacks; a callback returning true, false or throwing after creating a successor cannot publish, cancel or report against that successor. Profile capture and its final displayed-input callback have the same fence. A stale source retires old readiness and preserves private input. Failure reporting consumes the old attempt before invoking the reporter, allowing explicit retry or a reporter-created successor. Final close marks the session closed before mailbox cleanup, so a cleanup reporter cannot schedule new work into a closing owner.

The existing atomic replacement remains the only publication boundary. Exact active/prepared/private/raw checks, narrow preference patch installation, prebuilt profile activation and consumed publication remain. Cancel preserves the prior destination and applicable private corrections. Cleanup feedback is separate from primary preparation failure; a failed unlink may still leave an unpublished sibling and is not retried against a potentially reused pathname. Interrupts and queue removal remain best effort.

The shared preference session also serves ordinary/periodic active saves. Delivered preparation failure now remains pending until PreferenceSaveSession.frame accepts it, or close retires it without primary failure reporting. Coalescing, active-only persistence, unchanged periodic-failure suppression, explicit retry and the synchronous final-exit barrier retain their existing policies. A manual request received while an unaccepted preparation remains pending promotes that request; it does not implicitly inspect or retry its private failure payload.

Project/history/dirty state, private Properties/raw seeds, current engine instances and authored generation values remain separate. Update Trust configuration performs no network operation. No registry, schema, serialized shape, public headless API or mathematical rule changes. Owner capture, complete comparisons, final rename/unlink, UI reload and native consequences still cost owner-thread work. No measured provider, latency, memory, durability or Windows 11 guarantee is claimed.

## Deferred definitions and replay

Seventeen ReadyPreferenceApplyTest definitions cover:

- Stable ready-failure identity and Cancel/close across all three preference forms, plus Theme and Binding retention.
- One accepted failure/report, explicit retry, exact written/active equality and old callback isolation.
- Raw/display/active-source changes retiring preference/profile failures without primary feedback.
- Old shown versions refusing ready successors, including a successor carrying failure.
- Inline readiness followed by handoff/executor failure, with unpublished-file cleanup.
- Reentrant input/display callbacks returning true, false or throwing after creating a ready successor; reentrant initial profile capture; a failure reporter creating a successor.
- Final close rejecting cleanup-reporter scheduling, and active-save failure acceptance versus closure and periodic suppression/retry.

BackgroundMetadataApplyTest's existing invalid-profile/raw definition now explicitly accepts a delivered worker failure before asserting terminal failure. Existing queue/handoff/cancellation and synchronous definitions retain their prior expectations. None of these definitions ran.

BackgroundPreferenceProbe adds limits-ready-cancel and limits-ready-failure-cancel to its original nine top-level cases. BackgroundMetadataApplyProbe adds ready-cancel and ready-failure-cancel to its original nine. Each new case holds the production Cancel mouse press across delivery, checks stable running/ready/version identity, then releases Cancel before acceptance. Failure cases inject an AssertionError after writing the private sibling; the existing preparer must clean it. Both delivered success and failure preserve original destination bytes, active state and unrelated private/project state and leave no unpublished sibling. The reports require exact readyRetired/failureHeld flags. These four added native definitions are synthetic controls, not observed physical input or platform evidence.

The preference checker now defines eleven complete top-level preference comparisons and twenty-two unchanged authored projects, alongside the existing twelve appearance/binding and nine active-save cases. BackgroundPreferenceConsumer expands from thirty to **thirty-two cases / sixty-four saved projects**, with **1,344 independent constant-field observations and 9,216 EMPTY cells**. New preference cases contribute four unchanged saved projects, 84 fields and 576 cells. Root sizes 2/4 and signed chunk expectations remain handwritten .125/EMPTY values.

The profile checker now defines **eleven profile schema/receipt/created-project comparisons**. MetadataApplyConsumer defines **462 independent existing/future field observations**: eleven projects, seven signed X values, three signed Z values and two fields. New retired cases add two created projects and 84 observations, retaining existing .125 and future .25. Original successful Apply/Retry still expect future .75. Broader creation, preference, engine and schema semantics stay unchanged.

Existing manual-only Linux/Windows commands already reach the expanded nested probes, consumers and checkers. No workflow change or dispatch is required. Compilation, execution, broader native/GUI behavior, remaining feature audit, Windows 11 hardware and release acceptance remain outstanding.
