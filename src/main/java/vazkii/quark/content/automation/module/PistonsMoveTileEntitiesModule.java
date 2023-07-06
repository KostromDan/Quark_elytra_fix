package vazkii.quark.content.automation.module;

import com.google.common.collect.Lists;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.common.util.NonNullConsumer;
import net.minecraftforge.event.TickEvent.LevelTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.tuple.Pair;
import vazkii.quark.api.IIndirectConnector;
import vazkii.quark.api.IPistonCallback;
import vazkii.quark.api.QuarkCapabilities;
import vazkii.quark.base.Quark;
import vazkii.quark.base.handler.GeneralConfig;
import vazkii.quark.base.module.LoadModule;
import vazkii.quark.base.module.ModuleCategory;
import vazkii.quark.base.module.ModuleLoader;
import vazkii.quark.base.module.QuarkModule;
import vazkii.quark.base.module.config.Config;
import vazkii.quark.content.building.module.SturdyStoneModule;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

@LoadModule(category = ModuleCategory.AUTOMATION, hasSubscriptions = true)
public class PistonsMoveTileEntitiesModule extends QuarkModule {

	private static final WeakHashMap<Level, Map<BlockPos, CompoundTag>> movements = new WeakHashMap<>();
	private static final WeakHashMap<Level, List<Pair<BlockPos, CompoundTag>>> delayedUpdates = new WeakHashMap<>();

	@Config
	public static boolean enableChestsMovingTogether = true;

	public static boolean staticEnabled;

	@Config
	public static List<String> renderBlacklist = Lists.newArrayList("psi:programmer", "botania:starfield");
	@Config
	public static List<String> movementBlacklist = Lists.newArrayList("minecraft:spawner", "integrateddynamics:cable", "randomthings:blockbreaker", "minecraft:ender_chest", "minecraft:enchanting_table", "minecraft:trapped_chest", "quark:spruce_trapped_chest", "quark:birch_trapped_chest", "quark:jungle_trapped_chest", "quark:acacia_trapped_chest", "quark:dark_oak_trapped_chest", "endergetic:bolloom_bud");
	@Config
	public static List<String> delayedUpdateList = Lists.newArrayList("minecraft:dispenser", "minecraft:dropper");

	@Override
	public void register() {
		IIndirectConnector.INDIRECT_STICKY_BLOCKS.add(Pair.of(ChestConnection.PREDICATE, ChestConnection.INSTANCE));
	}

	@Override
	public void configChanged() {
		staticEnabled = enabled;
	}

	@SubscribeEvent
	public void onWorldTick(LevelTickEvent event) {
		if (!delayedUpdates.containsKey(event.level) || event.phase == Phase.START)
			return;

		List<Pair<BlockPos, CompoundTag>> delays = delayedUpdates.get(event.level);
		if (delays.isEmpty())
			return;

		for (Pair<BlockPos, CompoundTag> delay : delays) {
			BlockPos pos = delay.getLeft();
			BlockState state = event.level.getBlockState(pos);
			BlockEntity entity = loadBlockEntitySafe(event.level, pos, delay.getRight());
			callCallback(entity, IPistonCallback::onPistonMovementFinished);
			event.level.updateNeighbourForOutputSignal(pos, state.getBlock());
		}

		delays.clear();
	}

	@Override
	public void addAdditionalHints(BiConsumer<Item, Component> consumer) {
		MutableComponent comp = Component.translatable("quark.jei.hint.piston_te");

		if(ModuleLoader.INSTANCE.isModuleEnabled(SturdyStoneModule.class))
			comp = comp.append(" ").append(Component.translatable("quark.jei.hint.piston_sturdy"));

		if(GeneralConfig.pistonPushLimit != 12)
			comp = comp.append(" ").append(Component.translatable("quark.jei.hint.piston_max_blocks", GeneralConfig.pistonPushLimit));

		consumer.accept(Items.PISTON, comp);
		consumer.accept(Items.STICKY_PISTON, comp);
	}

