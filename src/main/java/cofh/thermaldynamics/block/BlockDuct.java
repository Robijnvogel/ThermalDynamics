package cofh.thermaldynamics.block;

import codechicken.lib.block.property.PropertyInteger;
import codechicken.lib.model.DummyBakedModel;
import codechicken.lib.model.ModelRegistryHelper;
import codechicken.lib.model.bakery.CCBakeryModel;
import codechicken.lib.model.bakery.IBakeryProvider;
import codechicken.lib.model.bakery.ModelBakery;
import codechicken.lib.model.bakery.generation.IBakery;
import codechicken.lib.raytracer.IndexedCuboid6;
import codechicken.lib.raytracer.RayTracer;
import codechicken.lib.render.particle.CustomParticleHandler;
import codechicken.lib.texture.TextureUtils;
import codechicken.lib.vec.Cuboid6;
import cofh.api.block.IConfigGui;
import cofh.core.init.CoreProps;
import cofh.core.network.PacketHandler;
import cofh.core.render.IBlockAppearance;
import cofh.core.render.IModelRegister;
import cofh.thermaldynamics.ThermalDynamics;
import cofh.thermaldynamics.duct.Attachment;
import cofh.thermaldynamics.duct.Duct;
import cofh.thermaldynamics.duct.DuctItem;
import cofh.thermaldynamics.duct.TDDucts;
import cofh.thermaldynamics.duct.attachments.cover.Cover;
import cofh.thermaldynamics.duct.entity.EntityTransport;
import cofh.thermaldynamics.duct.entity.TransportHandler;
import cofh.thermaldynamics.duct.fluid.PacketFluid;
import cofh.thermaldynamics.duct.tiles.*;
import cofh.thermaldynamics.init.TDProps;
import cofh.thermaldynamics.proxy.ProxyClient;
import cofh.thermaldynamics.render.DuctModelBakery;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMap;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Optional;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import team.chisel.ctm.api.IFacade;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

@Optional.Interface (iface = "team.chisel.ctm.api.IFacade", modid = "ctm-api")
public class BlockDuct extends BlockTDBase implements IBlockAppearance, IConfigGui, IModelRegister, IBakeryProvider, IFacade {

	public static final PropertyInteger META = new PropertyInteger("meta", 15);
	public static final ThreadLocal<BlockPos> IGNORE_RAY_TRACE = new ThreadLocal<>();
	public int offset;

	public BlockDuct(int offset) {

		super(Material.GLASS);

		setUnlocalizedName("duct");

		setHardness(1.0F);
		setResistance(10.0F);
		setDefaultState(getBlockState().getBaseState().withProperty(META, 0));

		this.offset = offset * 16;
	}

	@Override
	protected BlockStateContainer createBlockState() {

		return new BlockStateContainer(this, META);
	}

	@Override
	public void getSubBlocks(CreativeTabs tab, NonNullList<ItemStack> items) {

		for (int i = 0; i < 16; i++) {
			if (TDDucts.isValid(i + offset)) {
				Duct duct = TDDucts.getDuct(i + offset);
				if (duct instanceof DuctItem) {
					items.add(((DuctItem) duct).getVacuumItemStack());
					items.add(((DuctItem) duct).getDenseItemStack());
				}
				items.add(duct.itemStack.copy());
			}
		}
	}

	/* TYPE METHODS */
	@Override
	public IBlockState getStateFromMeta(int meta) {

		return getDefaultState().withProperty(META, meta);
	}

	@Override
	public int getMetaFromState(IBlockState state) {

		return state.getValue(META);
	}

	@Override
	public int damageDropped(IBlockState state) {

		return state.getValue(META);
	}

	@Override
	public TileEntity createTileEntity(World world, IBlockState state) {

		int metadata = state.getBlock().getMetaFromState(state);

		Duct duct = TDDucts.getType(metadata + offset);

		return duct.factory.createTileEntity(duct, world);
	}

