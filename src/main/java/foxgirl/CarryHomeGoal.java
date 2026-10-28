package foxgirl;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Пока она несёт игрока, другие цели (прогулка, слежка, атака) не должны ей мешать.
 * Само движение домой делает FoxGirlEntity.carryStep() каждый тик.
 */
public class CarryHomeGoal extends Goal {
	private final FoxGirlEntity mob;

	public CarryHomeGoal(FoxGirlEntity mob) {
		this.mob = mob;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
	}

	@Override public boolean canUse() { return mob.isCarrying(); }
	@Override public boolean canContinueToUse() { return mob.isCarrying(); }
	@Override public boolean requiresUpdateEveryTick() { return false; }
}
