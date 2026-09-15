package com.craftingveloce.crafting;

import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Spojny widok na wszystkie auto-craftery w sieci.
 *
 * <p>Zbiera informacje z blokow Veloce Crafting Table podlaczonych do sieci:
 * ktore itemy maja wlaczone auto-craftowanie oraz ktora receptura ma priorytet
 * (dla itemow z wieloma recepturami gracz moze wybrac recepture shift+scrollem).
 */
public final class VeloceCraftingRegistry {

    private VeloceCraftingRegistry() {
    }

    /**
     * Znajduje crafter w sieci, ktory ma wlaczone auto-craftowanie dla danego itemu.
     *
     * @return pierwszy taki block entity albo {@code null}
     */
    @Nullable
    public static VeloceCraftingTableBlockEntity findEnabledCrafter(ServerLevel level,
                                                                    VelocePipeNetwork network,
                                                                    Item item) {
        for (BlockPos pos : network.getTerminals()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VeloceCraftingTableBlockEntity crafter && crafter.isEnabled(item)) {
                return crafter;
            }
        }
        return null;
    }

    /** Czy w sieci istnieje jakikolwiek crafter z wlaczona receptura dla itemu. */
    public static boolean isCraftingEnabled(ServerLevel level, VelocePipeNetwork network, Item item) {
        return findEnabledCrafter(level, network, item) != null;
    }

    /**
     * Buduje mape preferowanych receptur na podstawie ustawien wszystkich
     * crafterow w sieci. Przy konflikcie wygrywa crafter blizej poczatku
     * zbioru terminali (kolejnosc stabilna).
     */
    public static Map<Item, ResourceLocation> getPreferredRecipes(ServerLevel level,
                                                                  VelocePipeNetwork network) {
        Map<Item, ResourceLocation> out = new HashMap<>();
        for (BlockPos pos : network.getTerminals()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VeloceCraftingTableBlockEntity crafter) {
                for (Map.Entry<Item, ResourceLocation> e : crafter.getPreferredRecipes().entrySet()) {
                    out.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    /** Zbior itemow, dla ktorych auto-crafting jest wlaczony w calej sieci. */
    public static java.util.Set<Item> getAllEnabledItems(ServerLevel level, VelocePipeNetwork network) {
        java.util.Set<Item> out = new java.util.HashSet<>();
        for (BlockPos pos : network.getTerminals()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VeloceCraftingTableBlockEntity crafter) {
                out.addAll(crafter.getEnabledItems());
            }
        }
        return out;
    }
}
