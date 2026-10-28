package foxgirl;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Запоминает, построена ли стартовая квартира и каких игроков уже в неё «заселили». */
public class ApartmentData extends SavedData {
	public boolean built = false;
	public BlockPos spawn = BlockPos.ZERO;
	public final Set<UUID> greeted = new HashSet<>();

	public static ApartmentData load(CompoundTag tag) {
		ApartmentData d = new ApartmentData();
		d.built = tag.getBoolean("built");
		d.spawn = BlockPos.of(tag.getLong("spawn"));
		ListTag list = tag.getList("greeted", Tag.TAG_STRING);
		for (int i = 0; i < list.size(); i++) {
			try { d.greeted.add(UUID.fromString(list.getString(i))); } catch (IllegalArgumentException ignored) {}
		}
		return d;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		tag.putBoolean("built", built);
		tag.putLong("spawn", spawn.asLong());
		ListTag list = new ListTag();
		for (UUID id : greeted) list.add(StringTag.valueOf(id.toString()));
		tag.put("greeted", list);
		return tag;
	}
}
