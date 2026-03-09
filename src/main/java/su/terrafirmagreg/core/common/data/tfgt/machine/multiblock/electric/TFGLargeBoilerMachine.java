package su.terrafirmagreg.core.common.data.tfgt.machine.multiblock.electric;

import java.util.*;
import java.util.function.Supplier;

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
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

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

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            TFGLargeBoilerMachine.class,
            WorkableMultiblockMachine.MANAGED_FIELD_HOLDER);

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    public record BoosterFluid(
            Supplier<Fluid> fluid,
            int fluidAmountMb,
            int temperatureBonus,
            int minBoilerTemperature, // maxTemperature required define in the Multiblock
            String translationKey) {
    }

    // Every Boosters available
    private static final List<BoosterFluid> BOOSTERS = List.of(
            new BoosterFluid(
                    () -> GTMaterials.Creosote.getFluid(1).getFluid(),
                    10, 500,
                    0,
                    "block.gtceu.creosote"),
            new BoosterFluid(
                    () -> GTMaterials.Lubricant.getFluid(1).getFluid(),
                    20, 1000,
                    1800,
                    "material.gtceu.lubricant")

    /*
    // Example of a Booster that requires 1800 Temp (minBoilerTemperature = 1800) :
    new BoosterFluid(
    () -> GTMaterials.Creosote.getFluid(1).getFluid(),
    20, 1000,
    1800
    )
    */
    );

    // Steam output boost depending of the water
    private static final Map<TagKey<Fluid>, Float> WATER_STEAM_MULTIPLIERS = new LinkedHashMap<>();
    static {
        WATER_STEAM_MULTIPLIERS.put(
                TagKey.create(Registries.FLUID, ResourceLocation.fromNamespaceAndPath("tfg", "water_boiler_t2")),
                1.5f);
        // WATER_STEAM_MULTIPLIERS.put(TagKey.create(Registries.FLUID, ResourceLocation.fromNamespaceAndPath("tfg", "water_boiler_t3")), 2.0f);
    }

    private static final TagKey<Fluid> WATER_BOILER = TagKey.create(
            Registries.FLUID,
            ResourceLocation.fromNamespaceAndPath("tfg", "water_boiler"));
    public static final int TICKS_PER_STEAM_GENERATION = 5;

    @Getter
    public final int maxTemperature, heatSpeed;
    @Persisted
    @Getter
    private int currentTemperature, throttle;
    @Nullable
    protected TickableSubscription temperatureSubs;
    private int steamGenerated;

    public TFGLargeBoilerMachine(IMachineBlockEntity holder, int maxTemperature, int heatSpeed, Object... args) {
        super(holder, args);
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

    // Check temp to allow the Booster
    private List<BoosterFluid> getCompatibleBoosters() {
        return BOOSTERS.stream()
                .filter(b -> maxTemperature >= b.minBoilerTemperature())
                .toList();
    }

    private float getWaterMultiplierFromTanks() {
        List<IRecipeHandler<?>> inputTanks = new ArrayList<>();
        inputTanks.addAll(getCapabilitiesFlat(IO.IN, FluidRecipeCapability.CAP));
        inputTanks.addAll(getCapabilitiesFlat(IO.BOTH, FluidRecipeCapability.CAP));

        float bestMultiplier = 1.0f;

        for (IRecipeHandler<?> tank : inputTanks) {
            for (Object content : tank.getContents()) {
                if (!(content instanceof net.minecraftforge.fluids.FluidStack fluidStack))
                    continue;
                if (fluidStack.isEmpty())
                    continue;

                for (Map.Entry<TagKey<Fluid>, Float> entry : WATER_STEAM_MULTIPLIERS.entrySet()) {
                    if (fluidStack.getFluid().is(entry.getKey())) {
                        if (entry.getValue() > bestMultiplier) {
                            bestMultiplier = entry.getValue();
                        }
                    }
                }
            }
        }
        return bestMultiplier;
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

            tryDrainBoostFluid();

            // Update the recipe duration
            if (getOffsetTimer() % 20 == 0) {
                getRecipeLogic().refreshDurationForTemperature();
            }

            double maxDrainExact = (double) currentTemperature * throttle * TICKS_PER_STEAM_GENERATION /
                    (ConfigHolder.INSTANCE.machines.largeBoilers.steamPerWater * 100.0);
            int maxDrain = (int) Math.round(maxDrainExact);

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

                steamGenerated = (int) Math.round(
                        maxDrainExact * ((double) drained / Math.max(1, maxDrain))
                                * ConfigHolder.INSTANCE.machines.largeBoilers.steamPerWater
                                * waterMultiplier);

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
                    doExplosion(2f);
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

    @Nullable
    private BoosterFluid getBestAvailableBooster() {
        List<IRecipeHandler<?>> inputTanks = new ArrayList<>();
        inputTanks.addAll(getCapabilitiesFlat(IO.IN, FluidRecipeCapability.CAP));
        inputTanks.addAll(getCapabilitiesFlat(IO.BOTH, FluidRecipeCapability.CAP));

        BoosterFluid bestBooster = null;
        for (BoosterFluid booster : getCompatibleBoosters()) {
            var checkBoost = List.of(FluidIngredient.of(booster.fluid().get(), booster.fluidAmountMb()));
            for (IRecipeHandler<?> tank : inputTanks) {
                var result = (List<FluidIngredient>) tank.handleRecipe(IO.IN, null, checkBoost, true);
                if (result == null || result.isEmpty()) {
                    if (bestBooster == null || booster.temperatureBonus() > bestBooster.temperatureBonus()) {
                        bestBooster = booster;
                    }
                    break;
                }
            }
        }
        return bestBooster;
    }

    // Drain the best Booster available
    // If no Booster then reset the max temperature

    private int tryDrainBoostFluid() {
        BoosterFluid bestBooster = getBestAvailableBooster();
        if (bestBooster == null)
            return 0;

        List<IRecipeHandler<?>> inputTanks = new ArrayList<>();
        inputTanks.addAll(getCapabilitiesFlat(IO.IN, FluidRecipeCapability.CAP));
        inputTanks.addAll(getCapabilitiesFlat(IO.BOTH, FluidRecipeCapability.CAP));

        var drainBoost = List.of(FluidIngredient.of(bestBooster.fluid().get(), bestBooster.fluidAmountMb()));
        for (IRecipeHandler<?> tank : inputTanks) {
            drainBoost = (List<FluidIngredient>) tank.handleRecipe(IO.IN, null, drainBoost, false);
            if (drainBoost == null || drainBoost.isEmpty())
                return bestBooster.temperatureBonus();
        }
        return 0;
    }

    public int getEffectiveMaxTemperature() {
        BoosterFluid best = getBestAvailableBooster();
        return maxTemperature + (best != null ? best.temperatureBonus() : 0);
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
                    currentTemperature + 274, getEffectiveMaxTemperature() + 274));
            textList.add(Component.translatable("gtceu.multiblock.large_boiler.steam_output",
                    steamGenerated / TICKS_PER_STEAM_GENERATION));

            BoosterFluid activeBooster = getBestAvailableBooster();
            if (activeBooster != null) {
                textList.add(Component.translatable("tfg.multiblock.large_boiler.booster_active",
                        Component.translatable(activeBooster.translationKey()).withStyle(ChatFormatting.GREEN),
                        Component.literal("+" + activeBooster.temperatureBonus()).withStyle(ChatFormatting.GREEN)));
            } else {
                textList.add(Component.translatable("tfg.multiblock.large_boiler.booster_none")
                        .withStyle(ChatFormatting.GRAY));
            }

            int efficiencyPercent = (int) Math.round(100 - ((1.0 - getRecipeLogic().getTemperatureMultiplier()) * 100));
            textList.add(Component.translatable("tfg.multiblock.large_boiler.fuel_efficiency",
                    ChatFormatting.YELLOW.toString() + efficiencyPercent + "%"));

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

        public double getTemperatureMultiplier() {
            TFGLargeBoilerMachine boiler = (TFGLargeBoilerMachine) machine;
            int current = boiler.getCurrentTemperature();
            final int THRESHOLD = 800;
            final double REDUCTION_PER_100_DEGREES = 0.05; // 5% every 100C over 800

            if (current <= THRESHOLD)
                return 1.0;

            double degreesAboveThreshold = current - THRESHOLD;
            double reduction = (degreesAboveThreshold / 100.0) * REDUCTION_PER_100_DEGREES;

            // Minimum
            return Math.max(0.1, 1.0 - reduction);
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

        // Modify the recipe duration depending of the booster and the throttle

        public void refreshDurationForTemperature() {
            if (lastRecipe != null) {
                double tempMultiplier = getTemperatureMultiplier();
                int targetDuration = (int) Math.round(lastRecipe.duration / (currentThrottle / 100.0) * tempMultiplier);
                if (Math.abs(duration - targetDuration) > 2) {
                    double progressRatio = (double) progress / Math.max(1, duration);
                    duration = targetDuration;
                    progress = (int) Math.round(progressRatio * duration);
                }
            }
        }
    }
}
