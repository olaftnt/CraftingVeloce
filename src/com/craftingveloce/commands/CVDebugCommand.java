package com.craftingveloce.commands;

import com.craftingveloce.block.entity.VelocePipeBlockEntity;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import com.craftingveloce.network.pipe.ConnectedEndpointInfo;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.block.VeloceTomTerminalBlock;
import com.craftingveloce.network.pipe.VelocePipeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Map;
import java.util.UUID;

public class CVDebugCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cv")
                .then(Commands.literal("debug")
                    .executes(CVDebugCommand::executeDebug))
                .then(Commands.literal("perf")
                    .executes(CVDebugCommand::executePerf))
                // JEDEN przelacznik do monitorowania chunkow.
                //
                // Wczesniej bylo tego trzy osobne komendy (chunkdebug, jego
                // podkomendy i chunks). Przy testowaniu to przeszkadzalo: trzeba
                // bylo pamietac, ktora co wlacza, a i tak nie bylo jasne, czy
                // mechanizm w ogole dziala.
                //
                // Teraz: jedno polecenie, dziala jak przelacznik, wlacza CALY
                // monitor. Bez argumentu przelacza, z "on"/"off" ustawia wprost.
                .then(Commands.literal("chunk")
                    .executes(ctx -> toggleChunkMonitor(ctx, null))
                    .then(Commands.literal("on")
                        .executes(ctx -> toggleChunkMonitor(ctx, true)))
                    .then(Commands.literal("off")
                        .executes(ctx -> toggleChunkMonitor(ctx, false)))
                    .then(Commands.literal("status")
                        .executes(CVDebugCommand::executeChunkStatus))
                    // Recznie: zwolnij sieroce force-loady. Minecraft zapisuje
                    // setChunkForced TRWALE, wiec chunki wymuszone przez starsza
                    // wersje kodu zostaly zaladowane na zawsze.
                    .then(Commands.literal("cleanup")
                        .executes(CVDebugCommand::cleanupOrphans)))
        );
    }

    /**
     * Jednym poleceniem wypisuje wszystko, co potrzebne do diagnozy lagow.
     *
     * <p>Po to, zeby jeden test w grze dawal komplet liczb zamiast zgadywania:
     * ile cache'ow zyje (wyciek?), ile chunkow realnie trzymamy, ile przebudow
     * czeka, jak dlugo trwal ostatni tick cache i czy kiedykolwiek go
     * przekroczylismy.
     */
    private static int executePerf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel sl = source.getLevel();
        var manager = VelocePipeNetworkManager.get(sl);

        source.sendSuccess(() -> Component.literal("§6=== [CraftingVeloce Perf] ==="), false);
        source.sendSuccess(() -> Component.literal(
                "§7Live crafting caches: §f" + com.craftingveloce.crafting.VeloceCraftingCache.liveCount()),
                false);
        source.sendSuccess(() -> Component.literal(
                "§7Forced chunks (loader, this level): §f"
                        + com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(sl)),
                false);
        source.sendSuccess(() -> Component.literal(
                "§7Pending network rebuilds: §f" + manager.pendingRebuildCount()
                        + " §7| networks: §f" + manager.getAllNetworks(sl).size()),
                false);
        source.sendSuccess(() -> Component.literal(
                "§7Ticking server: §fyes §7| gameTime: §f" + sl.getGameTime()), false);
        for (VelocePipeNetwork net : manager.getAllNetworks(sl)) {
            source.sendSuccess(() -> Component.literal(
                    "  §8net §7" + net.getId().toString().substring(0, 8)
                            + " §7pipes=§f" + net.getPipes().size()
                            + " §7endpoints=§f" + net.getEndpoints().size()), false);
        }
        source.sendSuccess(() -> Component.literal("§6======================================="), false);
        return 1;
    }

    /**
     * JEDEN przelacznik calego monitora chunkow.
     *
     * <p>Wlacza naraz wszystko, co potrzebne do testu:
     * <ul>
     *   <li>komunikat przy KAZDYM rozladowaniu chunka (z koordynatami),</li>
     *   <li>raport operacji na itemach w niezaladowanych chunkach,</li>
     *   <li>listy blokow sieci w chunku, ktory sie rozladowuje.</li>
     * </ul>
     *
     * <p>To NIE jest zwykly debug z configu - dziala niezaleznie, bo sluzy do
     * konkretnego testu: sprawdzenia, czy dany chunk w ogole probuje sie
     * rozladowac. Bez tego latwo testowac obszar, ktory w rzeczywistosci
     * caly czas siedzi w pamieci i nie dowiedziec sie niczego.
     *
     * <p>Bez argumentu przelacza stan (on <-> off).
     */
    private static int toggleChunkMonitor(CommandContext<CommandSourceStack> context, Boolean value) {
        boolean target = value == null
                ? !com.craftingveloce.debug.ChunkDebugNotifier.isEnabled()
                : value;
        com.craftingveloce.debug.ChunkDebugNotifier.setEnabled(target);
        // Operacje na itemach ida razem z monitorem - to jeden mechanizm,
        // nie dwa niezalezne przelaczniki do zapamietania.
        com.craftingveloce.debug.ChunkOpNotifier.setEnabled(target);

        String state = target ? "§aON" : "§cOFF";
        context.getSource().sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] monitor chunkow: " + state), false);
        if (target) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "§7Kazde rozladowanie chunka -> komunikat z koordynatami."), false);
            context.getSource().sendSuccess(() -> Component.literal(
                    "§7Operacje na itemach w niezaladowanych chunkach -> raport."), false);
            context.getSource().sendSuccess(() -> Component.literal(
                    "§7Uzyj §f/cv chunk status§7, aby zobaczyc trzymane chunki."), false);
        } else {
            context.getSource().sendSuccess(() -> Component.literal(
                    "§7Monitor wylaczony - czat zostaje czysty."), false);
        }
        return 1;
    }

    /**
     * Recznie zwalnia sieroce force-loady.
     *
     * <p>Minecraft zapisuje {@code setChunkForced} TRWALE w danych swiata,
     * a nasza ksiegowosc zyje tylko w pamieci - wiec chunki wymuszone przez
     * starsza wersje kodu zostaly zaladowane na zawsze, mimo ze nasz raport
     * pokazywal zero. Ta komenda je sprzata.
     */
    private static int cleanupOrphans(CommandContext<CommandSourceStack> context) {
        ServerLevel sl = context.getSource().getLevel();
        int before = com.craftingveloce.network.pipe.VeloceChunkLoader.gameForcedCount(sl);
        int orphans = VelocePipeNetworkManager.get(sl).releaseOrphanForceLoads(sl);
        int after = com.craftingveloce.network.pipe.VeloceChunkLoader.gameForcedCount(sl);
        context.getSource().sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] sprzatanie: gra trzymala §f" + before
                        + " §7wymuszonych, zwolniono sierot: §f" + orphans
                        + "§7, zostalo: §f" + after), false);
        return 1;
    }

    /** Krotki status: czy monitor dziala i ile chunkow trzymamy. */
    private static int executeChunkStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean on = com.craftingveloce.debug.ChunkDebugNotifier.isEnabled();
        source.sendSuccess(() -> Component.literal("§6=== [CraftingVeloce] Monitor chunkow ==="), false);
        source.sendSuccess(() -> Component.literal(
                "§7Stan: " + (on ? "§aON" : "§cOFF")
                        + " §7| operacje: " + (com.craftingveloce.debug.ChunkOpNotifier.isEnabled()
                        ? "§aON" : "§cOFF")), false);
        // Kolejka zadan chunkowych - to ona robi operacje na odleglych
        // magazynach BEZ blokowania ticku. Jesli rosnie, znaczy ze operacji
        // jest wiecej niz jestesmy w stanie obsluzyc.
        var queue = com.craftingveloce.network.pipe.VeloceChunkTaskQueue.pending();
        source.sendSuccess(() -> Component.literal(
                "§7Kolejka zadan chunkowych: §f" + queue
                        + " §7| wykonane: §f" + com.craftingveloce.network.pipe.VeloceChunkTaskQueue.completed()
                        + " §7| odrzucone: §f" + com.craftingveloce.network.pipe.VeloceChunkTaskQueue.dropped()), false);
        if (queue > 0) {
            for (String line : com.craftingveloce.network.pipe.VeloceChunkTaskQueue.describePending(5)) {
                source.sendSuccess(() -> Component.literal("  §8- §7" + line), false);
            }
        }
        // Pelna lista trzymanych chunkow - od razu, bez drugiej komendy.
        executeListChunks(context);
        return 1;
    }

    /**
     * Wypisuje liste chunkow trzymanych w pamieci.
     *
     * <p>Dla kazdego chunku: wspolrzedne, siec, blok ktory go trzyma i powod.
     * Wspolrzedne sa KLIKALNE (teleport), bo inaczej taka lista jest bezuzyteczna
     * - nie da sie sprawdzic, co siedzi w srodku.
     */
    private static int executeListChunks(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel sl = source.getLevel();
        var manager = VelocePipeNetworkManager.get(sl);
        var world = manager.getWorld();

        source.sendSuccess(() -> Component.literal(
                "§6=== [CraftingVeloce] Struktura rur — wymiar §f"
                        + sl.dimension().location() + " §6==="), false);
        source.sendSuccess(() -> Component.literal(
                "§7Rur: §f" + world.pipeCount()
                        + " §7| komponentow: §f" + world.componentCount()
                        + " §7| zcache'owanych opisow: §f" + world.cachedComponentCount()
                        + " §7| wymuszonych chunkow: §f"
                        + com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(sl)), false);
        // ROZJAZD: gra moze trzymac wiecej, niz my wiemy - Minecraft zapisuje
        // setChunkForced TRWALE, a nasza ksiegowosc tylko w pamieci.
        int gameForced = com.craftingveloce.network.pipe.VeloceChunkLoader.gameForcedCount(sl);
        int oursForced = com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(sl);
        if (gameForced != oursForced) {
            source.sendSuccess(() -> Component.literal(
                    "§c§lUWAGA: §cgra trzyma §f" + gameForced
                            + " §cwymuszonych chunkow, a my tylko §f" + oursForced
                            + "§c. Napraw: §f/cv chunk cleanup"), false);
        }

        if (world.pipeCount() == 0) {
            source.sendSuccess(() -> Component.literal(
                    "§7Struktura jest pusta - nie ma zadnych rur."), false);
            return 1;
        }

        // --- WSZYSTKIE ELEMENTY: wezly i magazyny, ze stanem chunku ---
        //
        // To jest wlasnie narzedzie do testu: kazdy element ma jasna informacje
        // LOADED/UNLOADED, wiec widac, ktore magazyny sa poza symulacja i czy
        // ich zawartosc pochodzi z cache.
        int loadedCount = 0;
        int unloadedCount = 0;

        source.sendSuccess(() -> Component.literal("§b--- WEZLY (trzymaja chunk na stale) ---"), false);
        java.util.List<BlockPos> allNodes = new java.util.ArrayList<>();
        for (var net : manager.getAllNetworks(sl)) {
            allNodes.addAll(net.getTerminals());
        }
        if (allNodes.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  §7(brak wezlow)"), false);
        }
        for (BlockPos p : sorted(allNodes)) {
            boolean loaded = sl.isLoaded(p);
            if (loaded) {
                loadedCount++;
            } else {
                unloadedCount++;
            }
            source.sendSuccess(() -> Component.literal(
                    "  §a" + describeNode(sl, p) + " §7" + shortPos(p)
                            + " §8" + chunkTag(p) + " " + loadedTag(sl, p)), false);
            source.sendSuccess(() -> blockLine(p), false);
        }

        source.sendSuccess(() -> Component.literal("§b--- MAGAZYNY (maja sie rozladowywac) ---"), false);
        java.util.Map<BlockPos, ConnectedEndpointInfo> allStorages = new java.util.LinkedHashMap<>();
        for (var net : manager.getAllNetworks(sl)) {
            allStorages.putAll(net.getEndpoints());
        }
        if (allStorages.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  §7(brak magazynow)"), false);
        }
        for (var e : sortedEntries(allStorages)) {
            BlockPos p = e.getKey();
            ConnectedEndpointInfo info = e.getValue();
            boolean loaded = sl.isLoaded(p);
            if (loaded) {
                loadedCount++;
            } else {
                unloadedCount++;
            }
            source.sendSuccess(() -> Component.literal(
                    "  §e" + info.getType() + " §7" + shortPos(p)
                            + " §8" + chunkTag(p) + " " + loadedTag(sl, p)
                            + (loaded ? " §7(dane ze swiata)" : " §6(dane z CACHE)")
                            + " §7| typow: §f" + info.getCachedCounts().size()), false);
            // Zawartosc - to wlasnie testujemy przy niezaladowanych.
            for (var ic : info.getCachedCounts().entrySet()) {
                if (ic.getValue() > 0) {
                    source.sendSuccess(() -> Component.literal(
                            "      §7" + ic.getKey().getDescription().getString()
                                    + " §fx" + ic.getValue()), false);
                }
            }
            source.sendSuccess(() -> blockLine(p), false);
        }

        int lc = loadedCount;
        int uc = unloadedCount;
        source.sendSuccess(() -> Component.literal(
                "§6Podsumowanie: §a" + lc + " LOADED §7| §c" + uc + " UNLOADED"), false);

        // --- CHUNKI WYMUSZONE, z powodem ---
        var held = com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(sl);
        source.sendSuccess(() -> Component.literal(
                "§b--- Wymuszone chunki: §f" + held.size() + " ---"), false);
        for (var hc : held) {
            boolean loaded = sl.isLoaded(new ChunkPos(hc.x(), hc.z()).getWorldPosition());
            source.sendSuccess(() -> Component.literal(
                    "  §f[" + hc.x() + ", " + hc.z() + "] "
                            + (loaded ? "§a[SYMULOWANY]" : "§c[POZA SYMULACJA]")
                            + " §7uzyc: §f" + hc.hits()), false);
            for (var t : hc.tickets()) {
                source.sendSuccess(() -> Component.literal(
                        "      §8powod: §f" + t.reason()
                                + " §8| wlasciciel: §7" + t.owner()), false);
                if (t.ownerPos() != null) {
                    source.sendSuccess(() -> blockLine(t.ownerPos()), false);
                }
            }
        }

        // --- KOLEJKA ZADAN ---
        var queue = com.craftingveloce.network.pipe.VeloceChunkTaskQueue.pending();
        source.sendSuccess(() -> Component.literal(
                "§b--- Kolejka zadan chunkowych: §f" + queue
                        + " §7| wykonane: §f"
                        + com.craftingveloce.network.pipe.VeloceChunkTaskQueue.completed()
                        + " §7| odrzucone: §f"
                        + com.craftingveloce.network.pipe.VeloceChunkTaskQueue.dropped()), false);
        for (String line : com.craftingveloce.network.pipe.VeloceChunkTaskQueue.describePending(5)) {
            source.sendSuccess(() -> Component.literal("  §8- §7" + line), false);
        }

        source.sendSuccess(() -> Component.literal(
                "§7Kliknij wspolrzedne bloku, aby sie teleportowac."), false);
        return 1;
    }




    /** Linia z klikalnymi wspolrzednymi bloku. */
    private static Component blockLine(BlockPos pos) {
        String plain = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Component.literal("§8      blok: §f" + plain)
                .withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                "/tp @s " + plain))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Kliknij, aby sie teleportowac"))));
    }

    /**
     * Pelny raport sieci: WSZYSTKIE podpięte bloki, z typem i stanem.
     *
     * <p><b>Po co tak szczegolowo.</b> Bez tego nie da sie odpowiedziec na
     * podstawowe pytanie: co jest czescia tej sieci i co przez to trzyma jej
     * chunki w pamieci. Wczesniej raport pokazywal tylko liczby ("Pipes: 218,
     * Terminals: 2"), z ktorych nie wynikało, gdzie te bloki sa ani czym sa.
     *
     * <p>Rozrozniamy cztery rodzaje wpisow, bo kazdy znaczy cos innego:
     * <ul>
     *   <li><b>WEZEL</b> - terminal, crafter, extractor. Ma block entity,
     *       ktore PRACUJE, wiec jego chunk MUSI byc trzymany.</li>
     *   <li><b>MAGAZYN</b> - skrzynia, beczka, RS. To tylko pojemnik; jego
     *       chunk NIE jest trzymany na stale, tylko doladowywany na czas
     *       operacji i puszczany.</li>
     *   <li><b>RURA</b> - lacznik bez logiki. Nie trzyma niczego.</li>
     * </ul>
     */
    private static void reportNetwork(ServerPlayer player, ServerLevel sl,
                                      VelocePipeNetwork net, BlockPos pipePos) {
        var manager = VelocePipeNetworkManager.get(sl);
        var world = manager.getWorld();

        player.sendSystemMessage(Component.literal("§6=== [CraftingVeloce] Trace polaczen ==="));
        player.sendSystemMessage(Component.literal(
                "§7Rura startowa: §f" + shortPos(pipePos) + " §8" + chunkTag(pipePos)
                        + " " + loadedTag(sl, pipePos)));

        // --- 1. SAME POLACZENIA TEJ RURY (bezposredni sasiedzi) ---
        var neighbours = world.neighbours(pipePos);
        player.sendSystemMessage(Component.literal(
                "§b--- Bezposrednie polaczenia: " + neighbours.size() + " z 6 mozliwych ---"));
        if (neighbours.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "  §c(brak - ta rura nie laczy sie z niczym)"));
        }
        // Pokazujemy WSZYSTKIE 6 kierunkow, takze te bez polaczenia - to
        // najszybszy sposob, zeby zobaczyc, gdzie siec sie urwala.
        for (Direction d : Direction.values()) {
            BlockPos np = pipePos.relative(d);
            // UWAGA: neighbours() zawiera TYLKO rury. Dla wezlow i magazynow
            // trzeba pytac ich wlasnym canConnectFrom - inaczej raport
            // pokazywalby "[-]" przy poprawnie podlaczonym terminalu (i mylil
            // przy diagnozie, bo wygladalo to jak rozcieta siec).
            boolean linked = neighbours.contains(np);
            boolean isNode = false;
            if (!linked && sl.isLoaded(np)) {
                var st = sl.getBlockState(np);
                var b = st.getBlock();
                if (b instanceof VeloceTomTerminalBlock
                        || b instanceof com.craftingveloce.block.VeloceExtractorBlock
                        || b instanceof com.craftingveloce.block.VeloceCraftingTableBlock
                        || b instanceof com.craftingveloce.block.VeloceControllerBlock) {
                    isNode = true;
                    linked = VelocePipeNetworkManager.nodeConnectsToPipe(
                            sl, np, d.getOpposite());
                } else if (VelocePipeBlock.canConnectToInventory(sl, np, d.getOpposite())) {
                    linked = true;   // magazyn laczy sie zawsze
                }
            }
            String what = describeAt(sl, world, np);
            String note = linked
                    ? (isNode ? " §8(wezel podlaczony)" : "")
                    : " §8(nie podlaczone)";
            player.sendSystemMessage(Component.literal(
                    "  " + (linked ? "§a[+] " : "§8[-] ") + "§7" + d.name().toLowerCase()
                            + " -> " + what + note));
        }

        // --- 2. CALY KOMPONENT (przejscie po polaczeniach) ---
        var members = world.componentMembers(pipePos);
        player.sendSystemMessage(Component.literal(
                "§b--- Cala polaczona grupa: §f" + members.size() + " §brur ---"));
        player.sendSystemMessage(Component.literal(
                "  §7Reprezentant: §f" + shortPos(world.componentOf(pipePos))));

        // --- 3. WEZLY: maszyny i terminale ---
        var nodes = net.getTerminals();
        player.sendSystemMessage(Component.literal("§b--- Wezly (maszyny): " + nodes.size() + " ---"));
        if (nodes.isEmpty()) {
            player.sendSystemMessage(Component.literal("  §7(brak)"));
        }
        for (BlockPos p : sorted(nodes)) {
            player.sendSystemMessage(Component.literal(
                    "  §a" + describeNode(sl, p) + " §7" + shortPos(p)
                            + " §8" + chunkTag(p) + " " + loadedTag(sl, p)));
            player.sendSystemMessage(coordsLine(p));
        }

        // --- 4. MAGAZYNY: skrzynie, beczki, RS ---
        var endpooints = net.getEndpoints();
        player.sendSystemMessage(Component.literal("§b--- Magazyny: " + endpooints.size() + " ---"));
        if (endpooints.isEmpty()) {
            player.sendSystemMessage(Component.literal("  §7(brak)"));
        }
        for (var e : sortedEntries(endpooints)) {
            BlockPos ep = e.getKey();
            ConnectedEndpointInfo info = e.getValue();
            boolean loaded = sl.isLoaded(ep);
            player.sendSystemMessage(Component.literal(
                    "  §e" + info.getType() + " §7" + shortPos(ep)
                            + " §8" + chunkTag(ep) + " " + loadedTag(sl, ep)
                            + " §7| typy itemow: §f" + info.getCachedCounts().size()
                            + (loaded ? "" : " §8<- z cache, nie ze swiata")));
            // Zawartosc - dla niezaladowanych to jest wlasnie ten cache,
            // ktory mamy przetestowac.
            for (var ic : info.getCachedCounts().entrySet()) {
                if (ic.getValue() > 0) {
                    player.sendSystemMessage(Component.literal(
                            "      §7" + ic.getKey().getDescription().getString()
                                    + " §fx" + ic.getValue()));
                }
            }
            player.sendSystemMessage(coordsLine(ep));
        }

        // --- 5. CHUNKI TRZYMANE PRZEZ TA SIEĆ ---
        var held = com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(sl);
        String prefix = "net:" + net.getId().toString().substring(0, 8);
        int mine = 0;
        player.sendSystemMessage(Component.literal("§b--- Chunki trzymane przez te siec ---"));
        for (var hc : held) {
            boolean ours = hc.tickets().stream().anyMatch(t -> t.owner().equals(prefix));
            if (!ours) {
                continue;
            }
            mine++;
            player.sendSystemMessage(Component.literal(
                    "  §f[" + hc.x() + ", " + hc.z() + "] §7powod: NETWORK"));
        }
        player.sendSystemMessage(Component.literal("  §7razem: §f" + mine + " §7chunk(ow)"));

        player.sendSystemMessage(Component.literal("§6==================================="));
    }

    /** Opis bloku na danej pozycji - do listy polaczen. */
    private static String describeAt(ServerLevel sl, VelocePipeWorld world, BlockPos p) {
        if (!sl.isLoaded(p)) {
            return world.hasPipe(p)
                    ? "§7rura §8" + chunkTag(p) + " §c[UNLOADED]"
                    : "§8(niezladowany chunk)";
        }
        var st = sl.getBlockState(p);
        var b = st.getBlock();
        if (b instanceof com.craftingveloce.block.VelocePipeBlock) {
            return "§7rura " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceTomTerminalBlock) {
            return "§aTERMINAL " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceExtractorBlock) {
            return "§aEXTRACTOR " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceCraftingTableBlock) {
            return "§aCRAFTER " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceControllerBlock) {
            return "§aKONTROLER " + loadedTag(sl, p);
        }
        if (VelocePipeBlock.canConnectToInventory(sl, p, Direction.UP)) {
            return "§eMAGAZYN (" + b.getName().getString() + ") " + loadedTag(sl, p);
        }
        return "§8" + b.getName().getString();
    }


    /** Typ bloku-wezla po nazwie klasy (terminal / crafter / extractor). */
    private static String describeNode(ServerLevel sl, BlockPos p) {
        var be = sl.getBlockEntity(p);
        if (be == null) {
            return "PUSTE(?)";
        }
        String n = be.getClass().getSimpleName();
        if (n.contains("Terminal")) {
            return "TERMINAL";
        }
        if (n.contains("CraftingTable")) {
            return "CRAFTER";
        }
        if (n.contains("Extractor")) {
            return "EXTRACTOR";
        }
        return n.toUpperCase(java.util.Locale.ROOT);
    }

    private static String shortPos(BlockPos p) {
        return "[" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "]";
    }

    private static String chunkTag(BlockPos p) {
        return "chunk[" + (p.getX() >> 4) + ", " + (p.getZ() >> 4) + "]";
    }

    private static String loadedTag(ServerLevel sl, BlockPos p) {
        return sl.isLoaded(p) ? "§a[LOADED]" : "§c[UNLOADED]";
    }

    /** Klikalne wspolrzedne bloku - klik teleportuje. */
    private static Component coordsLine(BlockPos pos) {
        String plain = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Component.literal("§8      /tp " + plain)
                .withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                "/tp @s " + plain))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Kliknij, aby sie teleportowac"))));
    }

    /** Posortowane pozycje - zeby raport byl powtarzalny. */
    private static java.util.List<BlockPos> sorted(java.util.Collection<BlockPos> in) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>(in);
        out.sort(java.util.Comparator.comparingInt((BlockPos p) -> p.getX())
                .thenComparingInt((BlockPos p) -> p.getZ())
                .thenComparingInt((BlockPos p) -> p.getY()));
        return out;
    }

    private static java.util.List<Map.Entry<BlockPos, ConnectedEndpointInfo>> sortedEntries(
            Map<BlockPos, ConnectedEndpointInfo> in) {
        java.util.List<Map.Entry<BlockPos, ConnectedEndpointInfo>> out =
                new java.util.ArrayList<>(in.entrySet());
        out.sort(java.util.Comparator.comparingInt(
                (Map.Entry<BlockPos, ConnectedEndpointInfo> e) -> e.getKey().getX())
                .thenComparingInt(e -> e.getKey().getZ())
                .thenComparingInt(e -> e.getKey().getY()));
        return out;
    }

    private static int executeDebug(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }

        HitResult hit = player.pick(20.0D, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            player.sendSystemMessage(Component.literal("§c[CraftingVeloce] You are not looking at any block!"));
            return 0;
        }

        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockPos pos = blockHit.getBlockPos();
        BlockEntity be = player.level().getBlockEntity(pos);
        ServerLevel sl = player.serverLevel();

        if (be instanceof VeloceTomTerminalBlockEntity terminalBE) {
            terminalBE.printDebugInfo(player);
            return 1;
        } else if (be instanceof com.craftingveloce.block.entity.VeloceExtractorBlockEntity extractorBE) {
            player.sendSystemMessage(Component.literal("§6=== [CraftingVeloce Extractor Debug] ==="));
            player.sendSystemMessage(Component.literal("§7Pos: §f[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]"));
            VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
            VelocePipeNetwork net = manager.getNetworkForTerminal(sl, pos);
            player.sendSystemMessage(Component.literal("§7Connected Network: §f" + (net != null ? net.getId() : "§cNONE")));
            player.sendSystemMessage(Component.literal("§b--- Configured Filters (3x3) ---"));
            for (int i = 0; i < 9; i++) {
                ItemStack filter = extractorBE.getFilter(i);
                ItemStack output = extractorBE.getOutputInventory().getItem(i);
                player.sendSystemMessage(Component.literal("  §7Slot " + (i + 1) + ": Filter=§e" + (!filter.isEmpty() ? filter.getHoverName().getString() : "EMPTY") + " §7| Stored=§a" + (!output.isEmpty() ? output.getCount() + "x " + output.getHoverName().getString() : "EMPTY")));
            }
            player.sendSystemMessage(Component.literal("§6======================================="));
            return 1;
        } else if (be instanceof VelocePipeBlockEntity) {
            VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
            VelocePipeNetwork net = manager.getNetworkForPipe(sl, pos);
            player.sendSystemMessage(Component.literal("§6=== [CraftingVeloce Pipe Debug] ==="));
            player.sendSystemMessage(Component.literal("§7Pipe Pos: §f[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]"));
            if (net == null) {
                player.sendSystemMessage(Component.literal("§cPipe is not currently assigned to any active network."));
                player.sendSystemMessage(Component.literal("§6==================================="));
                return 1;
            }
            reportNetwork(player, sl, net, pos);
            player.sendSystemMessage(Component.literal("§6==================================="));
            return 1;
        } else {
            player.sendSystemMessage(Component.literal("§c[CraftingVeloce] Looked-at block is not a Veloce Terminal or Pipe! (Found: " + (be != null ? be.getClass().getSimpleName() : player.level().getBlockState(pos).getBlock().getName().getString()) + ")"));
            return 0;
        }
    }
}
