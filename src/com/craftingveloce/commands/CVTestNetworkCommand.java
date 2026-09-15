package com.craftingveloce.commands;

import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.init.VeloceRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Buduje testowa siec Veloce do sprawdzania zachowania na niezaladowanych chunkach.
 *
 * <p><b>Po co to.</b> Ręczne stawianie 1000 rur, zeby przetestowac rozladowywanie
 * chunkow, jest niepraktyczne - a bez dlugiej sieci nie da sie w ogole wejsc
 * w sytuacje, w ktorej magazyn jest poza symulacja. Ta komenda stawia caly
 * uklad jednym poleceniem, a na koncu wypisuje gotowe komendy do tepania.
 *
 * <p><b>Uklad:</b>
 * <pre>
 *   [terminal] --- 1000 rur na polnoc --- [baryłka]
 *      start                                  koniec
 * </pre>
 *
 * <p><b>Dlaczego baryłka, a nie skrzynia.</b> Baryłka ({@code barrel}) jest
 * pojemnikiem bez dodatkowej logiki, wiec idealnie nadaje sie na "prosty
 * magazyn, ktory ma sie rozladowywac". Skrzynia vanilla laczy sie w podwojna,
 * co zmienialoby pozycje endpointu.
 *
 * <p><b>Dlaczego chunki sa ladowane na chwile.</b> Rury musza sie ze soba
 * polaczyc, a to wymaga symulacji. Komenda najpierw stawia caly uklad przy
 * zaladowanych chunkach, a potem pozwala im wypasc - dopiero wtedy test ma sens.
 */
public final class CVTestNetworkCommand {

    private CVTestNetworkCommand() {
    }

    /** Domyslna dlugosc sieci w blokach. */
    private static final int DEFAULT_LENGTH = 1000;

