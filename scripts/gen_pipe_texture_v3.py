#!/usr/bin/env python3
"""
Generator tekstury rury Veloce v3 (veloce_pipe.png).

Zmiana wzgledem v2 (autorstwa uzytkownika):
  - Rura poglebiona z 6x6 na 8x8 px (grubsza).
  - Okno na scianie powiekszone z 2x2 na 4x4 px.
  - Opaska zostaje 2 px z kazdej strony (te same proporcje co wczesniej).

Zaleznosc:  rozmiar sciany S = rozmiar_okna + 2 * grubosc_opaski
            8 = 4 + 2*2   -> OK

Zachowana paleta uzytkownika:
    czern (18,18,20) + szary (58,58,64)

Region rury to teraz [0,0 .. 8,8] (wczesniej [0,0 .. 6,6]).
Modele uzywaja UV [0,0,8,8] - patrz pipe_core.json / pipe_part.json.

Region glowicy (nozzle) zostaje [8,0 .. 16,8] (8x8) - bez zmian,
ale nozzle jest wiekszy (10x10) niz rura, wiec pasek boczny czyta
sie z lewej kolumny regionu jak dotychczas.
"""
import struct, zlib

W = H = 16

BLACK = (18, 18, 20, 255)   # czarna opaska (paleta uzytkownika)
GREY = (58, 58, 64, 255)    # szary odcien (paleta uzytkownika)
CLEAR = (0, 0, 0, 0)        # przezroczyste okno

px = [[CLEAR for _ in range(W)] for _ in range(H)]


def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[y][x] = c


def build_pipe_region():
    """Region A: [0,0 .. 8,8] - sciana rury 8x8, symetryczna.

        szer 0  K K K K K K K K
        szer 1  K K K K K K K K
        szer 2  K K . . . . K K
        szer 3  K K . . . . K K
        szer 4  K K . . . . K K
        szer 5  K K . . . . K K
        szer 6  K K K K K K K K
        szer 7  K K K K K K K K

    Okno 4x4 wysrodkowane, opaska 2 px z kazdej strony.
    Symetria: kol(i)<->kol(7-i), wiersz(i)<->wiersz(7-i).
    """
    SIZE = 8
    BAND = 2                      # grubosc opaski
    WIN = SIZE - 2 * BAND         # 4 -> okno 4x4

    # 1. baza: cala opaska
    for y in range(SIZE):
        for x in range(SIZE):
            put(x, y, BLACK)

    # 2. okno 4x4 na srodku (przezroczyste)
    for y in range(BAND, BAND + WIN):
        for x in range(BAND, BAND + WIN):
            put(x, y, CLEAR)

    # 3. szary pas rozbijajacy plaszczyzne opaski.
    #    Tylko w wierszach opaski (0,1,6,7) i kolumnach srodkowych (2..5),
    #    zeby nie dotknac okna ani nie zaburzyc symetrii.
    for y in (0, 1, 6, 7):
        for x in (2, 3, 4, 5):
            put(x, y, GREY)


def build_head_region():
    """Region B: [8,0 .. 16,8] - glowica 8x8 (bez zmian rozmiaru regionu)."""
    ox, oy = 8, 0
    for y in range(8):
        for x in range(8):
            put(ox + x, oy + y, BLACK)
    # szare pasy
    for y in (1, 6):
        for x in range(1, 7):
            put(ox + x, oy + y, GREY)
    # okno 4x2 na srodku
    for y in (3, 4):
        for x in (2, 3, 4, 5):
            put(ox + x, oy + y, CLEAR)


build_pipe_region()
build_head_region()


def chunk(tag, data):
    c = struct.pack('>I', len(data)) + tag + data
    return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)


raw = b''
for y in range(H):
    raw += b'\x00'
    for x in range(W):
        raw += bytes(px[y][x])

png = b'\x89PNG\r\n\x1a\n'
png += chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(raw, 9))
png += chunk(b'IEND', b'')

out = 'assets/craftingveloce/textures/block/veloce_pipe.png'
with open(out, 'wb') as f:
    f.write(png)
print(f"zapisano {out} ({len(png)} bajtow)")

print("\nPodglad (K=czarny, G=szary, .=okno):")
for y in range(H):
    s = ''
    for x in range(W):
        c = px[y][x]
        s += '.' if c[3] == 0 else ('G' if c == GREY else 'K')
    print(f"  {y:2d} {s}")
