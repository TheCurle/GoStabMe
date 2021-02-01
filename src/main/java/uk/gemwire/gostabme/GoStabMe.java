package uk.gemwire.gostabme;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.AbstractClientPlayerEntity;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.entity.model.PlayerModel;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Food;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.INBT;
import net.minecraft.util.*;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Prototype for the Stabbed player capability.
 * A boolean that represents whether or not a knife should be rendered
 * on this player.
 */
interface IStabbed {
    void setStabbed(boolean stabbed);
    boolean isStabbed();
}

/**
 * A wrapper for ICapabilitySerializable that allows us to define an Invalidate method.
 *
 * @param <T> A subclass of NBT to store
 */
interface IStabbedCodec<T extends INBT> extends ICapabilitySerializable<T> { void invalidate(); }

/**
 * @author Curle
 * @date 01/02/2021
 */
@Mod(GoStabMe.ID)
public class GoStabMe {
    /**
     * The MODID - GoStabMe
     */
    public static final String ID = "gostabme";

    /************************************************************************************************
     *                                    R E G I S T R Y                                           *
     ************************************************************************************************/

    /**
     * The ITEMS registration point.
     */
    public static final DeferredRegister ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);

    /**
     * The knife for stabbing on the go.
     * On right click, takes health (2 hearts) from you.
     * On left click, if you hit AshersLab, it buries the knife in his skull for the rest of eternity.
     * The item goes to the COMBAT tab.
     */
    public static final RegistryObject<Item> KNIFE = ITEMS.register("knife", () -> new Item(new Item.Properties().group(ItemGroup.COMBAT)) {
        @Override
        public ActionResult<ItemStack> onItemRightClick(World worldIn, PlayerEntity playerIn, Hand handIn) {
            playerIn.attackEntityFrom(DamageSource.CACTUS, 4);
            return ActionResult.resultSuccess(ItemStack.EMPTY);
        }
    });

    /**
     * The Food implementation for the Noodles.
     * Heals 2 hunger bars and 3 bars of saturation.
     */
    public static final Food NOODLES_FOOD = new Food.Builder().hunger(4).saturation(6).build();

    /**
     * The noodles for eating on the go.
     * Can be found in dungeon chests.
     * Eat it to gain food points.
     */
    public static final RegistryObject<Item> NOODLES = ITEMS.register("noodles", () -> new Item(new Item.Properties().group(ItemGroup.FOOD).food(NOODLES_FOOD)));

    /************************************************************************************************
     *                                      E V E N T S                                             *
     ************************************************************************************************/

    public GoStabMe() {
        /**
         * Register the ITEMS registry for registering registration registerily..
         */
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());

        /**
         * On the client, set up the knife model and prepare the render layers.
         */
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ModelLoader.addSpecialModel(new ResourceLocation(ID, "block/headknife")));
        DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> this::handleKnife);

        /**
         * CAPABILIIES
         * I use an anonymous class to handle the implementation of the Capability IStorage.
         * This should be another class.
         *
         * It merely saves the boolean into the tag named "stabbed". Nothing fancy.
         */
        CapabilityManager.INSTANCE.register(IStabbed.class, new Capability.IStorage<IStabbed>() {
            @Nullable
            @Override
            public INBT writeNBT(Capability<IStabbed> capability, IStabbed instance, Direction side) {
                CompoundNBT tag = new CompoundNBT();
                tag.putBoolean("stabbed", instance.isStabbed());
                return tag;
            }

            @Override
            public void readNBT(Capability<IStabbed> capability, IStabbed instance, Direction side, INBT nbt) {
                instance.setStabbed(((CompoundNBT)nbt).getBoolean("stabbed"));
            }
        }, StabbedProvider::new);

        /**
         * Add an event handler for the AttachCapabilities event,
         *
         * instantiate a StabbedCodec with our desired properties - the provider and the LazyOptional,
         * define the functions to save and retrieve the data we want,
         *
         * and save these with the event.addCapability & event.addListener functions.
         */
        MinecraftForge.EVENT_BUS.<AttachCapabilitiesEvent<Entity>>addListener(event -> {
            if(event.getObject() instanceof PlayerEntity) {
                IStabbedCodec<CompoundNBT> stabbedCodec = new IStabbedCodec<CompoundNBT>() {

                    private final StabbedProvider stabbed = new StabbedProvider();
                    private final LazyOptional<IStabbed> optionallyStabbed = LazyOptional.of(() -> stabbed);

                    public void invalidate() {
                        optionallyStabbed.invalidate();
                    }

                    @Nonnull
                    @Override
                    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
                        return cap == STABBED ? optionallyStabbed.cast() : LazyOptional.empty();
                    }

                    @Override
                    public CompoundNBT serializeNBT() {
                        return STABBED == null ? new CompoundNBT() :
                                (CompoundNBT) STABBED.writeNBT(stabbed, null);

                    }

                    @Override
                    public void deserializeNBT(CompoundNBT nbt) {
                        if(STABBED != null)
                            STABBED.readNBT(stabbed, null, nbt);
                    }
                };

                event.addCapability(new ResourceLocation(ID, "stabbed"), stabbedCodec);
                event.addListener(stabbedCodec::invalidate);
            }
        });

    }

    /************************************************************************************************
     *                                C A P A B I L I T I E S                                       *
     ************************************************************************************************/

    /**
     * The capability object itself to reference against.
     */
    @CapabilityInject(IStabbed.class)
    public static Capability<IStabbed> STABBED = null;

    /************************************************************************************************
     *                                    R E N D E R E R                                           *
     ************************************************************************************************/

    /**
     * The model of the knife. To be rendered on top of someone's head.
     */
    @OnlyIn(Dist.CLIENT)
    private static IBakedModel BAKED_KNIFE;

    /**
     * Handle setting up the rendering.
     */
    @OnlyIn(Dist.CLIENT)
    public boolean handleKnife() {
        /**
         * Add an event handler for the ModelBakeEvent - retrieve the baked model for the knife from the registry once
         * it's available.
         */
        FMLJavaModLoadingContext.get().getModEventBus().<ModelBakeEvent>addListener(event -> {
            BAKED_KNIFE = event.getModelRegistry().get(new ResourceLocation(ID, "block/headknife"));
        });

        Minecraft mc = Minecraft.getInstance();

        /**
         * If the player is AshersLab..
         */
        if(mc.player.getUniqueID() == UUID.fromString("9186b2cf-ae1f-4d69-aa67-81a7cb69008d")) {
            EntityRendererManager erm = mc.getRenderManager();
            /**
             * Prepare a LayerRenderer that attaches a block to the top of his head..
             */
            LayerRenderer<AbstractClientPlayerEntity, PlayerModel<AbstractClientPlayerEntity>> renderer = new LayerRenderer<AbstractClientPlayerEntity, PlayerModel<AbstractClientPlayerEntity>>(mc.getRenderManager().playerRenderer) {
                @Override
                public void render(MatrixStack stack, IRenderTypeBuffer buffer, int packedLightIn, AbstractClientPlayerEntity entitylivingbaseIn, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
                    stack.push();
                    stack.translate(0, -0.015f, 0);
                    if (!entitylivingbaseIn.inventory.armorInventory.get(3).isEmpty()) stack.translate(0, -0.02f, 0);
                    if (entitylivingbaseIn.isCrouching()) stack.translate(0, 0.27f, 0);
                    stack.rotate(Vector3f.YP.rotationDegrees(90));
                    stack.rotate(Vector3f.XP.rotationDegrees(180));
                    stack.rotate(Vector3f.YN.rotationDegrees(netHeadYaw));
                    stack.rotate(Vector3f.ZN.rotationDegrees(headPitch));
                    Minecraft.getInstance().getTextureManager().bindTexture(AtlasTexture.LOCATION_BLOCKS_TEXTURE);
                    if (BAKED_KNIFE != null) {
                        mc.getBlockRendererDispatcher().getBlockModelRenderer().renderModelBrightnessColor(stack.getLast(), buffer.getBuffer(RenderType.getCutout()), null, BAKED_KNIFE, 1f, 1f, 1f, packedLightIn, OverlayTexture.NO_OVERLAY);
                    }
                    stack.pop();
                }
            };

            /**
             * And add it to his model.
             */
            erm.getSkinMap().get("default").addLayer(renderer);
            erm.getSkinMap().get("slim").addLayer(renderer);
        }

        return false;
    }
}


