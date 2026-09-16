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
- **Jeden wyśrodkowany wiersz:** slot itemu, pole na liczbę (38 px), `+`, `-`
  i guzik trybu — bez żadnych napisów w GUI (tylko tytuł bloku)
- Guzik trybu to **pochodnia redstone**: zapalona = `When at least`,
  zgaszona = `When below`; klik odwraca sygnał
- Patrzy, ile **fizycznie** jest filtrowanego itemu w sieci, i wystawia redstone
- Domyślny próg **64**, dolna granica **1** (próg 0 nie znaczy nic)
- Wynik trzymany w **stanie bloku** (`POWERED`), bo to zmiana stanu rozglaszana
  sąsiadom uruchamia maszyny; moc **mocna** (`getSignal` + `getDirectSignal`)
- Sprawdza sieć raz na sekundę (`CHECK_INTERVAL_TICKS = 20`), bo skan magazynów
  jest drogi, a sensor służy do uruchamiania fabryki, nie do taktowania

### 9. Veloce Integrale (`veloce_integrale`)
- **Klatka z fioletowym szkłem**: rama to 12 cienkich prętów (2×2 px) po
  krawędziach sześcianu, a okna wypełnia **fioletowe szkło** — wzięte wprost
  z wanilii (`minecraft:block/purple_stained_glass`), więc wygląda dokładnie
  jak `purple stained glass`
- Szyba jest **wcięta o 2 px** (dokładnie w świetle ramy): nie dotyka
  płaszczyzn bloku, więc nie ma z-fightingu z prętami
- Model ma `render_type: minecraft:translucent` — bez tego fiolet byłby
  nieprzezroczysty (przez szybę widać świat)
- **Kolizja to zwykły pełny blok** — klatka wygląda jak szkielet, ale zachowuje
  się jak normalny klocek (można po niej chodzić, nie da się przez nią przejść);
  `noOcclusion` + przepuszczanie światła, żeby sąsiad nie renderował się
  „w dziurze”
- **Zabudowa stron od kabli**: gdy z danej strony dochodzi rura Veloce, okno
  od tej strony **zamyka się metalową blachą** — od razu widać, z której strony
  klatka jest podłączona. Stan trzymany jest w **stanie bloku** (sześć
  booleanów waniliowego `PipeBlock`, po jednym na stronę), a `updateShape`
  przelicza tylko tę jedną stronę; brak block entity i tickera, więc działa
  też na kliencie bez pakietów
- **Węzeł sieci Veloce**: rura łączy się z każdej strony, ale klatka **nie
  utrzymuje chunku** (`VeloceNetworkNode.keepChunkLoaded()` = `false`) — jest
  dekoracją, a force-loady mają trzymać to, co naprawdę pracuje
- Blockstate jest **wielocześciowy** (`multipart`): model ramy + po jednej
  blasze na stronę (`veloce_integrale_panel_<strona>`), a nie 64 warianty
- **Stol craftingu w klatce (podmiana bloku)**: right-click **z crafting table**
  (waniliowym albo naszym) **podmienia całą klatkę** na nasz
  `veloce_crafting_table` w stanie `facade`. W świecie stoi wtedy **prawdziwy
  stół craftingu** (auto-crafter, GUI, bufor, węzeł sieci), a nie atrapa;
  wygląda jak klatka (rama + blachy), a w środku renderuje się model crafting
  table, który **delikatnie się obraca i buja** (lewo-prawo, góra-dół)
- **Dlaczego podmiana bloku, a nie własny block entity**: pierwsza wersja
  trzymała w klatce BE stołu i **udawała** craftera — ale wtedy klatka nie była
  stołem, więc mod od receptur (JEI/EMI) nie miał czego rozpoznać, a sieć
  musiała znać wyjątek „klatka bywa crafterem”. Teraz wystarczy spojrzeć na
  **typ bloku** (`isActiveCrafter`), a `facade` zmienia tylko wygląd
- **Gabłota na dowolny blok**: right-click innym blokiem wystawia go w środku,
  a right-click z pustą ręką **oddaje eksponat** (jak ramka na przedmioty)
- **Droga powrotna**: shift + right-click z pustą ręką na stole w klatce
  rozbiera stację na **pustą klatkę + crafting table** — bez tego pustej klatki
  nie dałoby się odzyskać, bo zbita stacja oddaje jeden przedmiot „rama + stół”
