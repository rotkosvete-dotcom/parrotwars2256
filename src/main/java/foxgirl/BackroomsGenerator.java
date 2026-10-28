package foxgirl;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Бесконечные «Закулисья»: сетка комнат 6x6, стены с проёмами, потолочные лампы.
 * Блоки меняются в константах ниже.
 */
public class BackroomsGenerator extends ChunkGenerator {
	public static final Codec<BackroomsGenerator> CODEC = RecordCodecBuilder.create(inst -> inst.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource)
	).apply(inst, inst.stable(BackroomsGenerator::new)));

	private record Palette(BlockState floor, BlockState wall, BlockState ceiling, BlockState lamp, BlockState decor) {}

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	/** Уровни Закулисья: 0 — жёлтый (вход), 1 — тёмно-зелёные обои, 2 — бетон, 3 — белая плитка, 4 — трава с цветами. */
	private static final Palette[] PALETTES = {
		new Palette(Blocks.YELLOW_TERRACOTTA.defaultBlockState(), Blocks.SMOOTH_SANDSTONE.defaultBlockState(),
			Blocks.SMOOTH_QUARTZ.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState(), null),
		new Palette(Blocks.TERRACOTTA.defaultBlockState(), Blocks.GREEN_TERRACOTTA.defaultBlockState(),
			Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.SHROOMLIGHT.defaultBlockState(), null),
		new Palette(Blocks.POLISHED_ANDESITE.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(),
			Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState(), null),
		new Palette(Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState(), Blocks.QUARTZ_BRICKS.defaultBlockState(),
			Blocks.WHITE_CONCRETE.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState(), null),
		new Palette(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.MOSSY_STONE_BRICKS.defaultBlockState(),
			Blocks.GLASS.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState(), Blocks.BLUE_ORCHID.defaultBlockState()),
	};
	private static final int ZONE = 96; // размер «уровня» в блоках

	private static final int CELL = 6;
	private static final int FLOOR_Y = 0;
	private static final int CEILING_Y = 5; // коридоры высотой 4 блока (y = 1..4)

	public BackroomsGenerator(BiomeSource biomeSource) {
		super(biomeSource);
	}

	@Override
	protected Codec<? extends ChunkGenerator> codec() { return CODEC; }

	// ---- генерация ----

	private static long mix(long x) {
		x += 0x9E3779B97F4A7C15L;
		x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
		x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
		return x ^ (x >>> 31);
	}

	private static double rnd(int a, int b, int salt) {
		long h = mix(mix(a * 341873128712L ^ b * 132897987541L) ^ salt);
		return (h >>> 11) * (1.0 / (1L << 53));
	}

	// ---- «Её комната»: место, где игрок впервые видит Фурритян (x 24..42, z 18..36) ----

	private static boolean inLair(int wx, int wz) { return wx >= 25 && wx <= 41 && wz >= 19 && wz <= 35; }

	private static boolean onLairDoor(int wx, int wz) {
		return ((wx == 24 || wx == 42) && wz >= 26 && wz <= 28) || ((wz == 18 || wz == 36) && wx >= 32 && wx <= 34);
	}

	private static BlockState lairBlock(int wx, int y, int wz) {
		if (y == 1) {
			if (wz == 22 && wx == 36) return Blocks.RED_BED.defaultBlockState()
				.setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT);
			if (wz == 22 && wx == 37) return Blocks.RED_BED.defaultBlockState()
				.setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD);
			if (wx >= 30 && wx <= 35 && wz >= 24 && wz <= 29) return Blocks.RED_CARPET.defaultBlockState();
			if (wx >= 27 && wx <= 28 && wz >= 30 && wz <= 31) return Blocks.OAK_FENCE.defaultBlockState();
			if (wx == 39 && wz == 32) return Blocks.JUKEBOX.defaultBlockState();
		}
		if (y == 2 && wx >= 27 && wx <= 28 && wz >= 30 && wz <= 31) return Blocks.OAK_PRESSURE_PLATE.defaultBlockState();
		return null;
	}

	private static boolean lairMoss(int wx, int wz) {
		return inLair(wx, wz) && !isLamp(wx, wz) && rnd(wx, wz, 11) < 0.35;
	}

	/** Длина свисающих лоз в этом столбце (0 — нет). */
	private static int vineLen(int wx, int wz) {
		if (!lairMoss(wx, wz) || rnd(wx, wz, 12) >= 0.45) return 0;
		return 2 + (int) (rnd(wx, wz, 13) * 3);
	}

	private static boolean isWall(int wx, int wz) {
		if (inLair(wx, wz) || onLairDoor(wx, wz)) return false;
		int mx = Math.floorMod(wx, CELL), mz = Math.floorMod(wz, CELL);
		int cx = Math.floorDiv(wx, CELL), cz = Math.floorDiv(wz, CELL);
		if (mx == 0 && mz == 0) return true;               // столбик на углу
		if (mx == 0) {                                      // стена вдоль Z
			double r = rnd(cx, cz, 1);
			if (r < 0.15) return false;                     // совсем открытый пролёт
			return !(r < 0.60 && mz >= 2 && mz <= 4);       // проём 3 блока
		}
		if (mz == 0) {                                      // стена вдоль X
			double r = rnd(cx, cz, 2);
			if (r < 0.15) return false;
			return !(r < 0.60 && mx >= 2 && mx <= 4);
		}
		return false;
	}

	private static boolean isLamp(int wx, int wz) {
		int mx = Math.floorMod(wx, CELL), mz = Math.floorMod(wz, CELL);
		if (mx < 2 || mx > 3 || mz < 2 || mz > 3) return false;
		return rnd(Math.floorDiv(wx, CELL), Math.floorDiv(wz, CELL), 3) < 0.85; // иногда лампа "перегорела"
	}

	private static Palette paletteAt(int wx, int wz) {
		int rx = Math.floorDiv(wx, ZONE), rz = Math.floorDiv(wz, ZONE);
		if (rx == 0 && rz == 0) return PALETTES[0]; // вход всегда жёлтый
		int i = (int) (rnd(rx, rz, 9) * PALETTES.length);
		return PALETTES[Math.min(i, PALETTES.length - 1)];
	}

	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState random,
														StructureManager structures, ChunkAccess chunk) {
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();

		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int wx = minX + x, wz = minZ + z;
				boolean wall = isWall(wx, wz);
				Palette pal = paletteAt(wx, wz);

				set(chunk, pos.set(x, FLOOR_Y, z), pal.floor(), oceanFloor, surface);
				for (int y = FLOOR_Y + 1; y < CEILING_Y; y++) {
					BlockState st = wall ? pal.wall() : AIR;
					if (!wall && lairBlock(wx, y, wz) != null) st = lairBlock(wx, y, wz);
					int vn = wall ? 0 : vineLen(wx, wz);
					if (vn > 0 && lairBlock(wx, y, wz) == null && y >= CEILING_Y - vn) {
						boolean berries = rnd(wx, wz, 14) < 0.3;
						st = (y == CEILING_Y - vn)
							? Blocks.CAVE_VINES.defaultBlockState().setValue(BlockStateProperties.AGE_25, 25).setValue(BlockStateProperties.BERRIES, berries)
							: Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(BlockStateProperties.BERRIES, berries);
					}
					if (!wall && y == FLOOR_Y + 1 && pal.decor() != null && rnd(wx, wz, 7) < 0.03) st = pal.decor();
					set(chunk, pos.set(x, y, z), st, oceanFloor, surface);
				}
				set(chunk, pos.set(x, CEILING_Y, z), isLamp(wx, wz) ? pal.lamp() : (lairMoss(wx, wz) ? Blocks.MOSS_BLOCK.defaultBlockState() : pal.ceiling()), oceanFloor, surface);
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	private static void set(ChunkAccess chunk, BlockPos.MutableBlockPos pos, BlockState state, Heightmap a, Heightmap b) {
		chunk.setBlockState(pos, state, false);
		a.update(pos.getX(), pos.getY(), pos.getZ(), state);
		b.update(pos.getX(), pos.getY(), pos.getZ(), state);
	}

	// ---- остальные обязательные методы ----

	@Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes,
									   StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {}
	@Override public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {}
	@Override public void spawnOriginalMobs(WorldGenRegion region) {}
	@Override public int getGenDepth() { return 64; }
	@Override public int getSeaLevel() { return 0; }
	@Override public int getMinY() { return 0; }

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
		return FLOOR_Y + 1;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
		return new NoiseColumn(level.getMinBuildHeight(), new BlockState[]{PALETTES[0].floor()});
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {}
}
