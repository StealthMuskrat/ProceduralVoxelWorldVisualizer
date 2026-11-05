# Safe batch-sampling failure details

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, real update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and final release acceptance remain pending; no A01–A36 gate advances.

## Preserve failed-sample metadata while describing a host fault

The four primitive batch paths already stop at a failed sample and construct BatchSamplingException with its logical sample index, exact coordinates and original RuntimeException. Successful prefix values remain written, while the failing slot, remaining samples and padding stay untouched. Its constructor previously called cause.getMessage directly. If that host accessor threw, callers received the accessor fault instead of the expected batch exception and lost access to its structured failed-sample metadata. A very large message was also retained without a bound.

BatchSamplingException now uses the existing safe FailureDetails description helper without unwrapping the supplied cause. It reads host getMessage once and freezes that description. Unreadable/null/blank text uses the class name, with the full binary name for anonymous classes. The cause-description portion is bounded to 2,048 UTF-16 units, including any truncation ellipsis, without splitting a surrogate pair. The numeric sample/coordinate prefix is separate from that bound. Ordinary short text and the existing prefix spelling remain exact.

The original cause object remains attached, including an existing CompletionException envelope; constructing a batch failure does not read a host cause accessor or call host toString. Neither the cause nor its suppressed list is mutated. Null constructor causes still reject with NullPointerException. Public fields retain their supplied coordinate bits and long sample index, including signed zero in direct construction.

## Keep traversal and cancellation contracts

Only failure-description construction changes. Grid/point traversal, X-fast order, Z rows/Y planes, source/destination offsets, row/plane strides, signed-zero coordinate handling, preflight, finite-result checks and work checkpoints retain their existing implementations. There is no per-sample formatting or allocation on healthy paths and no sampler retry. Sampler CancellationException and Error still escape with their original identity; they do not become BatchSamplingException. Successful prefixes and healthy later batches use their existing independent caller buffers.

Host samplers and message accessors remain cooperative code and can block. This follow-up contains a thrown text-access fault; it does not preempt host code or certify a real-time cancellation bound or unusable-VM recovery. Engine/model/storage remain independent of editor/native dependencies. Public API shapes, project/archive/schema/registry formats, deterministic sampling/generation arithmetic and UI/preferences behavior remain unchanged.

## Deferred definitions and independent replay

Six new BatchFailureDetailsTest definitions cover all four public batch routes: unreadable messages throwing RuntimeException/Error at each of four failure positions with exact cause/index/coordinates/prefix ownership; one frozen mutable description and a healthy successor buffer; null/blank/oversized BMP/astral and exact-limit text with intact surrogate pairs; unchanged direct sampler cancellation/Error identity; invalid capacity/pre-cancellation before sampler or message access; and direct constructor coordinate bits, ordinary text, retained envelopes with unreadable cause access, and null-cause rejection. Existing compiled-expression span and nonfinite-result definitions remain in SamplingBatchTest. None has run.

BatchFailureConsumer is a separate public engine-JAR client invoked by the existing GridCoordinateConsumer. Its four grid/point routes, first/last failure positions and mutable/unreadable-Runtime/unreadable-Error/long descriptions define **32 cases, 80 failing-batch sampler calls and 32 message reads**. Each failure must retain original cause/index/exact coordinates and one frozen readable detail without retrying sampling or calling host toString.

Independent literal expectations use x + 4z in 2D and 2x + 3y + 5z in 3D, with different signed coordinates, strides and offsets from the module fixtures. The replay checks **48 successful prefix values and 336 untouched destination cells across 384 retained failed-batch cells**. Four healthy successor routes add **16 arithmetic observations and 48 retained destination cells**, with both failed and healthy buffers checked after every route has run. The future report is grid-coordinates/batch-failures/batch-failure-report.txt. No report was generated.

The original grid-coordinate report, its 768 2D/12,288 3D grid/point/scalar comparisons, native receipts and other independent replay dimensions remain unchanged. The manual-only workflow already compiles all example sources and invokes GridCoordinateConsumer on Linux/Windows; no command or trigger change is needed. This custom-sampler replay does not create or edit authoring data or generated chunks.

Source review and git whitespace/content/metadata checks are the available verification. Remaining feature/failure audit, runtime/concurrency/stress validation, native/Windows 11 and release acceptance remain outstanding.
