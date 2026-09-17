#!/usr/bin/env python3
"""
Generator of the Veloce pipe texture (veloce_pipe.png) - an atlas of 4 regions.

Goal: the pipe has a CONTINUOUS window along its length + a thin band (clamp)
per block.

ATLAS 16x16:
  A  [0,0 .. 8,8]     FACE   - a full frame + a 4x4 window (the pipe's face)
  B1 [8,0 .. 12,8]    SIDE   - for the east/west walls (u=Z=4, v=Y=8)
  B2 [8,8 .. 16,12]   SIDE   - for the up/down walls  (u=X=8, v=Z=4)
  C  [0,8 .. 8,16]    HEAD   - nozzle

THE KEY RULE (why B1 and B2 are separate):
  Different walls have different UV axes. The north arm is an element 8(X) x 8(Y) x 4(Z):
    east/west (the ZY plane): u=Z(4) - length, v=Y(8) - width
    up/down   (the XZ plane): u=X(8) - width,  v=Z(4) - length
  so on up/down the axes are SWAPPED. A single shared region would give
  stretching and the band would run in the wrong direction (the effect: no band
  from the top and a grid pattern on the sides).

  That is why B1 has 4 columns x 8 rows and B2 has 8 columns x 4 rows -
  it is the same pattern rotated by 90 degrees.
"""
import struct, zlib

W = H = 16
BLACK = (18, 18, 20, 255)
CLEAR = (0, 0, 0, 0)

px = [[CLEAR for _ in range(W)] for _ in range(H)]


def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[y][x] = c


def region_A():
    """FACE [0,0..8,8]: a full frame + a 4x4 window (the centre)."""
    for y in range(8):
        for x in range(8):
            put(x, y, BLACK)
    for y in range(2, 6):
        for x in range(2, 6):
            put(x, y, CLEAR)


def region_B1():
    """SIDE east/west [8,0..12,8]: 4 columns (Z) x 8 rows (Y).

    column 0 (Z=0) = a band across the full width -> the block's clamp
    columns 1-3    = the window
    rows 0,1,6,7   = the band on the sides (it runs along the pipe)
    """
    for y in range(8):
        for x in range(8, 12):
            put(x, y, BLACK)
    for y in range(2, 6):
        for x in range(9, 12):     # column 8 stays a band
            put(x, y, CLEAR)


def region_B2():
    """SIDE up/down [8,8..16,12]: 8 columns (X) x 4 rows (Z).

    row 0 (Z=0)     = a band across the full width -> the block's clamp
    rows 1-3        = the window
    columns 0,1,6,7 = the band on the sides
    """
    for y in range(8, 12):
        for x in range(8, 16):
            put(x, y, BLACK)
    for y in range(9, 12):         # row 8 stays a band
        for x in range(10, 14):    # the middle of the width
            put(x, y, CLEAR)


def region_C():
    """HEAD [0,8..8,16]: a frame + a 4x4 window."""
    for y in range(8, 16):
        for x in range(0, 8):
            put(x, y, BLACK)
    for y in range(10, 14):
        for x in range(2, 6):
            put(x, y, CLEAR)


region_A()
region_B1()
region_B2()
region_C()


def chunk(tag, data):
    c = struct.pack('>I', len(data)) + tag + data
    return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)


raw = b''
for y in range(H):
    raw += b'\x00'
    for x in range(W):
        raw += bytes(px[y][x])

png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(raw, 9))
       + chunk(b'IEND', b''))

out = 'assets/craftingveloce/textures/block/veloce_pipe.png'
with open(out, 'wb') as f:
    f.write(png)
print(f"saved {out} ({len(png)} bytes)")

print("\nAtlas (K=black, .=window):")
for y in range(H):
    s = ''.join('.' if px[y][x][3] == 0 else 'K' for x in range(W))
    note = ''
    if y == 0: note = '   A=[0,0..8,8] FACE | B1=[8,0..12,8] SIDE ew'
    if y == 8: note = '   C=[0,8..8,16] HEAD | B2=[8,8..16,12] SIDE ud'
    print(f"  {y:2d} {s}{note}")
