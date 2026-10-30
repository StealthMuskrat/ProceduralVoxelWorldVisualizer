"""Deferred independent replay of real Desktop startup and its ready backbuffer."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zlib


def ready_png(path, receipt):
    data = path.read_bytes()
    assert len(data) <= 70 << 20 and data[:8] == b'\x89PNG\r\n\x1a\n'
    assert hashlib.sha256(data).hexdigest() == receipt['sha256']
    at, payload, header, ended = 8, bytearray(), None, False
    while at < len(data):
        length, = struct.unpack_from('>I', data, at)
        kind = data[at + 4:at + 8]
        assert at + length + 12 <= len(data)
        chunk = data[at + 8:at + 8 + length]
        crc, = struct.unpack_from('>I', data, at + 8 + length)
        assert zlib.crc32(kind + chunk) & 0xffffffff == crc
        if kind == b'IHDR':
            assert header is None and at == 8
            header = struct.unpack('>IIBBBBB', chunk)
        elif kind == b'IDAT':
            payload.extend(chunk)
        elif kind == b'IEND':
            assert length == 0
            ended = True
            assert at + length + 12 == len(data)
        at += length + 12
    assert ended and header is not None
    width, height, depth, color, compression, filtering, interlace = header
    assert (depth, color, compression, filtering, interlace) == (8, 2, 0, 0, 0)
    assert width == receipt['width'] and height == receipt['height']
    assert width > 0 and height > 0 and width * height <= (64 << 20) // 4
    stride = width * 3
    size = (stride + 1) * height
    decoder = zlib.decompressobj()
    raw = decoder.decompress(payload, size + 1)
    assert len(raw) == size and decoder.eof and not decoder.unused_data
    assert not decoder.unconsumed_tail
    previous, colors = bytearray(stride), set()
    for y in range(height):
        start = y * (stride + 1)
        mode = raw[start]
        assert 0 <= mode <= 4
        row = bytearray(raw[start + 1:start + 1 + stride])
        for x in range(stride):
            left = row[x - 3] if x >= 3 else 0
            above = previous[x]
            corner = previous[x - 3] if x >= 3 else 0
            if mode == 1:
                predictor = left
            elif mode == 2:
                predictor = above
            elif mode == 3:
                predictor = (left + above) // 2
            elif mode == 4:
                p = left + above - corner
                distances = (abs(p - left), abs(p - above), abs(p - corner))
                predictor = (left, above, corner)[distances.index(min(distances))]
            else:
                predictor = 0
            row[x] = (row[x] + predictor) & 255
        if len(colors) < 256:
            for x in range(0, stride, 3):
                colors.add(bytes(row[x:x + 3]))
                if len(colors) == 256:
                    break
        previous = row
    assert 8 <= len(colors) == receipt['colors'] <= 256


out = Path(sys.argv[1])
report = json.loads((out / 'startup-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
assert report['realDesktop'] is True and report['emptyDefaultProject'] is True
for flag in ('physicalFirstPixelTimingVerified', 'physicalMaximizeVerified',
             'physicalDpiVerified', 'physicalDetachedVerified', 'packagedWindows11Verified'):
    assert report[flag] is False, flag
acquisition = ['window-created', 'context-created', 'backends-ready', 'services-ready',
               'frame-rendered', 'before-show', 'shown', 'presented']
scenarios = acquisition[:-1] + ['visible']
assert [case['scenario'] for case in report['cases']] == scenarios
for case in report['cases']:
    scenario = case['scenario']
    injected = scenario != 'visible'
    count = acquisition.index(scenario) + 1 if injected else len(acquisition)
    prefix = acquisition[:count]
    release = []
    if 'services-ready' in prefix:
        release += ['viewports-released', 'updates-released']
    if 'backends-ready' in prefix:
        release += ['renderer-released', 'window-backend-released']
    if 'context-created' in prefix:
        release += ['context-released']
    release += ['window-released', 'state-closed']
    events = case['stages']
    assert [event['stage'] for event in events] == prefix + release
    assert case['injected'] is injected and case['released'] is True
    assert case['projectEmpty'] is True
    assert case['failedPreferencesPreserved'] is injected
    rendered = next((e['frames'] for e in events if e['stage'] == 'frame-rendered'), None)
    for event in events:
        stage = event['stage']
        assert event['resources'] == 0 and event['bookmarks'] == 0
        assert event['projectDirty'] is False
        if stage in acquisition[:6] or stage in ('window-released', 'state-closed'):
            assert event['visible'] is False
        if stage in ('shown', 'presented'):
            assert event['visible'] is True and event['frames'] == rendered
        if stage in acquisition[:4]:
            assert event['frames'] == 0
        if stage == 'frame-rendered':
            assert event['vertices'] > 0 and rendered >= 1
        if stage == 'window-created' or stage in ('context-released', 'window-released', 'state-closed'):
            assert event['context'] is False
        else:
            assert event['context'] is True
    preferences = out / scenario / 'user-config.json'
    saved = preferences.read_bytes()
    assert hashlib.sha256(saved).hexdigest() == case['savedPreferencesSha256']
    if injected:
        assert case['initialPreferencesSha256'] == case['savedPreferencesSha256']
        assert not case['image']
    else:
        user = json.loads(saved)
        assert user['formatVersion'] == 1 and user['workspace']['docking'].strip()
        assert case['image']['file'] == 'visible/ready-frame.png'
        ready_png(out / case['image']['file'], case['image'])
assert not list(out.rglob('.user-config-*.tmp'))
print('Startup acquisition/presentation/cleanup order, empty project, preference ownership and ready PNG PASS')