	/* BLOCK METHODS */
	@Override
	public void addCollisionBoxToList(IBlockState state, World world, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, @Nullable Entity entity, boolean b) {

		if (entity instanceof EntityTransport) {
			return;
		}
		AxisAlignedBB bb = getBoundingBox(state, world, pos);
		addCollisionBoxToList(pos, entityBox, collidingBoxes, bb);
		TileGrid theTile = (TileGrid) world.getTileEntity(pos);

		if (theTile != null) {
			for (byte i = 0; i < 6; i++) {
				Attachment attachment = theTile.getAttachment(i);
				if (attachment != null) {
					attachment.addCollisionBoxesToList(entityBox, collidingBoxes, entity);
				}
				Cover cover = theTile.getCover(i);
				if (cover != null) {
					cover.addCollisionBoxesToList(entityBox, collidingBoxes, entity);
				}
			}

			float min = getSize(state);
			float max = 1 - min;
			boolean yMinConnected = theTile.getVisualConnectionType(0).renderDuct;;
			boolean yMaxConnected = theTile.getVisualConnectionType(1).renderDuct;
			boolean zMinConnected = theTile.getVisualConnectionType(2).renderDuct;
			boolean zMaxConnected = theTile.getVisualConnectionType(3).renderDuct;
			boolean xMinConnected = theTile.getVisualConnectionType(4).renderDuct;
			boolean xMaxConnected = theTile.getVisualConnectionType(5).renderDuct;

			if (xMinConnected || xMaxConnected) {
				bb = new AxisAlignedBB(xMinConnected ? 0 : min, min, min, xMaxConnected ? 1 : max, max, max);
				addCollisionBoxToList(pos, entityBox, collidingBoxes, bb);
			}
			if (yMinConnected || yMaxConnected) {
				bb = new AxisAlignedBB(min, yMinConnected ? 0 : min, min, max, yMaxConnected ? 1 : max, max);
				addCollisionBoxToList(pos, entityBox, collidingBoxes, bb);
			}
			if (zMinConnected || zMaxConnected) {
				bb = new AxisAlignedBB(min, min, zMinConnected ? 0 : min, max, max, zMaxConnected ? 1 : max);
				addCollisionBoxToList(pos, entityBox, collidingBoxes, bb);
			}

			//Step-up-assist for ducts that are just a block under the floor todo make the duplicate code here and in onEntityWalk# into 1 reusable method
			if (min < 0.1 || //it has to be no large duct, like a viaduct
					!TDProps.stepUpIsEnabled || //config option needs to be enabled
					!(TDProps.stepUpMethod == 0) || //potion method needs to be chosen
					world.isOutsideBuildHeight(pos.up()) || //duct needs to be at least one below max build height, otherwise this feature doesn't help anyone
					!(world.getBlockState(pos.up()).getCollisionBoundingBox(world, pos.up()) == NULL_AABB) ||
					(!world.isOutsideBuildHeight(pos.up(2)) && !(world.getBlockState(pos.up(2)).getCollisionBoundingBox(world, pos.up(2)) == NULL_AABB)) || //2 blocks above the duct need to be unobstructed
					(!world.isOutsideBuildHeight(pos.up(3)) && world.getBlockState(pos.up(3)).isFullBlock())) { //3rd block above the duct can't be a full block
				return;
			}

			int ductX = pos.getX();
			int ductY = pos.getY();
			int ductZ = pos.getZ();
			for (int i = 0; i < 2; i++) { //0 and 1
				for (int j = 0; j < 2; j++) { //0 and 1
					int relativeX = i == 0 ? 0 : j == 0 ? -1 : 1; //0, 0, -1 and 1
					int relativeZ = i == 1 ? 0 : j == 0 ? -1 : 1; //-1, 1, 0 and 0
					BlockPos posNeighbourUp = new BlockPos(ductX + relativeX, ductY + 1, ductZ + relativeZ);
					if (!world.getBlockState(posNeighbourUp).isFullBlock() || //block diagonally above the duct needs to be a full block
							(!world.isOutsideBuildHeight(pos.up(2)) && world.getBlockState(posNeighbourUp.up()).isFullBlock()) ||
							(!world.isOutsideBuildHeight(pos.up(3)) && world.getBlockState(posNeighbourUp.up(2)).isFullBlock())) { //2 blocks above that full block need to be non-full
						continue;
					}

					double xMin = relativeX == 0 ? (xMinConnected ? 0 : min) : relativeX == 1 ? 0.95 : 0;
					double xMax = relativeX == 0 ? (xMaxConnected ? 1 : max) : relativeX == 1 ? 1 : 0.05;
					double zMin = relativeZ == 0 ? (zMinConnected ? 0 : min) : relativeZ == 1 ? 0.95 : 0;
					double zMax = relativeZ == 0 ? (zMaxConnected ? 1 : max) : relativeZ == 1 ? 1 : 0.05;

					bb = new AxisAlignedBB(xMin, 0.95, zMin, xMax, 1.0, zMax);
					addCollisionBoxToList(pos, entityBox, collidingBoxes, bb);
				}
			}
		}
	}

