# Inspector worker and terminal-future recovery

Status: **uncompiled and untested** on main under the owner's development-phase testing pause. This source follow-up supports Tree 19 inspection/recovery, 16 picking and 4.4 background work. The observed baseline remains 922 tests / 120 suites. No compiler, JUnit, native probe, independent engine client, Python checker or GitHub workflow ran.

## Production Inspector paths

World Pick and coordinate Trace previously caught Exception inside their worker bodies. An Error, or a failure outside that catch, could be captured by the executor's Future while the Inspector still held a pending request forever. In addition, directly reading a failing exception's message could itself prevent useful feedback.

InspectorTask now exposes terminal failure observation to its UI owner. It calls get only after isDone, preserves original execution causes, surfaces external cancellation, restores interruption after an interrupted terminal read and contains Future status/read faults. Successful worker completion does not retire a request or publish a trace: the normal queued UI callback retains that authority. Pending work and the original target remain intact until the UI decides how to retire the request.

Viewports checks document/revision/view/input ownership before observing terminal work. Obsolete work still cancels through the existing request gate and cannot turn into a new failure notification or retry target. For a current failure, both task polling and normal failure callbacks use one recovery path. A queued callback from a retired attempt cannot finish a successor. An enqueued success callback also refuses an already known terminal worker failure, leaving that failure for UI recovery. Terminal failures are handled once because the request is retired before feedback/notification.

The Pick and Trace workers now catch Throwable at their normal computation boundary. Snapshot copying and scheduler submission also share the guarded UI failure boundary. The pending coordinate recovery target is captured before scheduling and retained separately until success, cancellation or failure. Coordinate failures offer Retry Trace for the exact failed target, preserving entered XYZ and the prior result. Pick failures offer a new pick or Trace XYZ and never invent a coordinate retry. Queue rejection keeps its existing explicit retry guidance. External cancellation is presented as cancellation; explicit user cancellation still clears recovery through the existing Cancel path. Nothing retries automatically.

Failure messages use a bounded, single-line description with a class fallback when getMessage throws, is blank or is absent. Reporting does not modify the original Throwable. Existing trace snapshots/log entries, source links, private Properties/raw seed input, project/history, user preferences, preview epochs and immutable engine results remain in their existing ownership paths. This is scoped to Inspector jobs; it does not certify every preview, renderer or external callback failure boundary. Background compilation may continue until its existing interruption checkpoints after cancellation, but retired publication is rejected.

Central Generation traces Help describes background failure retirement and explicit retry. No serialized project/preference/schema/registry/default, public headless API or generation calculation changes.

## Deferred definitions

Ten InspectorTaskFailureTest definitions cover unfinished nonblocking polls, normal completion waiting for UI, original RuntimeException/Error causes and exact targets, externally cancelled futures, successor isolation and late attachment, failing Future status/get methods, interrupted reads, enqueue-then-fail callbacks, closure/cancellation and bounded unreadable messages. Existing InspectorTask/InspectorRequests and exact retry contracts remain.

InspectorWorkerRecoveryProbe adds eight production native/worker cases under the existing ViewportDocumentProbe command. A separate editor/runtime reuses its caller's GL context. Pick/Trace, caught/escaped worker Error and readable/unreadable Error messages form the matrix. Coordinate cases start from a retained Logs target at (2,0,3) while entered XYZ stays (7,0,3). The real Retry Trace footer must recover the logged target; failed picks must allow a fresh explicit coordinate trace. Two initial traces and eight explicit successful successors define ten retained trace logs, with analytic field values 22 and 42 and final type 1 from the existing fixture. Every failed attempt must leave the prior trace and trace-log count unchanged.

The nested native report and unchanged before/after project feed added assertions in the existing deferred viewport-document checker. Existing document retirement, queue rejection, same-UUID reopen, old-publication rejection, immutable image and independent engine replay definitions remain. Workflow commands/triggers are unchanged, and no dispatch occurred. Physical input/DPI and Windows 11/hardware certification remain separate.

Only source inspection, Java formatting and git diff --check accompany this batch. Real scheduling/cancellation/native/platform/hardware and full acceptance/release remain pending. Main remains the sole branch.
