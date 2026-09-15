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
 * Maszyna kinetyczna Veloce dla receptur Create.
 *
 * <p><b>Czym rozni sie od maszyny na FE.</b> Create nie ma energii w naszym
 * rozumieniu - maszyna jest napedzana obrotem, a "zaplata" jest pobor SU
 * z sieci kinetycznej (obciazenie). Dlatego:
 * <ul>
 *   <li>{@link #isPowered()} = {@code getSpeed() != 0} - to KOMPLETNY test
 *       "jest napedzana": Create sam zwraca 0 przy overstress i przy
 *       zatrzymanej sieci,</li>
 *   <li>{@link #consumeOperations(long)} nic nie robi - operacje nie sa
 *       "paliwem"; koszt jest staly i rozliczany przez siec kinetyczna
 *       (obciazenie SU),</li>
 *   <li>{@link #availableOperations()} zwraca duza pule, gdy maszyna sie
 *       kreci - odpowiednik "jest czym zaplacic" dla planera.</li>
 * </ul>
 *
 * <p><b>Stala pula SU.</b> Create liczy obciazenie natywnie jako
 * {@code impact x |RPM|}, wiec przy wyzszych obrotach maszyna zjadalaby
 * wiecej SU. Nadpisujemy {@link #calculateStressApplied()} i dzielimy stala
 * przez predkosc - dzieki temu maszyna pobiera tyle samo SU niezaleznie od
 * RPM (jedyny poprawny sposob; {@code CStress.setImpact} rzuca wyjatek dla
 * blokow spoza Create).
 *
 * <p><b>Izolacja.</b> Ta klasa dziedziczy po klasie Create, wiec moze zyc
 * tylko w {@code compat/create} i tylko wtedy, gdy Create jest obecne -
 * dlatego jest tworzona wylacznie przez bramke {@code CreateCompat}.
 */
public class VeloceKineticModuleBlockEntity extends KineticBlockEntity
        implements VeloceProcessingSource {

    /**
     * Pula operacji dla planera, gdy maszyna sie kreci.
     *
     * <p>Kinetyka nie ma "paliwa" na operacje: dopoki siec sie kreci i nie jest
     * przeciążona, maszyna pracuje. Ta liczba jest wiec odpowiedzia na pytanie
     * "czy jest czym robic" (tak), a nie licznikiem, ktory sie wyczerpuje.
     */
    public static final long KINETIC_OPERATION_POOL = 1_000_000L;

    private final KineticModule module;

    public VeloceKineticModuleBlockEntity(KineticModule module, BlockEntityType<?> type,
                                         BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.module = module;
    }

    /** Opis maszyny (typ receptury, etykieta, SU) - do diagnostyki. */
    public KineticModule module() {
        return module;
    }

    /**
     * Create wymaga tej metody, ale nasza maszyna nie ma zadnych zachowan
     * (nie ma ekwipunku, GUI ani filtrów) - dlatego pusto.
     */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    /**
     * Stala pula SU niezaleznie od obrotow.
     *
     * <p>{@code lastStressApplied} jest polem protected w KineticBlockEntity
     * i MUSI byc ustawione - Create czyta je przy liczeniu obciazenia sieci.
     */
    @Override
    public float calculateStressApplied() {
        float speed = Math.abs(getTheoreticalSpeed());
        float impact = speed < 1f ? module.constantSu() : module.constantSu() / speed;
        this.lastStressApplied = impact;
        return impact;
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
        // Typ receptury rozwiazujemy dopiero tutaj - DeferredHolder Create jest
        // wiazany po zdarzeniach rejestracji.
        return Set.of(module.recipeType().get());
    }

    @Override
    public long availableOperations() {
        return isPowered() ? KINETIC_OPERATION_POOL : 0L;
    }

    @Override
    public void consumeOperations(long operations) {
        // Kinetyka placi obciazeniem sieci (SU), a nie operacjami - patrz
        // komentarz klasy. Nic tu nie zabieramy.
    }

    @Override
    public boolean isPowered() {
        // Create zwraca 0 takze przy overstress i zatrzymanej sieci, wiec to
        // jest kompletny test "maszyna jest napedzana".
        return getSpeed() != 0;
    }

    @Override
    public String sourceName() {
        return module.label();
    }
}
