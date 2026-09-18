package com.craftingveloce.compat.create.block.entity;

import com.craftingveloce.block.entity.VeloceProcessingSource;
import com.craftingveloce.compat.create.KineticModule;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;

/**
 * Veloce kinetic machine for Create recipes.
 *
 * <p><b>How it differs from the FE-powered machine.</b> Create has no energy in
 * our sense - the machine is driven by rotation, and the "payment" is the SU
 * draw from the kinetic network (stress). Therefore:
 * <ul>
 *   <li>{@link #isPowered()} = {@code getSpeed() != 0} - this is the COMPLETE
 *       "is it powered" test: Create itself returns 0 under overstress and on a
 *       stopped network,</li>
 *   <li>{@link #consumeOperations(long)} does nothing - operations are not
 *       "fuel"; the cost is constant and settled by the kinetic network
 *       (SU stress),</li>
 *   <li>{@link #availableOperations()} returns a large pool when the machine is
 *       spinning - the planner's equivalent of "there is something to pay
 *       with".</li>
 * </ul>
 *
 * <p><b>Constant SU pool.</b> Create natively computes stress as
 * {@code impact x |RPM|}, so at higher speeds the machine would eat more SU. We
 * override {@link #calculateStressApplied()} and divide the constant by the
 * speed - thanks to that the machine draws the same amount of SU regardless of
 * RPM (the only correct way; {@code CStress.setImpact} throws an exception for
 * blocks outside Create).
 *
 * <p><b>Isolation.</b> This class extends a Create class, so it can only live
 * in {@code compat/create} and only when Create is present - which is why it is
 * created exclusively by the {@code CreateCompat} gate.
 */
