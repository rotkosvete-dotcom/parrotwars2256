package foxgirl;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/** Дикий котёнок ходит за ближайшей взрослой Фурритян. */
public class FollowMotherGoal extends Goal {
	private final FoxGirlEntity kitten;
	private FoxGirlEntity mother;

	public FollowMotherGoal(FoxGirlEntity kitten) {
		this.kitten = kitten;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		if (!kitten.isKitten() || kitten.isTame()) return false;
		List<FoxGirlEntity> adults = kitten.level().getEntitiesOfClass(FoxGirlEntity.class,
			kitten.getBoundingBox().inflate(20.0), e -> !e.isKitten());
		mother = adults.stream().min(Comparator.comparingDouble(kitten::distanceToSqr)).orElse(null);
		return mother != null && kitten.distanceToSqr(mother) > 16.0;
	}

	@Override
	public boolean canContinueToUse() {
		return mother != null && mother.isAlive() && kitten.distanceToSqr(mother) > 9.0;
	}

	@Override
	public void tick() {
		if (kitten.tickCount % 10 == 0) kitten.getNavigation().moveTo(mother, 1.1);
	}

	@Override
	public void stop() { kitten.getNavigation().stop(); }
}
