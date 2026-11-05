# Complete preference handoffs and owned active-save recovery

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, real update check or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and final release acceptance remain pending.

## Complete worker return before configuration publication

PreferenceApplySession and MetadataApplySession already retained success/failure readiness for current panel controls. Explicit acceptance could still take a staged payload inside inline posting or before executor.execute returned. A later poster/executor failure could then follow an already replaced configuration file and activated settings/profile.

Both owners now require the current readiness receipt, successful executor admission and normal complete FutureTask return before taking their owned staging payload. Current input/display guards still run before publication. An unfinished handoff remains RUNNING with stable shown version and previous destination/live values; later owner acceptance completes the original action without an additional confirmation or automatic retry. Captured preparation failure also waits for that complete gate. Operational scheduler/handoff/Future-fault observation keeps its existing refresh recovery policy.

| Path | Protected publication |
| --- | --- |
| Performance limits, UI scales and update trust | Atomic User Config replacement, then activation of only the form's owned fields. |
| Theme palette and keyboard bindings | Atomic User Config replacement, then exact palette/canonical binding activation. |
| Application profile | Atomic App Config replacement, then activation of exactly the staged metadata and private-form checkpoint. |
| Active preference save | Atomic persistence of captured active values, preserving live nested owners and excluding private forms. |

An executor fault after inline work takes precedence over an unaccepted value or captured preparation failure. Cancel, final close, source/input/display mismatch and explicit retry retain their existing policies. Retired staging is closed through the existing mailbox; a late receipt cannot restore it or consume a successor. Update trust editing remains local configuration work and sends no update request.

## Cleanup, failure feedback and active-save ownership

Each worker owner now reserves its retired identity before mailbox cleanup/cancellation callbacks can run, and returns that exact identity to Start, Cancel, stale-input handling and failure handling. A cleanup error's description or reporter can create a ready successor without the older caller relabelling it, reporting an obsolete primary fault or overwriting its source. Failure-message getters are separately checked after description before feedback assignment/reporting. Existing terminal observation continues to contain status/get faults and preserve interruption; acceptance rechecks the original request before any mailbox take.

PreferenceSaveSession also owns an identity for each captured/coalesced request. Frame and request paths recheck it after worker refresh, status observation and acceptance. A failure reporter that starts a ready replacement, or closes the save owner, cannot be followed by older frame cleanup that clears that replacement or resurrects a closed owner. Capture-failure description is likewise tied to its original attempt. Successful publication consumes before notification; notification failure can be reported without replaying the saved file or consuming a callback-created successor.

Manual requests still promote one pending periodic save to one notification. Periodic saves remain quiet, unchanged failed sources retain explicit-retry suppression, and changed active values coalesce to a current snapshot. The final exit barrier still closes earlier background work before synchronously persisting the latest active values. Public synchronous UserConfig/profile persistence, atomic replacement rules and three configuration domains remain unchanged.

## Deferred definitions and remaining scope

Twenty-one PreferenceCompletionTest definitions cover seven actor paths and the active-save controller:

- Real posting held after a receipt, normal complete publication, late poster failure with staging disposal and explicit retry; cancellation/final closure of successful and failed preparation.
- Acceptance inside posting and again before executor return; subsequent poster/executor failure; captured failure feedback waiting for completion; complete work without a delivered receipt.
- Contained Future status/get faults and preserved interruption; terminal reads creating ready successors; failure-message getters creating successors or closing owners.
- Input mismatch during unfinished posting, final close before cancellation callbacks, old shown-version/receipt isolation and cleanup feedback creating a ready replacement without obsolete primary failure.
- Active-save manual promotion only after complete worker return; error reporters creating ready successors or closing during frame/request observation; failure-message getters creating ready successors; consumed notification failure with a ready quiet successor; coalescing latest active values while an older post remains held.

Real-worker definitions use latches, explicit executor barriers and release in finally blocks. Three prior real-worker definitions in BackgroundPreferenceApplyTest, BackgroundMetadataApplyTest and PreferenceSaveSessionTest now await a worker barrier before final acceptance. Interruption is checked before unrelated fixture file I/O. No new or adapted definition ran.

The actor fixtures preserve complete committed project/private/raw/selection/history/diagnostic ownership while exercising configuration work. Existing native controls/receipt counts, saved-model/schema comparisons, public formats/APIs, workflow wiring and independent field/voxel replay dimensions remain unchanged. No native case or generated-world fixture is added; engine/model/storage dependencies and authored mathematics are unchanged.

Source review, git whitespace/content checks and repository metadata review are the available checks. Owner capture/comparison/rename/cleanup/reload/rendering costs, remaining worker and required-feature audit, measured native/Windows 11 and final release acceptance remain outstanding. This follow-up does not certify repository-wide completion.
