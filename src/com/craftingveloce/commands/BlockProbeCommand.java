package com.craftingveloce.commands;

import com.craftingveloce.block.entity.VeloceModuleInfoSource;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * {@code /cv block} - statystyki klocka, na ktory patrzysz.
 *
 * <p>Gracz: "mam creative motor, ktory teoretycznie daje 256 obrotow, a modul
 * pokazuje not enough. Dodaj komende, ktora pokazuje statystyki klocka, na
 * ktory patrze - ile mu brakuje speeda i wszystko o tym bloku".
 *
 * <p>Dane bierze z rdzeniowego {@link VeloceModuleInfoSource#moduleInfo} - tego
 * samego, ktory wypelnia okno maszyny i Jade. Dlatego komenda dziala dla maszyn
 * z Create, Mekanism i Alchemistry bez znajomosci ani jednego typu z tych modow.
 */
public final class BlockProbeCommand {

    /** Zasięg "patrzenia" - jak w vanilla (kreatywny zasieg to 5, survival 4.5). */
    private static final double REACH = 8.0;

    private BlockProbeCommand() {
    }

    /** Wypisuje wszystko, co wiemy o klocku pod celownikiem. */
    public static int describe(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Ta komenda dziala tylko dla gracza."));
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        BlockHitResult hit = (BlockHitResult) player.pick(REACH, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendSuccess(() -> Component.literal("§ePatrz na jakis blok."), false);
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        BlockEntity be = level.getBlockEntity(pos);
        line(source, ChatFormatting.GOLD, "=== [Veloce] blok " + pos.toShortString() + " ===");
        line(source, ChatFormatting.GRAY, "blok: " + level.getBlockState(pos).getBlock()
                + "  state: " + level.getBlockState(pos));
        line(source, ChatFormatting.GRAY, "block entity: "
                + (be == null ? "brak" : be.getClass().getSimpleName())
                + (be instanceof VeloceNetworkNode ? "  [wezel sieci rur]" : ""));

        if (be instanceof VeloceModuleInfoSource module) {
            describeModule(source, module.moduleInfo(level));
        } else {
            line(source, ChatFormatting.YELLOW,
                    "To nie jest nasza maszyna - brak statystyk modulu.");
        }
        return 1;
    }

    /** Statystyki maszyny: predkosc i ILE BRAKUJE, SU, elementy, energia, sieć. */
    private static void describeModule(CommandSourceStack source, CompoundTag info) {
        if (info.contains("speed")) {
            float speed = info.getFloat("speed");
            int required = info.getInt("requiredSpeed");
            float missing = required - speed;
            line(source, ChatFormatting.AQUA, "--- kinetyczna (Create) ---");
            line(source, ChatFormatting.WHITE, "predkosc: " + fmt(speed)
                    + " RPM / wymagane " + fmt(required) + " RPM");
            line(source, missing > 0 ? ChatFormatting.RED : ChatFormatting.GREEN,
                    missing > 0
                            ? "BRAKUJE: " + fmt(missing) + " RPM"
                            : "predkosc wystarcza (z zapasem " + fmt(-missing) + " RPM)");
            line(source, ChatFormatting.YELLOW, "pobor SU: " + fmt(info.getFloat("suDraw"))
                    + "  (modul zada " + fmt(info.getFloat("suNeeded")) + " SU)");
            line(source, ChatFormatting.YELLOW, "siec kinetyczna: stress "
                    + fmt(info.getFloat("suStress")) + " / pojemnosc "
                    + fmt(info.getFloat("suCapacity")));
            line(source, ChatFormatting.GRAY, "wklikane elementy: " + info.getInt("parts"));
        }
        if (info.contains("energy")) {
            long energy = info.getLong("energy");
            long capacity = Math.max(1L, info.getLong("energyCapacity"));
            line(source, ChatFormatting.AQUA, "--- na energie (FE) ---");
            line(source, ChatFormatting.WHITE, "energia: "
                    + com.craftingveloce.util.VeloceFormat.feCompact(energy) + " / "
                    + com.craftingveloce.util.VeloceFormat.feCompact(capacity) + " FE ("
                    + fmt((float) (100.0 * energy / capacity)) + "%)");
            line(source, info.getBoolean("powered") ? ChatFormatting.GREEN : ChatFormatting.RED,
                    "koszt cyklu: "
                            + com.craftingveloce.util.VeloceFormat.feCompact(
                                    info.getLong("fePerOperation"))
                            + " FE  ->  cykli: "
                            + com.craftingveloce.util.VeloceFormat.compact(
                                    info.getLong("operations"))
                            + (info.getBoolean("powered") ? "  [zasilane]" : "  [BRAK ZASILANIA]"));
        }
        if (info.contains("networkNodes")) {
            line(source, ChatFormatting.GRAY, "siec rur: wezly " + info.getInt("networkNodes")
                    + ", magazyny " + info.getInt("networkStorages")
                    + ", typy itemow "
                    + com.craftingveloce.util.VeloceFormat.compact(info.getInt("networkItems")));
        }
    }

    /** Liczby tylko przez wspolny formater (inaczej tooltipy rozjezdzaja sie stylami). */
    private static String fmt(float value) {
        return com.craftingveloce.util.VeloceFormat.rate(value);
    }

    private static String fmt(int value) {
        return com.craftingveloce.util.VeloceFormat.compact(value);
    }

    private static void line(CommandSourceStack source, ChatFormatting color, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(color), false);
    }
}
