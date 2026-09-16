package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * C→S: prosba o policzenie "ile da sie dorobic" dla podanych itemow.
 *
 * <p>Klient wysyla tylko itemy widoczne na ekranie (jeden ekran creative to
 * ok. 45 slotow), a nie wszystkie ~850 craftowalnych. Serwer odpowiada
 * {@link SyncCraftableCountsPKT}.
 *
 * <p>To rozwiazuje problem wydajnosciowy: wczesniej serwer liczyl craftowalnosc
 * dla wszystkich itemow co sekunde, rekurencyjnie, i sie zadlawial.
 */
public record RequestCraftableCountsPKT(BlockPos pos, List<Item> items)
        implements CustomPacketPayload {

    public static final Type<RequestCraftableCountsPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "request_craftable_counts"));

    public static final StreamCodec<FriendlyByteBuf, RequestCraftableCountsPKT> STREAM_CODEC =
            StreamCodec.of(RequestCraftableCountsPKT::encode, RequestCraftableCountsPKT::decode);

    private static void encode(FriendlyByteBuf buf, RequestCraftableCountsPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        // Nigdy nie wysylamy wiecej, niz druga strona przyjmie - inaczej
        // nasze wlasne zadanie zostanie odrzucone jako bledne.
        if (pkt.items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "RequestCraftableCountsPKT: too many items (" + pkt.items.size() + ")");
        }
        buf.writeInt(pkt.items.size());
        for (Item item : pkt.items) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(item));
        }
    }

    /**
     * Gorny limit liczby itemow w jednym zadaniu.
     *
     * <p><b>Bezpieczenstwo.</b> {@code n} przychodzi OD KLIENTA i przed tą
     * zmiana bylo uzywane bez zadnego ograniczenia: {@code new ArrayList<>(n)}
     * z ogromnym {@code n} to natychmiastowa proba alokacji (OutOfMemoryError),
     * a petla czytajaca potrafila ciagnac dalej, az do bledu bufora. Zlosliwy
     * albo po prostu popsuty klient mogl wiec polozyc serwer jednym pakietem.
     *
     * <p>Limit jest hojny - terminal widzi najwyzej kilkadziesiat pozycji na
     * strone, wiec setka z zapasem wystarcza.
     */
    public static final int MAX_ITEMS = 256;

    private static RequestCraftableCountsPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int n = buf.readInt();
        // Odrzucamy zadania spoza sensownego zakresu, zamiast probowac je
        // zaalokowac. Wartosc ujemna tez jest tu bledem, nie "zero itemow".
        if (n < 0 || n > MAX_ITEMS) {
            throw new io.netty.handler.codec.DecoderException(
                    "RequestCraftableCountsPKT: invalid item count " + n
                            + " (max " + MAX_ITEMS + ")");
        }
        List<Item> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            if (item != null) {
                items.add(item);
            }
        }
        return new RequestCraftableCountsPKT(pos, items);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestCraftableCountsPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // Nie liczymy liczb dla terminala na drugim koncu swiata.
            // Kazdy inny nasz pakiet po stronie serwera ma taki bezpiecznik -
            // ten go nie mial, a jako jedyny zleca serwerowi realna prace
            // (planowanie drzewa receptur dla widocznej strony).
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5,
                    pkt.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            // Rozgalezienie po INTERFEJSIE, nie po konkretnym bloku: dzieki
            // temu kontroler (i kazdy przyszly ekran z liczbami) dziala bez
            // zmiany tego pakietu.
            BlockEntity be = player.level().getBlockEntity(pkt.pos());
            if (!(be instanceof com.craftingveloce.block.entity.VeloceCraftCountSource source)) {
                return;
            }
            // Siec tej maszyny - z niej idzie cache (jeden na siec, wspolny dla
            // terminala i kontrolera).
            var serverLevel = (net.minecraft.server.level.ServerLevel) player.level();
            var network = com.craftingveloce.network.pipe.VelocePipeNetworkManager
                    .get(serverLevel)
                    .getNetworkForTerminal(serverLevel, pkt.pos());

            // 1) NATYCHMIAST cache: gracz otwiera GUI i od razu widzi cyferki,
            //    ktore siec juz policzyla. Bez tego liczenie widocznej strony
            //    zaczynalo sie od zera i cyferki "wchodzily" po kolei.
            if (network != null) {
                var memo = network.getCraftableMemo();
                if (!memo.isEmpty()) {
                    PacketDistributor.sendToPlayer(player,
                            new SyncCraftableCountsPKT(pkt.pos(), memo, false));
                }
            }

            // 2) Dopiero teraz dzisiejsza logika: liczy WIDOCZNA strone
            //    i dopisuje wynik do cache (cache sam sie doucza - bez osobnego
            //    budowania w tle i bez obciazania serwera).
            var result = source.computeCraftableCounts(pkt.items());
            if (network != null) {
                network.rememberCraftable(result.counts());
            }
            PacketDistributor.sendToPlayer(player,
                    new SyncCraftableCountsPKT(pkt.pos(), result.counts(), result.complete()));
        });
    }
}