- **Jeden przedmiot na wyjściu**: zbicie stacji wypuszcza
  `veloce_integrale_crafting` („Veloce Integrale (Crafting)”) — rama + stół
  w jednym przedmiocie, z własną ikoną (rama z crafting table w środku), żeby
  gracz i mody od receptur widziały, że w tym bloku można craftować
- **Wszystko po stronie klienta**: zamiast encji `BlockDisplay` (którą trzeba by
  tworzyć, zapisywać i utrzymywać na serwerze) klient czyta *jeden stos* z block
  entity i renderuje jego model — serwer nie robi nic ponad zapis NBT.
  Renderer (`VeloceDisplayRenderer`) wisi na BE stolu craftingu i wychodzi od
  razu, gdy nie ma czego pokazać, więc zwykły crafting table nic nie kosztuje
- Zbicie klatki z eksponatem wypuszcza go (a samą klatkę loot table) — schowek
  nie zjada przedmiotów
- Pusta klatka **nie jest** crafterem (decyduje typ bloku), nie wystawia bufora
  sieci i nie trzyma chunku; stół, który powstaje z podmiany — jest, wystawia
  i trzyma
- Modelu i ikony pilnuje `validate_integrale_model` w `build.py` (12 prętów,
  szyba wcięta i ze szkła, `render_type: translucent`, `ambientocclusion: false`,
  6 blach dokładnie w świetle okna, 6 warunków w blockstate, model stolu dla
  `facade=true` i ikona z kostką stolu 4..12), a samej mechaniki — 
  `validate_integrale_display` (podmiana bloku, zgłoszenie węzła do sieci,
  gablota, droga powrotna). Ikona jest **generowana** z modelu ramy
  (`scripts/gen_integrale_crafting_item.py`), więc nie może rozjechać się z ramą


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
│   ├── cp.txt                   # Classpath do kompilacji (generowany, ogromny)
│   ├── build.py                 # Kompilacja + pakowanie + kontrole jakości (krok 4)
│   ├── gen_loot_tables.py       # Loot table i tagi „mineable” z rejestru bloków
│   └── gen_integrale_crafting_item.py  # Ikona itemu „stół w klatce” z modelu ramy
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
- **compileOnly (opcjonalne integracje)** — `scripts/build.py` sam znajduje te JAR-y
  w `libs/`, w folderze mods profilu `testing` albo w `~/Downloads`:
  `create-1.21.1-6.0.10.jar`, `ponder-neoforge-1.0.82+mc1.21.1.jar` (wyciągany
  automatycznie z `META-INF/jarjar/` Create), `alchemistry-1.21.1-2.4.5.jar`,
  `alchemylib-1.21.1-1.1.6.jar`, `chemlib-1.21.1-2.1.5.jar`,
  `Mekanism-...-api.jar` (albo pełny `Mekanism-...jar`).
  JAR-y są wymagane **tylko wtedy**, gdy jakieś źródło naprawdę importuje dany
  obcy pakiet — etap „same bramki, zero bloków" kompiluje się bez nich.
  `*.jar` jest w `.gitignore`, więc nic z tego nie trafia do repozytorium.


### ⚠️ Ważne pułapki

1. **`src/moze_intel/`** — NIE należy do naszego moda! To są patchowane klasy `inventoryexchange-1.0.0.jar`. Jeśli trafią do naszego JARa → `ResolutionException` przy starcie MC (duplicate package export). Zawsze wykluczaj je z kompilacji do craftingveloce.

2. **`src/eatawesome/`** — też nie nasze, to stary mod InventoryExchange. Nie kompilować.

3. **`src_meta/META-INF/`** — JEDYNE źródło `neoforge.mods.toml`. Build czyści
   staging tylko z `com/`, `assets/`, `data/` i kopiuje `META-INF` ze `src_meta/`,
   więc plik jest wersjonowany i nie znika przy czystym rebuildzie. Nie trzymaj
   kopii `mods.toml` w `craftingveloce_jar_root/` — to była przyczyna JAR-a bez
   metadanych (loader takiego moda nie widzi).

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
| Create / Alchemistry / Mekanism | opcjonalne (`compat/*`) — patrz niżej |

### Opcjonalne integracje (`com/craftingveloce/compat/`)

Zasada nadrzędna: **rdzeń nie zna żadnego obcego moda**. Moduł integracji żyje w
`compat/<mod>/`, ma obce typy wyłącznie w ciałach metod i jest wołany dopiero po
sprawdzeniu obecności moda w bramce (`XCompat.isPresent()` → `XCompat.register()`
z `CraftingVeloceMod`). Każdy mod jest w `neoforge.mods.toml` jako
`type="optional"` + `ordering="AFTER"`.

