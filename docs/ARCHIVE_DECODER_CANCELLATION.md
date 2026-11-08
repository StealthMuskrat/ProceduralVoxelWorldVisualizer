# Archive decoder cancellation and retry

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, independent consumer, checker, packaging operation or workflow ran. The observed baseline remains **922 tests / 120 suites**. No A01–A36, Windows 11 or release gate advances.

## Preserve the decoder's cancellation result

VoxelChunk's tree decoder already checks thread interruption at node boundaries and throws CancellationException without clearing the flag. VoxelArchive.Reader.read previously caught every RuntimeException from the decoding scope and wrapped it as IOException("Corrupt archive chunk", cause). A caller withdrawing a decode therefore received a corruption error even for unchanged valid bytes, unlike direct tree decoding and archive writing.

Reader.read now rethrows CancellationException before its ordinary runtime-corruption wrapper. The same exception escapes, interruption remains set, and no partial VoxelChunk is returned. Existing try-with-resources and finally cleanup still close the decoding streams and end the inflater. Cancellation does not retire or close a retained Reader: after explicitly clearing interruption, the caller can retry through its existing indexed seek with fresh decode state. Previously completed chunks remain independent. readSingleChunk propagates the same cancellation while its existing resource scope closes the reader.

This preserves existing decoder checkpoints; it adds no interrupt check before payload allocation/file transfer and does not preempt blocking IO. Covered EMPTY entries and unarchived-position queries still use their existing non-decoding paths. Closed-reader checks keep their IOException precedence. Header construction failures retain the earlier malformed-header/cleanup policy. Genuine compressed/tree/dictionary corruption and missing semantic IDs retain their rejection paths after interruption is cleared.

Writer behavior is unchanged: cancelled encoding before file/index mutation can retry on its open writer; cancelled writeSingleChunk aborts owned staging and preserves the previous destination. Public signatures, semantic registries, EMPTY/coverage distinctions, compression/index/resource limits, archive versions 1/2 and project/editor formats remain unchanged. This is error classification, not new encoding, fallback publication or a measured IO responsiveness guarantee.

## Deferred module definitions

Six ArchiveCancellationTest definitions cover raw/archive cancellation equivalence across both formats and roots 2/4/8; repeated same-reader retry with retained chunks, exact bytes and reordered registry; interruption after payload transfer using the existing package-private transferred-handle boundary, with no premature reader close; single-chunk cancellation/close/reopen; cancellation followed by genuine corrupt-payload rejection with healthy EMPTY coverage; cancelled append retry producing the same bytes as a clean writer; and cancelled single-write staging cleanup preserving prior bytes. The last two behaviors are unchanged writer controls. Each harness clears only its deliberately set interrupt flag in finally. These definitions remain uncompiled/untested.

## Deferred independent consumer

ArchiveCancellationConsumer is invoked by ArchiveReaderRecoveryConsumer, already reached by the engine-JAR-only ArchiveRecoveryConsumer command. Both formats and roots 2/4/8 cover uniform, patterned, sparse and covered EMPTY chunks under a reordered runtime registry. The replay defines **36 payload decode cancellations, six cancelled appends, six aborted single writes and six single-reader cancellations**. It also defines **six cancellation-then-corruption pairs and six closed-reader rejections**, retaining existing metadata and non-decoding coverage behavior. Retry/control archive bytes, rejected input bytes, destination bytes and both semantic registries remain exact.

Independent integer cell formulas define **4,672 source-cell observations** after encoding/retry and **66 retained chunks / 12,848 exact remapped cell expectations** after reader closure. Three retained versions per occupied root (before cancellation and two explicit retries), covered EMPTY roots and reopened singleton roots make those counts explicit. No engine-generated oracle is used for expected voxel values. All counts describe future definitions, not observed results.

The future report is `archive-recovery/reader-recovery/cancellation/archive-cancellation-report.json`; no report has been generated. Existing manual-only Linux/Windows commands compile all example sources and invoke the parent consumer. Earlier writer/reader/publication/parse/native replay dimensions remain unchanged. Source review and git whitespace/content/metadata checks are the available verification; the broader archive/scheduler/acceptance audit remains open.
