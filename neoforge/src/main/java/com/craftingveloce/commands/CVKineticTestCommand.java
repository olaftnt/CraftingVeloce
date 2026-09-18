package com.craftingveloce.commands;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * {@code /cv kinetic place <block>} and {@code /cv kinetic read} - the two halves of the
 * only test that can prove a machine module really takes power from a rotational network.
 *
 * <p><b>Why this exists.</b> A player reported that placing an Integrale and inserting a
 * Create module left the module unable to see power. Reading the code cannot settle that:
 * the attachment runs on a tick, through Create's own propagator, and the difference
 * between "attached and turning" and "standing next to a network" is one number that only
 * the running game can produce. So this builds the smallest rig that can have that number
 * and then reads it back.
 *
 * <p><b>Why it is split into two commands.</b> Create propagates rotation over the ticks
 * that follow a placement, so a speed read in the same tick as the motor is always zero -
 * a test that did both at once would report a failure that means nothing. The script
 * therefore places, waits, and only then reads (see {@code veloce-tests/module-create.txt}).
 *
 * <p><b>Why nothing here names Create.</b> The mod has to start without Create installed,
 * so a core command must not mention a Create class even in passing - naming one in a
 * method body is enough to make the whole class fail to link. Everything it needs is
 * reached through the block registry (the motor is looked up by the id
 * {@code create:creative_motor}) and through {@code VeloceKineticInfo}, which
 * {@code compat/create} implements.
 */
public final class CVKineticTestCommand {

    /** The machine the last {@code place} built, for the {@code read} that follows. */
    private static BlockPos lastMachinePos;

    /** The motor the last {@code place} built - the rig's own sanity check. */
    private static BlockPos lastMotorPos;

    /**
     * What the last {@code place} decided, echoed by {@code read}.
     *
     * <p><b>Why this is remembered at all.</b> Chat is not part of the test result, so a
     * {@code place} that refuses - for a missing block, a missing Create, or a missing
     * player - leaves the script with only the LATER failure ("nothing was placed yet"),
     * which names the symptom and hides the cause. Carrying the reason forward means a red
     * run always says why the rig was never built.
     */
    private static String lastPlaceReport;

    /**
     * The level's game time when the rig was built.
     *
     * <p><b>Why the clock is part of the measurement.</b> Everything Create does to a newly
     * placed kinetic block happens on a tick, so a speed of zero has two completely
     * different meanings: the machine never attached, or NO TICK HAS RUN AT ALL. Printing
     * how much time passed between the placement and the reading separates them - a delta
     * of zero means the rig was measured in a world that is not moving, and then the speed
     * says nothing at all about the machine.
     *
     * <p>It is {@code MinecraftServer.getTickCount()}, the same counter the test driver's
     * own {@code wait:} uses. Measuring the LEVEL's game time instead looked equivalent and
     * was not: it reported a delta of zero across a wait that had demonstrably happened,
     * which is the sort of instrument that turns a real answer into a false one.
     */
    private static long lastPlaceTick;

    /**
     * Wall-clock milliseconds when the rig was built.
     *
     * <p><b>Why a second clock is needed at all.</b> A frozen tick counter is produced by
     * two completely different faults: the world is not ticking, or the script's
     * {@code wait:} never actually waited and both commands ran back to back. Game time
     * cannot tell those apart - it is zero in both. Wall-clock can: ten seconds for a
     * 200-tick wait means the wait happened and the world stood still, while no elapsed
     * time at all means the wait was skipped.
     */
    private static long lastPlaceMillis;

    /**
     * The block id of Create's own infinite rotation source.
     *
     * <p>A creative motor and not a hand crank: the point of the rig is to hold a STEADY
     * speed, so that the impact read back can be compared against a number instead of
     * against a curve.
     */
    private static final ResourceLocation MOTOR =
            ResourceLocation.parse("create:creative_motor");

    /**
     * The block id whose stress must never appear.
     *
     * <p>Separate from {@link #MOTOR} because it is checked against the value a caller
     * passes, not registered here - it is only named so the failure message can say which
     * mod is missing.
     */
    private static final String CREATE_NAMESPACE = "create";

