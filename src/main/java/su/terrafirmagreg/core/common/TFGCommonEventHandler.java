package su.terrafirmagreg.core.common;

import static appeng.api.upgrades.Upgrades.add;

import java.util.Objects;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialRegistryEvent;
import com.gregtechceu.gtceu.api.data.chemical.material.event.PostMaterialEvent;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.PacketDistributor;

import appeng.core.definitions.AEItems;
import appeng.core.localization.GuiText;
import de.mari_023.ae2wtlib.AE2wtlib;

import su.terrafirmagreg.core.TFGCore;
import su.terrafirmagreg.core.common.data.TFGItems;
import su.terrafirmagreg.core.common.data.capabilities.LargeEggCapability;
import su.terrafirmagreg.core.common.data.capabilities.LargeEggHandler;
import su.terrafirmagreg.core.common.data.utils.CustomSpawnHelper;
import su.terrafirmagreg.core.common.data.utils.CustomSpawnSaveHandler;
import su.terrafirmagreg.core.compat.grappling_hook.GrapplehookCompat;
import su.terrafirmagreg.core.compat.gtceu.materials.TFGMaterialHandler;
import su.terrafirmagreg.core.compat.tfcambiental.TFCAmbientalCompat;
import su.terrafirmagreg.core.config.TFGConfig;
import su.terrafirmagreg.core.network.TFGNetworkHandler;
import su.terrafirmagreg.core.network.packet.FuelSyncPacket;
import su.terrafirmagreg.core.utils.TFGModsResolver;

public final class TFGCommonEventHandler {

    @SuppressWarnings("removal")
    public static void init() {
        final IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        final IEventBus otherBus = MinecraftForge.EVENT_BUS;

        otherBus.addGenericListener(ItemStack.class, TFGCommonEventHandler::attachItemCapabilities);
        otherBus.addListener(TFGCommonEventHandler::onPlayerLogin);
        otherBus.addListener(TFGCommonEventHandler::onPlayerRespawn);
        otherBus.addListener(TFGCommonEventHandler::onLevelLoad);

        bus.addListener(TFGConfig::onLoad);
        bus.addListener(TFGCommonEventHandler::onCommonSetup);
        bus.addListener(TFGCommonEventHandler::onRegisterMaterialRegistry);
        bus.addListener(TFGCommonEventHandler::onPostRegisterMaterials);
        bus.addListener(TFGInteractionManager::init);
    }

    private static void onRegisterMaterialRegistry(final MaterialRegistryEvent event) {
        TFGCore.MATERIAL_REGISTRY = GTCEuAPI.materialManager.createRegistry(TFGCore.MOD_ID);
    }

    private static void onPostRegisterMaterials(final PostMaterialEvent event) {
        TFGHelpers.isMaterialRegistrationFinished = true;
        TFGMaterialHandler.postInit();
    }

    private static void attachItemCapabilities(AttachCapabilitiesEvent<ItemStack> event) {
        ItemStack stack = event.getObject();
        if (!stack.isEmpty()) {
            if (stack.getItem() == TFGItems.SNIFFER_EGG.get() || stack.getItem() == TFGItems.WRAPTOR_EGG.get()) {
                event.addCapability(LargeEggCapability.KEY, new LargeEggHandler(stack));
            }
        }
    }