	// This is called from injected code and subsequently flipped, so to make it move, we return false
	public static boolean shouldMoveTE(boolean te, BlockState state) {
		if (!ModuleLoader.INSTANCE.isModuleEnabled(PistonsMoveTileEntitiesModule.class))
			return te;

		return shouldMoveTE(state);
	}

	public static boolean shouldMoveTE(BlockState state) {
		// Jukeboxes that are playing can't be moved so the music can be stopped
		if (state.getValues().containsKey(JukeboxBlock.HAS_RECORD) && state.getValue(JukeboxBlock.HAS_RECORD))
			return true;

		if (state.getBlock() == Blocks.PISTON_HEAD)
			return true;

		ResourceLocation res = Registry.BLOCK.getKey(state.getBlock());
		return res == null || PistonsMoveTileEntitiesModule.movementBlacklist.contains(res.toString()) || PistonsMoveTileEntitiesModule.movementBlacklist.contains(res.getNamespace());
	}

	public static void detachTileEntities(Level world, PistonStructureResolver helper, Direction facing, boolean extending) {
		if (!ModuleLoader.INSTANCE.isModuleEnabled(PistonsMoveTileEntitiesModule.class))
			return;

		if (!extending)
			facing = facing.getOpposite();

		List<BlockPos> moveList = helper.getToPush();

		for (BlockPos pos : moveList) {
			BlockState state = world.getBlockState(pos);
			if (state.getBlock() instanceof EntityBlock) {
				BlockEntity tile = world.getBlockEntity(pos);
				if (tile != null) {
					callCallback(tile, IPistonCallback::onPistonMovementStarted);

					CompoundTag tag = tile.saveWithFullMetadata();
					setMovingBlockEntityData(world, pos.relative(facing), tag);
					world.removeBlockEntity(pos);
				}
			}
		}
	}

	public static boolean setPistonBlock(Level world, BlockPos pos, BlockState state, int flags) {
		if (!ModuleLoader.INSTANCE.isModuleEnabled(PistonsMoveTileEntitiesModule.class)) {
			world.setBlock(pos, state, flags);
			return false;
		}

		if(!enableChestsMovingTogether && state.getValues().containsKey(ChestBlock.TYPE))
			state = state.setValue(ChestBlock.TYPE, ChestType.SINGLE);

		Block block = state.getBlock();
		CompoundTag entityTag = getAndClearMovement(world, pos);
		boolean destroyed = false;

		if (entityTag != null) {
			BlockState currState = world.getBlockState(pos);
			BlockEntity currEntity = world.getBlockEntity(pos);
			CompoundTag currTag = currEntity == null ? null : currEntity.saveWithFullMetadata();

			world.removeBlock(pos, false);
			if (!block.canSurvive(state, world, pos)) {
				world.setBlock(pos, state, flags);
				BlockEntity entity = loadBlockEntitySafe(world, pos, entityTag);
				callCallback(entity, IPistonCallback::onPistonMovementFinished);
				Block.dropResources(state, world, pos, entity);
				world.removeBlock(pos, false);
				destroyed = true;
			}

			if (!destroyed) {
				world.setBlockAndUpdate(pos, currState);
				if(currTag != null)
					loadBlockEntitySafe(world, pos, currTag);
			}
		}

		if (!destroyed) {
			world.setBlock(pos, state, flags);

			if (world.getBlockEntity(pos) != null)
				world.setBlock(pos, state, 0);

			if (entityTag != null && !world.isClientSide) {
				if (delayedUpdateList.contains(Objects.toString(Registry.BLOCK.getKey(block))))
					registerDelayedUpdate(world, pos, entityTag);
				else {
					BlockEntity entity = loadBlockEntitySafe(world, pos, entityTag);
					callCallback(entity, IPistonCallback::onPistonMovementFinished);
				}
			}
			world.updateNeighborsAt(pos, block);
		}

		return true;
	}

