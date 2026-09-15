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

## 🔍 Transparency / render_type (WAŻNE dla animowanych itemów)

**Wszystkie bloki Veloce muszą obsługiwać przezroczystość** — w przyszłości będziemy renderować
animowane itemy *wewnątrz* bloków, więc każdy block model ma ustawiony `render_type`.

### Dwa mechanizmy (używamy obu)

1. **Model JSON `render_type`** (główny, kanoniczny dla 1.21.1) — dziedziczony przez item modele:
   - `assets/craftingveloce/models/block/pipe_core.json` → `"render_type": "minecraft:translucent"`
   - `assets/craftingveloce/models/block/pipe_part.json` → `translucent`
   - `assets/craftingveloce/models/block/pipe_extract.json` → `translucent`
   - `assets/craftingveloce/models/block/veloce_extractor.json` → `translucent`
   - `assets/craftingveloce/models/block/veloce_crafting_table.json` → `translucent`
   - `assets/craftingveloce/models/block/veloce_tom_terminal.json` → `translucent`

2. **Java** — `CraftingVeloceMod` w `FMLClientSetupEvent` rejestruje render layer dla
   `VELOCE_PIPE`, `VELOCE_EXTRACTOR`, `VELOCE_CRAFTING_TABLE`, `VELOCE_TOM_TERMINAL`.

### Dlaczego `translucent`, a nie `cutout`?

`cutout` daje twardą, 1-bitową alfę (piksel albo w pełni widoczny, albo wcale) — źle wygląda
przy animowanych itemach renderowanych wewnątrz bloku, bo krawędzie się „szarpią".
`translucent` obsługuje stopniowaną alfę i poprawne mieszanie kolorów.

### ⚠️ Pułapki

- `ItemBlockRenderTypes.setRenderLayer()` ma guard `checkClientLoading()` w
  `ClientModLoader.isLoading()`. Wywołanie **poza** client setup rzuca
  `IllegalStateException: Render layers can only be set during client loading!`
- `RenderShape` musi zostać `MODEL` (tak jest w `VelocePipeBlock`, `VeloceExtractorBlock`,
  `VeloceCraftingTableBlock`) — przezroczystość kontroluje render type, nie render shape.
- Modele używające vanilla parenta `block/cube_all` **nie mogą** polegać na dziedziczeniu,
  bo ten parent jest nieprzezroczysty — `render_type` trzeba wpisać w naszym modelu-dziecku.

---

## 🚧 Co jeszcze do zrobienia / Known Issues

- **Crafting Table** — na razie tylko UI i toggle stanu. Auto-crafting (faktyczne craftowanie itemów z sieci na podstawie włączonych receptur) nie jest jeszcze zaimplementowany
- **Loot tables** — bloki po zniszczeniu nie dropują się (brak `data/craftingveloce/loot_table/blocks/`)
- **Crafting recipes** — brak receptur craftu dla bloków (można tylko creative)
- **Textures** — `veloce_crafting_table.png` to przebarwiona wersja extractora (placeholder)
- **`veloce_pipe.png` jest CZARNA** — 100 nieprzezroczystych pikseli ma kolor `(0,0,0)`,
  czyli brak danych kolorystycznych. Alpha (przezroczystość) jest poprawna. Oryginalna,
  kolorowa wersja leży w `veloce_pipe-kopia.png` (fiolet `201,45,234` + szarości) i można ją
  przywrócić jednym `cp`. Render fix (`translucent`) NIE zmienia koloru tekstury — naprawia
  tylko przezroczystość.
- **src/moze_intel/** — patchowane klasy InventoryExchange moda, logika wyłączenia EMC tooltipów — trzeba zdecydować jak czysto to rozwiązać (osobny JAR patch?)
