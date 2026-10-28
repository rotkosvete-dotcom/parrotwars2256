package foxgirl;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Дикая Фурритян идёт на шум (проигрыватель с пластинкой / нотный блок), если не видит игрока. */
public class InvestigateLureGoal extends Goal {
	private static final double HEAR_RANGE = 48.0;
	private final FoxGirlEntity mob;
	private BlockPos lure;

	public InvestigateLureGoal(FoxGirlEntity mob) {
		this.mob = mob;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (mob.isTame() || mob.getTarget() != null) return false;
		BlockPos p = Lures.get(mob.level());
		if (p == null || mob.distanceToSqr(Vec3.atCenterOf(p)) > HEAR_RANGE * HEAR_RANGE) return false;
		this.lure = p;
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		return mob.getTarget() == null && lure != null && lure.equals(Lures.get(mob.level()));
	}

	@Override
	public void tick() {
		Vec3 c = Vec3.atCenterOf(lure);
		mob.getLookControl().setLookAt(c.x, c.y, c.z);
		if (mob.distanceToSqr(c) > 9.0) {
			if (mob.tickCount % 10 == 0) mob.getNavigation().moveTo(c.x, lure.getY(), c.z, 1.0);
		} else {
			mob.getNavigation().stop(); // стоит у источника звука и «слушает»
		}
	}

	@Override
	public void stop() { mob.getNavigation().stop(); }
}