`try/catch` wokół kodu integracji **nie działa** — `NoClassDefFoundError` leci
przy ładowaniu i linkowaniu klasy, zanim `try` zdąży zadziałać. Dlatego obcy typ
nie może wystąpić w polu ani sygnaturze klasy ładowanej bezwarunkowo.

Pilnują tego cztery kontrole w `scripts/build.py` (krok 4):

| Kontrola | Czego pilnuje |
|----------|----------------|
| `validate_core_isolation` | żaden plik poza `compat/` nie importuje obcego pakietu; `compat/<mod>` nie importuje innego obcego moda |
| `validate_compat_gates` | klasy ładowane zawsze (rdzeń + `compat/` + bramki) nie mają obcego typu w sygnaturze — na poziomie bajtkodu (`javap`) |
| `validate_jar_isolation` | żadna klasa rdzenia w JARze nie odwołuje się do obcego pakietu (łapie też użycie bez `import`) |
| `validate_isolation_runtime` | **L1**: uruchamia `scripts/isolation/L1Test.java` bez obcych modów — bramki muszą się załadować i zwrócić `false` |

Zakres rodzin receptur rejestrują moduły (`XRecipeFamily`) w
`VeloceRecipeFamilies.registerModFamily` — w `FMLCommonSetupEvent`, bo
DeferredHoldery obcego moda są wiązane dopiero po rejestracji.

### Moduły maszyn (bloki z `compat/*`)

Zaimplementowane moduły: **Mekanism** i **Alchemistry**. Wszystkie maszyny
itemowe dzielą jeden blok rdzenia (`VeloceFeModuleBlock`) i jeden block entity
(`VeloceFeModuleBlockEntity`) — różni je wyłącznie opis `FeModule` (typ
receptury, koszt FE, etykieta).

| Blok | Rodzina receptur | Koszt |
|------|------------------|-------|
| `veloce_mekanism_crusher_module` | `mekanism:crushing` | 4000 FE/operację |
| `veloce_mekanism_enrichment_module` | `mekanism:enriching` | 4000 FE/operację |
| `veloce_mekanism_combiner_module` | `mekanism:combining` (2 wejścia) | 4000 FE/operację |
| `veloce_mekanism_sawmill_module` | `mekanism:sawing` (wynik losowy) | 4000 FE/operację |
| `veloce_alchemistry_compactor_module` | `alchemistry:compactor` | 2500 FE/operację |
| `veloce_alchemistry_combiner_module` | `alchemistry:combiner` (N składników) | 10 000 FE/operację |
| `veloce_alchemistry_fission_module` | `alchemistry:fission` (1 → 2 wyniki) | 15 000 FE/operację |
| `veloce_alchemistry_fusion_module` | `alchemistry:fusion` (2 → 1) | 15 000 FE/operację |
| `veloce_create_millstone_module` | `create:milling` | kinetyka |
| `veloce_create_saw_module` | `create:cutting` | kinetyka |
| `veloce_create_crushing_module` | `create:crushing` (wyniki losowe) | kinetyka |
| `veloce_create_mechanical_crafter_module` | `create:mechanical_crafting` (siatki > 3×3) | kinetyka |

Koszty są przepisane z oryginalnych maszyn (Mekanism: 20 FE/t × 200 t;
Alchemistry: `energyPerTick` × `ticksPerOperation`), bufory odpowiednio
40 000 FE i 100 000 FE. Każda maszyna stoi w sieci jak każda inna, przyjmuje FE
kablem (capability `EnergyStorage`), a kliknięcie pokazuje stan akumulatora na
pasku akcji. Sawmill planuje tylko wynik główny, a dodatkowy dorzuca po rzucie
kością; fission planuje oba wyniki, bo oba są gwarantowane.

Maszyny Create są **kinetyczne**, nie na FE: wał napędowy wchodzi od dołu
(os obrotu Y), a „zasilenie" to `getSpeed() != 0` (Create sam zwraca 0 przy
overstress i zatrzymanej sieci). Pobór SU jest **stały** niezależnie od RPM —
`calculateStressApplied()` dzieli stałą przez prędkość, bo `CStress.setImpact`
rzuca wyjątek dla bloków spoza Create. Rury Veloce łączą się z każdej strony,
niezależnie od napędu. Receptury z płynami i wymagające ciepła (blaze burner)
są w v1 pomijane — nie mamy czym ich „opłacić".

