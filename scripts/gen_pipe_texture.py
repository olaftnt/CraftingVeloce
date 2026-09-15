#!/usr/bin/env python3
"""
Generator tekstury rury Veloce (veloce_pipe.png).

Projekt:
  - Rura ma przekroj kwadratowy 6x6 px.
  - Wszystkie sciany "wzdluz rury" uzywaja JEDNEGO regionu 6x6 (Region A),
    dzieki czemu opaska jest CIAGLA na zakretach.
  - Wzorzec jest symetryczny lustrzanie wzgledem obu osi, wiec obroty
    o 90 stopni (zakrety, ramiona w roznych osiach) daja identyczny wynik.

Region A [0,0 .. 6,6]  - sciany rury:
     szer 0  K K G G K K
     szer 1  K K G G K K
     szer 2  K . K K . K      K = czarna opaska
     szer 3  K . K K . K      G = szary odcien (rozjaśnia plaszczyzne)
     szer 4  K K G G K K      . = przezroczyste okno
     szer 5  K K G G K K

Region B [8,0 .. 16,8] - glowica/nozzle (8x8), z okienkiem.

Paleta jest CELOWO ograniczona do czerni + jednego szarego odcienia.
Brak fioletu i brak dodatkowych rozjasnien.
"""
import struct, zlib

W = H = 16

# --- paleta ---
OPAQUE_BLACK = (18, 18, 20, 255)      # opaska - niemal czarna, lekko niebieskawa
GREY = (58, 58, 64, 255)              # odcien szarosci (3.2x jasniejszy od bazy)
CLEAR = (0, 0, 0, 0)                  # pelna przezroczystosc (okno)

px = [[CLEAR for _ in range(W)] for _ in range(H)]


def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[y][x] = c


def build_pipe_region():
    """Region A: [0,0 .. 6,6] - wzor sciany rury, symetryczny.

    Uklad (wiersz = szerokosc sciany, kolumna = dlugosc wzdluz rury):
        szer 0  K K G G K K
        szer 1  K K G G K K
        szer 2  K . K K . K
        szer 3  K . K K . K
        szer 4  K K G G K K
        szer 5  K K G G K K

    Symetria: kol1<->kol4, kol2<->kol3, wiersz1<->wiersz4, wiersz0<->wiersz5.
    Wzorzec jest symetryczny wzgledem OBU osi, wiec obrot o 90 stopni
    (zakrety, ramiona w roznych osiach) daje identyczny obraz.

    UWAGA na kolizje rol: kolumny 2,3 to jednoczesnie
      - czesc poziomej opaski (wiersze 0,1,4,5)
      - POPRZECZKA miedzy oknami (wiersze 2,3)
    Szarosc dajemy TYLKO w wierszach opaski; poprzeczka zostaje czarna,
    inaczej okna stracilyby kontrast.
    """
    WINDOW_COLS = (1, 4)
    WINDOW_ROWS = (2, 3)

    # 1. baza: cala opaska czarna
    for y in range(6):
        for x in range(6):
            put(x, y, OPAQUE_BLACK)

    # 2. okna (przezroczyste)
    for y in WINDOW_ROWS:
        for x in WINDOW_COLS:
            put(x, y, CLEAR)

    # 3. szary pas rozbijajacy plaszczyzne czerni.
    #    Tylko wiersze opaski (0,1,4,5) i tylko kolumny 2,3.
    #    Poprzeczka (wiersze 2,3) zostaje czarna -> kontrast okien.
    for y in (0, 1, 4, 5):
        for x in (2, 3):
            put(x, y, GREY)


def build_head_region():
    """Region B: [8,0 .. 16,8] - glowica 8x8 z okienkiem."""
    ox, oy = 8, 0
    for y in range(8):
        for x in range(8):
            put(ox + x, oy + y, OPAQUE_BLACK)
    # szary pas w gornej i dolnej czesci glowicy (rozjaśnia plaszczyzne)
    for y in (1, 6):
        for x in (1, 2, 3, 4, 5, 6):
            put(ox + x, oy + y, GREY)
    # okienko 4x2 na srodku
    for y in (3, 4):
        for x in (2, 3, 4, 5):
            put(ox + x, oy + y, CLEAR)


build_pipe_region()
build_head_region()


# --- zapis PNG (RGBA, bez filtrow) ---
def chunk(tag, data):
    c = struct.pack('>I', len(data)) + tag + data
    return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)


raw = b''
for y in range(H):
    raw += b'\x00'  # filter type 0
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


# --- podglad ASCII ---
print("\nPodglad (K=czarny, G=szary, .=okno):")
for y in range(H):
    s = ''
    for x in range(W):
        c = px[y][x]
        if c[3] == 0:
            s += '.'
        elif c == GREY:
            s += 'G'
        else:
            s += 'K'
    print(f"  {y:2d} {s}")
