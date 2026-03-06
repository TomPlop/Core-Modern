package su.terrafirmagreg.core.common.data.tfgt.machine.multiblock.electric;

import java.util.*;

import javax.annotation.ParametersAreNonnullByDefault;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.IExplosionMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IDisplayUIMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.modifier.ModifierFunction;
import com.gregtechceu.gtceu.common.data.GTMaterials;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.Fluid;

import lombok.Getter;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class TFGLargeBoilerMachine extends WorkableMultiblockMachine implements IDisplayUIMachine, IExplosionMachine {

    private static final Map<TagKey<Fluid>, Float> WATER_STEAM_MULTIPLIERS = new LinkedHashMap<>();
    static {
        WATER_STEAM_MULTIPLIERS.put(
                TagKey.create(Registries.FLUID, ResourceLocation.fromNamespaceAndPath("tfg", "water_boiler_t2")),
                1.5f);
        // WATER_STEAM_MULTIPLIERS.put(TagKey.create(Registries.FLUID, ResourceLocation.fromNamespaceAndPath("tfg", "water_boiler_t3"))_t3, 2.0f);
    }

    private static final TagKey<Fluid> WATER_BOILER = TagKey.create(
            Registries.FLUID,
            ResourceLocation.fromNamespaceAndPath("tfg", "water_boiler"));
    public static final int TICKS_PER_STEAM_GENERATION = 5;

    private static Fluid getBoostFluid() {
        return GTMaterials.Lubricant.getFluid(1).getFluid();
    }

    private static final int BOOST_FLUID_AMOUNT_MB = 10;
    private static final int BOOST_TEMPERATURE_BONUS = 500;

    @Getter
    public final int maxTemperature, heatSpeed;
    @Persisted
    @Getter
    private int currentTemperature, throttle;
    @Nullable
    protected TickableSubscription temperatureSubs;
    private int steamGenerated;

    public TFGLargeBoilerMachine(IMachineBlockEntity holder, int maxTemperature, int heatSpeed) {
        super(holder, maxTemperature, heatSpeed);
        this.maxTemperature = maxTemperature;
        this.heatSpeed = heatSpeed;
        this.throttle = 100;
    }

    @Override
    protected RecipeLogic createRecipeLogic(Object... args) {
        return new TFGLargeBoilerRecipeLogic(this);
    }

    @Override
    public TFGLargeBoilerRecipeLogic getRecipeLogic() {
        return (TFGLargeBoilerRecipeLogic) super.getRecipeLogic();
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(0, this::updateSteamSubscription));
        }
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(0, this::updateSteamSubscription));
        }
    }

    @Override
    public void onUnload() {
        if (temperatureSubs != null) {
            temperatureSubs.unsubscribe();
            temperatureSubs = null;
        }
        super.onUnload();
    }

    protected void updateSteamSubscription() {
        if (currentTemperature > 0) {
            temperatureSubs = subscribeServerTick(temperatureSubs, this::updateCurrentTemperature);
        } else if (temperatureSubs != null) {
            temperatureSubs.unsubscribe();
            temperatureSubs = null;
        }
    }

    private float getWaterMultiplierFromTanks() {
        List<IRecipeHandler<?>> inputTanks = new ArrayList<>();
        inputTanks.addAll(getCapabilitiesFlat(IO.IN, FluidRecipeCapability.CAP));
        inputTanks.addAll(getCapabilitiesFlat(IO.BOTH, FluidRecipeCapability.CAP));

        for (Map.Entry<TagKey<Fluid>, Float> entry : WATER_STEAM_MULTIPLIERS.entrySet()) {
            var checkFluid = List.of(FluidIngredient.of(entry.getKey(), 1));
            for (IRecipeHandler<?> tank : inputTanks) {
                var result = (List<FluidIngredient>) tank.handleRecipe(IO.IN, null, checkFluid, true);
                if (result == null || result.isEmpty())
                    return entry.getValue();
            }
        }
        return 1.0f;
    }

    protected void updateCurrentTemperature() {
        int effectiveMaxTemp = getEffectiveMaxTemperature();

        if (recipeLogic.isWorking()) {
            if (getOffsetTimer() % 10 == 0) {
                if (currentTemperature < effectiveMaxTemp) {
                    currentTemperature = Mth.clamp(currentTemperature + heatSpeed * 10, 0, effectiveMaxTemp);
                } else if (currentTemperature > effectiveMaxTemp) {
                    currentTemperature = Math.max(effectiveMaxTemp, currentTemperature - getCoolDownRate() * 10);
                }
            }
        } else if (currentTemperature > 0) {
            currentTemperature -= getCoolDownRate();
        }

        if (isFormed() && getOffsetTimer() % TICKS_PER_STEAM_GENERATION == 0) {

            boolean fluidBoosted = tryDrainBoostFluid();

            var maxDrain = currentTemperature * throttle * TICKS_PER_STEAM_GENERATION /
                    (ConfigHolder.INSTANCE.machines.largeBoilers.steamPerWater * 100);

            if (currentTemperature < 100) {
                steamGenerated = 0;
            } else if (maxDrain > 0) {

                float waterMultiplier = getWaterMultiplierFromTanks();

                var drainWater = List.of(FluidIngredient.of(WATER_BOILER, maxDrain));
                List<IRecipeHandler<?>> inputTanks = new ArrayList<>();
                inputTanks.addAll(getCapabilitiesFlat(IO.IN, FluidRecipeCapability.CAP));
                inputTanks.addAll(getCapabilitiesFlat(IO.BOTH, FluidRecipeCapability.CAP));
                for (IRecipeHandler<?> tank : inputTanks) {
                    drainWater = (List<FluidIngredient>) tank.handleRecipe(IO.IN, null, drainWater, false);
                    if (drainWater == null || drainWater.isEmpty())
                        break;
                }
                var drained = (drainWater == null || drainWater.isEmpty()) ? maxDrain : maxDrain - drainWater.get(0).getAmount();

                steamGenerated = drained * ConfigHolder.INSTANCE.machines.largeBoilers.steamPerWater;
                steamGenerated = (int) (steamGenerated * waterMultiplier);

                if (drained > 0) {
                    var fillSteam = List.of(FluidIngredient.of(GTMaterials.Steam.getFluid(steamGenerated)));
                    List<IRecipeHandler<?>> outputTanks = new ArrayList<>();
                    outputTanks.addAll(getCapabilitiesFlat(IO.OUT, FluidRecipeCapability.CAP));
                    outputTanks.addAll(getCapabilitiesFlat(IO.BOTH, FluidRecipeCapability.CAP));
                    for (IRecipeHandler<?> tank : outputTanks) {
                        fillSteam = (List<FluidIngredient>) tank.handleRecipe(IO.OUT, null, fillSteam, false);
                        if (fillSteam == null)
                            break;
                    }
                }
                if (drained < maxDrain) {
                    doExplosion(getPos(), 2f);
                    var center = getPos().below().relative(getFrontFacing().getOpposite());
                    if (GTValues.RNG.nextInt(100) > 80) {
                        doExplosion(center, 2f);
                    }
                    for (Direction x : Direction.Plane.HORIZONTAL) {
                        for (Direction y : Direction.Plane.HORIZONTAL) {
                            if (GTValues.RNG.nextInt(100) > 80) {
                                doExplosion(center.relative(x).relative(y), 2f);
                            }
                        }
                    }
                }
            }
        }
        updateSteamSubscription();
    }

    private boolean tryDrainBoostFluid() {
        var drainBoost = List.of(FluidIngredient.of(getBoostFluid(), BOOST_FLUID_AMOUNT_MB));
        List<IRecipeHandler<?>> inputTanks = new ArrayList<>();
        inputTanks.addAll(getCapabilitiesFlat(IO.IN, FluidRecipeCapability.CAP));
        inputTanks.addAll(getCapabilitiesFlat(IO.BOTH, FluidRecipeCapability.CAP));
        for (IRecipeHandler<?> tank : inputTanks) {
            drainBoost = (List<FluidIngredient>) tank.handleRecipe(IO.IN, null, drainBoost, false);
            if (drainBoost == null || drainBoost.isEmpty())
                return true;
        }
        return false;
    }

    private int getEffectiveMaxTemperature() {
        var checkBoost = List.of(FluidIngredient.of(getBoostFluid(), BOOST_FLUID_AMOUNT_MB));
        List<IRecipeHandler<?>> inputTanks = new ArrayList<>();
        inputTanks.addAll(getCapabilitiesFlat(IO.IN, FluidRecipeCapability.CAP));
        inputTanks.addAll(getCapabilitiesFlat(IO.BOTH, FluidRecipeCapability.CAP));
        for (IRecipeHandler<?> tank : inputTanks) {
            var result = (List<FluidIngredient>) tank.handleRecipe(IO.IN, null, checkBoost, true);
            if (result == null || result.isEmpty())
                return maxTemperature + BOOST_TEMPERATURE_BONUS;
        }
        return maxTemperature;
    }

    protected int getCoolDownRate() {
        return 1;
    }

    @Override
    public boolean onWorking() {
        boolean value = super.onWorking();
        if (currentTemperature < getEffectiveMaxTemperature()) {
            currentTemperature = Math.max(1, currentTemperature);
            updateSteamSubscription();
        }
        return value;
    }

    public static ModifierFunction recipeModifier(@NotNull MetaMachine machine, @NotNull GTRecipe recipe) {
        return ModifierFunction.IDENTITY;
    }

    public void addDisplayText(List<Component> textList) {
        IDisplayUIMachine.super.addDisplayText(textList);
        if (isFormed()) {
            textList.add(Component.translatable("gtceu.multiblock.large_boiler.temperature",
                    currentTemperature + 274, maxTemperature + 274));
            textList.add(Component.translatable("gtceu.multiblock.large_boiler.steam_output",
                    steamGenerated / TICKS_PER_STEAM_GENERATION));

            var throttleText = Component.translatable("gtceu.multiblock.large_boiler.throttle",
                    ChatFormatting.AQUA.toString() + getThrottle() + "%")
                    .withStyle(Style.EMPTY.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.translatable("gtceu.multiblock.large_boiler.throttle.tooltip"))));
            textList.add(throttleText);

            var buttonText = Component.translatable("gtceu.multiblock.large_boiler.throttle_modify");
            buttonText.append(" ");
            buttonText.append(ComponentPanelWidget.withButton(Component.literal("[-]"), "sub"));
            buttonText.append(" ");
            buttonText.append(ComponentPanelWidget.withButton(Component.literal("[+]"), "add"));
            textList.add(buttonText);
        }
    }

    public void handleDisplayClick(String componentData, ClickData clickData) {
        if (!clickData.isRemote) {
            int result = componentData.equals("add") ? 5 : -5;
            this.throttle = Mth.clamp(throttle + result, 25, 100);
            ((TFGLargeBoilerRecipeLogic) this.getRecipeLogic()).modifyFuelBurnTime(this.throttle);
        }
    }

    @Override
    public IGuiTexture getScreenTexture() {
        return GuiTextures.DISPLAY_STEAM.get(maxTemperature > 800);
    }

    public static class TFGLargeBoilerRecipeLogic extends RecipeLogic {

        @Persisted
        @DescSynced
        @Getter
        int currentThrottle;

        public TFGLargeBoilerRecipeLogic(IRecipeLogicMachine machine) {
            super(machine);
            currentThrottle = 100;
        }

        public void setCurrentThrottle(int currentThrottle) {
            this.currentThrottle = currentThrottle;
        }

        /**
         * Multplicator depending on the temperature of the boiler.
         * When max temp -> 0.5 (burn 2 times faster)
         */
        private double getTemperatureMultiplier() {
            TFGLargeBoilerMachine boiler = (TFGLargeBoilerMachine) machine;
            int effectiveMax = boiler.getMaxTemperature() + 500; // Adding the boost - Could change it so first LBB isn't impacted
            int current = boiler.getCurrentTemperature();
            if (effectiveMax <= 0)
                return 1.0;

            // 1.0 is max temp
            double ratio = Math.min(1.0, (double) current / effectiveMax);

            // The higher the return the lower the increase in consumption
            return 1.0 - (ratio * 0.1);
        }

        @Override
        public void setupRecipe(GTRecipe recipe) {
            super.setupRecipe(recipe);
            if (lastRecipe != null) {
                setCurrentThrottle(((TFGLargeBoilerMachine) machine).getThrottle());
                double tempMultiplier = getTemperatureMultiplier();
                duration = (int) Math.round(lastRecipe.duration / (currentThrottle / 100.0) * tempMultiplier);
            }
        }

        public void modifyFuelBurnTime(int newThrottle) {
            if (lastRecipe != null) {
                double newThrottleMultiplier = (double) currentThrottle / newThrottle;
                double tempMultiplier = getTemperatureMultiplier();
                duration = (int) Math.round(lastRecipe.duration / (newThrottle / 100.0) * tempMultiplier);
                progress = (int) Math.round(newThrottleMultiplier * progress);
            }
            setCurrentThrottle(newThrottle);
        }
    }
}