Dissolver (probabilistyczny `ProbabilitySet`), press/mixer/basin Create (pracują
na zawartości Basenu) oraz maszyny na płynach/chemikaliach czekają na osobną
politykę probabilistyki i warstwę płynów.

Jak dołożyć kolejny moduł — cała procedura:

1. `XCompat.register(...)` → `XBlocks`, `XBlockEntities`, `XCapabilities`,
   `XRecipeFamily` oraz `XModule` (rejestracja modułu w `FMLCommonSetupEvent`).
2. Blok w `compat/<mod>/block/` implementuje `VeloceNetworkNode` i woła
   `VeloceNodeBlocks.onNodePlaced` / `onNodeRemoved` (pilnuje tego
   `validate_node_blocks`).
3. Block entity implementuje `VeloceProcessingSource` (+ `IEnergyStorage` dla
   FE): podaje `recipeTypes()`, `availableOperations()`, `consumeOperations()`,
   `isPowered()`. Rdzeń nie zna ani jednej nazwy z obcego moda.
4. `XModule implements VeloceProcessingModule`: `producible`, `available`,
   `powered`, `recipesFor` — i tylko tyle.
5. Receptury obcego moda tłumaczy `XRecipeHarvest` na wspólny `ProcessingEntry`
   (wyniki, szanse, liczby sztuk na składnik).
6. Zasoby: blockstate + model bloku + model itemu + loot table (generator
   `scripts/gen_loot_tables.py` czyta rejestry `compat/*/*Blocks.java`, więc
   nowy blok sam dostanie loot table i tag `mineable`) + klucze w `en_us.json`.
7. Wpis w zakładce kreatywnej **wewnątrz** lambdy `displayItems`, pod
   `isPresent()` — zakładka buduje się zawsze, także bez tego moda.

Rdzeń nie wymaga przy tym żadnej zmiany.

---

## 🧩 Overlay: Jade (`compat/jade`)

Jade pokazuje nasze bloki (nazwy z rejestru), a nasz plugin **dokłada linie ze
stanem** — dla **wszystkich** bloków w namespace `craftingveloce`:

| Blok | Linie |
|------|-------|
| crafter (także stół w klatce) | auto-crafting włączony (ile itemów wyłączonych), rozmiar bufora |
| klatka Integrale | pusty schowek / „Display: &lt;item&gt;”, ile stron zabudowanych, podpowiedź o prawym kliku |
| stół w klatce (`facade`) | „Integrale case: crafting table inside” |
| piec paliwowy i elektryczny | nazwa źródła ciepła + ile operacji, „Powered: tak/nie” |
| moduły Create / Mekanism / Alchemistry | id modułu, ile operacji zostało, „Powered” |
| kontroler | ile typów itemów w sieci, ile itemów ma stały trend |
| ekstraktor | ile slotów filtra ustawionych, ile z auto-craftingiem |
| sensor progu | filtr, próg (+ tryb „when at least / when below”), ile jest w sieci |
| terminal | węzły, magazyny, ile typów itemów w sieci |
| rura | ile rur w sieci (albo „nie podłączona do sieci”) |

Zasady tej integracji:

1. **Jade jest miękka zależnością.** Plugin jest odkrywany przez Jade po
   adnotacji `@WailaPlugin`, więc bez Jade jego klasa **nie jest nawet
   ładowana**, a rdzeń go nie woła (`validate_jade_plugin` tego pilnuje).
2. **Liczby liczy serwer** (`VeloceBlockDataProvider`), bo w tooltipie na
   kliencie nie ma aktualnych danych, a liczenie ich w locie znaczyłoby skan
   sieci przy każdej klatce tooltipa. Linia pojawia się tylko wtedy, gdy dana
   wartość naprawdę przyszła — żadnego „0” udającego prawdę.
3. **Jedna rejestracja na `Block.class`** + filtr po namespace, a nie ręczna
   lista naszych bloków (lista modułów rośnie, a spis rozjechałby się
   z rejestrem).
4. `Jade-1.21.1-NeoForge-15.10.6.jar` jest zależnością **compileOnly**
   (rozwiązywaną automatycznie z profilu `testing`), a kontrola wycieków
   pilnuje, żeby żadna klasa Jade nie trafiła do naszego JAR-a.

---

## 🌐 Network (Packets)

