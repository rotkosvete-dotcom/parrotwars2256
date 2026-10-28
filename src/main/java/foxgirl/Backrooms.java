package foxgirl;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

public final class Backrooms {
	public static final ResourceKey<Level> DIMENSION =
		ResourceKey.create(Registries.DIMENSION, new ResourceLocation(FoxGirlMod.MOD_ID, "backrooms"));

	// Клетка (5, 4): x = 5*6+3, z = 4*6+3 — центр комнаты, гарантированно без стен
	private static final int FOX_X = 33, FOX_Z = 27;

	private Backrooms() {}

	public static void toggle(ServerPlayer player) {
		if (player.level().dimension().equals(DIMENSION)) leave(player);
		else enter(player);
	}

	private static void enter(ServerPlayer player) {
		MinecraftServer server = player.server;
		ServerLevel backrooms = server.getLevel(DIMENSION);
		if (backrooms == null) {
			player.displayClientMessage(Component.translatable("message.foxgirl.no_dimension"), false);
			return;
		}
		player.teleportTo(backrooms, 3.5, 1.0, 3.5, player.getYRot(), 0f);
		spawnFoxGirlOnce(backrooms);
	}

	private static void leave(ServerPlayer player) {
		ServerLevel overworld = player.server.overworld();
		BlockPos spawn = overworld.getSharedSpawnPos();
		BlockPos top = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, spawn);
		player.teleportTo(overworld, top.getX() + 0.5, top.getY(), top.getZ() + 0.5, player.getYRot(), 0f);
	}

	/** В каждом мире Закулисья лисичка появляется ровно один раз. */
	private static void spawnFoxGirlOnce(ServerLevel level) {
		BackroomsData data = level.getDataStorage().computeIfAbsent(BackroomsData::load, BackroomsData::new, "foxgirl_backrooms");
		if (data.spawned) return;

		level.getChunk(FOX_X >> 4, FOX_Z >> 4); // сгенерировать чанк заранее
		FoxGirlEntity fox = FoxGirlMod.FOX_GIRL.get().create(level);
		if (fox == null) return;
		fox.moveTo(FOX_X + 0.5, 1.0, FOX_Z + 0.5, level.random.nextFloat() * 360f, 0f);
		fox.setPersistenceRequired();
		level.addFreshEntity(fox);

		// Котята ("котяты") — маленькие копии рядом с ней
		for (int i = 0; i < 3; i++) {
			FoxGirlEntity kitten = FoxGirlMod.FOX_GIRL.get().create(level);
			if (kitten == null) continue;
			kitten.setKitten(true);
			kitten.moveTo(FOX_X + 0.5 + (i - 1) * 1.2, 1.0, FOX_Z + 2.0, level.random.nextFloat() * 360f, 0f);
			kitten.setPersistenceRequired();
			level.addFreshEntity(kitten);
		}

		data.spawned = true;
		data.setDirty();
	}
}
