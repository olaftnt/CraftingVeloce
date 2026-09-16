package com.craftingveloce.compat.jade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Linie Veloce w overlayu Jade.
 *
 * <p><b>Skad biora sie liczby.</b> Jade rysuje tooltip po stronie KLIENTA,
 * wiec block entity widziane tam moga nie miec aktualnych liczb (energii,
 * zawartosci sieci). Dlatego wszystkie wartosci przychodza z
 * {@link VeloceBlockDataProvider}, ktory liczy je RAZ na serwerze i wysyla
 * zwyklym mechanizmem Jada. Linia pojawia sie tylko wtedy, gdy dana wartosc
 * naprawde przyszla - zero "Operations left: 0" udajace prawde.
 *
 * <p>Z tego samego powodu zniknal pomysl liczenia sieci w locie: to bylo
 * skanowanie calej sieci przy KAZDEJ klatce tooltipa.
 */
public class VeloceBlockInfoProvider implements IBlockComponentProvider {

    public static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("craftingveloce", "block_info");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (data == null || !data.contains("veloce")) {
            return;   // cudzy blok albo brak danych z serwera
        }
        CompoundTag veloce = data.getCompound("veloce");

        // Klatka: pusty schowek albo gabłota + zabudowane strony (te są w stanie
        // bloku, więc klient zna je zawsze).
        if (veloce.getBoolean("integrale")) {
            tooltip.add(veloce.getBoolean("filled") && veloce.contains("display")
                    ? Component.translatable("gui.craftingveloce.jade.integrale.filled",
                            veloce.getString("displayName"))
                    : Component.translatable("gui.craftingveloce.jade.integrale.empty"));
            tooltip.add(Component.translatable("gui.craftingveloce.jade.integrale.closed",
                    veloce.getInt("coveredSides")));
            if (veloce.getBoolean("integraleHint")) {
                tooltip.add(Component.translatable("gui.craftingveloce.jade.integrale.hint"));
            }
        }

        // Stol stojacy w klatce: to on jest crafterem, a nie sama klatka.
        if (veloce.getBoolean("integraleCase")) {
            tooltip.add(Component.translatable("gui.craftingveloce.jade.integrale.crafting"));
        }

        // Crafter (takze klatka po wypelnieniu).
        if (veloce.getBoolean("crafter")) {
            tooltip.add(Component.translatable("gui.craftingveloce.jade.crafter.enabled",
                    veloce.getInt("disabledItems")));
            tooltip.add(Component.translatable("gui.craftingveloce.jade.crafter.buffer",
                    veloce.getInt("bufferSlots")));
        }

        // Zrodlo ciepla (piec paliwowy / elektryczny).
        if (veloce.contains("heatName")) {
            tooltip.add(Component.translatable("gui.craftingveloce.jade.heat.source",
                    veloce.getString("heatName"), veloce.getLong("operations")));
        }
        if (veloce.contains("powered")) {
            tooltip.add(Component.translatable("gui.craftingveloce.jade.heat.powered",
                    yesNo(veloce.getBoolean("powered"))));
        }

        // Modul z innego moda (Create/Mekanism/Alchemistry).
        if (veloce.contains("moduleId")) {
            tooltip.add(Component.translatable("gui.craftingveloce.jade.module.id",
                    veloce.getString("moduleId")));
            tooltip.add(Component.translatable("gui.craftingveloce.jade.module.operations",
                    veloce.getLong("operations")));
        }

        // Kontroler.
        if (veloce.contains("stockTypes")) {
            tooltip.add(Component.translatable("gui.craftingveloce.jade.controller.stock",
                    veloce.getInt("stockTypes")));
            tooltip.add(Component.translatable("gui.craftingveloce.jade.controller.flow",
                    veloce.getInt("steadyFlow")));
        }

        // Ekstraktor.
        if (veloce.contains("filtersSet")) {
            tooltip.add(Component.translatable("gui.craftingveloce.jade.extractor.filters",
                    veloce.getInt("filtersSet")));
            tooltip.add(Component.translatable("gui.craftingveloce.jade.extractor.crafting",
                    veloce.getInt("filtersCrafting")));
        }

        // Sensor progu.
        if (veloce.contains("threshold")) {
            if (!veloce.getString("filterName").isEmpty()) {
                tooltip.add(Component.translatable("gui.craftingveloce.jade.sensor.filter",
                        veloce.getString("filterName")));
            }
            tooltip.add(Component.translatable("gui.craftingveloce.jade.sensor.threshold",
                    veloce.getLong("threshold"),
                    Component.translatable(veloce.getBoolean("sensorHigh")
                            ? "gui.craftingveloce.jade.sensor.atleast"
                            : "gui.craftingveloce.jade.sensor.below")));
            tooltip.add(Component.translatable("gui.craftingveloce.jade.sensor.count",
                    veloce.getLong("sensorCount")));
        }

        // Terminal i rura: co wiedza o sieci.
        if (veloce.contains("networkNodes")) {
            tooltip.add(Component.translatable("gui.craftingveloce.jade.terminal.network",
                    veloce.getInt("networkNodes"), veloce.getInt("networkStorages")));
            tooltip.add(Component.translatable("gui.craftingveloce.jade.terminal.items",
                    veloce.getInt("networkItemTypes")));
        } else if (veloce.contains("networkPipes")) {
            tooltip.add(Component.translatable("gui.craftingveloce.jade.pipe.network",
                    veloce.getInt("networkPipes")));
        } else if (veloce.getBoolean("noNetwork")) {
            tooltip.add(Component.translatable("gui.craftingveloce.jade.network.none"));
        }
    }

    private Component yesNo(boolean value) {
        return Component.translatable(value
                ? "gui.craftingveloce.jade.yes" : "gui.craftingveloce.jade.no");
    }
}
