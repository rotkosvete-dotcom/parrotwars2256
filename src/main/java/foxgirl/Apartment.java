package foxgirl;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Стартовая квартира: полосатые обои, кровать, стол, печь, книги, проигрыватель и сундук
 * с миндальной водой, едой и пластинками. Строится на спавне при создании мира.
 * Размер 11 x 9, высота 4.
 */
public final class Apartment {
	private static final int W = 11, D = 9;
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private Apartment() {}

	public static ApartmentData data(ServerLevel overworld) {
		return overworld.getDataStorage().computeIfAbsent(ApartmentData::load, ApartmentData::new, "foxgirl_apartment");
	}

	private static void put(ServerLevel level, int x, int y, int z, BlockState st) {
		level.setBlock(new BlockPos(x, y, z), st, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
	}

	private static boolean replaceable(BlockState s) {
		return s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced()
			|| s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.FLOWERS);
	}

	/** Строит квартиру вокруг center, возвращает точку появления игрока. */
	public static BlockPos build(ServerLevel level, BlockPos center) {
		int ox = center.getX() - W / 2, oz = center.getZ() - D / 2;
		for (int cx = (ox - 1) >> 4; cx <= (ox + W) >> 4; cx++)
			for (int cz = (oz - 1) >> 4; cz <= (oz + D) >> 4; cz++) level.getChunk(cx, cz);

		int fy = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ()), level.getSeaLevel());

		// расчистить место (деревья, холмы)
		for (int x = -1; x <= W; x++)
			for (int z = -1; z <= D; z++)
				for (int y = 1; y <= 8; y++) put(level, ox + x, fy + y, oz + z, AIR);

		// фундамент вниз до земли
		for (int x = 0; x < W; x++) {
			for (int z = 0; z < D; z++) {
				for (int y = fy - 1; y >= fy - 24 && y > level.getMinBuildHeight(); y--) {
					if (!replaceable(level.getBlockState(new BlockPos(ox + x, y, oz + z)))) break;
					put(level, ox + x, y, oz + z, Blocks.COBBLESTONE.defaultBlockState());
				}
			}
		}

		BlockState stripeA = Blocks.LIME_TERRACOTTA.defaultBlockState();
		BlockState stripeB = Blocks.WHITE_TERRACOTTA.defaultBlockState();

		for (int x = 0; x < W; x++) {
			for (int z = 0; z < D; z++) {
				put(level, ox + x, fy, oz + z, Blocks.OAK_PLANKS.defaultBlockState());          // пол
				put(level, ox + x, fy + 5, oz + z, Blocks.WHITE_TERRACOTTA.defaultBlockState()); // потолок
				boolean edge = x == 0 || z == 0 || x == W - 1 || z == D - 1;
				if (edge) {
					for (int y = 1; y <= 4; y++) put(level, ox + x, fy + y, oz + z, ((x + z) & 1) == 0 ? stripeA : stripeB);
				}
			}
		}
		// окна
		for (int y = 2; y <= 3; y++) {
			for (int x : new int[]{3, 4, 6, 7}) put(level, ox + x, fy + y, oz, Blocks.GLASS.defaultBlockState());
			for (int z = 3; z <= 5; z++) put(level, ox + W - 1, fy + y, oz + z, Blocks.GLASS.defaultBlockState());
		}
		// дверь на юге
		put(level, ox + 5, fy + 1, oz + D - 1, Blocks.OAK_DOOR.defaultBlockState()
			.setValue(DoorBlock.FACING, Direction.SOUTH).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		put(level, ox + 5, fy + 2, oz + D - 1, Blocks.OAK_DOOR.defaultBlockState()
			.setValue(DoorBlock.FACING, Direction.SOUTH).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		// свет
		for (int x : new int[]{2, 5, 8}) put(level, ox + x, fy + 5, oz + 4, Blocks.GLOWSTONE.defaultBlockState());

		// ковёр
		for (int x = 3; x <= 7; x++) for (int z = 3; z <= 5; z++) put(level, ox + x, fy + 1, oz + z, Blocks.GREEN_CARPET.defaultBlockState());
		// кровать
		put(level, ox + 1, fy + 1, oz + 1, Blocks.GREEN_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT));
		put(level, ox + 2, fy + 1, oz + 1, Blocks.GREEN_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD));
		// стол с растением, верстак, печь, стул
		put(level, ox + 8, fy + 1, oz + 1, Blocks.OAK_PLANKS.defaultBlockState());
		put(level, ox + 8, fy + 2, oz + 1, Blocks.POTTED_FERN.defaultBlockState());
		put(level, ox + 9, fy + 1, oz + 1, Blocks.CRAFTING_TABLE.defaultBlockState());
		put(level, ox + 9, fy + 1, oz + 2, Blocks.FURNACE.defaultBlockState());
		put(level, ox + 8, fy + 1, oz + 2, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
		// книжные полки и проигрыватель
		for (int x = 3; x <= 4; x++) for (int y = 1; y <= 2; y++) put(level, ox + x, fy + y, oz + 7, Blocks.BOOKSHELF.defaultBlockState());
		put(level, ox + 1, fy + 1, oz + 7, Blocks.JUKEBOX.defaultBlockState());

		// сундук со стартовыми вещами
		BlockPos chest = new BlockPos(ox + 9, fy + 1, oz + 3);
		level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
		BlockEntity be = level.getBlockEntity(chest);
		if (be instanceof Container c) {
			c.setItem(0, new ItemStack(FoxGirlMod.ALMOND_WATER.get(), 4));
			c.setItem(1, new ItemStack(Items.BREAD, 8));
			c.setItem(2, new ItemStack(Items.COOKED_CHICKEN, 8));
			c.setItem(3, new ItemStack(Items.SWEET_BERRIES, 8));
			c.setItem(4, new ItemStack(Items.JUKEBOX, 2));
			c.setItem(5, new ItemStack(Items.MUSIC_DISC_CAT, 1));
			c.setItem(6, new ItemStack(Items.MUSIC_DISC_13, 1));
			c.setItem(7, new ItemStack(Items.NOTE_BLOCK, 4));
			c.setItem(8, new ItemStack(Items.TORCH, 16));
		}
		return new BlockPos(ox + 5, fy + 1, oz + 4);
	}
}
