"""Deferred independent fractional native font, tooltip geometry and preview receipt replay."""
import json
import math
from pathlib import Path
import sys

report = json.loads((Path(sys.argv[1]) / 'tooltip-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['dpiSource'] == 'synthetic'
for key in ('projectPreserved', 'parentItemPreserved', 'fractionalMetrics', 'nativeHover',
            'nativeDrag', 'colorHoverPreserved', 'nativeSlotRestored'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuTextureVerified',
            'multilineInternalsVerified', 'colorPickerInternalsVerified'):
    assert report[key] is False, key
assert report['singleVertices'] > 0
assert report['replacementVertices'] == report['singleVertices']
atlas = report['atlas']
assert atlas['rasterSize'] == 52 and atlas['fontScale'] == .25
assert atlas['rgbaBytes'] == atlas['width'] * atlas['height'] * 4 <= 16 << 20
factors = ((1, 1, 1, 1), (2, 1, 1.25, 1.5), (2, 2, 1.25, 1.5),
           (3, 1, 1, 1), (2, 1, 2.5, 2.5), (1, 1, 1.125, 1.25))
forms = ('text', 'button', 'edit3', 'edit4', 'drag')
assert len(report['cases']) == 30
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, form) for i in range(6) for form in forms}
source = '100% ### snow 雪 😀\n' + 'full hover guidance ' * 1000


def utf16_length(value):
    return len(value.encode('utf-16-le')) // 2


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    content, framebuffer, global_, window = factors[case['scenario']]
    assert (case['content'], case['framebuffer'], case['global'], case['window']) == (content, framebuffer, global_, window)
    expected = 13 * global_ * window * content / framebuffer
    shown = case['tooltip']
    for measured in (case['rootFont'], case['nestedFont'], case['afterFont'], shown['parentFont'], shown['font']):
        assert math.isfinite(measured) and math.isclose(measured, expected, rel_tol=1e-6, abs_tol=.001)
    if case['form'] == 'drag':
        assert math.isclose(case['dragFont'], expected, rel_tol=1e-6, abs_tol=.001)
    if case['form'] == 'text':
        assert math.isclose(case['nativeFont'], 13 * global_ * content / framebuffer, rel_tol=1e-6, abs_tol=.001)
    assert case['itemBefore'] == case['itemAfter']
    contains(shown['work'], shown['bounds'])
    assert 0 < shown['budget']['width'] <= 36 * expected + .1
    assert 0 < shown['budget']['height'] <= 12 * expected + .1
    assert utf16_length(shown['preview']) <= 4099
    assert shown['viewport'] != 0
    if case['form'] in ('text', 'drag'):
        assert shown['sourceCharacters'] == utf16_length(source)
        assert shown['truncated'] is True and shown['preview'].endswith('...')
        assert source.startswith(shown['preview'][:-3])
    else:
        assert shown['truncated'] is False
        assert shown['preview'].startswith('R 0.200  G 0.400  B 0.600  A ')
        assert shown['preview'].endswith('1.000' if case['form'] == 'edit3' else '0.800')
print('Fractional font inheritance, bounded native tooltip previews/replacement and hover/drag receipts PASS')
