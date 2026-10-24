# Exact palette storage and archive compatibility

`VoxelChunk` is an immutable exact snapshot. EMPTY is palette index 0; positive runtime IDs are stored once in a private chunk dictionary. Immediate uniform nodes store a negative dictionary index. Branches store eight child references in an int arena. No object is allocated for each voxel or persistent tree node.

Heterogeneous leaves contain at most 4³ cells in X-fast, Z-row, Y-plane order. A leaf has a small local dictionary of chunk-palette indices and uses the minimum 1..6 bits per cell, including indices crossing 32-bit word boundaries. The builder chooses a primitive dense leaf when a local dictionary would take at least as many arena ints. Thus a chunk with hundreds of types can still use one-bit leaves where each leaf contains only EMPTY and one material. Runtime IDs up to Integer.MAX_VALUE are supported. Local dictionary construction and chunk interning are deterministic; palette indices are private representation, not stable public identities.

`paletteRuntimeIds()` returns a defensive copy including EMPTY. `storageStatistics()` reports reachable regions/nodes, occupied cells, palette entries, arena ints and packed data words. `estimatedBytes()` includes the chunk, its compact arena and palette, excluding registries, caller ownership and construction scratch. The statistics call traverses stored nodes/cells and is intended for diagnostics rather than a per-frame hot path. Public representation labels remain EMPTY/UNIFORM/BRANCH/PACKED; PACKED includes the dense fallback.

## Immutable region replacement

```java
VoxelChunk one = original.withVoxel(255, 255, 255, material);
VoxelChunk filled = original.withFilledBox(0, 0, 0, 127, 255, 255, material);
VoxelChunk cleared = filled.withFilledBox(0, 0, 0, 127, 255, 255, 0);
```

Boxes are inclusive chunk-local coordinates. Out-of-bounds, reversed boxes and negative types reject before replacement. A fully covered subtree becomes an immediate EMPTY/UNIFORM reference. Only intersecting leaves expand into at most 64 temporary cells. The method copies retained tree content into compact new storage; its cost includes retained storage, so repeated single-cell edits are not an in-place staging API. Coalescence removes redundant branches and unreachable palette values. A full 256³ replacement allocates a uniform snapshot, not a root-sized voxel array. Original snapshots remain unchanged on success, sampling failure or interruption; partial replacement checks thread interruption at node boundaries and throws CancellationException without clearing the interrupt flag.

Engine staging admission adds 32 bytes per possible chunk palette entry to the previous conservative 16 bytes per root voxel and 2D cache allowance. The palette bound uses the compiled registry and root cell count. Collection admission adds the final dictionary bound to its retained-arena estimate. These are admission estimates rather than measured JVM heap accounting.

## Archive versions

The default writer now emits archive version 2. Readers accept versions 1 and 2. Version 1 remains writable for consumers using an older engine:

```java
try (var writer = VoxelArchive.openWorldWriter(path, registry, VoxelArchive.Format.LEGACY_V1)) {
    writer.append(chunk);
    writer.finish();
}
```

`Reader.version()` exposes the header version. Old readers reject version 2; use explicit LEGACY_V1 when producing files for them. Project JSON remains format 2 and is unaffected by binary archive versioning.

Both archive versions retain the same signature, zlib compression identifier, semantic string dictionary, 28-byte random-access index entries and coverage semantics. A covered root with no payload is exact EMPTY; an uncovered position returns Optional.empty. Serialization always maps back to semantic runtime values before writing the archive dictionary indices. No display LOD or palette implementation detail is persisted as voxel identity.

Tree tags 0..3 retain the v1 meanings: EMPTY, positive uniform dictionary ID, eight child trees, or dense leaf IDs. Version 2 additionally supports tag 4: one unsigned byte palette count, ascending distinct nonnegative int32 dictionary IDs, and packed uint32 words. Bits are the minimum width for the palette count; cell i starts at bit i×width, low bit first across words, while each word is serialized big-endian. Every local entry must occur and unused high bits must be zero. Version 2 chooses tag 4 only when smaller than a dense leaf. `writeTree` retains the v1 raw-tree contract; `writePackedTree` enables tag 4, and `readTree` accepts either. Mapping callbacks must be stable during a call, nonnegative and map EMPTY to zero. A mapping may collapse types to EMPTY/uniform.

The machine-readable vocabulary and resource limits are in `registries/voxel-storage-format.json`. Readers validate node shape, dictionary and local indices, ordering/usage/padding, compressed termination, trailing compressed bytes and contiguous indexed payload coverage. They reject malformed data before returning a chunk. Missing required semantic IDs are reported without modifying the registry; unused file-level dictionary entries do not require resolution. Dictionaries are limited to one million entries and 16 MiB of encoded bytes, roots to 100 MiB compressed and 80 MiB decoded, and coverage to one million entries. Writers stage a sibling file and replace the destination on successful finish. Constructor failures clean up staging files; rejected appends leave the writer usable.

## Evidence and scope

`generate_storage_vectors.py` constructs reference bit streams and complete v1/v2 archives with Python integer/struct/zlib operations, without invoking Java. PackedArchiveTest consumes these fixed vectors, including multiword 3/5/6-bit layouts and semantic remapping. PaletteStorageTest compares random inclusive fills/clears with a dense oracle, checks snapshot independence, all root sizes, 255 boundaries, local-vs-global palettes, dense fallback, primitive interner growth and concurrent readers.

`StorageBenchmark` is an explicit independent-engine entry point that records generation, bulk-fill, snapshot size, palette counts and v1/v2 raw-tree sizes for uniform/sparse/checkerboard/many-type scenes. CI retains its CSV on Linux and Windows. It measures the current storage policy; it does not compare complete application throughput against a certified baseline or claim representative hardware performance. The renderer, archive consumers and engine regression suite are validated after this batch. General mutable staging, archive recovery, checksums beyond zlib, and hardware performance certification are outside this extension.