	/**
	 * Use to update your tile entity data. Use with care
	 * @param world current world
	 * @param pos moving tile position
	 * @param nbt tile entity data
	 */
	public static void setMovingBlockEntityData(Level world, BlockPos pos, CompoundTag nbt) {
		movements.computeIfAbsent(world, l -> new HashMap<>()).put(pos, nbt);
	}

	@Deprecated(forRemoval = true)
	public static BlockEntity getMovement(Level world, BlockPos pos) {
		return null;
	}

	public static CompoundTag getMovingBlockEntityData(Level world, BlockPos pos) {
		return getMovingBlockEntityData(world, pos, false);
	}

	private static CompoundTag getMovingBlockEntityData(Level world, BlockPos pos, boolean remove) {
		if (!movements.containsKey(world))
			return null;

		Map<BlockPos, CompoundTag> worldMovements = movements.get(world);
		if (!worldMovements.containsKey(pos))
			return null;

		CompoundTag ret = worldMovements.get(pos);
		if (remove)
			worldMovements.remove(pos);

		return ret;
	}

	private static CompoundTag getAndClearMovement(Level world, BlockPos pos) {
		return getMovingBlockEntityData(world, pos, true);
		// TODO this function formerly called the callback, make sure it's called from all the right places
	}

	private static void registerDelayedUpdate(Level world, BlockPos pos, CompoundTag tag) {
		if (!delayedUpdates.containsKey(world))
			delayedUpdates.put(world, new ArrayList<>());

		delayedUpdates.get(world).add(Pair.of(pos, tag));
	}

	private static void callCallback(@Nullable BlockEntity entity, NonNullConsumer<? super IPistonCallback> caller) {
		if(entity != null)
			entity.getCapability(QuarkCapabilities.PISTON_CALLBACK).ifPresent(caller);
	}

	public static class ChestConnection implements IIndirectConnector {

		public static ChestConnection INSTANCE = new ChestConnection();
		public static Predicate<BlockState> PREDICATE = ChestConnection::isValidState;

		@Override
		public boolean isEnabled() {
			return enableChestsMovingTogether;
		}

		private static boolean isValidState(BlockState state) {
			if(!(state.getBlock() instanceof ChestBlock))
				return false;

			ChestType type = state.getValue(ChestBlock.TYPE);
			return type != ChestType.SINGLE;
		}

		@Override
		public boolean canConnectIndirectly(Level world, BlockPos ourPos, BlockPos sourcePos, BlockState ourState, BlockState sourceState) {
			ChestType ourType = ourState.getValue(ChestBlock.TYPE);

			Direction baseDirection = ourState.getValue(ChestBlock.FACING);
			Direction targetDirection = ourType == ChestType.LEFT ? baseDirection.getClockWise() : baseDirection.getCounterClockWise();

			BlockPos targetPos = ourPos.relative(targetDirection);

			return targetPos.equals(sourcePos);
		}

	}

	@Nullable
	private static BlockEntity loadBlockEntitySafe(Level level, BlockPos pos, CompoundTag tag) {
		BlockEntity inWorldEntity = level.getBlockEntity(pos);
		String expectedTypeStr = tag.getString("id");
		if (inWorldEntity == null) {
			Quark.LOG.warn("No block entity found at {} (expected {})", pos.toShortString(), expectedTypeStr);
			return null;
		} else if (inWorldEntity.getType() != ForgeRegistries.BLOCK_ENTITY_TYPES.getValue(new ResourceLocation(expectedTypeStr))) {
			Quark.LOG.warn("Wrong block entity found at {} (expected {}, got {})", pos.toShortString(), expectedTypeStr, BlockEntityType.getKey(inWorldEntity.getType()));
			return null;
		} else {
			inWorldEntity.load(tag);
			inWorldEntity.setChanged();
			return inWorldEntity;
		}
	}
}
