#!/usr/bin/env python3
"""Rebuild deterministic, original GameTest NBT fixtures using only Python's stdlib."""
import gzip
import struct
from pathlib import Path


def string(value):
    encoded = value.encode()
    return struct.pack('>H', len(encoded)) + encoded


def tag(kind, name, value):
    return bytes([kind]) + string(name) + value


def list_int(values):
    return bytes([3]) + struct.pack('>i', len(values)) + b''.join(struct.pack('>i', v) for v in values)


def template(name, width, height, floor=False, namespace='paracraft'):
    body = tag(3, 'DataVersion', struct.pack('>i', 3465))
    body += tag(9, 'size', list_int([width, height, width]))
    palette = tag(8, 'Name', string('minecraft:stone' if floor else 'minecraft:air')) + b'\0'
    body += tag(9, 'palette', bytes([10]) + struct.pack('>i', 1) + palette)
    blocks = []
    if floor:
        for x in range(width):
            for z in range(width):
                blocks.append(tag(9, 'pos', list_int([x, 0, z])) + tag(3, 'state', struct.pack('>i', 0)) + b'\0')
    body += tag(9, 'blocks', bytes([10]) + struct.pack('>i', len(blocks)) + b''.join(blocks))
    body += tag(9, 'entities', bytes([10]) + struct.pack('>i', 0))
    path = Path(__file__).resolve().parent.parent / 'src/gameTest/resources/data' / namespace / 'structures' / (name + '.nbt')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(b'\x0a\x00\x00' + body + b'\0', mtime=0))
    print(path.name, path.stat().st_size, 'bytes')


if __name__ == '__main__':
    template('empty', 8, 8)
    template('arena', 96, 8, floor=True)
    template('empty', 8, 8, namespace='paracraft_restart')
