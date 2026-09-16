package com.craftingveloce.compat.jade;

import com.craftingveloce.block.VeloceIntegraleBlock;
import com.craftingveloce.block.VeloceIntegraleFrame;
import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.craftingveloce.block.entity.VeloceExtractorBlockEntity;
import com.craftingveloce.block.entity.VeloceHeatSource;
import com.craftingveloce.block.entity.VeloceProcessingSource;
import com.craftingveloce.block.entity.VeloceThresholdSensorBlockEntity;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import com.craftingveloce.block.entity.VelocePipeBlockEntity;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * Liczy dane dla overlayu Jade PO STRONIE SERWERA i wysyla je razem z tooltipem.
 *
 * <p><b>Dlaczego nie klient.</b> Tooltip Jada rysuje klient, a tam liczby
 * (energia, operacje, zawartosc sieci) sa albo nieaktualne, albo ich nie ma.
 * Liczenie ich w locie na kliencie znaczyloby ponadto skan sieci przy KAZDEJ
 * klatce tooltipa. Tutaj liczymy raz, wtedy gdy Jade o to poprosi.
 *
 * <p><b>Wszystko w jednym worku</b> ({@code "veloce"}), a provider klienta
 * pokazuje linie tylko dla kluczy, ktore naprawde przyszly - dzieki temu nie
 * ma "Operations left: 0" udajacego prawde.
 */
public class VeloceBlockDataProvider implements IServerDataProvider<BlockAccessor> {

    /** Klucz worka z naszymi danymi (jeden, zeby nie zasmiecac NBT Jada). */
    static final String KEY = "veloce";

    /** Ten sam identyfikator co provider klienta - Jade laczy je po nim. */
    public static final net.minecraft.resources.ResourceLocation UID =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "craftingveloce", "block_info");

    @Override
    public net.minecraft.resources.ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        Block block = accessor.getBlock();
        if (!isVeloce(block)) {
            return;
        }
        CompoundTag veloce = new CompoundTag();
        BlockState state = accessor.getBlockState();

        if (block instanceof VeloceIntegraleBlock) {
            boolean filled = VeloceIntegraleBlock.isFilled(state);
            veloce.putBoolean("integrale", true);
            veloce.putBoolean("filled", filled);
            veloce.putInt("coveredSides", VeloceIntegraleFrame.coveredSides(state));
            // Pusta klatka nie mowi sama z siebie, do czego sluzy - a to
            // jedyne miejsce, w ktorym gracz zobaczy te podpowiedz bez
            // szukania w dokumentacji.
            veloce.putBoolean("integraleHint", !filled);
            if (accessor.getBlockEntity() instanceof VeloceCraftingTableBlockEntity table) {
                ItemStack display = table.getDisplayItem();
                if (!display.isEmpty()) {
                    veloce.putBoolean("display", true);
                    veloce.putString("displayName", display.getHoverName().getString());
                }
            }
        }

        // Stol stojacy w KLATCE (stan "facade"): to on jest crafterem, a nie
        // klatka - dlatego linia mowi wprost, ze w srodku jest stol craftingu.
        if (block instanceof com.craftingveloce.block.VeloceCraftingTableBlock
                && com.craftingveloce.block.VeloceCraftingTableBlock.isFacade(state)) {
            veloce.putBoolean("integraleCase", true);
        }

        if (accessor.getBlockEntity() instanceof VeloceCraftingTableBlockEntity crafter
                && crafter.isActiveCrafter()) {
            veloce.putBoolean("crafter", true);
            veloce.putInt("disabledItems", crafter.getDisabledItems().size());
            veloce.putInt("bufferSlots", crafter.getBuffer().getContainerSize());
        }

        if (accessor.getBlockEntity() instanceof VeloceHeatSource heat) {
            veloce.putString("heatName", heat.heatSourceName());
            veloce.putLong("operations", heat.availableOperations());
            veloce.putBoolean("powered", heat.isPowered());
        }

        if (accessor.getBlockEntity() instanceof VeloceProcessingSource module) {
            veloce.putString("moduleId", module.moduleId());
            veloce.putLong("operations", module.availableOperations());
            veloce.putBoolean("powered", module.isPowered());
        }

        if (accessor.getBlockEntity()
                instanceof com.craftingveloce.block.entity.VeloceControllerBlockEntity controller) {
            veloce.putInt("stockTypes", controller.stockItemTypes());
            veloce.putInt("steadyFlow", controller.steadyFlowCount());
        }

        if (accessor.getBlockEntity() instanceof VeloceExtractorBlockEntity extractor) {
            int set = 0;
            int crafting = 0;
            for (int i = 0; i < 9; i++) {
                if (!extractor.getFilterAt(i).isEmpty()) {
                    set++;
                }
                if (extractor.isCraftingAllowed(i)) {
                    crafting++;
                }
            }
            veloce.putInt("filtersSet", set);
            veloce.putInt("filtersCrafting", crafting);
        }

        if (accessor.getBlockEntity() instanceof VeloceThresholdSensorBlockEntity sensor) {
            veloce.putLong("threshold", sensor.getThreshold());
            veloce.putLong("sensorCount", sensor.getLastCount());
            veloce.putBoolean("sensorHigh",
                    sensor.getMode() == VeloceThresholdSensorBlockEntity.Mode.HIGH);
            ItemStack filter = sensor.getFilter();
            veloce.putString("filterName", filter.isEmpty() ? "" : filter.getHoverName().getString());
        }

        appendNetwork(veloce, accessor);
        data.put(KEY, veloce);
    }

    /** Sieć: terminal i rura widzą ją inaczej, ale liczymy w jednym miejscu. */
    private void appendNetwork(CompoundTag veloce, BlockAccessor accessor) {
        boolean terminal = accessor.getBlockEntity() instanceof VeloceTomTerminalBlockEntity;
        boolean pipe = accessor.getBlockEntity() instanceof VelocePipeBlockEntity;
        if (!terminal && !pipe) {
            return;
        }
        if (!(accessor.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = accessor.getPosition();
        VelocePipeNetwork network = VelocePipeNetworkManager.get(level)
                .getNetworkForTerminal(level, pos);
        if (network == null) {
            veloce.putBoolean("noNetwork", true);
            return;
        }
        if (terminal) {
            veloce.putInt("networkNodes", network.getTerminals().size());
            veloce.putInt("networkStorages", network.getEndpoints().size());
            veloce.putInt("networkItemTypes", network.getAllItemCounts(level).size());
        } else {
            veloce.putInt("networkPipes", network.getPipes().size());
        }
    }

    private boolean isVeloce(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && "craftingveloce".equals(id.getNamespace());
    }
}
