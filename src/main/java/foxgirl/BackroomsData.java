package foxgirl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Хранит в мире Закулисья флаг «лисичка уже заспавнена» — она там одна. */
public class BackroomsData extends SavedData {
	public boolean spawned = false;

	public static BackroomsData load(CompoundTag tag) {
		BackroomsData d = new BackroomsData();
		d.spawned = tag.getBoolean("spawned");
		return d;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		tag.putBoolean("spawned", spawned);
		return tag;
	}
}
