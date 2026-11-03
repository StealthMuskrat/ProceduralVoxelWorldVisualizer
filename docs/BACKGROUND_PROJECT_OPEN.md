# Background Project Open — unvalidated development

This source batch supports specification 2.6.3–2.6.6, 4.4, 18.7.3 and 19.2.1–19.2.2 and A03/A05/A32. It remains uncompiled and untested. No compiler, JUnit, native probe, engine consumer, Python checker, packaging process or workflow ran. The owner execution pause, main-only policy and last observed 922-test / 120-suite baseline remain unchanged.

## Prepare privately, publish explicitly

The desktop Open path previously read the file, parsed its project data, ran complete compiler validation and serialized its saved checkpoint inside the UI action. Those operations now run on the existing bounded background executor. Desktop uses the background ProjectFileDialog factory; the immediate package-private constructors remain available to the older isolated decision fixtures. No external service or extra thread pool is introduced.

A Loading modal explains the pending read/validation and offers Cancel Open. Escape cancels the same request. The active project, document lifetime, selection, dirty/saved state, history, private Properties and raw seed text remain owned by the current document until installation succeeds. Cancellation requests interruption and retires publication authority immediately. Some filesystem/compiler work may finish after cancellation; that completion cannot publish. This is cooperative cancellation, not a guarantee of immediate interruption of arbitrary filesystem calls.

Successful preparation queues one UI publication. Identity/revision/lifetime, path/checkpoint, selection/private values and raw-input ownership are rechecked; a final committed JSON comparison also rejects direct mutations that preserve object identity, revision and dirty state. The original validator identity must still apply. A changed owner retires the old decision, preserving the current data and requiring a new request. UI publication installs the already validated candidate without rerunning compiler validation. It replaces the document once, creates a new lifetime, clears history, establishes the prepared saved checkpoint, clears selection/private state and refreshes the resource UI. Completion callback failures are reported after installation and cannot turn the completed replacement into a retry of the old Open.

Malformed input, file errors, compiler failures, scheduler rejection and failed UI handoff remain recoverable. The terminal Future is observed on the UI thread if no callback arrived. Failure returns to the path dialog with bounded error text; the exact entered path remains across a fast completion or a rendered loading transition. Retry is explicit, with a fresh preparation. Completed and cancelled predecessor callbacks cannot consume or clear a successor request. Desktop lifecycle teardown retires pending Open work.

The UI still performs short ownership snapshots/comparisons; responsiveness for very large committed documents requires measurement. Save/Save As and the existing draft Apply remain explicit synchronous transactions in this scoped batch. Their correctness and any remaining responsiveness work stay in the broader audit. No project format, schema, resource identity, generated value or preference default changes.

## Model API

DocumentSession.requestOpen(path) captures the document identity/lifetime/path/saved checkpoint and current validation policy without file IO. Its OpenRequest.prepare() may run on a worker. It loads into a separate candidate, validates it, owns an immutable diagnostic list and computes the exact serialized checkpoint. PreparedOpen exposes success() and diagnostics(), with no mutable candidate accessor.

On the owning document thread, applyOpen(prepared) verifies the original DocumentSession, managed document identity/lifetime/path/checkpoint and validator identity. Invalid, foreign, stale or consumed preparations reject before replacement. A successful preparation is single-use. DocumentSession.open(path) retains its synchronous signature through the same prepare/apply path. New and Save behavior remains unchanged.

DocumentSession itself remains owner-thread confined. Only request preparation is delegated; a configured custom validator must be safe for the candidate-only worker call. Use a real executor/Future/UI queue handoff for visibility. Callers retaining mutable data() references must additionally enforce their own pending-decision/raw-value ownership; the editor supplies that final complete-value check. This is an additive model API carried in the independent engine JAR, with no native/UI dependency.

## Deferred definitions

Eight PreparedProjectOpenTest definitions cover no worker publication, off-thread captured validation, no second validation, immutable rejection diagnostics, missing files/validator Errors, foreign owners/policy changes, managed edit/save/New invalidation, single use, exact captured file/checkpoint values and synchronous API compatibility.

Twelve BackgroundProjectOpenTest definitions cover controlled worker/UI queues, cancellation before execution and after handoff, direct same-revision committed edits, private draft changes, policy-change retry, parser/compiler rejection, unreadable worker Errors, scheduler/UI rejection, shutdown, successor callbacks and actual worker-thread validation. Existing HelpCatalogTest now defines the direct Open context-help topic.

ProjectFileCompletionProbe invokes a separate eight-case ProjectOpenProbe using the production background factory and real ImGui input. Controlled compiler gates define twelve responsive frames while no replacement is permitted. Cases cover success, Cancel Open, Escape, raw committed/private changes, compiler Error, malformed input and policy replacement. Rejected requests retain complete authored/private values and preferences; failed-path cases retain the entered path. The existing deferred Python checker reads its nested report and checks constant .25/.75 project definitions, exact seeds and final phases. These synthetic ImGui cases do not certify physical input, DPI, Windows 11 hardware or broad GUI performance.

The existing engine-only ProjectParseConsumer command includes a prepared-open sub-replay: worker preparation preserves the active document/history; owner-thread Apply installs one consumed candidate and resets its checkpoint/history. Independent analytical expectations require 42 scalar observations (x+.125 before, x+.625 after) and 588 exact signed/root-2/4 voxel observations. With threshold .25 and inclusive Y=-2..2, selection moves from X>=1 to X>=0. Saved incoming bytes remain unchanged. The original parsing replay remains intact.

All commands/artifact directories are reused beneath existing manual-only workflow invocations, without changing triggers or dispatching execution. Full concurrency/load, large-file responsiveness, Save/draft/other IO integration, physical/native/platform/Windows 11 and final release acceptance remain pending.

The subsequent [background Save follow-up](BACKGROUND_PROJECT_SAVE.md) adds worker snapshot validation/write/force and guarded atomic publication to the same desktop dialog factory. The earlier synchronous Save/draft note describes this Open-only batch; draft Apply and remaining IO responsiveness still require audit.

The subsequent [explicit launch integration](EXPLICIT_PROJECT_LAUNCH.md) also routes the command-line filename through this background path, with an admission receipt, retained retry text and per-instance argument ownership. Its gated whole-Desktop and pre-frame dialog definitions remain unexecuted.
