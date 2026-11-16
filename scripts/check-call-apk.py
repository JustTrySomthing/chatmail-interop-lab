#!/usr/bin/env python3
"""Check the packaged DEX, not debug classes: JNI_OnLoad needs these exact names."""
import struct
import sys
import zipfile

def uint(data, offset):
    return struct.unpack_from('<I', data, offset)[0]

def leb(data, offset):
    result = 0
    for shift in range(0, 35, 7):
        value = data[offset]
        offset += 1
        result |= (value & 127) << shift
        if not value & 128:
            return result, offset
    raise ValueError('Invalid DEX LEB128')

def classes(data):
    strings = []
    for i in range(uint(data, 56)):
        offset = uint(data, uint(data, 60) + 4 * i)
        _, offset = leb(data, offset)
        strings.append(data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace'))
    types = [strings[uint(data, uint(data, 68) + 4*i)] for i in range(uint(data, 64))]
    methods = [strings[uint(data, uint(data, 92) + 8*i + 4)] for i in range(uint(data, 88))]
    for i in range(uint(data, 96)):
        base = uint(data, 100) + 32*i
        name = types[uint(data, base)]
        offset = uint(data, base + 24)
        names = set()
        if offset:
            counts = []
            for _ in range(4):
                count, offset = leb(data, offset)
                counts.append(count)
            for _ in range(counts[0] + counts[1]):
                _, offset = leb(data, offset)
                _, offset = leb(data, offset)
            for count in counts[2:]:
                method = 0
                for _ in range(count):
                    delta, offset = leb(data, offset)
                    method += delta
                    _, offset = leb(data, offset)
                    _, offset = leb(data, offset)
                    names.add(methods[method])
        yield name, names

required = {
    'Lorg/jni_zero/JniInit;': {'init', 'crashIfMultiplexingMisaligned'},
    'Lorg/jni_zero/JniUtil;': {'arrayToMap', 'mapToArray'},
    'Lorg/webrtc/PeerConnectionFactory;': {'initialize'},
}
with zipfile.ZipFile(sys.argv[1]) as apk:
    found = dict(item for path in apk.namelist() if path.startswith('classes') and path.endswith('.dex')
                 for item in classes(apk.read(path)))
    for name, methods in required.items():
        assert name in found, f'Missing native bootstrap class: {name}'
        assert methods <= found[name], f'Missing native bootstrap methods: {name}: {methods - found[name]}'
    assert 'lib/arm64-v8a/libjingle_peerconnection_so.so' in apk.namelist()
print('PASS: release APK contains native WebRTC bootstrap classes and methods')
