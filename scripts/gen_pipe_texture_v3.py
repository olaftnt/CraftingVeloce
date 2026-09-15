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

    # 3. UWAGA: wersja v4 (autorstwa uzytkownika) NIE ma szarego pasa.
    #    Rura jest czysta czernia z oknem 4x4 - wyglada jak "szyba".
    #    Szary GREY zostal zachowany w palecie tylko dla regionu glowicy
    #    i na wypadek powrotu do poprzedniego wzoru.


def build_side_region():
    """Region B: [8,0 .. 16,8] - BOK rury (wzdluz osi).

    To jest kluczowy region dla ciaglosci! Opaska biegnie TYLKO po bokach
    (wiersze 0,1,6,7), a okno jest OTWARTE na krawedziach dlugosci
    (kolumny 8 i 15).

    Dlaczego: boki ramion stykaja sie ze soba na granicy blokow. Gdyby
    okno bylo zamkniete ramka (jak w regionie A), na kazdym styku
    powstalby czarny pasek w poprzek rury - widoczny jako "podzial
    na kwadraty". Otwarte krawedzie daja jedno ciagle okno wzdluz rury.

    Modele uzywaja UV [8,0,12,8] (4 px dlugosci = dlugosc ramienia),
    wiec mapowanie jest 1:1 i okno zajmuje pelne 4 px.

        szer 0  K K K K
        szer 1  K K K K
        szer 2  . . . .     <- otwarte na OBU krawedziach
        szer 3  . . . .
        szer 4  . . . .
        szer 5  . . . .
        szer 6  K K K K
        szer 7  K K K K
    """
    for y in range(8):
        for x in range(8, 16):
            put(x, y, BLACK)
    # okno otwarte: wiersze 2..5, WSZYSTKIE kolumny regionu B
    for y in range(2, 6):
        for x in range(8, 16):
            put(x, y, CLEAR)


def build_head_region():
    """Region C: [0,8 .. 8,16] - glowica/nozzle 8x8.

    UWAGA: region zostal PRZENIESIONY z [8,0..16,8] na [0,8..8,16],
    bo stare miejsce zajmuje teraz BOK rury (region B). Bez tego
    glowica nadpisalaby bok rury i zepsula ciaglosc okna.
    """
    ox, oy = 0, 8
    for y in range(8):
        for x in range(8):
            put(ox + x, oy + y, BLACK)
    # okno 4x4 na srodku (takie samo jak na licu rury)
    for y in (2, 3, 4, 5):
        for x in (2, 3, 4, 5):
            put(ox + x, oy + y, CLEAR)


build_pipe_region()
build_side_region()
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
