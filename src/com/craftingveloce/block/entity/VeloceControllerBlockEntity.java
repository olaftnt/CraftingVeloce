package com.craftingveloce.block.entity;

import com.craftingveloce.crafting.VeloceCraftingRegistry;
import com.craftingveloce.crafting.VeloceHeatSources;
import com.craftingveloce.crafting.VeloceRecipeRegistry;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.OpenControllerScreenPKT;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Block entity Veloce Controller.
 *
 * <p>Zbiera i wysyla do klienta pelny obraz sieci potrzebny do GUI:
 * <ul>
 *   <li>{@code stock} - ile sztuk kazdego itemu jest fizycznie w sieci</li>
 *   <li>{@code craftable} - ktore itemy maja recepture wykonywalna bez energii</li>
 *   <li>{@code craftingEnabled} - dla ktorych auto-crafting jest wlaczony
 *       (czyli crafter potrafi je zrobic, nawet bez itemow na stocku)</li>
 *   <li>{@code hotbar} - co gracz ma w hotbarze (do kolorowania ikon)</li>
 * </ul>
 */
public class VeloceControllerBlockEntity extends BlockEntity {

    public VeloceControllerBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_CONTROLLER_BE.get(), pos, state);
    }

    /** Zbiera aktualny stan sieci i wysyla GUI graczowi. */
    public void syncToPlayer(ServerPlayer player) {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);

        // BRAK SIECI NIE MOZE BYC PRZEMILCZANY.
        //
        // BUG, ktory to ukrywalo: kontroler nie byl rozpoznawany jako wezel
        // (patrz VeloceNodeBlocks), wiec `net` bylo tu ZAWSZE null. Kontroler
        // dostawal pusty stock i pusty zbior itemow z wlaczonym auto-craftingiem
        // - i pokazywal "auto-crafting wylaczony" dla WSZYSTKICH itemow, mimo
        // ze crafter w sieci mial je wlaczone. Bez tego logu wygladalo to jak
        // blad w samym auto-craftingu, a nie w podlaczeniu kontrolera.
        if (net == null) {
            com.craftingveloce.util.VeloceLog.Block.failure(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "controller at %s is NOT connected to any pipe network "
                            + "(stock and auto-crafting will show as empty) - "
                            + "place a pipe directly next to it",
                    worldPosition);
        }

        Map<Item, Long> stock = net == null ? Map.of() : net.getAllItemCounts(sl);
        Set<Item> craftable = VeloceRecipeRegistry.getAllCraftableItems(sl);
        Set<Item> craftingEnabled = net == null
                ? Set.of()
                : VeloceCraftingRegistry.getAllEnabledItems(sl, net);

        // PIEC: receptury pieca sa uzywalne TYLKO gdy w sieci stoi ZASILONY piec.
        // Rozrozniamy trzy stany, bo kazdy znaczy dla gracza cos innego:
        //   brak pieca      -> "tego nie da sie przepalic"
        //   piec bez paliwa -> "receptura jest, ale piec stoi" (podpowiedz!)
        //   piec zasilony   -> "mozna przepalac"
        Set<Item> furnaceCraftable = VeloceRecipeRegistry.getAllFurnaceCraftableItems(sl);
        boolean furnaceInNetwork = net != null && VeloceHeatSources.hasAnyHeatSource(sl, net);
        boolean furnacePowered = net != null && VeloceHeatSources.hasPower(sl, net);

        // Hotbar gracza - ktore itemy ma pod reka (kolorowanie ikon).
        Map<Item, Integer> hotbar = new HashMap<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack st = player.getInventory().getItem(slot);
            if (!st.isEmpty()) {
                hotbar.merge(st.getItem(), st.getCount(), Integer::sum);
            }
        }

        PacketDistributor.sendToPlayer(player, new OpenControllerScreenPKT(
                this.getBlockPos(), stock, craftable, craftingEnabled,
                furnaceCraftable, furnaceInNetwork, furnacePowered, hotbar));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // Controller nie przechowuje stanu - jest tylko widokiem na siec.
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
    }

    /** Pomocnicze: id itemu (do NBT/debugowania). */
    public static ResourceLocation idOf(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
    }
}
