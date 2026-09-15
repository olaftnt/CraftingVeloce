# CraftingVeloce — NeoForge 1.21.1 Minecraft Mod

Mod dodający inteligentną sieć logistyczną do Minecraft, zbudowaną na bazie systemu Tom's Simple Storage. Pozwala na zarządzanie magazynem, automatyczne wyciąganie itemów z sieci i auto-crafting.

---

## 🟢 Stan projektu (2026-09-15)

**Stabilny / w aktywnym rozwoju.** Mod działa w grze. Ostatnia sesja zakończyła się wdrożeniem Veloce Crafting Table oraz naprawą krytycznego błędu modułu Java.

---

## 📦 Bloki i przedmioty

### 1. Veloce Storage Terminal (`veloce_tom_terminal`)
- Rozszerza `AbstractStorageTerminalBlock` z Tom's Storage
- Otwiera ekran w stylu Creative Inventory (bez hotbara) z listą wszystkich itemów w sieci
- Kliknięcie na item wyciąga go z sieci (1 szt. LPM, max stack SHIFT+LPM)
- Wyświetla liczby przy itemkach (zielone cyfry) pokazujące ilość w sieci
- Tooltip: tylko domyślny MC (bez dodatkowych linijek)
- Łączy się z siecią przez `IInventoryCable` (Tom's Storage)

### 2. Veloce Extractor (`veloce_extractor`)
- Kwadratowy blok węzłowy sieci
- **Prawy klik** → otwiera interfejs z 9 slotami filtru (lewa strona) + 9 slotami output (prawa)
- **Filtr (lewa strona):** kliknięcie każdego slotu filtru otwiera creative picker (bez hotbara) do wybrania ghost-itemu jako filtr. Wyświetla liczby dostępnych itemów w sieci
- **Output (prawa strona):** prawdziwe sloty z itemami wyciągniętymi z sieci zgodnie z filtrem
- Extractor sam co tick wyciąga pasujące itemy z sieci do output inventory
- **Kompatybilność:** implementuje `WorldlyContainer` → można ssać hopperem; implementuje `Capabilities.ItemHandler.BLOCK` → można ssać rurami NeoForge (Pipez itp.)
- Rura Veloce podłączona do extractora: tylko **Connected/Disconnected** (bez push/pull nozzle)
- Łączy się z siecią przez `IInventoryCable`

### 3. Veloce Crafting Table (`veloce_crafting_table`)
- Kwadratowy blok węzłowy sieci
- **Prawy klik** → otwiera creative inventory (bez hotbara) z kolorowymi overlayami na itemkach:
  - 🟥 **Czerwone** = ma recepturę craftingu, jest **OFF** (domyślnie)
  - 🟩 **Zielone** = jest **ON** (auto-craftowany)
  - 🟪 **Fioletowe** = **brak receptury** w zwykłym crafting table (nie można włączyć)
- Kliknięcie przełącza red ↔ green (toggle), fioletowe są nieaktywne
- Stan zapisuje się do NBT per-blok (persystencja po restarcie serwera)
- Rura Veloce: tylko **Connected/Disconnected** (bez push/pull)
- Łączy się z siecią przez `IInventoryCable`

### 4. Veloce Pipe (`veloce_pipe`)
- Rura łącząca wszystkie bloki sieci
- Renderuje się jako cienki przewód z nozzle (głowica wyciągająca) gdy jest w trybie Pull
- **Wrench (klucz):** prawy klik na połączenie toggleuje tryb:
  - Na zwykłym inventory: `Push/Pull → Pull → Disconnected → Push/Pull`
  - Na extractorze/crafting table: `Connected → Disconnected → Connected`
- Waterloggable
- Łączy się z Tom's Storage, Refined Storage, zwykłymi inventory, NeoForge ItemHandler
### 5. Veloce Wrench (`wrench`)
- Narzędzie do konfiguracji połączeń rury
- Prawy klik na `VelocePipeBlock` wywołuje `onWrenchClicked`
- Cykl trybów strony rury: **Push/Pull → Pull → Disconnected**
  - `Pull` = z tego magazynu wolno tylko zabierać (wstawianie zablokowane)
  - `Disconnected` = brak połączenia (magazyn wypada z sieci)
  - flaga łącza rura↔rura ma **jednego właściciela** (mniejsza pozycja), więc
    przełączanie działa z obu stron; szczegóły w komentarzu `togglePipeLink`

### 6. Velocity Furnace (`velocity_furnace`)
- Źródło ciepła dla auto-craftera; pali się **bez przerwy**, też na bezczynności
- 6 slotów filtra paliwa (wybór jak w ekstraktorze) + slot paliwa; z pustymi
  filtrami przyjmuje **każde** paliwo
- Wyjście: jedna OPERACJA = jedno instant przepalenie
  (`burnTicksRemaining / SMELT_HEAT_COST`)
- Po zjedzeniu ciepla piec **natychmiast** dobiera paliwo z sieci
  (`wantImmediatePull`), zamiast czekać do końca `PULL_INTERVAL_TICKS`

### 7. Velocity Electric Furnace (`electric_furnace`)
- Akumulator FE (25 000 000 FE, 200 000 FE na przepalenie); ładują go kable
  z innych modów przez capability — mod nie ma własnego ładowania
- **Priorytet 0** — crafter bierze najpierw z niego, a paliwowy (priorytet 1)
  jest fallbackiem, także w trakcie: brak prądu na 5 przepaleń to 3 z prądu
  i 2 z paliwa

### 8. Veloce Threshold Sensor (`threshold_sensor`)
- Jeden slot filtra + pole na liczbę + trzy guziki (tryb, `-`, `+`)
- Patrzy, ile **fizycznie** jest filtrowanego itemu w sieci, i wystawia redstone
- Tryby: `When below` (prad, gdy mniej niż próg) i `When at least` (odwrotny)
- Wynik trzymany w **stanie bloku** (`POWERED`), bo to zmiana stanu rozglaszana
  sąsiadom uruchamia maszyny; moc **mocna** (`getSignal` + `getDirectSignal`)
- Sprawdza sieć raz na sekundę (`CHECK_INTERVAL_TICKS = 20`), bo skan magazynów
  jest drogi, a sensor służy do uruchamiania fabryki, nie do taktowania

---

## 🏗️ Architektura techniczna

### Lokalizacja projektu
```
/Users/olafalencynowicz/Desktop/CraftingVeloce/
├── src/                          # Flat source root (NIE main/java)
│   ├── com/craftingveloce/
│   │   ├── CraftingVeloceMod.java          # Główna klasa moda
│   │   ├── block/                          # Bloki
│   │   │   ├── VelocePipeBlock.java
│   │   │   ├── VeloceExtractorBlock.java
│   │   │   ├── VeloceCraftingTableBlock.java
│   │   │   ├── VeloceTomTerminalBlock.java
│   │   │   └── entity/                     # Block Entities
│   │   │       ├── VelocePipeBlockEntity.java
│   │   │       ├── VeloceExtractorBlockEntity.java
│   │   │       ├── VeloceCraftingTableBlockEntity.java
│   │   │       └── VeloceTomTerminalBlockEntity.java
│   │   ├── client/
│   │   │   ├── ClientTerminalHelper.java   # Dispatcher dla packet handlerów (client-side)
│   │   │   └── gui/
│   │   │       ├── VeloceTerminalScreen.java       # GUI terminala
│   │   │       ├── VeloceExtractorScreen.java      # GUI extractora (menu-based)
│   │   │       ├── VeloceFilterPickerScreen.java   # Creative picker dla filtrów
│   │   │       └── VeloceCraftingTableScreen.java  # GUI crafting table
│   │   ├── network/
│   │   │   ├── VelocePacketHandler.java            # Rejestracja wszystkich packetów
│   │   │   ├── OpenTerminalScreenPKT.java          # S→C: otwórz terminal
│   │   │   ├── SyncTerminalCountsPKT.java          # S→C: wyślij Map<Item,Long> z ilościami
│   │   │   ├── OpenFilterPickerPKT.java            # S→C: otwórz filter picker
│   │   │   ├── SyncExtractorFiltersPKT.java        # S→C: wyślij filtry do extractora
│   │   │   ├── OpenCraftingTableScreenPKT.java     # S→C: otwórz crafting table GUI
│   │   │   ├── SyncCraftingTableStatePKT.java      # S→C: sync stan crafting table
│   │   │   ├── TerminalPullItemPKT.java            # C→S: wyciągnij item z sieci
│   │   │   ├── ExtractorSetFilterPKT.java          # C→S: ustaw filtr w extractorze
│   │   │   ├── ExtractorOpenFilterPKT.java         # C→S: otwórz filter picker (triggeruje sync counts)
│   │   │   └── CraftingTableToggleItemPKT.java     # C→S: toggle item w crafting table
│   │   │   └── pipe/
│   │   │       ├── VelocePipeNetworkManager.java   # SavedData: zarządza sieciami rur
│   │   │       ├── VelocePipeNetwork.java          # Pojedyncza sieć (zbiór rur + terminale)
│   │   │       └── ConnectedEndpointInfo.java      # Info o podłączonym inventory
│   │   ├── inventory/
│   │   │   └── VeloceExtractorMenu.java            # AbstractContainerMenu dla extractora
│   │   ├── init/
│   │   │   └── VeloceRegistry.java                 # DeferredRegister dla bloków/itemów/BE/menu
│   │   ├── item/
│   │   │   └── VeloceWrenchItem.java
│   │   ├── rs/
│   │   │   └── RefinedStorageHelper.java           # Integracja z Refined Storage
│   │   └── commands/
│   │       └── CVDebugCommand.java
│   └── moze_intel/projecte/...                     # UWAGA: to są patche do inventoryexchange moda
│                                                   # NIE kompilować do craftingveloce JAR!
├── assets/craftingveloce/
│   ├── blockstates/             # JSON blockstate dla każdego bloku
│   ├── models/block/            # JSON modele bloków
│   ├── models/item/             # JSON modele itemów
│   ├── textures/block/          # PNG tekstury
│   └── lang/en_us.json         # Tłumaczenia
├── data/                        # Data pack (Tom's Storage tags, loot tables)
├── scripts/
│   └── cp.txt                   # Classpath do kompilacji (generowany, ogromny)
├── mod_jar/                     # Zewnętrzne JARy dla kompilacji (inventoryexchange)
├── craftingveloce_classes/      # Skompilowane klasy (output javac)
├── craftingveloce_jar_root/     # Staging dir dla JAR (klasy + assets + META-INF)
└── craftingveloce-1.0.0.jar    # Gotowy JAR moda
```

### Build Process
```bash
# 1. Kompilacja (WYKLUCZ src/moze_intel i src/eatawesome!)
python3 -c "
import subprocess, glob
with open('scripts/cp.txt') as f:
    cp = f.read().strip()
tom_jar = '/Users/olafalencynowicz/Library/Application Support/ModrinthApp/profiles/testing/mods/toms_storage-1.21-2.4.2.jar'
rs_jar = '/Users/olafalencynowicz/Library/Application Support/ModrinthApp/profiles/testing/mods/refinedstorage-neoforge-2.0.9.jar'
src_files = [f for f in glob.glob('src/**/*.java', recursive=True)
             if 'eatawesome' not in f and 'moze_intel' not in f]
res = subprocess.run(['javac', '--release', '21', '-cp', cp+':'+tom_jar+':'+rs_jar,
                      '-d', 'craftingveloce_classes'] + src_files,
                     capture_output=True, text=True)
print(res.returncode, res.stderr[:500])
"

# 2. Budowanie JARa
import shutil, subprocess
shutil.copytree('craftingveloce_classes', 'craftingveloce_jar_root', dirs_exist_ok=True)
shutil.copytree('assets', 'craftingveloce_jar_root/assets', dirs_exist_ok=True)
shutil.copytree('data', 'craftingveloce_jar_root/data', dirs_exist_ok=True)
subprocess.run(['jar', 'cf', 'craftingveloce-1.0.0.jar', '-C', 'craftingveloce_jar_root', '.'])

# 3. Deploy
shutil.copyfile('craftingveloce-1.0.0.jar',
    '/Users/olafalencynowicz/Library/Application Support/ModrinthApp/profiles/testing/mods/craftingveloce-1.0.0.jar')

# 4. Commit & push
git add -A && git commit -m "..." && git push origin main
```

### Kluczowe zależności kompilacji
- `scripts/cp.txt` — pełny classpath (MC + NeoForge + wszystkie mody)
- `toms_storage-1.21-2.4.2.jar` — z folderu mods (profil testing)
- `refinedstorage-neoforge-2.0.9.jar` — z folderu mods (profil testing)

### ⚠️ Ważne pułapki

1. **`src/moze_intel/`** — NIE należy do naszego moda! To są patchowane klasy `inventoryexchange-1.0.0.jar`. Jeśli trafią do naszego JARa → `ResolutionException` przy starcie MC (duplicate package export). Zawsze wykluczaj je z kompilacji do craftingveloce.

2. **`src/eatawesome/`** — też nie nasze, to stary mod InventoryExchange. Nie kompilować.

3. **`craftingveloce_jar_root/META-INF/`** — musi zawierać `mods.toml` i `MANIFEST.MF`. Generowane przez poprzedni build, nie kasuj tego folderu całkowicie.

4. **VeloceRegistry.createBEType()** — używa reflection bo NeoForge 21.1.x nie ma publicznego 3-arg konstruktora `BlockEntityType`. Nie zmieniaj bez potrzeby.

5. **Creative Screen bez hotbara** — wszystkie 3 GUI (terminal, filter picker, crafting table) muszą: zastępować sloty player inventory dummy slotami, wypełniać hotbar area szarym `0xFFC6C6C6`, restorować `GameType` po zamknięciu.

6. **Układ pakietów w JAR** — output `javac -d <dir>` tworzy `<dir>/com/craftingveloce/...`.
   Kopiując to do stagingu, trzeba skopiować **zawartość** `<dir>/com` do `<root>/com`, a nie
   cały folder `com` — inaczej w JARze powstaje `com/com/craftingveloce/...` i mod **w ogóle się
   nie ładuje**. Zawsze weryfikuj: `unzip -l craftingveloce-1.0.0.jar | grep CraftingVeloceMod.class`
   musi pokazać `com/craftingveloce/CraftingVeloceMod.class`.

7. **Nie kasuj `META-INF/` przy rebuildzie** — przy czyszczeniu stagingu usuwaj tylko
   `com/`, `assets/`, `data/`. `META-INF/neoforge.mods.toml` musi zostać.

8. **Pliki tylko w stagingu** — `data/minecraft/tags/block/mineable/axe.json` istniał kiedyś
   wyłącznie w `craftingveloce_jar_root/` i został skasowany przez czysty rebuild. Trzymaj
   wszystkie zasoby w źródłowym `data/`, żeby build był odtwarzalny.

---

## 🔌 Integracje

| System | Jak |
|--------|-----|
| Tom's Simple Storage | `VeloceTomTerminalBlock extends AbstractStorageTerminalBlock`; wszystkie bloki implementują `IInventoryCable` |
| Refined Storage | `RefinedStorageHelper.hasRSNetwork()` — sprawdza i łączy z RS network |
| NeoForge ItemHandler | `Capabilities.ItemHandler.BLOCK` zarejestrowane dla extractora (pozwala rurą Pipez ssać) |
| Vanilla Hopper | `VeloceExtractorBlockEntity implements WorldlyContainer` |

---

## 🌐 Network (Packets)

Wszystkie packety używają NeoForge `CustomPacketPayload` / `StreamCodec`.

| Packet | Kierunek | Zawartość |
|--------|----------|-----------|
| `OpenTerminalScreenPKT` | S→C | `BlockPos terminalPos` |
| `SyncTerminalCountsPKT` | S→C | `Map<Item, Long> counts` |
| `OpenFilterPickerPKT` | S→C | `BlockPos extractorPos, int filterIndex` |
| `SyncExtractorFiltersPKT` | S→C | `BlockPos pos, List<ItemStack> filters` |
| `OpenCraftingTableScreenPKT` | S→C | `BlockPos pos, Set<Item> enabledItems` |
| `SyncCraftingTableStatePKT` | S→C | `BlockPos pos, Set<Item> enabledItems` |
| `TerminalPullItemPKT` | C→S | `BlockPos pos, ItemStack item, int count` |
| `ExtractorSetFilterPKT` | C→S | `BlockPos pos, int filterIndex, ItemStack item` |
| `ExtractorOpenFilterPKT` | C→S | `BlockPos pos, int filterIndex` (triggeruje sync counts) |
| `CraftingTableToggleItemPKT` | C→S | `BlockPos pos, Item item` |

### ClientTerminalHelper
Centralny dispatcher dla client-side packet handlerów:
- `openTerminalScreen(BlockPos)` — otwiera VeloceTerminalScreen
- `handleSyncCounts(Map<Item,Long>)` — routuje do aktywnego ekranu (terminal lub filter picker)
- `openFilterPickerScreen(BlockPos, int)` — otwiera VeloceFilterPickerScreen
- `handleSyncExtractorFilters(BlockPos, List<ItemStack>)` — update slotów w extractorze
- `openCraftingTableScreen(BlockPos, Set<Item>)` — otwiera VeloceCraftingTableScreen
- `updateCraftingTableState(BlockPos, Set<Item>)` — live update crafting table GUI

---

## 📊 VelocePipeNetworkManager

`SavedData` przechowywany w `ServerLevel`. Skanuje i buduje grafy sieci rur.

**Typy węzłów:**
- **Terminal** (`VeloceTomTerminalBlock`, `VeloceExtractorBlock`, `VeloceCraftingTableBlock`) — dostęp do pełnej sieci
- **Endpoint** — zwykłe inventory (skrzynia, piec), Refined Storage network

**Kluczowe metody:**
- `get(ServerLevel)` — pobiera singleton dla danego wymiaru
- `getNetworkForTerminal(ServerLevel, BlockPos)` — sieć dla terminala
- `onTerminalPlaced/Removed(ServerLevel, BlockPos)` — update przy zmianie świata
- `onPipeBroken(ServerLevel, BlockPos)` — rebuild sieci po zniszczeniu rury

**VelocePipeNetwork:**
- `getAllItemCounts(ServerLevel)` → `Map<Item, Long>` — agreguje wszystkie inventory w sieci
- `extractItem(ServerLevel, Item, int)` — wyciąga item z dowolnego inventory w sieci

---

## 🕹️ GUI Patterns

Wszystkie ekrany rozszerzają `CreativeModeInventoryScreen` i:
1. Przy `init()` ustawiają `GameType.CREATIVE` jeśli gracz nie jest w creative
2. Wywołują `suppressHotbarSlots()` — zastępuje player inventory sloty dummy inactive slotami (offset -10000)
3. Override `renderSlot()` — pomija sloty gracza
4. Override `render()` — wypełnia szarym pasek hotbara
5. Override `containerTick()` — puste (wyłącza automatyczny update)
6. W `removed()` restoruje `GameType` gracza

**Overlay liczb (VeloceTerminalScreen i VeloceFilterPickerScreen):**
- Scale 0.6f, kolor `0x55FF55` (jasny zielony), z cieniem
- Używają `VeloceTerminalScreen.formatCount(long)` → "1K", "2.5M" etc.

**Kolorowe overlaye (VeloceCraftingTableScreen):**
- `0x77AA0000` red (OFF), `0x7700AA00` green (ON), `0x77800080` purple (no recipe)
- Cache `craftableItems` z `RecipeManager.getAllRecipesFor(RecipeType.CRAFTING)`

---

## 🔍 Transparency / render_type — WYŁĄCZONE

**Przezroczystość bloków została celowo wyłączona.** Wszystkie bloki Veloce
(rura, extractor, crafting table, terminal) renderują się jako `solid`.

Usunięte zostały **oba** mechanizmy:
1. `"render_type": "minecraft:translucent"` z wszystkich modeli w `assets/.../models/block/`
2. rejestracja `ItemBlockRenderTypes.setRenderLayer(...)` z `CraftingVeloceMod`

### ⚠️ Jeśli chcesz przywrócić przezroczystość

Potrzebne są **oba** elementy powyżej. Dodatkowo **tekstura musi być pod to przygotowana**:

Bez `render_type` piksele z `alpha == 0` renderują się jako **czarne**, nie znikają.
Tekstura rury (`veloce_pipe.png`) jest tak zaprojektowana, że **żaden obszar używany
przez UV nie zawiera przezroczystych pikseli** — dlatego rura wygląda poprawnie
jako solid. Sprawdź to przed zmianą UV:

```python
# dla każdej ściany: policz puste piksele w prostokącie UV
n = sum(1 for y in range(v0, v1) for x in range(u0, u1) if alpha(x, y) == 0)
# n musi być 0, inaczej solid render pokaże czarne pasy
```

### Historia (dlaczego wracamy do solid)

Przezroczysta rura wymagała `translucent`, ale kolejne próby poprawy UV
(ciągłość na zakrętach, brak czarnych pasków na stykach) nie dały zadowalającego
efektu wizualnego. Zdecydowano o powrocie do solidnej rury 6×6 z oryginalną
teksturą. Wcześniejsze wersje są w historii gita (commit `8553ae5` i wcześniejsze).

## 🚧 Co jeszcze do zrobienia / Known Issues

### 🔴 Zrobic pozniej: podglad bufora craftera w zakladce ekwipunku

**Stan obecny:** zakladka "Survival Inventory" jest **ukryta** w crafterze
(`VeloceCraftingTableScreen.acceptTab` odrzuca `Type.INVENTORY`).

**Dlaczego:** dwie nieudane proby, opisane tu, zeby ich nie powtarzac:

1. **Podmiana slotow w ekranie vanilla** - nadpisywalismy zawartosc slotow
   ekwipunku gracza w `CreativeModeInventoryScreen`. Efekt: itemy na slocie
   glowy i zepsuty uklad. Problem: walczylismy z ukladem vanilla zamiast go
   kontrolowac.

2. **Wlasny ekran kontenera** (`VeloceCrafterStorageMenu/Screen`) - osobny
   ekran ze scrollbarem. Efekt: otwieral sie **sam** przy wejsciu w crafter
   (wykrywanie zakladki w `containerTick`, nie tylko w kliknieciu) i nie dalo
   sie z niego wyjsc. Usuniety.

**Wlasciwy sposob (TODO):** skopiowac `CreativeModeInventoryScreen` do moda
jako wlasna klase i modyfikowac bezposrednio. Wtedy zakladka ekwipunku moze
pokazac bufor craftera bez walki z vanilla, bo mamy pelna kontrole nad ukladem
slotow. **Nie** robic tego przez podmiane slotow ani przez drugi ekran.

- **Crafting Table** — na razie tylko UI i toggle stanu. Auto-crafting (faktyczne craftowanie itemów z sieci na podstawie włączonych receptur) nie jest jeszcze zaimplementowany
- **Loot tables** — bloki po zniszczeniu nie dropują się (brak `data/craftingveloce/loot_table/blocks/`)
- **Crafting recipes** — brak receptur craftu dla bloków (można tylko creative)
- **Textures** — `veloce_crafting_table.png` to przebarwiona wersja extractora (placeholder)
- **Rura: stan obecny** — geometria `5..11` (6×6), render `solid`, tekstura
  `veloce_pipe.png` = kopia `veloce_pipe-kopia.png` (fiolet + szarości, pełne wypełnienie).
  Wszystkie obszary używane przez UV są wypełnione, więc **nie ma czarnych pasów**.
  ⚠️ Nie zamieniaj tej tekstury na wersję z przezroczystymi pikselami dopóki
  render jest `solid` — alpha 0 renderuje się wtedy jako czerń.
- **UV rury: `[10,0,16,6]` (rdzeń + lica ramion) i `[0,0,5,6]` (boki ramion)** —
  to oryginalne UV. Ma ono tę wadę, że rdzeń i ramię używają różnych regionów,
  więc wzór może się rozjeżdżać na zakrętach. Próby naprawy (commity `f3feac1`–`8553ae5`)
  zostały wycofane razem z przezroczystością.
- **src/moze_intel/** — patchowane klasy InventoryExchange moda, logika wyłączenia EMC tooltipów — trzeba zdecydować jak czysto to rozwiązać (osobny JAR patch?)
- **Crafter: przełączanie CAŁEJ kategorii (prawy klik na ikonce zakładki)** —
  **USUNIĘTE Z KODU na życzenie** (2026-09-15). Działało jako „round robin":
  prawy klik na ikonce zakładki włączał albo wyłączał wszystkie itemy tej
  kategorii naraz. Wycięte razem z podpowiedzią w tooltipie, bo dla kategorii
  typu „Building Blocks" (setki itemów) jeden gest zmieniał stan połowy sieci
  i nie dawał się cofnąć jednym kliknięciem.

  Usunięte elementy, gdyby ktoś chciał to przywrócić:
  - `VeloceCreativeScreen.mouseClicked` (gałąź `button == 1`)
  - `VeloceCreativeScreen.tabUnderMouse`
  - `VeloceCreativeScreen.renderTabTooltip` (istniał WYŁĄCZNIE dla tej podpowiedzi)
  - `VeloceCreativeScreen.toggleWholeTab`
  - klucze lang `category.hint`, `category.willDisable`, `category.willEnable`,
    `crafter.category.hint`

  **Zostaje** przełączanie pojedynczego itemu lewym klikiem
  (`isToggleable` / `isToggledOn` / `applyToggle` w `VeloceCraftingTableScreen`)
  — to działa i tego nie ruszamy.

  Gdyby wracać do tematu, sensowniejszy wariant: przełączanie tylko
  **widocznej strony** zamiast całej zakładki. Samo „cała kategoria" bez
  zabezpieczenia jest zbyt szerokie.