    /**
     * Ile chunkow przy forsie zostawiamy wokol trasy.
     *
     * <p>To nie jest force-load - tylko chwilowe wymuszenie, zeby WSZYSTKIE rury
     * zdazyly sie polaczyc i zeby siec zapisala sie w NBT w komplecie.
     */
    private static final int BUILD_CHUNK_MARGIN = 1;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cv")
                .then(Commands.literal("testnet")
                    .executes(ctx -> build(ctx, DEFAULT_LENGTH))
                    .then(Commands.literal("build")
                        .executes(ctx -> build(ctx, DEFAULT_LENGTH))
                        .then(Commands.argument("length", IntegerArgumentType.integer(8, 4000))
                            .executes(ctx -> build(ctx, IntegerArgumentType.getInteger(ctx, "length")))))
                    .then(Commands.literal("clear")
                        .executes(CVTestNetworkCommand::clear))
                    .then(Commands.literal("tp")
                        .then(Commands.literal("start")
                            .executes(ctx -> teleport(ctx, true)))
                        .then(Commands.literal("end")
                            .executes(ctx -> teleport(ctx, false))))
                    // Przypomnienie komend tepania - bez przebudowy sieci.
                    .then(Commands.literal("where")
                        .executes(CVTestNetworkCommand::where))
                ));
    }

    // ------------------------------------------------------------------
    // Budowa
    // ------------------------------------------------------------------

    private static int build(CommandContext<CommandSourceStack> context, int length) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        // Zaczynamy na POSADZCE gracza, a nie w jego oczach - latwiej trafic
        // i nie trzeba sie potem cofac, gdy cos wisi w powietrzu.
        BlockPos origin = source.getPlayer() != null
                ? source.getPlayer().blockPosition()
                : BlockPos.containing(source.getPosition());

        // Uklad biegnie na polnoc, zeby nie zalezec od kierunku patrzenia
        // (ten sam test zawsze wychodzi tak samo).
        Direction forward = Direction.NORTH;

        BlockPos startPos = origin;
        BlockPos endPos = origin.relative(forward, length);

        // Poziom posadzki: polozmy ja, zeby bylo po czym chodzic i zeby
        // baryłka/terminal nie wisialy w powietrzu.
        List<BlockPos> floor = new ArrayList<>();
        for (int i = 0; i <= length; i++) {
            floor.add(origin.relative(forward, i));
        }

        // 1. Wymuszenie chunkow na czas budowy.
        //
        // Bez tego rury w dalszych chunkach nie polaczylyby sie (nie ma
        // symulacji), a siec zapisalaby sie w NBT jako lancuch bez zwiazkow.
        List<ChunkPos> touched = new ArrayList<>();
        for (BlockPos p : floor) {
            ChunkPos cp = new ChunkPos(p);
            if (!touched.contains(cp)) {
                touched.add(cp);
            }
        }

        // BUDZET CZASU NA TE FAZE.
        //
        // 1000 blokow w linii to ~63 chunki, a wymuszenie chunku to wczytanie
        // go z dysku (superflat: kilka-kilkanascie ms). Bez limitu jedna
        // komenda zamrozilaby serwer na ponad sekunde - dokladnie ta klasa
        // bledu, ktora w tym modzie juz raz wystapila.
        //
        // Dlatego faza wymuszania ma twardy budzet. Jesli sie nie zmiesci,
        // MOWIMY o tym wprost i przerywamy, zamiast zamulac gre.
        long buildDeadline = System.nanoTime() + 400_000_000L;   // 400 ms
        int forced = 0;
        for (ChunkPos cp : touched) {
            if (System.nanoTime() > buildDeadline) {
                for (int j = 0; j < forced; j++) {
                    ChunkPos done = touched.get(j);
                    level.setChunkForced(done.x, done.z, false);
                }
                source.sendFailure(Component.literal(
                        "§cBudowa przerwana: " + touched.size()
                                + " chunkow nie zmiescilo sie w budzecie czasu."));
                source.sendSuccess(() -> Component.literal(
                        "§7Sprobuj krocej: §f/cv testnet build 500§7, "
                                + "albo rozbij na kilka komend."), false);
                return 0;
            }
            level.setChunkForced(cp.x, cp.z, true);
            forced++;
        }

        // 2. Posadzka + rury + konce.
        int pipes = 0;
        for (int i = 0; i <= length; i++) {
            BlockPos p = origin.relative(forward, i);
            // Posadzka pod rura - zeby dalo sie chodzic i tepac.
            level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);

            if (i == 0) {
                // Terminal na starcie.
                //
                // UWAGA: setBlock omija getStateForPlacement, wiec defaultBlockState()
                // daje DOMYSLNY FACING. Ustawiamy go recznie tak, zeby przod
                // terminala NIE byl zwrocony w strone rur - inaczej
                // canConnectFrom() odrzuci polaczenie i siec nie powstanie
                // (dokladnie ten blad naprawialismy w canConnectFrom).
                //
                // Rury ida na NORTH, terminal stoi na poludnie od nich, wiec
                // przodem (czyli w swoja "niepodlaczalna" strone) obracamy go
                // na SOUTH - wtedy strona polaczalna patrzy na NORTH, na rury.
                BlockState terminalState = VeloceRegistry.VELOCE_TOM_TERMINAL.get()
                        .defaultBlockState();
                if (terminalState.hasProperty(
                        net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
                    terminalState = terminalState.setValue(
                            net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                            Direction.SOUTH);
                }
                level.setBlock(p, terminalState, 3);
            } else if (i == length) {
                // Baryłka na koncu - prosty magazyn, ktory MA sie rozladowywac.
                level.setBlock(p, Blocks.BARREL.defaultBlockState(), 3);
            } else {
                placePipe(level, p);
                pipes++;
            }
        }

        // 3. Domkniecie polaczen: przechodzimy jeszcze raz i przeliczamy stan
        //    kazdej rury oraz sasiadow, zeby siec byla spojna w NBT.
        for (int i = 0; i <= length; i++) {
            BlockPos p = origin.relative(forward, i);
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof VelocePipeBlock pipe) {
                level.setBlock(p, pipe.updateConnections(level, p, st), 3);
            }
        }

        // 4. Przebudowa sieci od zera, zeby menedzer zobaczyl caly lancuch.
        var manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level);
        manager.clearPendingRebuilds();
        var net = manager.scanAndBuildNetwork(level, origin.relative(forward, 1), null);

        // 5. Zwolnienie chunkow - od tej chwili gra rzadzi sie swoimi zasadami.
        //
        // To jest istota testu: chunkow NIE trzymamy na sile. Zostana tylko te
        // z wezlami (terminal), a reszta ma wypasc.
        for (ChunkPos cp : touched) {
            level.setChunkForced(cp.x, cp.z, false);
        }

        // 6. Raport + komendy do tepania.
        int nodes = net == null ? 0 : net.getTerminals().size();
        int endpoints = net == null ? 0 : net.getEndpoints().size();
        final int pipeCount = pipes;
        final int chunkCount = touched.size();
        final String netLabel = net == null
                ? "§cBRAK"
                : net.getId().toString().substring(0, 8);

        source.sendSuccess(() -> Component.literal(
                "§6=== [CraftingVeloce] Testowa siec zbudowana ==="), false);
        source.sendSuccess(() -> Component.literal(
                "§7Rur: §f" + pipeCount + " §7| dlugosc: §f" + length
                        + " §7| chunki: §f" + chunkCount), false);
        source.sendSuccess(() -> Component.literal(
                "§7Sieci: §f" + netLabel
                        + " §7| wezly: §f" + nodes + " §7| magazyny: §f" + endpoints), false);
        if (net != null && endpoints == 0) {
            source.sendSuccess(() -> Component.literal(
                    "§eUwaga: baryłka nie zostala wykryta jako magazyn. "
                            + "Sprawdz, czy siec sie polaczyla (§f/cv debug§e na rurze)."), false);
        }
        if (source.getPlayer() != null) {
            rememberPositions(source.getPlayer(), origin, endPos);
        }

        // SZCZEGOLOWY ZAPIS DO KONSOLI - co postawilismy i w jakim stanie
        // sa chunki. Na czacie tylko potwierdzenie, bo kilkadziesiat linii
        // raportu jest nieczytelne w oknie czatu i znika po chwili.
        com.craftingveloce.debug.ChunkTrace.event("BUILD",
                "start=%s koniec=%s dlugosc=%d rur=%d chunkow=%d",
                origin.toShortString(), endPos.toShortString(), length, pipeCount, chunkCount);
        com.craftingveloce.debug.ChunkTrace.event("BUILD",
                "terminal@%s baryłka@%s siec=%s wezlow=%d magazynow=%d",
                origin.toShortString(), endPos.toShortString(), netLabel, nodes, endpoints);
        if (net != null) {
            com.craftingveloce.debug.ChunkTrace.snapshotChunks("PO BUDOWIE", level, net);
            com.craftingveloce.debug.ChunkTrace.snapshotStock("PO BUDOWIE", level, net);
        }
        com.craftingveloce.debug.ChunkTrace.event("BUILD",
                "chunki wymuszone na czas budowy ZWOLNIONE - teraz obowiazuja "
                        + "normalne zasady (terminal trzyma, baryłka ma wypasc)");

        printTeleportHints(source, origin, endPos);

        source.sendSuccess(() -> Component.literal(
                "§7Wpisz §f/cv chunk§7, zeby wlaczyc monitor rozladowan."), false);
        return 1;
    }

    /** Stawia rure z poprawnymi polaczeniami. */
    private static void placePipe(ServerLevel level, BlockPos pos) {
        BlockState state = VeloceRegistry.VELOCE_PIPE.get().defaultBlockState();
        level.setBlock(pos, state, 3);
        BlockState placed = level.getBlockState(pos);
        if (placed.getBlock() instanceof VelocePipeBlock pipe) {
            level.setBlock(pos, pipe.updateConnections(level, pos, placed), 3);
        }
    }

    // ------------------------------------------------------------------
    // Sprzatanie i teleport
    // ------------------------------------------------------------------

    /**
     * Usuwa zbudowana siec testowa.
     *
     * <p>Kasujemy wylacznie bloki, ktore same postawilismy i tylko miedzy
     * zapamietanym startem a koncem - zeby nie zmiotlo przypadkiem prawdziwej
     * bazy gracza stojacej obok.
     */
    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§cTa komenda wymaga gracza."));
            return 0;
        }
        BlockPos start = readStartTag(player);
        BlockPos end = readEndTag(player);
        if (start == null || end == null) {
            source.sendFailure(Component.literal(
                    "§cNie znam pozycji testu. Zbuduj siec: §f/cv testnet"));
            return 0;
        }

        int removed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Trasa jest zawsze w linii prostej, wiec idziemy po niej krok po kroku.
        int steps = (int) Math.sqrt(start.distSqr(end)) + 1;
        for (int i = 0; i <= steps; i++) {
            cursor.set(start.getX(), start.getY(), start.getZ() + (start.getZ() > end.getZ() ? -i : i));
            var st = level.getBlockState(cursor);
            if (st.getBlock() instanceof VelocePipeBlock
                    || st.is(VeloceRegistry.VELOCE_TOM_TERMINAL.get())
                    || st.is(Blocks.BARREL)) {
                level.removeBlock(cursor.immutable(), false);
                removed++;
            }
            // Posadzka, ktora polozylismy pod rura.
            BlockPos below = cursor.below();
            if (level.getBlockState(below).is(Blocks.STONE)) {
                level.removeBlock(below, false);
                removed++;
            }
        }
        int total = removed;
        source.sendSuccess(() -> Component.literal(
                "§6Usunieto §f" + total + " §6blokow testowej sieci."), false);
        return 1;
    }

    private static int teleport(CommandContext<CommandSourceStack> context, boolean start) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("§cTa komenda wymaga gracza."));
            return 0;
        }
        // Zapamietana pozycja jest zapisywana w samym graczu (persistent data),
        // wiec dziala tez po wyjsciu i ponownym wejsciu do swiata - a wlasnie
        // tak wyglada test ("zresetuj gierke").
        BlockPos target = start ? readStartTag(player) : readEndTag(player);
        if (target == null) {
            context.getSource().sendFailure(Component.literal(
                    "§cNie znam pozycji testu. Zbuduj siec: §f/cv testnet"));
            return 0;
        }
        player.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        context.getSource().sendSuccess(() -> Component.literal(
                "§7Teleport na " + (start ? "§astart" : "§ckoniec") + "§7."), false);
        return 1;
    }

    /** Klucz w danych gracza, gdzie trzymamy pozycje startu testu. */
    private static final String TAG_START = "VeloceTestStart";
    private static final String TAG_END = "VeloceTestEnd";

    /** Zapisuje pozycje testu w graczu (przezywa wyjscie do menu). */
    private static void rememberPositions(ServerPlayer player, BlockPos start, BlockPos end) {
        var data = player.getPersistentData();
        data.putLong(TAG_START, start.asLong());
        data.putLong(TAG_END, end.asLong());
    }

    /** Pozycja startu testu zapamietana w graczu (null gdy brak). */
    public static BlockPos readStartTag(ServerPlayer player) {
        var data = player.getPersistentData();
        return data.contains(TAG_START) ? BlockPos.of(data.getLong(TAG_START)) : null;
    }

    /** Pozycja konca testu zapamietana w graczu (null gdy brak). */
    public static BlockPos readEndTag(ServerPlayer player) {
        var data = player.getPersistentData();
        return data.contains(TAG_END) ? BlockPos.of(data.getLong(TAG_END)) : null;
    }

    private static int where(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("§cTa komenda wymaga gracza."));
            return 0;
        }
        BlockPos start = readStartTag(player);
        BlockPos end = readEndTag(player);
        if (start == null || end == null) {
            context.getSource().sendFailure(Component.literal(
                    "§cNie znam pozycji testu. Zbuduj siec: §f/cv testnet"));
            return 0;
        }
        printTeleportHints(context.getSource(), start, end);
        return 1;
    }

    /** Wypisuje dwie krotkie, klikalne komendy do tepania. */
    private static void printTeleportHints(CommandSourceStack source, BlockPos start, BlockPos end) {
        source.sendSuccess(() -> Component.literal("§6--- Tepanie (kliknij, aby wykonac) ---"), false);
        source.sendSuccess(() -> tpLine("START", start, "§a"), false);
        source.sendSuccess(() -> tpLine("KONIEC", end, "§c"), false);
    }

    /** Jedna linia z klikalna komenda /tp. */
    private static MutableComponent tpLine(String label, BlockPos pos, String color) {
        String coords = pos.getX() + " " + (pos.getY() + 1) + " " + pos.getZ();
        return Component.literal("  " + color + label + "§7: §f/tp " + coords)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/tp @s " + coords))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Kliknij, aby sie teleportowac"))));
    }
}