Wszystkie packety używają NeoForge `CustomPacketPayload` / `StreamCodec`.

| Packet | Kierunek | Zawartość |
|--------|----------|-----------|
| `BufferPullItemPKT` | C→S | `BlockPos pos, ItemStack itemStack, int count` |
| `ControllerFlowRequestPKT` | C→S | `BlockPos pos` |
| `CraftingTableCycleRecipePKT` | C→S | `BlockPos pos, Item item, ResourceLocation recipeId` |
| `CraftingTableToggleItemPKT` | C→S | `BlockPos pos, Item item` |
| `ExtractorToggleCraftingPKT` | C→S | `BlockPos pos, int filterIndex` |
| `OpenControllerScreenPKT` | S→C | `BlockPos pos, Map<Item, Long> stock, Set<Item> craftingEnabled, Set<Item> furnaceCraftable, boolean furnacePowered, Set<Item> furnacePreferred` |
| `OpenCraftingTableScreenPKT` | S→C | `BlockPos pos, Set<Item> enabledItems, Map<Item, ResourceLocation> preferredRecipes, List<ItemStack> bufferContents` |
| `OpenFilterPKT` | C→S | `BlockPos pos, int filterIndex` |
| `OpenFilterPickerPKT` | S→C | `BlockPos pos, int filterIndex` |
| `OpenTerminalScreenPKT` | S→C | `BlockPos terminalPos` |
| `RequestCraftableCountsPKT` | C→S | `BlockPos pos, List<Item> items` |
| `SensorConfigPKT` | C→S | `BlockPos pos, long threshold, boolean highMode` |
| `ControllerPreferKindPKT` | C→S | `BlockPos pos, Item item, boolean preferFurnace` |
| `SetFilterPKT` | C→S | `BlockPos pos, int filterIndex, ItemStack filterItem` |
| `SyncControllerFlowPKT` | S→C | `BlockPos pos, Map<Item, Long> changed, Set<Item> removed, Map<Item, Float> rates, boolean full` |
| `SyncCraftableCountsPKT` | S→C | `BlockPos pos, Map<Item, Long> counts, boolean complete` |
| `SyncCraftingTableStatePKT` | S→C | `BlockPos pos, Set<Item> enabledItems, Map<Item, ResourceLocation> preferredRecipes` |
| `SyncExtractorFiltersPKT` | S→C | `BlockPos pos, List<ItemStack> filters, List<Boolean> allowCrafting` |
| `SyncTerminalCountsPKT` | S→C | `Map<Item, Long> itemCounts, Map<Item, Long> craftableCounts` |
| `TerminalPullItemPKT` | C→S | `BlockPos terminalPos, ItemStack itemStack, int count` |
| `TerminalStoreItemPKT` | C→S | `BlockPos terminalPos, int mode` |
| `TerminalWatcherPKT` | C→S | `BlockPos pos, boolean watching` |

> Ta tabela jest generowana z `VelocePacketHandler.java`. Wcześniej wypisywała
> pakiety, których już nie ma (`ExtractorSetFilterPKT`, `ExtractorOpenFilterPKT`)
> i nie znała ośmiu nowych — czyli dokładnie ten sam rozjazd, co przy innych
> ręcznie pisanych listach w tym projekcie. Build (`scripts/build.py`, krok 4)
> sprawdza teraz, że **każdy zarejestrowany pakiet jest tu wymieniony**.

---

## 🧭 Komendy diagnostyczne `/cv`

Wszystkie wymagają **poziomu uprawnień 2** (jak `/gamemode`) — wcześniej nie
miały żadnego wymogu, więc na serwerze mógł ich użyć każdy gracz.

| Komenda | Co robi |
|---------|---------|
| `/cv debug` | Ślad połączeń bloku, na który patrzysz (strona, tryb, sąsiedzi) |
| `/cv perf` | Rozmiary map śledzenia loadera chunków i cache'y |
| `/cv chunk on\|off\|status\|cleanup` | Podgląd / sprzątanie force-loadów chunków |
| `/cv trace` | Przełącznik szczegółowego logowania zdarzeń sieci |
| `/cv testnet [build]` | Buduje sieć testową i wypisuje wynik skanu |
| `/cv getitems <item> [recipe]` | Wkłada do skrzyni, na którą patrzysz, **wszystkie składniki** receptury podanego itemu (id podpowiada się jak w `/give`; numer wybiera recepturę, gdy item ma ich kilka) |

