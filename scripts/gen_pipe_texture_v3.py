#!/usr/bin/env python3
"""
Generator tekstury rury Veloce (veloce_pipe.png) - atlas 4 regionow.

Cel: rura ma CIAGLE okno wzdluz dlugosci + cienka opaske (obejme) co blok.

ATLAS 16x16:
  A  [0,0 .. 8,8]     LICO   - pelna ramka + okno 4x4 (czolo rury)
  B1 [8,0 .. 12,8]    BOK    - dla scian east/west (u=Z=4, v=Y=8)
  B2 [8,8 .. 16,12]   BOK    - dla scian up/down  (u=X=8, v=Z=4)
  C  [0,8 .. 8,16]    GLOWICA- nozzle

KLUCZOWA ZASADA (dlaczego B1 i B2 sa osobne):
  Rozne sciany maja rozne osie UV. Ramię north to element 8(X) x 8(Y) x 4(Z):
    east/west (plaszczyzna ZY): u=Z(4) - dlugosc, v=Y(8) - szerokosc
    up/down   (plaszczyzna XZ): u=X(8) - szerokosc, v=Z(4) - dlugosc
  czyli na up/down osie sa ZAMIENIONE. Jeden wspolny region dalby
  rozciagniecie i opaska poszlaby w zlym kierunku (efekt: brak opaski
  od gory i kratki po bokach).

  Dlatego B1 ma 4 kolumny x 8 wierszy, a B2 8 kolumn x 4 wiersze -
  to ten sam wzor obrocony o 90 stopni.
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
    """LICO [0,0..8,8]: pelna ramka + okno 4x4 (srodek)."""
    for y in range(8):
        for x in range(8):
            put(x, y, BLACK)
    for y in range(2, 6):
        for x in range(2, 6):
            put(x, y, CLEAR)


def region_B1():
    """BOK east/west [8,0..12,8]: 4 kolumny (Z) x 8 wierszy (Y).

    kolumna 0 (Z=0) = opaska na calej szerokosci -> obejma bloku
    kolumny 1-3     = okno
    wiersze 0,1,6,7 = opaska po bokach (biegnie wzdluz rury)
    """
    for y in range(8):
        for x in range(8, 12):
            put(x, y, BLACK)
    for y in range(2, 6):
        for x in range(9, 12):     # kolumna 8 zostaje opaska
            put(x, y, CLEAR)


def region_B2():
    """BOK up/down [8,8..16,12]: 8 kolumn (X) x 4 wiersze (Z).

    wiersz 0 (Z=0)  = opaska na calej szerokosci -> obejma bloku
    wiersze 1-3     = okno
    kolumny 0,1,6,7 = opaska po bokach
    """
    for y in range(8, 12):
        for x in range(8, 16):
            put(x, y, BLACK)
    for y in range(9, 12):         # wiersz 8 zostaje opaska
        for x in range(10, 14):    # srodek szerokosci
            put(x, y, CLEAR)


def region_C():
    """GLOWICA [0,8..8,16]: ramka + okno 4x4."""
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
print(f"zapisano {out} ({len(png)} bajtow)")

print("\nAtlas (K=czarny, .=okno):")
for y in range(H):
    s = ''.join('.' if px[y][x][3] == 0 else 'K' for x in range(W))
    note = ''
    if y == 0: note = '   A=[0,0..8,8] LICO | B1=[8,0..12,8] BOK ew'
    if y == 8: note = '   C=[0,8..8,16] GLOW | B2=[8,8..16,12] BOK ud'
    print(f"  {y:2d} {s}{note}")