	@Override
	public void onEntityWalk(World world, BlockPos pos, Entity entity) {
		super.onEntityWalk(world, pos, entity);

		//Step-up-assist for ducts that are just a block under the floor
		if (getSize(world.getBlockState(pos)) < 0.1 || //it has to be no large duct, like a viaduct
				!TDProps.stepUpIsEnabled || //config option needs to be enabled
				!(TDProps.stepUpMethod == 1) || //potion method needs to be chosen
				world.isOutsideBuildHeight(pos.up()) || //duct needs to be at least one below max build height, otherwise this feature doesn't help anyone
				!(entity instanceof EntityLivingBase) ||
				!(world.getBlockState(pos.up()).getCollisionBoundingBox(world, pos.up()) == NULL_AABB) ||
				(!world.isOutsideBuildHeight(pos.up(2)) && !(world.getBlockState(pos.up(2)).getCollisionBoundingBox(world, pos.up(2)) == NULL_AABB)) || //2 blocks above the duct need to be unobstructed
				(!world.isOutsideBuildHeight(pos.up(3)) && world.getBlockState(pos.up(3)).isFullBlock())) { //3rd block above the duct can't be a full block
			return;
		}

		int ductX = pos.getX();
		int ductY = pos.getY();
		int ductZ = pos.getZ();
		for (int i = 0; i < 2; i++) { //0 and 1
			for (int j = 0; j < 2; j++) { //0 and 1
				int relativeX = i == 0 ? 0 : j == 0 ? -1 : 1; //0, 0, -1 and 1
				int relativeZ = i == 1 ? 0 : j == 0 ? -1 : 1; //-1, 1, 0 and 0
				BlockPos posNeighbourUp = new BlockPos(ductX + relativeX, ductY + 1, ductZ + relativeZ);
				if (!world.getBlockState(posNeighbourUp).isFullBlock() || //block diagonally above the duct needs to be a full block
						(!world.isOutsideBuildHeight(pos.up(2)) && world.getBlockState(posNeighbourUp.up()).isFullBlock()) ||
						(!world.isOutsideBuildHeight(pos.up(3)) && world.getBlockState(posNeighbourUp.up(2)).isFullBlock())) { //2 blocks above that full block need to be non-full
					continue;
				}

				EntityLivingBase entityLiving = (EntityLivingBase )entity;
				Potion potion = Potion.getPotionById(8); //todo get the jump-boost/leaping potion by resource location instead
				if (potion == null) {
					ThermalDynamics.LOG.error("potion for id 8 not found");
				} else {
					//entityLiving.stepHeight = 1.52F; //this doesn't work very well imo, as you'd have to manually reset it somehow and this also makes you automatically escape the shallow hole as you walk into the edge of it while you may not want to.
					entityLiving.addPotionEffect(new PotionEffect(potion, 10)); //duration is in ticks, I guess?
				}
				return;
			}
		}
	}

