package foxgirl;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/** Выпил — «провалился» в Закулисье. Выпил там — вернулся обратно. */
public class AlmondWaterItem extends Item {
	public AlmondWaterItem(Properties props) { super(props); }

	@Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.DRINK; }
	@Override public int getUseDuration(ItemStack stack) { return 32; }

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		return ItemUtils.startUsingInstantly(level, player, hand);
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
		Player player = entity instanceof Player p ? p : null;
		if (player instanceof ServerPlayer sp) {
			CriteriaTriggers.CONSUME_ITEM.trigger(sp, stack);
			Backrooms.toggle(sp);
		}
		if (player != null) {
			player.awardStat(Stats.ITEM_USED.get(this));
			if (!player.getAbilities().instabuild) stack.shrink(1);
		}
		if (player == null || !player.getAbilities().instabuild) {
			if (stack.isEmpty()) return new ItemStack(Items.GLASS_BOTTLE);
			if (player != null) player.getInventory().add(new ItemStack(Items.GLASS_BOTTLE));
		}
		return stack;
	}
}
