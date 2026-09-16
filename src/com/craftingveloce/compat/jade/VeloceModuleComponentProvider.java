package com.craftingveloce.compat.jade;

import com.craftingveloce.block.entity.VeloceModuleInfoSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Linie w tooltipie Jade: predkosc/SU albo energia + sieć + status.
 *
 * <p><b>Te same teksty co okno po prawym kliku</b> - obie strony biora je
 * z {@link VeloceModuleInfoLines}, wiec nie da sie ich rozjechac. Dane przyszly
 * z serwera ({@code accessor.getServerData()}), bo tylko on zna wymagana
 * predkosc, pobor SU i stan sieci.
 *
 * <p>Filtrujemy po rdzeniowym {@link VeloceModuleInfoSource}: provider jest
 * zarejestrowany dla wszystkich blokow (nie znamy - i nie chcemy znac - klas
 * blokow z modulow integracji), a obcy blok po prostu nie przechodzi testu.
 */
public enum VeloceModuleComponentProvider implements IComponentProvider<BlockAccessor> {

    INSTANCE;

    @Override
    public ResourceLocation getUid() {
        return VeloceJadePlugin.MODULE_INFO_UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!(accessor.getBlockEntity() instanceof VeloceModuleInfoSource)) {
            return;
        }
        CompoundTag info = accessor.getServerData();
        if (info == null || info.isEmpty()) {
            return;
        }
        // JEDNA linia: pracuje albo nie ma sily/energii. Gracz nie chce tu nic
        // wiecej (zadnych predkosci, SU, energii ani sieci).
        tooltip.add(com.craftingveloce.crafting.VeloceModuleStatus.message(info));
    }
}