Uprawnienia są zdefiniowane w **jednym** miejscu: `CVCommandRoot.root()`.
Cztery pliki komend rejestrują ten sam korzeń `cv` (Brigadier scala je w jeden
węzeł), więc wspólny warunek dostępu musi być identyczny — inaczej jedna
komenda mogłaby po cichu decydować o dostępie do wszystkich.

`/cv getitems` bierze składniki ze **wszystkich źródeł receptur**
(`VeloceRecipeFinder`): crafting table, rodziny modułów (Create/Alchemistry/
Mekanism) oraz piec — w tej kolejności, z deduplikacją po id receptury. Dzięki
temu item powstający wyłącznie w maszynie modułu (np. `create:crushing_wheel`
z mechanical craftingu) ma swoją recepturę widoczną także wtedy, gdy gracz nie
ma jeszcze tej maszyny w sieci. W czacie widać, skąd receptura pochodzi
(`receptura maszyny: create:mechanical_crafting`), ile sztuk daje jedno
wykonanie i **które warianty składników z tagów** zostały wybrane (np. które
deski), bo tylko one trafiają do skrzyni. Daje **jeden poziom** składników (nie
rozwija drzewa typu deski → kłody) i nic nie wyrzuca na ziemię: to, co się nie
zmieściło, jest wypisane w czacie.

### ClientTerminalHelper

Jedyne miejsce, w którym client-side handlery pakietów dotykają ekranów.
Wcześniej każdy pakiet sam szukał swojego ekranu (`instanceof` w kilku
miejscach), więc dodanie ekranu wymagało pamiętania o wszystkich.

Wszystkie metody publiczne (stan na teraz — pilnuj, żeby ta lista nie
została w tyle, tak jak wcześniej lista pakietów i sekcja GUI):

- `openTerminalScreen(BlockPos pos)`
- `clearSavedTerminalViews()` — czyści zapamiętane widoki terminali (wyjście ze świata)
- `handleSyncCounts(Map<Item, Long> itemCounts)` / `handleSyncCounts(itemCounts, craftableCounts)`
- `openFilterPickerScreen(BlockPos pos, int filterIndex)`
- `reopenFilterHostScreen(BlockPos pos)` — powrót z pickera do GUI bloku,
  z którego go otwarto. Przywraca **zapamiętany ekran** (nie pyta o typ menu —
  tamta wersja crashowała) i **oddaje graczowi menu tego ekranu**: picker
  dziedziczy po ekranie creative, który w konstruktorze podmienia
  `player.containerMenu` na swoje `ItemPickerMenu`, a bez oddania menu każde
  kliknięcie w ekwipunku wracającego GUI jest cicho odrzucane
  (`Ignoring click in mismatching container`). Zwraca `false`, gdy nie ma do
  czego wracać — wtedy wołający musi zamknąć ekran sam.
- `handleSyncExtractorFilters(BlockPos pos, List<ItemStack> filters, List<Boolean> allowCrafting)`
- `getClientHitResult()` — co gracz widzi pod kursorem (klient)
- `openCraftingTableScreen(BlockPos pos, Set<Item> disabledItems, Map<Item, ResourceLocation> preferredRecipes, List<ItemStack> bufferContents)`
- `updateCraftingTableState(BlockPos pos, Set<Item> disabledItems, Map<Item, ResourceLocation> preferredRecipes)`
- `handleCraftableCounts(BlockPos pos, Map<Item, Long> counts, boolean complete)` — odpowiedź serwera z liczbami "ile da się dorobić"

