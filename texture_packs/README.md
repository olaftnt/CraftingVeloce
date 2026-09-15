# Zestawy tekstur Veloce — propozycje do wyboru

Cztery warianty **tego samego** zestawu tekstur, każdy w innym materiale.
Nie są wdrożone do moda — mod dalej używa swoich obecnych tekstur. Służą do
obejrzenia i wybrania.

## Podglądy

- **`PREVIEW-ALL.png`** — wszystkie 4 warianty w jednym arkuszu
  (wiersze: netherite / iron / obsidian / copper, kolumny: crafter, extractor,
  rura, kontroler, piec, piec elektryczny, klucz).
- `PREVIEW.png` w każdym folderze — 11 tekstur tego jednego wariantu.

## Jak to jest zrobione

**Baza to prawdziwe waniliowe bloki**, przemalowane na materiał. Dzięki temu
każdy blok nadal wygląda jak to, czym jest — tylko zrobiony z czegoś innego:

| nasz blok | waniliowa baza |
|---|---|
| crafter (auto-crafter) | `crafting_table_top` |
| extractor | `dropper_front` |
| Velocity Furnace | `furnace_front` / `_side` / `_top` |
| Velocity Electric Furnace | `blast_furnace_front` / `_side` / `_top` |

Przemalowanie idzie po **jasności**: liczymy jasność każdego piksela
waniliowej tekstury, rozciągamy ją do pełnego zakresu i wstawiamy kolor
z palety materiału. Kształt, cienie, nity i detale zostają.

**Rura, kontroler i klucz to inna historia** — zgodnie z ustaleniem zostają
w **oryginalnym schemacie moda**, zmienia się wyłącznie **kolor akcentu**:
bierzemy oryginalny odcień (fiolet ~290°) i podmieniamy sam odcień, zachowując
nasycenie i jasność. Ciemny korpus zostaje dokładnie taki, jaki był.

**Terminala celowo nie ma w żadnej paczce** — zostaje bez zmian.

## Cztery propozycje

| wariant | materiał | akcent |
|---|---|---|
| `netherite` | ciemny, ciepły grafit (jak blok netherytu) | rozżarzony pomarańcz |
| `iron` | jasna, chłodna stal | stalowy błękit |
| `obsidian` | niemal czarny z fioletowym tintem | fiolet obsydianu |
| `copper` | ciepły miedziany brąz | zielona patyna |

## Jak obejrzeć w grze

Paczki są już skopiowane do profilu:

    ~/Library/Application Support/ModrinthApp/profiles/testing/resourcepacks/

**Opcje → Zasoby** i włącz **jedną** z nich: `veloce_netherite`, `veloce_iron`,
`veloce_obsidian`, `veloce_copper`. Włączaj pojedynczo — nadpisują te same
pliki. Wyłączenie packa albo usunięcie folderu wraca do obecnych tekstur moda;
sam mod nie jest w tym miejscu w żaden sposób zmieniany.

## Regeneracja / zmiany

    python3 scripts/gen_material_textures.py

Skrypt jest deterministyczny. Palety materiałów i akcenty siedzą w jednym
słowniku `MATERIALS` na górze pliku — zmiana koloru to zmiana jednej linii.
Dodanie kolejnego materiału to dopisanie jednego wpisu do tej mapy.

## Gdy wybierzesz

Powiedz który wariant (albo które elementy z których), a wdrożę go jako
tekstury moda — wtedy znajdą się w `assets/craftingveloce/textures/` i w JARze.