    private static void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (TFGConfig.COMMON.ENABLE_TFC_AMBIENTAL_COMPAT.get() && TFGModsResolver.TFC_AMBIENTAL.isLoaded())
                TFCAmbientalCompat.register();
            if (TFGModsResolver.GRAPPLEMOD.isLoaded())
                GrapplehookCompat.init();
            addUpgrades(AEItems.WIRELESS_TERMINAL);
            addUpgrades(AEItems.WIRELESS_CRAFTING_TERMINAL);
            addUpgrades(AE2wtlib.PATTERN_ENCODING_TERMINAL);
            addUpgrades(AE2wtlib.PATTERN_ACCESS_TERMINAL);
            addUpgrades(AE2wtlib.UNIVERSAL_TERMINAL);
        });
    }

    private static void addUpgrades(ItemLike item) {
        add(TFGItems.WIRELESS_CARD.get(), item, 1, GuiText.WirelessTerminals.getTranslationKey());
    }

    /**
     * Send the blaze burner liquid fuel map to send to the client and populate emi.
     */
    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TFGNetworkHandler.INSTANCE.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new FuelSyncPacket(FuelSyncPacket.capturedJsonData));
        }
    }

    private static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServer server = player.getServer();
            GlobalPos worldSpawn = CustomSpawnSaveHandler.getSpawnPos(Objects.requireNonNull(server).overworld());

            if ((worldSpawn.dimension().equals(ServerLevel.OVERWORLD) || player.getRespawnPosition() != null))
                return;
            CustomSpawnHelper.respawnTeleporter(player, server.getLevel(worldSpawn.dimension()), worldSpawn);
        }
    }

    private static void onLevelLoad(LevelEvent.Load event) {
        LevelAccessor level = event.getLevel();
        if (level instanceof ClientLevel)
            return;

        MinecraftServer server = Objects.requireNonNull(level.getServer());
        if (server.overworld() != level) {
            GlobalPos spawnPos = CustomSpawnSaveHandler.getSpawnPos(server.overworld());

            var targetLevel = server.getLevel(spawnPos.dimension());
            if (targetLevel == level) {

                RandomSource random = new XoroshiroRandomSource(targetLevel.getSeed());

                BlockPos validSpawn = null;

                int count = 0;
                while (Objects.isNull(validSpawn)) {
                    ChunkPos chunkPos = new ChunkPos(random.nextInt(32), random.nextInt(32));
                    System.out.println("ChunkPos: " + chunkPos);
                    var testPos = chunkPos.getMiddleBlockPosition(128);

                    int buildHeightLimit = Math.min(targetLevel.getMaxBuildHeight(), targetLevel.getMinBuildHeight() + targetLevel.getLogicalHeight()) - 1;
                    BlockPos.MutableBlockPos mutableTestPos = testPos.mutable();

                    System.out.println(targetLevel.getBlockState(mutableTestPos).getBlock().toString() + targetLevel.getBlockState(mutableTestPos.above()).getBlock());

                    ChunkAccess chunk = targetLevel.getChunk(mutableTestPos.immutable());

                    for (int testY = buildHeightLimit; testY > 0; testY--) {
                        mutableTestPos.setY(testY);

                        /*System.out.println("\tBlocks");
                        System.out.println("spawn point: " + mutableTestPos);*/
                        var blockA = chunk.getBlockState(mutableTestPos.above());
                        var blockB = chunk.getBlockState(mutableTestPos);
                        var blockC = chunk.getBlockState(mutableTestPos.below());

                        /*System.out.println(blockA);
                        System.out.print(blockA.isAir());
                        System.out.println(blockB);
                        System.out.print(!blockB.isCollisionShapeFullBlock(targetLevel, mutableTestPos.immutable()));
                        System.out.println(blockC);
                        System.out.print(blockC.isCollisionShapeFullBlock(targetLevel, mutableTestPos.immutable()));
                        
                         */

                        if (blockA.isAir() && !blockB.isCollisionShapeFullBlock(targetLevel, mutableTestPos.immutable())) {
                            if (blockC.isCollisionShapeFullBlock(targetLevel, mutableTestPos.immutable())) {
                                ResourceKey<Biome> biomeKey = targetLevel.getBiome(mutableTestPos).unwrapKey().orElse(null);

                                //System.out.println(biomeKey);
                                if (Objects.nonNull(biomeKey) && biomeKey.location().equals(ResourceLocation.fromNamespaceAndPath(TFGCore.MOD_ID, "nether/lush_hollow"))) {
                                    validSpawn = mutableTestPos.immutable();
                                }
                                //If it is not in the right biome it is unlikely that going down more will be in the valid biome
                                break;
                            }
                        }

                    }

                    if (count >= 50) {
                        validSpawn = BlockPos.ZERO;
                        System.out.println("No Valid Spawn :(");
                    }

                    count++;
                }

                System.out.println("Found valid spawn point: " + validSpawn);
                CustomSpawnSaveHandler.setSpawnPos(server.overworld(), GlobalPos.of(spawnPos.dimension(), validSpawn));
            }
        }
    }
}