> **Liczby "ile da się dorobić" — dwie różne rzeczy, dwie różne miary.**
> Liczba przy itemie odpowiada na pytanie „ile tego **mogę mieć** z tego, co
> leży w sieci", więc dla receptur pieca liczy się **surowiec**, a nie to, ile
> piec ma teraz w buforze: brak zasilanego pieca = receptury pieca wyłączone,
> jakikolwiek zapas ciepła = receptury działają, a liczba idzie za piaskiem
> (64 piasku → 64 szkła). Plan **wykonania** nadal używa prawdziwego budżetu
> ciepła (`VeloceHeatSources.totalOperations` + `consumeFrom`) — inaczej
> obiecywałby przepalenia, których piec nie ugnie w jednym planie.
>
> **Tempo w kontrolerze: jedna linia, tylko stały trend.** Serwer liczy próbki
> co 5 s (okno minuty, 13 próbek) i co 60 s (okno godziny, 61 próbek), a tempo
> to różnica skrajnych próbek podzielona przez **realny** czas z pierścienia
> ticków. Do GUI trafiają **wyłącznie** itemy o trendzie jednokierunkowym
> (≥80% ruchu w jedną stronę i co najmniej dwa ruchy w tę stronę) — stąd jedna
> linia `+2.0/min, +120/h` (jedna liczba w dwóch skalach) i **brak** linii dla
> itemów stojących lub szarpiących się. Historia żyje **tylko w pamięci**
> kontrolera (kontroler nie zapisuje stanu do NBT), więc po restarcie świata
> liczy się od nowa; przerwa w tykaniu > 20 s kasuje historię. Koszt: dla
> każdego itemu najpierw tani test (różnica skrajnych próbek, O(1)), a pełny
> spacer po próbkach tylko dla itemów, które się ruszają — dlatego 100 tys.
> typów itemów w sieci nie zamula GUI.
>
> Partia liczenia ma wspólny budżet czasu (25 ms) i bywa przerywana
> (`complete=false` w logu). Dlatego serwer startuje kolejną partię tam, gdzie
> skończył poprzednią (rotacja — inaczej ogon listy nigdy nie doczekałby się
> przeliczenia i trzymał stare liczby), a klient ponawia zapytanie, dopóki
> odpowiedź nie jest pełna. Każdy item w partii dostaje **równy udział** czasu:
> jeden zbyt złożony item jest pomijany (i trafia do kolejnej partii), a nie
> zabija całej partii — wcześniej kończył ją, więc w logu gracza widać było
> `45 item(s) -> 0 result(s)`, czyli GUI bez ani jednej świeżej liczby.
> Item, który powstaje **wyłącznie** w piecu z surowców bez własnych receptur
> (np. szkło z piasku), liczy się osobną, tanią ścieżką — bez planera.
- `openControllerScreen(BlockPos pos, Map<Item, Long> stock, Set<Item> craftingEnabled, Set<Item> furnaceCraftable, boolean furnacePowered, Set<Item> furnacePreferred)`

> Uwaga na `disabledItems`: crafter działa w modelu **opt-out**, więc to zbiór
> WYJĄTKÓW (wyłączonych), a nie lista włączonych. Odwrócenie tego znaczenia było
> już raz źródłem błędu.

---

## 📊 VelocePipeNetworkManager

`SavedData` trzymany w `ServerLevel` (`level.getDataStorage()`), więc każdy
wymiar ma własną instancję i nie ma tu żadnego stanu statycznego.

**Płaska struktura, nie obiekty sieci.**
Źródłem prawdy o połączeniach jest `VelocePipeWorld`: mapa „pozycja rury ->
realne sąsiedztwo". Obiekty `VelocePipeNetwork` są tylko WYNIKIEM zapytania o
komponent tej struktury — nie ma już scalania ani rozdzielania sieci.
Dzięki temu rozcięcie sieci nie wymaga zgadywania podziału: komponenty
rozdzielają się same, bo wynikają wprost z połączeń.

Są DWIE ścieżki budowy sieci i **obie muszą respektować te same reguły**:
`scanAndBuildNetwork()` (skan świata) oraz `toNetwork()` (odbudowa z
zapamiętanego komponentu — ta działa w praktyce). Rozjazd między nimi był już
kilka razy źródłem błędów, dlatego reguły (tryb Pull, strony rozłączone,
rodzaje węzłów) siedzą we wspólnych helperach, a nie w obu pętlach osobno.

**Typy węzłów — jedyne źródło prawdy to `VeloceNodeBlocks.isNode()`.**
Nie wypisuj tej listy po raz drugi (właśnie tak powstały dwie nieaktualne
kopie w `/cv debug`):
`VeloceTomTerminalBlock`, `VeloceControllerBlock`, `VeloceCraftingTableBlock`,
`VeloceExtractorBlock`, `VeloceVelocityFurnaceBlock`,
`VeloceElectricFurnaceBlock`, `VeloceThresholdSensorBlock`.

**Endpoint** to zwykły magazyn podłączony do rury: skrzynia / inventory
NeoForge, sieć Refined Storage albo bufor craftera. Endpointy pamiętają
ostatnio znaną zawartość, żeby terminal widział skrzynię także wtedy, gdy jej
chunk nie jest w symulacji.