    private CVKineticTestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(CVCommandRoot.root()
                .then(Commands.literal("kinetic")
                        .then(Commands.literal("place")
                                .then(Commands.argument("block",
                                                net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(ctx -> place(ctx.getSource(),
                                                net.minecraft.commands.arguments.ResourceLocationArgument
                                                        .getId(ctx, "block")))))
                        .then(Commands.literal("read")
                                .executes(ctx -> read(ctx.getSource())))));
    }

    private static int place(CommandSourceStack source, ResourceLocation blockId) {
        // Set before anything can refuse, so that a null report can only mean "this
        // command never ran" - a distinction that cost a whole test run to make, because
        // a Brigadier argument that refuses to parse never reaches this method and so
        // reports nothing at all.
        lastPlaceReport = "entered with block='" + blockId + "' at tick="
                + source.getServer().getTickCount();
        ServerLevel level = source.getLevel();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            lastPlaceReport = "refused: no player (the rig is built next to one)";
            source.sendFailure(Component.literal("§c[kinetic] " + lastPlaceReport));
            return 0;
        }

        Block module = resolve(blockId);
        if (module == null || module == Blocks.AIR) {
            lastPlaceReport = "refused: no such block '" + blockId + "'";
            source.sendFailure(Component.literal("§c[kinetic] " + lastPlaceReport));
            return 0;
        }
        Block motor = resolve(MOTOR);
        if (motor == null || motor == Blocks.AIR) {
            lastPlaceReport = "refused: " + MOTOR + " is missing - this test needs the "
                    + CREATE_NAMESPACE + " mod installed";
            source.sendFailure(Component.literal("§c[kinetic] " + lastPlaceReport));
            return 0;
        }

        BlockPos base = player.blockPosition().offset(3, 0, 0);
        BlockPos machinePos = base;
        BlockPos motorPos = base.east();
        clearArea(level, base);

        // The machine's rotation AXIS and the motor's FACING are both set by NAME, taking
        // the property instance off the block's own state rather than using a constant
        // from elsewhere. Create's `DirectionalKineticBlock.FACING` is its own
        // DirectionProperty, not vanilla's BlockStateProperties.FACING, and setting a
        // property instance that the block does not own throws - so the property has to
        // come from the block itself.
        //
        // The axes have to agree for rotation to be transferred at all: the module turns
        // about X, and the motor's FACING decides where its shaft is (Create answers
        // `hasShaftTowards(..., side)` with `side == FACING`), so a motor standing EAST of
        // the module must face WEST to put its shaft against it.
        level.setBlock(machinePos, setByName(module.defaultBlockState(), "axis", Direction.Axis.X),
                Block.UPDATE_ALL);
        level.setBlock(motorPos, setByName(motor.defaultBlockState(), "facing", Direction.WEST),
                Block.UPDATE_ALL);
        lastMachinePos = machinePos;
        lastMotorPos = motorPos;
        // The states are recorded rather than the ids: what a block was placed AS is the
        // part that can be wrong, and reading it back here means a later failure can be
        // paired with the state that produced it.
        lastPlaceTick = level.getServer().getTickCount();
        lastPlaceMillis = System.currentTimeMillis();
        lastPlaceReport = "placed " + BuiltInRegistries.BLOCK.getKey(module) + " as "
                + level.getBlockState(machinePos) + " with " + MOTOR + " as "
                + level.getBlockState(motorPos) + " at tick=" + lastPlaceTick;

