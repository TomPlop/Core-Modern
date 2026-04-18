package su.terrafirmagreg.core.mixins.common.tfc.features;

import net.dries007.tfc.common.blocks.TFCBlocks;
import net.dries007.tfc.world.feature.LooseRockFeature;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LooseRockFeature.class, remap = false)
public class LooseRockFeatureMixin {

	@Inject(method = "canGenerateOn", at = @At("HEAD"),	remap = false, cancellable = true)
	private void tfg$canGenerateOn(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (state == Blocks.ICE.defaultBlockState() || state == TFCBlocks.SEA_ICE.get().defaultBlockState()) {
			cir.setReturnValue(false);
		}
	}
}
