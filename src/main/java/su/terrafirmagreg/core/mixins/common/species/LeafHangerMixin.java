package su.terrafirmagreg.core.mixins.common.species;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.ninni.species.server.entity.mob.update_3.Hanger;
import com.ninni.species.server.entity.mob.update_3.LeafHanger;

import net.dries007.tfc.common.entities.aquatic.AquaticMob;
import net.dries007.tfc.common.fluids.TFCFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;

@Mixin(value = LeafHanger.class, remap = false)
public abstract class LeafHangerMixin implements AquaticMob {

    @Override
    public boolean canSpawnIn(Fluid fluid) {
        return fluid.isSame(TFCFluids.SALT_WATER.getSource());
    }

    /**
     * @author Pyritie
     * @reason Lets leaf hangers spawn in salt water, and above the sea level
     */
    @Overwrite
    public static boolean canSpawn(EntityType<? extends Hanger> type, LevelAccessor world, MobSpawnType reason, BlockPos pos, RandomSource random) {
        return world.getFluidState(pos).is(TFCFluids.SALT_WATER.getSource())
                && world.getFluidState(pos.above()).is(TFCFluids.SALT_WATER.getSource())
                && world.getDifficulty() != Difficulty.PEACEFUL;
    }
}
