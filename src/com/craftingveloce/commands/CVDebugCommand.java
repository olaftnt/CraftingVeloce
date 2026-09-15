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
import net.minecraft.core.BlockPos;
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
                        .executes(CVDebugCommand::executeChunkStatus)))
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
                        + " §7| networks: §f" + manager.getAllNetworks().size()),
                false);
        source.sendSuccess(() -> Component.literal(
                "§7Ticking server: §fyes §7| gameTime: §f" + sl.getGameTime()), false);
        for (VelocePipeNetwork net : manager.getAllNetworks()) {
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

        var held = com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(sl);
        source.sendSuccess(() -> Component.literal(
                "§6=== [CraftingVeloce] Trzymane chunki: §f" + held.size()
                        + " §6w §f" + sl.dimension().location() + " §6==="), false);

        if (held.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "§7Brak. Zaden chunk nie jest aktualnie wymuszony."), false);
            return 1;
        }

        var manager = VelocePipeNetworkManager.get(sl);
        for (var hc : held) {
            boolean loaded = sl.isLoaded(new ChunkPos(hc.x(), hc.z()).getWorldPosition());
            source.sendSuccess(() -> Component.literal(
                    "§8- §f[" + hc.x() + ", " + hc.z() + "] "
                            + (loaded ? "§a[SYMULOWANY]" : "§c[POZA SYMULACJA]")
                            + " §7uzyc: §f" + hc.hits()), false);

            if (hc.tickets().isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        "§8    powod: §7(nieznany - wpis bez biletu)"), false);
                continue;
            }
            for (var t : hc.tickets()) {
                String netId = networkIdAt(manager, t.ownerPos());
                source.sendSuccess(() -> Component.literal(
                        "§8    siec: §e" + netId
                                + " §8| powod: §f" + t.reason()
                                + " §8| wlasciciel: §7" + t.owner()), false);
                if (t.ownerPos() != null) {
                    source.sendSuccess(() -> blockLine(t.ownerPos()), false);
                }
            }
        }
        source.sendSuccess(() -> Component.literal(
                "§7Kliknij wspolrzedne bloku, aby sie teleportowac."), false);
        return 1;
    }

    /**
     * Wykrywa chunki, ktore wygladaja na trzymane bez powodu.
     *
     * <p>To test na wyciek: chunk wymuszony, ale zaden blok zadnej sieci w nim
     * nie lezy - czyli zostal po sieci, ktora juz nie istnieje.
     */
    private static int executeCheckStrict(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel sl = source.getLevel();
        var manager = VelocePipeNetworkManager.get(sl);

        var held = com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(sl);
        int orphaned = 0;
        for (var hc : held) {
            // Czy w tym chunku jest cokolwiek, co usprawiedliwia trzymanie?
            boolean justified = false;
            for (VelocePipeNetwork net : manager.getAllNetworks()) {
                for (BlockPos p : net.getPipes()) {
                    if ((p.getX() >> 4) == hc.x() && (p.getZ() >> 4) == hc.z()) {
                        justified = true;
                        break;
                    }
                }
                if (justified) {
                    break;
                }
                for (BlockPos p : net.getTerminals()) {
                    if ((p.getX() >> 4) == hc.x() && (p.getZ() >> 4) == hc.z()) {
                        justified = true;
                        break;
                    }
                }
                if (justified) {
                    break;
                }
            }
            if (!justified) {
                orphaned++;
                int fx = hc.x();
                int fz = hc.z();
                source.sendSuccess(() -> Component.literal(
                        "§cSierota: §f[" + fx + ", " + fz + "] §7- zaden blok sieci tu nie lezy"), false);
            }
        }
        int total = held.size();
        int found = orphaned;
        source.sendSuccess(() -> Component.literal(
                "§6Wynik: §f" + found + " §7sierot na §f" + total + " §7trzymanych chunkow."), false);
        return 1;
    }

    /** ID sieci wlasciciela danej pozycji (skrocone) albo "-". */
    private static String networkIdAt(VelocePipeNetworkManager manager, BlockPos pos) {
        if (pos == null) {
            return "-";
        }
        VelocePipeNetwork byPipe = manager.getNetworkForPipe(pos);
        VelocePipeNetwork net = byPipe != null ? byPipe : manager.getNetworkForTerminal(null, pos);
        return net == null ? "-" : net.getId().toString().substring(0, 8);
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
        UUID id = net.getId();
        player.sendSystemMessage(Component.literal("§aNetwork ID: §e" + id));
        player.sendSystemMessage(Component.literal(
                "§7Pipes: §f" + net.getPipes().size()
                        + " §7| Nodes: §f" + net.getTerminals().size()
                        + " §7| Storages: §f" + net.getEndpoints().size()));

        // --- WEZLY: to one trzymaja chunki na stale ---
        player.sendSystemMessage(Component.literal("§b--- Nodes (trzymaja chunk na stale) ---"));
        if (net.getTerminals().isEmpty()) {
            player.sendSystemMessage(Component.literal("  §7(brak - siec nie ma czym pracowac)"));
        }
        for (BlockPos p : sorted(net.getTerminals())) {
            player.sendSystemMessage(Component.literal(
                    "  §a" + describeNode(sl, p) + " §7at " + shortPos(p)
                            + " §8" + chunkTag(p) + " " + loadedTag(sl, p)));
            player.sendSystemMessage(coordsLine(p));
        }

        // --- MAGAZYNY: doladowywane tylko na czas operacji ---
        player.sendSystemMessage(Component.literal("§b--- Storages (doladowywane tylko na czas operacji) ---"));
        if (net.getEndpoints().isEmpty()) {
            player.sendSystemMessage(Component.literal("  §7(brak)"));
        }
        for (Map.Entry<BlockPos, ConnectedEndpointInfo> e : sortedEntries(net.getEndpoints())) {
            BlockPos epPos = e.getKey();
            ConnectedEndpointInfo ep = e.getValue();
            player.sendSystemMessage(Component.literal(
                    "  §e" + ep.getType() + " §7at " + shortPos(epPos)
                            + " §8" + chunkTag(epPos) + " " + loadedTag(sl, epPos)
                            + " §7(item types: §f" + ep.getCachedCounts().size() + "§7)"));
            player.sendSystemMessage(coordsLine(epPos));
        }

        // --- CHUNKI: ktore REALNIE sa wymuszone i dlaczego ---
        player.sendSystemMessage(Component.literal("§b--- Chunks faktycznie wymuszone przez te siec ---"));
        var held = com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(sl);
        String prefix = "net:" + id.toString().substring(0, 8);
        int mine = 0;
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

        // --- POLACZENIA: tablica polaczen miedzy sieciami ---
        var graph = VelocePipeNetworkManager.get(sl).getGraph();
        var links = graph.describeLinks();
        player.sendSystemMessage(Component.literal(
                "§b--- Polaczone sieci (tablica polaczen): " + links.size() + " ---"));
        if (links.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "  §7(brak - ta siec stoi samodzielnie)"));
        }
        for (var e : links.entrySet()) {
            player.sendSystemMessage(Component.literal(
                    "  §e" + e.getKey() + " §7stykow: §f" + e.getValue()));
        }
        player.sendSystemMessage(Component.literal(
                "  §7W grupie tej sieci: §f"
                        + graph.findGroup(id).size() + " §7sieci"));

        // --- ZASOBY: co siec widzi ---
        Map<Item, Long> netCounts = net.getAllItemCounts(sl);
        player.sendSystemMessage(Component.literal("§6Resources (" + netCounts.size() + " types):"));
        for (Map.Entry<Item, Long> itemEntry : netCounts.entrySet()) {
            player.sendSystemMessage(Component.literal(
                    "  §e" + itemEntry.getKey().getDescription().getString()
                            + " §7x§a" + itemEntry.getValue()));
        }
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
            VelocePipeNetwork net = manager.getNetworkForPipe(pos);
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