public class VeloceKineticModuleBlockEntity extends KineticBlockEntity
        implements VeloceProcessingSource, com.craftingveloce.block.VeloceCaseSpin,
        com.craftingveloce.block.VeloceCaseBuildable,
        com.craftingveloce.block.entity.VeloceModuleInfoSource,
        com.craftingveloce.block.entity.VeloceModuleDisplay,
        com.craftingveloce.block.VeloceKineticInfo {

    /**
     * Operation pool for the planner while the machine is spinning.
     *
     * <p>Kinetics has no "fuel" per operation: as long as the network spins and
     * is not overstressed, the machine works. This number is therefore the
     * answer to the question "is there something to work with" (yes), and not a
     * counter that runs out.
     */
    public static final long KINETIC_OPERATION_POOL = 1_000_000L;

    private final KineticModule module;

    public VeloceKineticModuleBlockEntity(KineticModule module, BlockEntityType<?> type,
                                         BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.module = module;
    }

    /** Machine description (recipe type, label, SU) - for diagnostics. */
    public KineticModule module() {
        return module;
    }



    // ------------------------------------------------------------------
    // Machine parts (millstones, mechanical crafter eyes)
    // ------------------------------------------------------------------

    /**
     * How many machine parts are built.
     *
     * <p>The player adds them by right-clicking with the appropriate Create
     * item: the crusher needs TWO millstones (one per click), and the mechanical
     * crafter collects eyes (up to 9x9). No other machine has parts.
     */
    private int parts;

    /** How many parts the machine needs in order to work at all. */
    public int requiredParts() {
        if (module() == com.craftingveloce.compat.create.CreateKineticModules.CRUSHING) {
            return 2;
        }
        if (module() == com.craftingveloce.compat.create.CreateKineticModules.MECHANICAL_CRAFTING) {
            return 1;   // an unbuilt crafter has no grid slot at all
        }
        return 0;
    }

    /** How many grid slots this machine has (see VeloceProcessingSources.maxGridSide). */
    @Override
    public int availableParts() {
        return parts;
    }

    /** Upper bound on the number of parts (0 = the machine does not accept any). */
    public int partsLimit() {
        if (module() == com.craftingveloce.compat.create.CreateKineticModules.CRUSHING) {
            return 2;
        }
        if (module() == com.craftingveloce.compat.create.CreateKineticModules.MECHANICAL_CRAFTING) {
            return GRID_LIMIT * GRID_LIMIT;
        }
        return 0;
    }

    /** Whether the machine has everything it needs built. */
    public boolean hasRequiredParts() {
        return parts >= requiredParts();
    }

    /**
     * Adds a single part (right click). Returns false when the machine is full
     * or does not accept parts at all.
     */
    @Override
    public boolean addPart() {
        if (partsLimit() <= 0 || parts >= partsLimit()) {
            return false;
        }
        parts++;
        setChanged();
        if (level != null && !level.isClientSide) {
            // CREATE ATTACHES A KINETIC BLOCK FROM tick(), NOT FROM onPlace.
            //
            // Read from Create's own bytecode: KineticBlockEntity.tick() calls
            // attachKinetics() while needsSpeedUpdate() is true, and that is the ONLY
            // caller of RotationPropagator.handleAdded in the whole kinetic base.
            // KineticBlock.onPlace does not call either of them.
            //
            // A machine that appears by CONVERSION never went through a placement tick
            // in the usual way - the frame is replaced under it - and the player reported
            // exactly this: "I place an Integrale, insert a Create module, and the module
            // does not see the power, something does not refresh". So the attach Create
            // would have done for itself is done here, at the moment we know a machine
            // has appeared or changed shape.
            //
            // addPart() is the right place and not convert(): it is called once right
            // after the conversion AND on every later right-click that adds a wheel or a
            // crafter, which is precisely when the rotation network has to be told again.
            attachKinetics();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        return true;
    }

    @Override
    public boolean addToGoggleTooltip(java.util.List<net.minecraft.network.chat.Component> tooltip, boolean isPlayerSneaking) {
        float speed = Math.abs(getSpeed());
        float stressVal = this.stress;
        float capacityVal = this.capacity;
        float remaining = capacityVal - stressVal;

        String goggleKey = "gui";
        goggleKey += ".goggles.kinetic_stats";
        com.simibubi.create.foundation.utility.CreateLang.translate(goggleKey)
            .style(net.minecraft.ChatFormatting.GRAY)
            .forGoggles(tooltip);
            
        if (!hasEnoughRotationSpeed()) {
            com.simibubi.create.foundation.utility.CreateLang.text("Speed: " + Math.round(speed) + " / " + com.craftingveloce.compat.create.CreateKineticModules.REQUIRED_SPEED + " RPM")
                .style(net.minecraft.ChatFormatting.RED)
                .forGoggles(tooltip, 1);
        } else {
            com.simibubi.create.foundation.utility.CreateLang.text("Speed: " + Math.round(speed) + " RPM")
                .style(net.minecraft.ChatFormatting.GREEN)
                .forGoggles(tooltip, 1);
        }
        
        net.minecraft.ChatFormatting color = remaining < 0 ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.AQUA;
        com.simibubi.create.foundation.utility.CreateLang.text("Stress: " + Math.round(stressVal) + " SU / " + Math.round(capacityVal) + " SU")
            .style(color)
            .forGoggles(tooltip, 1);
        
        return true;
    }

    /**
     * Whether the drive provides the required speed (256 RPM).
     *
     * <p>The threshold is deliberately hard: at 255 RPM the machine stands
     * still, at 256 it works. The client asks about the same thing (the "not
     * enough rotation speed" overlay), and the value comes from
     * {@code CreateKineticModules.REQUIRED_SPEED}.
     */
    public boolean hasEnoughRotationSpeed() {
        // TOLERANCE: an engine set to 256 RPM can deliver 255.99998 along the way
        // (float + Create network propagation), and then the ">= 256" threshold
        // reported "not enough" despite full rotation. Half an RPM is still a hard
        // bound (at 255 the machine stands still), but it no longer depends on
        // rounding error.
        return Math.abs(getSpeed()) + REQUIRED_SPEED_TOLERANCE
                >= com.craftingveloce.compat.create.CreateKineticModules.REQUIRED_SPEED;
    }

    /** Speed threshold margin - see {@link #hasEnoughRotationSpeed()}. */
    public static final float REQUIRED_SPEED_TOLERANCE = 0.5F;

    /**
     * Data for the module window: speed, SU draw and pipe network.
     *
     * <p>Computed on the SERVER (only there are the real numbers of the kinetic
     * network and storages), and the client receives ready-made fields together
     * with the opening of the window.
     */
    /** Window fields on the CLIENT (the kinetic speed is synced by Create). */
    @Override
    public net.minecraft.nbt.CompoundTag moduleDisplay() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putFloat("speed", Math.abs(getSpeed()));
        tag.putInt("requiredSpeed",
                com.craftingveloce.compat.create.CreateKineticModules.REQUIRED_SPEED);
        tag.putFloat("suDraw", module.constantSu());
        tag.putInt("parts", parts);
        tag.putBoolean("enoughSpeed", hasEnoughRotationSpeed());
        tag.putFloat("suStress", this.stress);
        tag.putFloat("suCapacity", this.capacity);
        return tag;
    }

    @Override
    public net.minecraft.nbt.CompoundTag moduleInfo(
            net.minecraft.server.level.ServerLevel level) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putFloat("speed", Math.abs(getSpeed()));
        tag.putInt("requiredSpeed", com.craftingveloce.compat.create.CreateKineticModules.REQUIRED_SPEED);
        tag.putInt("maxSpeed", com.craftingveloce.compat.create.CreateKineticModules.REQUIRED_SPEED);
        tag.putFloat("suNeeded", module.constantSu());
        tag.putBoolean("enoughSpeed", hasEnoughRotationSpeed());
        tag.putInt("parts", parts);
        try {
            var kinetic = getOrCreateNetwork();
            if (kinetic != null) {
                tag.putFloat("suStress", kinetic.calculateStress());
                tag.putFloat("suCapacity", kinetic.calculateCapacity());
                tag.putFloat("suDraw", kinetic.getActualStressOf(this));
            }
        } catch (Throwable ignored) {
            // The kinetic network is sometimes unavailable (e.g. briefly after a
            // reload) - the window then shows plain zeros instead of blowing up.
        }
        var pipes = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level)
                .getNetworkForTerminal(level, worldPosition);
        if (pipes != null) {
            tag.putInt("networkNodes", pipes.getTerminals().size());
            tag.putInt("networkStorages", pipes.getEndpoints().size());
            tag.putInt("networkItems", pipes.getAllItemCounts(level).size());
        }
        return tag;
    }

    /** Required speed to show the player (overlay). */
    public int requiredSpeed() {
        return com.craftingveloce.compat.create.CreateKineticModules.REQUIRED_SPEED;
    }

    /** Upper bound of the crafter grid (Create raises the vanilla limit to 9x9). */
    public static final int GRID_LIMIT = 9;

    /** How many parts to show in the casing (millstones / crafter eyes). */
    @Override
    public int caseParts() {
        return parts;
    }

    /**
     * Rotation speed of the parts in degrees per tick.
     *
     * <p>Create's {@code getSpeed()} returns RPM, and the renderer computes in
     * degrees per tick: 1 RPM = 360 degrees / 60 s = 6 degrees/s = 0.3
     * degrees/tick. Thanks to that a faster drive means faster wheels (and not a
     * constant animation).
     */
    @Override
    public float caseSpinDegreesPerTick() {
        if (!(getBlockState().getBlock()
                instanceof com.craftingveloce.compat.create.block.VeloceKineticModuleBlock moduleBlock)
                || moduleBlock.ignoresPowerInModel()) {
            return 0.0F;   // crafter: the model does not react to the drive in any way
        }
        return Math.abs(getSpeed()) * 0.3F;
    }

    /**
     * Part layout: a SQUARE growing from the centre outwards.
     *
     * <p>Player: "it is supposed to go one by one, two by two, three by three...
     * in a square, not in a rectangle". The side is the smallest square that
     * holds the clicked-in eyes: 1 -&gt; 1x1, 2..4 -&gt; 2x2, 5..9 -&gt; 3x3, ... 81 -&gt; 9x9.
     * Millstones stand next to each other (a separate case).
     */
    @Override
    public int caseGridColumns() {
        if (caseSpinDegreesPerTick() != 0.0F) {
            return Math.max(1, parts);
        }
        return gridSide();
    }

    @Override
    public int caseGridRows() {
        if (caseSpinDegreesPerTick() != 0.0F) {
            return 1;
        }
        return gridSide();
    }

    /** Side of the square that fits this many eyes (1, 2, 3, ... 9). */
    private int gridSide() {
        return Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(1, parts))));
    }

    /** Only millstones each spin around themselves (and mesh with each other). */
    @Override
    public boolean casePartsSpinIndividually() {
        return module() == com.craftingveloce.compat.create.CreateKineticModules.CRUSHING;
    }

    /** Whether the machine requires clicked-in parts (crusher, crafter). */
    @Override
    public boolean caseBuiltFromParts() {
        return partsLimit() > 0;
    }

    /** Grid layout as text for the player: "1x2", "5x5", "9x9" (no words). */
    public String gridLabel() {
        return caseGridColumns() + "x" + caseGridRows();
    }

    @Override
    protected void write(net.minecraft.nbt.CompoundTag tag,
                         net.minecraft.core.HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("VeloceParts", parts);
    }

    @Override
    protected void read(net.minecraft.nbt.CompoundTag tag,
                        net.minecraft.core.HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        parts = tag.getInt("VeloceParts");
    }
    /**
     * Create requires this method, but our machine has no behaviours at all
     * (no inventory, GUI or filters) - hence empty.
     */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    /**
     * The machine's draw expressed as SU PER RPM - the rate Create multiplies by the
     * current speed.
     *
     * <p>Create computes a network's stress as {@code impact x |RPM|}, so the number
     * returned here is not the total the machine costs, it is the rate. Returning
     * {@code STRESS_SU / REQUIRED_SPEED} ({@code 1024 / 256 = 4.0}, see
     * {@code CreateKineticModules}) costs exactly 1024 SU once the network turns at the
     * required 256 RPM - and the same 1024 SU at every other speed, because the two
     * factors cancel. Measured against Create's own source (create 6.0.10,
     * {@code CStress.setImpact}): a millstone, a mechanical saw, a mixer and a deployer
     * each declare an impact of 4.0, i.e. the same 1024 SU at 256 RPM. A press and a
     * crushing wheel declare 8.0 (2048 SU), a mechanical crafter 2.0 (512 SU) - our
     * modules are deliberately the 4.0 group, every one of them.
     *
     * <p><b>What this deliberately does NOT do.</b> It does not read the current speed,
     * and it does not read the number of wheels or crafters in the casing. An earlier
     * version divided the constant by the current speed: that reported an impact of 1024
     * while the machine stood still, and then 1024 * 256 = 262144 SU once the shaft
     * reached 256 RPM - the "the network screams overstressed" a player reported. A
     * casing holding four crushing wheels has to cost the same as one holding a single
     * saw, so there is nothing here to sum over.
     *
     * <p>{@code lastStressApplied} is a protected field in KineticBlockEntity
     * and MUST be set - Create reads it when computing the network's stress.
     */
    @Override
    public float calculateStressApplied() {
        float impact = module.constantSu()
                / com.craftingveloce.compat.create.CreateKineticModules.REQUIRED_SPEED;
        this.lastStressApplied = impact;
        return impact;
    }

    // ------------------------------------------------------------------
    // VeloceKineticInfo
    // ------------------------------------------------------------------
    //
    // The three answers a test needs in order to tell "this machine is taking power from
    // a network" apart from "this machine is standing next to a network". They are the
    // raw values and not the status sentence shown to a player, because a tooltip reading
    // "not enough force" is equally consistent with a machine that is correctly starved
    // and with one that was never connected to anything.

    /**
     * The network's speed in RPM, as seen by this machine.
     *
     * <p>{@code getSpeed()} and not {@code getTheoreticalSpeed()}: the theoretical speed
     * is what the network WOULD deliver, which stays non-zero even when this machine has
     * no source - and that is precisely the distinction the test exists to make.
     */
    @Override
    public float kineticRpm() {
        return getSpeed();
    }

    /** SU per RPM, i.e. what {@link #calculateStressApplied()} hands to the network. */
    @Override
    public float kineticStressImpact() {
        return calculateStressApplied();
    }

    /** Whether a rotation source reaches this machine at all. */
    @Override
    public boolean kineticHasSource() {
        return hasSource();
    }

    // ------------------------------------------------------------------
    // VeloceProcessingSource
    // ------------------------------------------------------------------

    @Override
    public String moduleId() {
        return module.id();
    }

    @Override
    public Set<RecipeType<?>> recipeTypes() {
        // We resolve the recipe type only here - Create's DeferredHolder is bound
        // after the registration events.
        return Set.of(module.recipeType().get());
    }

    @Override
    public long availableOperations() {
        return isPowered() ? KINETIC_OPERATION_POOL : 0L;
    }

    @Override
    public void consumeOperations(long operations) {
        // Kinetics pays with network stress (SU), not with operations - see the
        // class comment. We take nothing away here.
    }

    @Override
    public boolean isPowered() {
        // Create also returns 0 under overstress and on a stopped network, so this
        // is the complete "the machine is powered" test. In addition the machine
        // must have the REQUIRED SPEED (256 RPM) and be BUILT: a crusher without
        // two millstones spins, but does nothing.
        return hasEnoughRotationSpeed() && hasRequiredParts();
    }

    @Override
    public String sourceName() {
        return module.label();
    }
}