	@Override
	public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase living, ItemStack stack) {

		super.onBlockPlacedBy(world, pos, state, living, stack);

		TileEntity tile = world.getTileEntity(pos);

		if (tile instanceof TileGrid) {
			((TileGrid) tile).onPlacedBy(living, stack);
		}
	}

	@Override
	public boolean canConnectRedstone(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {

		if (side == null) {
			return false;
		}
		int s;
		if (side == EnumFacing.DOWN) {
			s = 2;
		} else if (side == EnumFacing.UP) {
			s = 5;
		} else if (side == EnumFacing.NORTH) {
			s = 3;
		} else {
			s = 4;
		}
		TileGrid theTile = (TileGrid) world.getTileEntity(pos);
		return theTile != null && theTile.getAttachment(s ^ 1) != null && theTile.getAttachment(s ^ 1).shouldRSConnect();
	}

	@Override
	public boolean hasTileEntity(IBlockState state) {

		return TDDucts.isValid(getMetaFromState(state) + offset);
	}

	@Override
	public boolean isFullCube(IBlockState state) {

		return false;
	}

	@Override
	public boolean isNormalCube(IBlockState state, IBlockAccess world, BlockPos pos) {

		return false;
	}

	@Override
	public boolean isOpaqueCube(IBlockState state) {

		return false;
	}

	@Override
	public boolean isSideSolid(IBlockState base_state, IBlockAccess world, BlockPos pos, EnumFacing side) {

		TileGrid theTile = (TileGrid) world.getTileEntity(pos);
		return (theTile != null && (theTile.getCover(side.ordinal()) != null || theTile.getAttachment(side.ordinal()) != null && theTile.getAttachment(side.ordinal()).makesSideSolid())) || super.isSideSolid(base_state, world, pos, side);
	}

	//	@Override
	//	public int getStrongPower(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {
	//
	//		return 0;
	//	}

	@Override
	public int getWeakPower(IBlockState blockState, IBlockAccess world, BlockPos pos, EnumFacing side) {

		TileGrid theTile = (TileGrid) world.getTileEntity(pos);
		if (theTile != null && theTile.getAttachment(side.ordinal() ^ 1) != null) {
			return theTile.getAttachment(side.ordinal() ^ 1).getRSOutput();
		}
		return 0;
	}

	@Override
	public boolean getWeakChanges(IBlockAccess world, BlockPos pos) {

		return true;
	}

	@Override
	public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {

		float min = getSize(state);
		float max = 1 - min;

		return new AxisAlignedBB(min, min, min, max, max, max);
	}

	@Override
	public ItemStack getPickBlock(IBlockState state, RayTraceResult target, World world, BlockPos pos, EntityPlayer player) {

		if (target.subHit >= 14 && target.subHit < 20) {
			TileGrid tileEntity = (TileGrid) world.getTileEntity(pos);
			Attachment attachment = tileEntity.getAttachment(target.subHit - 14);
			if (attachment != null) {
				ItemStack pickBlock = attachment.getPickBlock();
				if (pickBlock != null) {
					return pickBlock;
				}
			}
		}
		if (target.subHit >= 20 && target.subHit < 26) {
			TileGrid tileEntity = (TileGrid) world.getTileEntity(pos);
			Cover cover = tileEntity.getCover(target.subHit - 20);
			if (cover != null) {
				ItemStack pickBlock = cover.getPickBlock();
				if (pickBlock != null) {
					return pickBlock;
				}
			}
		}
		return super.getPickBlock(state, target, world, pos, player);
	}

	@Override
	public RayTraceResult collisionRayTrace(IBlockState blockState, World world, BlockPos pos, Vec3d start, Vec3d end) {

		BlockPos ignore_pos = IGNORE_RAY_TRACE.get();
		if (ignore_pos != null && ignore_pos.equals(pos)) {
			return null;
		}
		TileEntity tile = world.getTileEntity(pos);

		if (tile instanceof TileGrid) {
			List<IndexedCuboid6> cuboids = new LinkedList<>();
			((TileGrid) tile).addTraceableCuboids(cuboids);
			return RayTracer.rayTraceCuboidsClosest(start, end, pos, cuboids);
		}
		return null;
	}

	@Override
	public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing side) {

		return BlockFaceShape.UNDEFINED;
	}

	/* RENDERING METHODS */
	@Override
	@SideOnly (Side.CLIENT)
	public void randomDisplayTick(IBlockState state, World world, BlockPos pos, Random rand) {

		TileEntity tileEntity = world.getTileEntity(pos);
		if (tileEntity instanceof TileGrid) {
			((TileGrid) tileEntity).randomDisplayTick();
		}
	}

	@Override
	@SideOnly (Side.CLIENT)
	public boolean canRenderInLayer(IBlockState state, BlockRenderLayer layer) {

		return true;
	}

	@Override
	@SideOnly (Side.CLIENT)
	public EnumBlockRenderType getRenderType(IBlockState state) {

		return ProxyClient.renderType;
	}

	@Override
	@SideOnly (Side.CLIENT)
	public boolean addDestroyEffects(World world, BlockPos pos, ParticleManager manager) {

		TileEntity tileEntity = world.getTileEntity(pos);
		if (tileEntity instanceof TileGrid) {
			IBlockState state = world.getBlockState(pos);
			TileGrid gridTile = ((TileGrid) tileEntity);
			Duct duct = gridTile.getDuctType();

			float min = getSize(state);
			float max = 1 - min;

			Cuboid6 bb = new Cuboid6(min, min, min, max, max, max).add(pos);

			CustomParticleHandler.addBlockDestroyEffects(world, bb, getAllParticleIcons(duct), manager);
			return true;
		}
		return false;
	}

	@Override
	@SideOnly (Side.CLIENT)
	public boolean addHitEffects(IBlockState state, World world, RayTraceResult target, ParticleManager manager) {

		if (target != null && target.typeOfHit == RayTraceResult.Type.BLOCK) {
			TileEntity tileEntity = world.getTileEntity(target.getBlockPos());
			if (tileEntity instanceof TileGrid) {
				TileGrid gridTile = ((TileGrid) tileEntity);
				Duct duct = gridTile.getDuctType();

				float min = getSize(state);
				float max = 1 - min;

				Cuboid6 bb = new Cuboid6(min, min, min, max, max, max).add(target.getBlockPos());

				TextureAtlasSprite[] possiblities = getAllParticleIcons(duct);

				CustomParticleHandler.addBlockHitEffects(world, bb, target.sideHit, possiblities[world.rand.nextInt(possiblities.length)], manager);
				return true;
			}
		}
		return false;
	}

	@SideOnly (Side.CLIENT)
	public static TextureAtlasSprite[] getAllParticleIcons(Duct duct) {

		Set<TextureAtlasSprite> sprites = new HashSet<>();
		if (duct.iconBaseTexture != null) {
			sprites.add(duct.iconBaseTexture);
		}
		if (duct.iconConnectionTexture != null) {
			sprites.add(duct.iconConnectionTexture);
		}
		if (duct.iconFluidTexture != null) {
			sprites.add(duct.iconFluidTexture);
		}
		if (duct.iconFrameTexture != null) {
			sprites.add(duct.iconFrameTexture);
		}
		if (duct.iconFrameBandTexture != null) {
			sprites.add(duct.iconFrameBandTexture);
		}
		if (duct.iconFrameFluidTexture != null) {
			sprites.add(duct.iconFrameFluidTexture);
		}

		if (sprites.isEmpty()) {
			sprites.add(TextureUtils.getMissingSprite());
		}
		return sprites.toArray(new TextureAtlasSprite[0]);
	}

	/* IBlockAppearance */
	@Override
	public IBlockState getVisualState(IBlockAccess world, BlockPos pos, EnumFacing side) {

		if (side == null) {
			return world.getBlockState(pos);
		}
		TileEntity tileEntity = world.getTileEntity(pos);

		if (tileEntity instanceof TileGrid) {
			Cover cover = ((TileGrid) tileEntity).getCover(side.ordinal());
			if (cover != null) {
				return cover.state;
			}
		}
		return world.getBlockState(pos);
	}

	@Override
	public boolean supportsVisualConnections() {

		return true;
	}

	/* IFacade */
	@Nonnull
	@Override
	@Optional.Method (modid = "ctm-api")
	public IBlockState getFacade(@Nonnull IBlockAccess world, @Nonnull BlockPos pos, @Nullable EnumFacing side) {

		return getVisualState(world, pos, side);
	}

	/* IConfigGui */
	@Override
	public boolean openConfigGui(World world, BlockPos pos, EnumFacing side, EntityPlayer player) {

		TileGrid tile = (TileGrid) world.getTileEntity(pos);

		if (tile instanceof IConfigGui) {
			return ((IConfigGui) tile).openConfigGui(world, pos, side, player);
		} else if (tile != null) {
			int subHit = side.ordinal();

			if (world instanceof World) {
				RayTraceResult rayTrace = RayTracer.retraceBlock((World) world, player, pos);
				if (rayTrace == null) {
					return false;
				}
				if (subHit > 13 && subHit < 20) {
					subHit = rayTrace.subHit - 14;
				}
			}
			if (subHit > 13 && subHit < 20) {
				Attachment attachment = tile.getAttachment(subHit - 14);

				if (attachment instanceof IConfigGui) {
					return ((IConfigGui) attachment).openConfigGui(world, pos, side, player);
				}
			}
			for (DuctUnit ductUnit : tile.getDuctUnits()) {
				if (ductUnit instanceof IConfigGui) {
					return ((IConfigGui) ductUnit).openConfigGui(world, pos, side, player);
				}
			}
		}
		return false;
	}

	/* Rendering Init */
	@Override
	@SideOnly (Side.CLIENT)
	public void registerModels() {

		//Mask Model errors for blocks.
		ModelLoader.setCustomStateMapper(this, new StateMap.Builder().ignore(META).build());
		ModelResourceLocation normalLocation = new ModelResourceLocation(getRegistryName(), "normal");
		ModelRegistryHelper.register(normalLocation, new DummyBakedModel());
		//Actual model related stuffs.
		ModelResourceLocation invLocation = new ModelResourceLocation(getRegistryName(), "inventory");
		ModelLoader.setCustomMeshDefinition(Item.getItemFromBlock(this), stack -> invLocation);
		ModelBakery.registerItemKeyGenerator(ItemBlock.getItemFromBlock(this), item -> {
			StringBuilder builder = new StringBuilder(item.getItem().getRegistryName() + "|" + item.getItemDamage());
			builder.append(",weight=").append(getWeight(item));
			return builder.toString();
		});
		ModelRegistryHelper.register(invLocation, new CCBakeryModel());
	}

	@Override
	@SideOnly (Side.CLIENT)
	public IBakery getBakery() {

		return DuctModelBakery.INSTANCE;
	}

	/* IInitializer */
	@Override
	public boolean preInit() {

		ForgeRegistries.BLOCKS.register(this.setRegistryName("duct_" + offset));
		ForgeRegistries.ITEMS.register(new ItemBlockDuct(this).setRegistryName("duct_" + offset));

		for (int i = 0; i < 16; i++) {
			if (TDDucts.isValid(offset + i)) {
				TDDucts.getType(offset + i).itemStack = new ItemStack(this, 1, i);
			}
		}

		/* ENERGY */
		GameRegistry.registerTileEntity(TileDuctEnergy.Basic.class, "thermaldynamics:duct_energy_basic");
		GameRegistry.registerTileEntity(TileDuctEnergy.Hardened.class, "thermaldynamics:duct_energy_hardened");
		GameRegistry.registerTileEntity(TileDuctEnergy.Reinforced.class, "thermaldynamics:duct_energy_reinforced");
		GameRegistry.registerTileEntity(TileDuctEnergy.Signalum.class, "thermaldynamics:duct_energy_signalum");
		GameRegistry.registerTileEntity(TileDuctEnergy.Resonant.class, "thermaldynamics:duct_energy_resonant");
		GameRegistry.registerTileEntity(TileDuctEnergySuper.class, "thermaldynamics:duct_energy_super");

		/* FLUID */
		GameRegistry.registerTileEntity(TileDuctFluid.Basic.Transparent.class, "thermaldynamics:duct_fluid_fragile_transparent");
		GameRegistry.registerTileEntity(TileDuctFluid.Basic.Opaque.class, "thermaldynamics:duct_fluid_fragile_opaque");
		GameRegistry.registerTileEntity(TileDuctFluid.Hardened.Transparent.class, "thermaldynamics:duct_fluid_hardened_transparent");
		GameRegistry.registerTileEntity(TileDuctFluid.Hardened.Opaque.class, "thermaldynamics:duct_fluid_hardened_opaque");
		GameRegistry.registerTileEntity(TileDuctFluid.Energy.Transparent.class, "thermaldynamics:duct_fluid_energy_transparent");
		GameRegistry.registerTileEntity(TileDuctFluid.Energy.Opaque.class, "thermaldynamics:duct_fluid_energy_opaque");
		GameRegistry.registerTileEntity(TileDuctFluid.Super.Transparent.class, "thermaldynamics:duct_fluid_super_transparent");
		GameRegistry.registerTileEntity(TileDuctFluid.Super.Opaque.class, "thermaldynamics:duct_fluid_super_opaque");

		GameRegistry.registerTileEntity(TileDuctItem.Basic.Transparent.class, "thermaldynamics:duct_item_transparent");
		GameRegistry.registerTileEntity(TileDuctItem.Basic.Opaque.class, "thermaldynamics:duct_item_opaque");
		GameRegistry.registerTileEntity(TileDuctItem.Fast.Transparent.class, "thermaldynamics:duct_item_fast_transparent");
		GameRegistry.registerTileEntity(TileDuctItem.Fast.Opaque.class, "thermaldynamics:duct_item_fast_opaque");
		GameRegistry.registerTileEntity(TileDuctItem.Energy.Transparent.class, "thermaldynamics:duct_item_energy_transparent");
		GameRegistry.registerTileEntity(TileDuctItem.Energy.Opaque.class, "thermaldynamics:duct_item_energy_opaque");
		GameRegistry.registerTileEntity(TileDuctItem.EnergyFast.Transparent.class, "thermaldynamics:duct_item_energy_fast_transparent");
		GameRegistry.registerTileEntity(TileDuctItem.EnergyFast.Opaque.class, "thermaldynamics:duct_item_energy_fast_opaque");

		//		GameRegistry.registerTileEntity(TileDuctItem.Warp.Transparent.class, "thermaldynamics:duct_item_warp.transparent");
		//		GameRegistry.registerTileEntity(TileDuctItem.Warp.Opaque.class, "thermaldynamics:duct_item_warp.opaque");
		//		GameRegistry.registerTileEntity(TileDuctOmni.Transparent.class, "thermaldynamics:duct_item_ender.transparent");
		//	    GameRegistry.registerTileEntity(TileDuctOmni.Opaque.class, "thermaldynamics:duct_item_ender.opaque");

		GameRegistry.registerTileEntity(TileStructuralDuct.class, "thermaldynamics:duct_structure");
		//      GameRegistry.registerTileEntity(TileDuctLight.class, "thermaldynamics:duct_structure_light");

		GameRegistry.registerTileEntity(TileTransportDuct.class, "thermaldynamics:duct_transport_basic");
		GameRegistry.registerTileEntity(TileTransportDuct.LongRange.class, "thermaldynamics:duct_transport_long_range");
		GameRegistry.registerTileEntity(TileTransportDuct.Linking.class, "thermaldynamics:duct_transport_linking");

		ThermalDynamics.proxy.addIModelRegister(this);

		return true;
	}

	@Override
	public boolean initialize() {

		if (offset != 0) {
			return false;
		}
		PacketHandler.INSTANCE.registerPacket(PacketFluid.class);

		EntityRegistry.registerModEntity(new ResourceLocation("thermaldynamics:transport"), EntityTransport.class, "transport", 0, ThermalDynamics.instance, CoreProps.ENTITY_TRACKING_DISTANCE, 1, true);
		MinecraftForge.EVENT_BUS.register(TransportHandler.INSTANCE);
		FMLCommonHandler.instance().bus().register(TransportHandler.INSTANCE);

		addRecipes();

		return true;
	}

	/* HELPERS */
	private void addRecipes() {

		// TODO
	}

	public static byte getWeight(ItemStack stack) {

		if (!stack.hasTagCompound()) {
			return 0;
		}
		return stack.getTagCompound().getByte(DuctItem.PATHWEIGHT_NBT);
	}

	public float getSize(IBlockState state) {

		return TDDucts.getDuct(offset + getMetaFromState(state)).isLargeTube() ? 0.05F : 0.3F;
	}

	/* CONNECTIONS */
	public enum ConnectionType {

		// @formatter:off
		NONE(false),
		STRUCTURE_CLEAN,
		DUCT,
		CLEAN_DUCT,
		STRUCTURE_CONNECTION,
		TILE_CONNECTION;
		// @formatter:on

		private final boolean renderDuct;

		ConnectionType() {

			this(true);
		}

		ConnectionType(boolean renderDuct) {

			this.renderDuct = renderDuct;
		}

		public static ConnectionType getPriority(ConnectionType a, ConnectionType b) {

			if (a.ordinal() < b.ordinal()) {
				return b;
			}
			return a;
		}

		public boolean renderDuct() {

			return this.renderDuct;
		}
	}

}
