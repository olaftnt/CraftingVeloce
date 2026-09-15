# Zestawy tekstur Veloce — podgląd

Dwa alternatywne zestawy tekstur w stylu **Endera**, przygotowane jako gotowe
resource packi. **Nie są wdrożone do moda** — mod dalej używa swoich obecnych
tekstur. Te paczki służą tylko do obejrzenia i wybrania.

## Skąd te kolory

Paleta jest wypróbowana z **prawdziwych tekstur wanilii**, nie zgadywana:

| źródło | kolory |
|---|---|
| `ender_pearl` | `#032620` `#0b4d42` `#105e51` `#258474` `#349988` |
| `ender_eye` | `#102c31` `#1e4835` `#316364` `#4e8386` `#659b7d` `#71ac49` |
| `end_portal_frame_top` | `#061914` `#24433b` `#2f584e` `#427367` |

Tony **end stone** (`#c5be8b` … `#f6fabd`, te kremowo-żółte) są **świadomie
pominięte** — zgodnie z ustaleniem „jak ender portal, ale bez end stona".
Ciemnozielone elementy Endera, jak w ender chest, zostają.

## Dwa zestawy

| | `ender_deep` | `ender_pearl` |
|---|---|---|
| nastrój | jak **ender chest** / rama portalu | jak **ender perła** / oko |
| korpus | prawie czarna zieleń | średnia zieleń morska |
| akcenty | oszczędne, jasne perły | więcej perłowych refleksów |
| efekt | mroczny, mocny kontrast | miększy, „żywszy" |

Oba zestawy mają **dokładnie ten sam zestaw 14 plików**, więc podmiana jest
równa jeden do jednego.

## Jak obejrzeć

### W grze (na żywo)
Paczki są już skopiowane do profilu:

    ~/Library/Application Support/ModrinthApp/profiles/testing/resourcepacks/

W Minecraftcie: **Opcje → Zasoby** i włącz `veloce_ender_deep` **albo**
`veloce_ender_pearl`. Włączaj pojedynczo — oba naraz nie mają sensu, bo
nadpisują te same pliki (choć wtedy wygrywa ten wyżej na liście).

Aby cofnąć: wyłącz pack albo usuń folder z `resourcepacks/`. Mod nie jest
w tym miejscu w żaden sposób modyfikowany.

### Bez gry
W każdym folderze jest `PREVIEW.png` — tablica wszystkich 14 tekstur
powiększona 6×, żeby dało się je porównać obok siebie.

## Zawartość (14 tekstur)

Bloki: rura, kontroler, stół craftingu, ekstraktor, terminal (przód/bok/tył),
piec paliwowy (przód/bok/góra), piec elektryczny (przód/bok/góra).
Item: klucz (wrench).

## Regeneracja / zmiany

Wszystko powstaje z jednego skryptu:

    python3 scripts/gen_ender_textures.py

Skrypt ma **stały seed na plik**, więc dwa uruchomienia dają identyczną
grafikę — „ten podoba mi się bardziej" nie zależy od losowego szumu.
Po zmianie palety wystarczy uruchomić skrypt ponownie i skopiować folder do
`resourcepacks/`.

## Gdy wybierzesz

Powiedz który (`ender_deep` czy `ender_pearl`), a wdrożę go jako tekstury moda
(będą wtedy w `assets/craftingveloce/textures/` i w JARze). Obecne tekstury
zostaną zastąpione.
