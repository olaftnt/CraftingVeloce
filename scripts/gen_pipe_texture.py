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
     szer 0  K K K K K K
     szer 1  K K K K K K
     szer 2  K . K K . K      K = czarna opaska
     szer 3  K . K K . K      . = przezroczyste okno
     szer 4  K K K K K K
     szer 5  K K K K K K

Region B [8,0 .. 16,8] - glowica/nozzle (8x8), z okienkiem.
"""
import struct, zlib

W = H = 16

# --- paleta ---
OPAQUE_BLACK = (18, 18, 20, 255)      # opaska - niemal czarna, lekko niebieskawa
OPAQUE_BLACK2 = (28, 28, 32, 255)     # rozjasnienie krawedzi opaski
CLEAR = (0, 0, 0, 0)                  # pelna przezroczystosc (okno)
RIM = (52, 52, 58, 255)               # delikatny highlight na krawedzi opaski
ACCENT = (201, 45, 234, 255)          # fiolet akcentowy (z oryginalu)
ACCENT_D = (150, 32, 176, 255)        # ciemniejszy fiolet

px = [[CLEAR for _ in range(W)] for _ in range(H)]


def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[y][x] = c


def build_pipe_region():
    """Region A: [0,0 .. 6,6] - wzor sciany rury, symetryczny.

    Uklad (wiersz = szerokosc sciany, kolumna = dlugosc wzdluz rury):
        szer 0  o K K K K o      o=rozjasnienie  K=opaska  .=okno
        szer 1  K K K K K K
        szer 2  K . K K . K
        szer 3  K . K K . K
        szer 4  K K K K K K
        szer 5  o K K K K o

    Symetria: kol1<->kol4, kol2<->kol3, wiersz2<->wiersz3.
    Wzorzec jest symetryczny wzgledem OBU osi, wiec obrot o 90 stopni
    (zakrety, ramiona w roznych osiach) daje identyczny obraz.
    """
    WINDOW_COLS = (1, 4)
    WINDOW_ROWS = (2, 3)

    # 1. baza: cala opaska
    for y in range(6):
        for x in range(6):
            put(x, y, OPAQUE_BLACK)

    # 2. okna (przezroczyste)
    for y in WINDOW_ROWS:
        for x in WINDOW_COLS:
            put(x, y, CLEAR)

    # 3. rozjasnienie naroznikow opaski (symetrycznie, wszystkie 4 narozniki)
    for x in (0, 5):
        put(x, 0, OPAQUE_BLACK2)
        put(x, 5, OPAQUE_BLACK2)

    # 4. rim nad i pod kazdym oknem -> okno ma czytelna ramke
    for x in WINDOW_COLS:
        put(x, 1, RIM)
        put(x, 4, RIM)

    # 5. akcent: fioletowy blysk na srodkowej poprzeczce.
    #    Kolumny 2,3 to poprzeczka miedzy oknami - jest OPAQUE,
    #    wiec fiolet nie psuje przezroczystosci.
    #    JEDNORODNY kolor (nie jasny/ciemny) -> pelna symetria koloru,
    #    dzieki czemu obrot o 90 st. na zakretach daje identyczny obraz.
    for x in (2, 3):
        put(x, 1, ACCENT)
        put(x, 4, ACCENT)


def build_head_region():
    """Region B: [8,0 .. 16,8] - glowica 8x8 z okienkiem."""
    ox, oy = 8, 0
    for y in range(8):
        for x in range(8):
            put(ox + x, oy + y, OPAQUE_BLACK)
    # ramka
    for x in range(8):
        put(ox + x, oy + 0, OPAQUE_BLACK2)
        put(ox + x, oy + 7, OPAQUE_BLACK2)
    for y in range(8):
        put(ox + 0, oy + y, OPAQUE_BLACK2)
        put(ox + 7, oy + y, OPAQUE_BLACK2)
    # okienko 4x2 na srodku
    for y in (3, 4):
        for x in (2, 3, 4, 5):
            put(ox + x, oy + y, CLEAR)
    # akcent
    put(ox + 3, oy + 6, ACCENT)
    put(ox + 4, oy + 6, ACCENT_D)


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
print("\nPodglad (K=opaska, .=okno, o=rozjasnienie, P=fiolet):")
for y in range(H):
    s = ''
    for x in range(W):
        c = px[y][x]
        if c[3] == 0:
            s += '.'
        elif c == ACCENT or c == ACCENT_D:
            s += 'P'
        elif c == RIM:
            s += '-'
        elif c == OPAQUE_BLACK2:
            s += 'o'
        else:
            s += 'K'
    print(f"  {y:2d} {s}")
