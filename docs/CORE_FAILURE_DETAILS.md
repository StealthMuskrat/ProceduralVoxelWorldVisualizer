# Safe expression and compilation failure details

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, real update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and final release acceptance remain pending; no A01–A36 gate advances.

## One description cannot replace its original failure

Expression.BatchFailure directly called a host RuntimeException's getMessage while constructing the failed-sample exception. A throwing accessor could replace the intended BatchFailure and hide its sample index/cause. CompilationFailure separately read getMessage for its exception text and Diagnostic, potentially reading it three times. Mutable descriptions could disagree, null descriptions could differ between exception and diagnostic, and an unreadable accessor could prevent structured compilation diagnostics entirely.

Both constructors now use a package-private core FailureText helper. It observes one message per construction, catches text-access faults, and uses the failure class name for unreadable/null/blank text, including full binary names for anonymous classes. Descriptions are bounded to 2,048 UTF-16 units, including any truncation ellipsis, without splitting a surrogate pair. An exact-limit message remains exact. The expression-batch sample prefix is separate from the cause-description bound.

CompilationFailure freezes the description before constructing its exception and Diagnostic, so both retain the same string. Code/severity, source/item UUIDs, parameter key, input-pointer escaping and algorithm/parameter locations retain their existing construction. Both exceptions keep the supplied cause without unwrapping an existing CompletionException envelope, reading host cause access or calling host toString. Host failures and their suppressed lists are not mutated. Null causes retain NullPointerException rejection. Ordinary short messages and expression sample-prefix spelling remain exact.

The formatter stays private to engine-core so core does not acquire an engine-api dependency or introduce a public formatting API. Its text policy matches the existing engine-api failure-description bound. No model/editor/native dependency is added.

## Keep expression execution and cleanup

No parser, optimizer, instruction, variable-read, numeric, branch or frame-scheduling implementation changes. Scalar/Boolean batches still stop at the first RuntimeException, retain its sample index/cause and publish only the successful prefix. The failed output, remaining cells and padding remain untouched; variables are never retried for error reporting. Expression batches retain their existing wrapping of CancellationException as BatchFailure and direct propagation of Error. NoiseSampler batch cancellation retains its separate existing behavior.

The expression plan still releases its frame in finally after failure, including failures while reporting. A host message accessor may reenter the same program through nested single/batch evaluation; existing independent frame ownership remains necessary until outer formatting returns. Later healthy batches, named/indexed evaluation and lazy unselected inputs retain their existing contracts.

Host variable providers and message accessors remain cooperative and may block. This follow-up contains thrown description faults; it does not isolate/preempt host code or certify a real-time bound or recovery from an unusable VM. Public signatures/records, grammar, registries, saved formats, deterministic field/voxel arithmetic and UI/preferences behavior remain unchanged.

## Deferred coverage and independent replay

Seven CoreFailureDetailsTest definitions cover one frozen exception/diagnostic description with exact location fields and escaped pointers; unreadable RuntimeException/Error and null/blank text for both constructors without host cause access; actual Scalar/Boolean batches failing at each sample with exact cause/index/prefix; message-time nested evaluation and healthy successor buffers; exact/oversized BMP/astral text, original envelopes and null causes; unchanged cancellation/Error behavior with frame recovery; and failed-batch input epochs followed by lazy skipped inputs and invalid preflight. Existing parser-span, compiler-diagnostic, threading and numeric definitions remain unchanged. None has run.

ExpressionFailureConsumer uses only public engine-JAR classes and is invoked by the existing GridCoordinateConsumer. Scalar/Boolean programs, four failure positions and mutable/unreadable-Runtime/unreadable-Error/long descriptions define **32 batch cases, 80 input reads and 32 message reads**. Independently specified x² + 2x and x ≥ 0 expectations define **48 successful prefix values and 208 untouched cells across 256 retained failed-batch destination cells**. Healthy successor batches add **8 values/16 retained destination cells**, plus **16 exact named/indexed observations**. Retained failed and healthy buffers are checked after every program has run.

Two additional public NoiseCompiler cases use 2D/3D definitions with an unknown expression input. They require the original Expression.Problem and exact 0..7 span, matching exception/diagnostic text, exact code/severity/UUID/parameter/pointer ownership, and unchanged complete authoring JSON. The future report is grid-coordinates/expression-failures/expression-failure-report.txt. No report was generated.

The original grid-coordinate and sampler-failure reports/dimensions, native receipts and other independent replays remain unchanged. Existing manual-only Linux/Windows commands already compile all example sources and invoke GridCoordinateConsumer; no workflow command or trigger changes are required. Source review and git whitespace/content/metadata checks are the available verification. Remaining feature/failure audit, runtime/concurrency/stress validation, native/Windows 11 and release acceptance remain outstanding.
