"""Independent integer/struct specification vectors; no Java implementation is invoked."""
from pathlib import Path
import json, struct, zlib
I = lambda v: struct.pack('>I', v)
Q = lambda v: struct.pack('>Q', v)
def packed(size, count):
    values = [i % count for i in range(size ** 3)]
    width = (count - 1).bit_length()
    # One mathematical little-endian bit stream, then big-endian 32-bit wire words.
    stream = sum(value << (i * width) for i, value in enumerate(values))
    words = (len(values) * width + 31) // 32
    tree = bytes([4, count]) + b''.join(I(i) for i in range(count))
    tree += b''.join(I((stream >> (i * 32)) & 0xffffffff) for i in range(words))
    return values, tree
vectors = []
for size, count in [(2, 3), (4, 2), (4, 3), (4, 5), (4, 9), (4, 17), (4, 33)]:
    values, tree = packed(size, count)
    vectors.append(dict(size=size, count=count, values=values, treeHex=tree.hex()))
values, compact = packed(4, 5)
legacy = bytes([3]) + b''.join(I(v) for v in values)
def archive(version, tree):
    names = ['EMPTY', 'a', 'b', 'c', 'd']
    head = Q(0x505643574f524c44) + I(version) + I(1) + I(len(names))
    for name in names:
        data = name.encode('ascii'); head += struct.pack('>H', len(data)) + data
    payload = zlib.compress(tree, 6)
    start = len(head) + len(payload)
    entries = struct.pack('>iiiiqi', -3, 2, 4, 4, len(head), len(payload))
    entries += struct.pack('>iiiiqi', 1, 1, 1, 8, 0, 0)
    return head + payload + I(2) + entries + Q(start) + Q(0x505643494e444558)
result = dict(provenance=__doc__, leafVectors=vectors, legacyTreeHex=legacy.hex(),
              archiveV1Hex=archive(1, legacy).hex(), archiveV2Hex=archive(2, compact).hex())
path = Path(__file__).resolve().parent.parent / 'voxel-storage/src/test/resources/io/voxelcreator/storage/tree-vectors.json'
path.write_text(json.dumps(result, indent=2) + '\n')
print(path)