**Kluczowe metody:**
- `get(ServerLevel)` — instancja dla wymiaru
- `getNetworkForTerminal(ServerLevel, BlockPos)` — sieć dla terminala
- `onTerminalPlaced/Removed(ServerLevel, BlockPos)` — węzeł dołączony/odłączony
- `onPipeBroken(ServerLevel, BlockPos)` — kolejka przebudowy (NIE robi BFS od razu)
- `refreshInsertModes(ServerLevel, net, pipes)` — jedna wspólna reguła trybów
  wstawiania dla obu ścieżek budowy

**VelocePipeNetwork:**
- `getAllItemCounts(ServerLevel)` → `Map<Item, Long>` — agregat z cache (TTL 10 ticków)
- `capacityFor(ServerLevel, Item)` — ile jeszcze wejdzie (0 = dowód, że nie wejdzie;
  `-1` = nie wiemy — tych dwóch NIE wolno traktować zamiennie)
- `extractItem(ServerLevel, Item, int)` — wyciąga item z pierwszego magazynu, który go ma

---

## 🕹️ GUI Patterns

**Dwie rodziny ekranów.**
* "Creative-like": `VeloceTerminalScreen`, `VeloceControllerScreen`,
  `VeloceCraftingTableScreen`, `VeloceFilterPickerScreen` — rozszerzają
  `VeloceCreativeScreen`, a ten `CreativeModeInventoryScreen`. Potrzebują
  siatki itemów.
* Maszyny: `VeloceExtractorScreen`, oba piece, sensor — zwykły
  `AbstractContainerScreen`. Siatka itemów nie jest im do niczego potrzebna.

**Wspólna baza `VeloceCreativeScreen`:**
1. `init()` ustawia `GameType.CREATIVE`, jeśli gracz nie jest w creative
2. `suppressPlayerSlots()` zastępuje sloty gracza nieaktywnymi slotami poza
   ekranem. Hook `keepPlayerHotbar()` decyduje, czy hotbar zostaje — terminal
   nadpisuje go na `true`, bo gracz odkłada tam wyciągnięte itemy
3. `renderSlot()` nadpisany w każdej z czterech podklas — pomija sloty gracza
4. `render()` zamalowuje pasek hotbara (`drawHotbarCover(..., 0xFFC6C6C6)`)
5. `containerTick()` **nie jest puste**: baza utrzymuje tam filtr listy i
   ukrywanie zakładek administracyjnych, a terminal, kontroler i picker
   zamawiają na żywo liczby "ile da się dorobić". Pusta wersja w podklasie
   oznaczała kiedyś, że po zmianie zakładki lista przestawała być filtrowana
6. `removed()` zapisuje widok, oddaje creative jego zakładkę, przywraca
   `GameType` **i oddaje stos trzymany na kursorze** (do ekwipunku, a gdy się
   nie zmieści — na ziemię)

**Overlay liczb — wspólny `VeloceSlotOverlay`** (terminal, kontroler, picker,
crafter używają tego samego kodu):
- scale `0.6f`, z cieniem
- stock: biały `0xFFFFFF`, prawy dolny róg slotu (`drawStock`)
- "do dorobienia": pomarańczowy `0xFFA500`, lewy górny róg (`drawCraftable`)
- `formatCount(long)` skraca liczby do "1K", "2.5M" itd.
  (`VeloceTerminalScreen.formatCount` to już tylko zgodnościowy delegat)

**Overlay stanu auto-craftingu (`VeloceCraftingTableScreen`):**
- `0x7700AA00` zielony — receptura jest i auto-crafting WŁĄCZONY
- `0x77AA0000` czerwony — receptura jest, ale WYŁĄCZONY
- `0xAA000000` ciemny — crafter tego itemu nie zrobi
- Cache `craftableItems` kluczowany `RecipeManager`-em: nowe wejście do świata
  tworzy nowy manager, więc cache unieważnia się sam

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
- **`crafting/VeloceRecipeGraph.java` — MARTWY KOD (212 linii, zero uzyc)** —
  odwrotny indeks receptur (item skladnik -> co mozna z niego zrobic), pisany
  jako fundament przyrostowego przeliczania craftowalnosci. Nigdy nie zostal
  podpiety: `VeloceCraftingCache` poszedl inna droga (okresowy pelny przelicznik
  w tle). Klasa kompiluje sie do JARa i nic nie robi. Do decyzji: albo ja
  podpiac (przyrostowe odswiezanie zamiast pelnego skanu), albo usunac.
  Sprawdzone: zaden inny plik moda sie do niej nie odwoluje.

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
