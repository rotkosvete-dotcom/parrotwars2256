package foxgirl;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/** Источники шума (проигрыватель с пластинкой, нотный блок), на которые идёт дикая Фурритян. */
public final class Lures {
	private record Lure(BlockPos pos, long expires) {}
	private static final Map<ResourceKey<Level>, Lure> LURES = new HashMap<>();

	private Lures() {}

	public static void add(Level level, BlockPos pos, int ticks) {
		LURES.put(level.dimension(), new Lure(pos.immutable(), level.getGameTime() + ticks));
	}

	public static BlockPos get(Level level) {
		Lure l = LURES.get(level.dimension());
		if (l == null) return null;
		if (level.getGameTime() > l.expires()) {
			LURES.remove(level.dimension());
			return null;
		}
		return l.pos();
	}

	public static void clear() { LURES.clear(); }
}