        source.sendSuccess(() -> Component.literal("§e[kinetic] placed " + blockId + " at "
                + describe(machinePos) + " with " + MOTOR + " at " + describe(motorPos)
                + " §7- now wait a few ticks and run §f/cv kinetic read"), false);
        return 1;
    }

    private static int read(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        // Echoed BEFORE the null check, deliberately: when the rig was never built this is
        // the only line that says why, and the early return below is exactly the path that
        // used to hide the reason.
        String placeReport = lastPlaceReport;
        source.sendSuccess(() -> Component.literal("[kinetic] place: " + placeReport), false);
        if (lastMachinePos == null) {
            source.sendFailure(Component.literal(
                    "§c[kinetic] nothing was placed yet - run /cv kinetic place <block> first"));
            return 0;
        }
        var blockEntity = level.getBlockEntity(lastMachinePos);
        boolean ours = blockEntity instanceof com.craftingveloce.block.VeloceKineticInfo;
        float rpm;
        float impact;
        boolean hasSource;
        if (ours) {
            var info = (com.craftingveloce.block.VeloceKineticInfo) blockEntity;
            rpm = info.kineticRpm();
            impact = info.kineticStressImpact();
            hasSource = info.kineticHasSource();
        } else {
            // A block that is NOT one of our kinetic machines is still measured, on
            // purpose: dropping a plain Create shaft into the same rig is the only way to
            // tell "this rig cannot turn anything" apart from "it turns a shaft but not
            // our machine". The strictness lives in the script, whose assertions on
            // `impact=` and `source=` can only ever be satisfied by our own machine.
            rpm = probeSpeed(level, lastMachinePos);
            impact = 0.0F;
            hasSource = rpm != 0.0F;
        }

        // The rig's OWN sanity check, and the reason it comes first.
        //
        // Without it a reading of zero is ambiguous in the worst possible way: "the motor
        // is not turning" and "the machine failed to attach to a turning motor" produce
        // the identical output, and only the second is a defect in this mod. Reporting the
        // first as the second would send someone hunting through our placement code for a
        // bug that lives in the test rig - so the motor's speed is read and reported
        // separately, and a dead motor is called out as a broken rig.
        float motorRpm = probeSpeed(level, lastMotorPos);
        boolean rigOk = motorRpm != 0.0F;
        String motorReport = describeMotor(level, lastMotorPos);
        long ticksSincePlace = level.getServer().getTickCount() - lastPlaceTick;
        long absoluteTick = level.getServer().getTickCount();
        long millisSincePlace = System.currentTimeMillis() - lastPlaceMillis;
        String ticksReport = "tick=" + absoluteTick + " ticksSincePlace=" + ticksSincePlace
                + " millisSincePlace=" + millisSincePlace;
        source.sendSuccess(() -> Component.literal("[kinetic] rig: " + motorReport
                + " " + ticksReport + " rig=" + (rigOk ? "OK" : "BROKEN")), false);

        // The verdict covers only what the COMMAND can know: that a source reaches the
        // machine and that it is turning. The numeric expectation lives in the script,
        // because the number that matters (1024 SU / 256 RPM = an impact of 4.0) is a
        // property of the Create material and belongs in the test, where it is written
        // down next to the assertion it justifies.
        boolean turning = rpm != 0.0F;
        String machineReport = "rpm=" + rpm + " impact=" + impact + " source=" + hasSource
                + " ours=" + ours + " be="
                + (blockEntity == null ? "NONE" : blockEntity.getClass().getSimpleName());
        source.sendSuccess(() -> Component.literal("§e[kinetic] " + machineReport), false);

        // Built as a plain value rather than one nested ternary inside the literal call:
        // the first version of this lost a bracket there and did not compile, and a
        // verdict nobody can read is not worth compressing.
        String verdict;
        if (!rigOk) {
            verdict = "FAIL (THE RIG IS BROKEN - the motor itself is not turning, "
                    + "so this run says nothing about the machine)";
        } else if (turning && hasSource) {
            verdict = "PASS";
        } else {
            verdict = "FAIL";
            if (!turning) {
                verdict += " (speed is 0 - the machine is not attached to the network)";
            }
            if (!hasSource) {
                verdict += " (no rotation source reaches the machine)";
            }
        }
        String finalVerdict = verdict;
        source.sendSuccess(() -> Component.literal("[kinetic] verdict: " + finalVerdict), false);
        return rigOk && turning && hasSource ? 1 : 0;
    }

    /**
     * Everything about the rig's motor that a red run needs to be diagnosable.
     *
     * <p>A bare speed of zero cannot be acted on, because it is the same number for
     * "no block at all", "a block with no block entity", "a block entity that is not
     * kinetic" and "a motor that is simply standing still" - and those need four
     * different fixes. So the report carries the block id, its state, the block entity
     * class and whether a {@code getSpeed()} method was even found.
     */
    private static String describeMotor(ServerLevel level, BlockPos pos) {
        if (pos == null) {
            return "motor=<none placed>";
        }
        BlockState state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        String speed;
        if (blockEntity == null) {
            speed = "n/a";
        } else {
            try {
                speed = String.valueOf(((Number) blockEntity.getClass()
                        .getMethod("getSpeed").invoke(blockEntity)).floatValue());
            } catch (ReflectiveOperationException e) {
                speed = "NO getSpeed()";
            }
        }
        return "motor=" + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + " state=" + state
                + " be=" + (blockEntity == null ? "NONE" : blockEntity.getClass().getSimpleName())
                + " motorRpm=" + speed
                + " generated=" + probe(blockEntity, "getGeneratedSpeed");
    }

    /**
     * Calls a no-argument numeric getter, or answers {@code "n/a"}.
     *
     * <p>Reflective for the reason given on {@link #probeSpeed}: these getters belong to
     * Create, whose classes the core must not name. The distinction being made here is
     * worth the reflection - a motor whose {@code getGeneratedSpeed} is 16 while its
     * {@code getSpeed} is 0 is a motor that is generating and not being propagated to,
     * which is a completely different problem from a motor that is generating nothing.
     */
    private static String probe(Object target, String getter) {
        if (target == null) {
            return "n/a";
        }
        try {
            return String.valueOf(((Number) target.getClass()
                    .getMethod(getter).invoke(target)).floatValue());
        } catch (ReflectiveOperationException e) {
            return "NO " + getter + "()";
        }
    }

    /**
     * The speed of ANY block entity that has a {@code getSpeed()} method.
     *
     * <p><b>Why reflection, in one place, only here.</b> The rig's own check has to reach
     * Create's motor, and the core must not name a Create class - naming one in a method
     * body is enough to make this whole class fail to link without Create installed. A
     * missing method is also a legitimate answer ("this is not a kinetic block"), not an
     * error, which is exactly what reflection expresses. Nothing in the mod depends on
     * this result; it exists so that a red run can say which half is broken.
     */
    private static float probeSpeed(ServerLevel level, BlockPos pos) {
        if (pos == null) {
            return 0.0F;
        }
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return 0.0F;
        }
        try {
            var method = blockEntity.getClass().getMethod("getSpeed");
            return ((Number) method.invoke(blockEntity)).floatValue();
        } catch (ReflectiveOperationException ignored) {
            return 0.0F;
        }
    }

    /**
     * Resolves a block id, defaulting a bare path to OUR namespace.
     *
     * <p>With no colon the argument parser fills in {@code minecraft:}, so a bare
     * {@code veloce_create_millstone_module} arrives as {@code minecraft:veloce_create_...}.
     * Rather than make every script spell out {@code craftingveloce:}, a lookup that only
     * found air is retried in our namespace - which is what the author meant, since the
     * other ids in this command are all foreign and are always written with their own
     * namespace.
     */
    private static Block resolve(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block != Blocks.AIR || !"minecraft".equals(id.getNamespace())) {
            return block;
        }
        return BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("craftingveloce", id.getPath()));
    }

    /**
     * Clears the rig area first.
     *
     * <p>The dev world persists between runs, so a machine left behind by an earlier case
     * stays in the network and answers for the one under test - and a leftover SHAFT next
     * to the new rig would carry rotation into it and make a module that never attached
     * itself look as if it had.
     */
    private static void clearArea(ServerLevel level, BlockPos base) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
            }
        }
    }

    /**
     * Sets a block state property by its NAME.
     *
     * <p>Needed because the property instances are not interchangeable between blocks: two
     * properties can both be called {@code facing} and still be different objects, and
     * {@code setValue} rejects a property the block does not own. Taking the instance from
     * {@code state.getProperties()} is the only version that is right for every block.
     */
    private static BlockState setByName(BlockState state, String name, Comparable<?> value) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return setUnchecked(state, property, value);
            }
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setUnchecked(BlockState state, Property property, Comparable value) {
        return state.setValue(property, value);
    }

    private static String describe(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }
}
