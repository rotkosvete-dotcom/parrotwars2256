package foxgirl;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Несёт схваченного игрока к себе домой (в комнату в Закулисье). */
public class CarryHomeGoal extends Goal {
	private static final int MAX_CARRY_TICKS = 900; // 45 с, потом телепорт, если дорога не нашлась
	private final FoxGirlEntity mob;

	public CarryHomeGoal(FoxGirlEntity mob) {
		this.mob = mob;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
	}

	@Override
	public boolean canUse() { return mob.isCarrying(); }

	@Override
	public boolean canContinueToUse() { return mob.isCarrying(); }

	@Override
	public void tick() {
		double dx = Backrooms.HOME_X - mob.getX(), dz = Backrooms.HOME_Z - mob.getZ();
		double distSq = dx * dx + dz * dz;
		if (distSq < 9.0) {                       // пришла домой
			mob.deliverPassenger(false);
			return;
		}
		if (mob.getCarryTicks() > MAX_CARRY_TICKS) { // не дойти — переносимся
			mob.deliverPassenger(true);
			return;
		}
		if (mob.tickCount % 10 == 0) {
			mob.getNavigation().moveTo(Backrooms.HOME_X, Backrooms.HOME_Y, Backrooms.HOME_Z, 1.0);
		}
	}
}
