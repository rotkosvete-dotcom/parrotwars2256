package foxgirl;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.NoteBlockEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public class ModEvents {
	@Mod.EventBusSubscriber(modid = FoxGirlMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
	public static class ModBus {
		@SubscribeEvent
		public static void onAttributes(EntityAttributeCreationEvent event) {
			event.put(FoxGirlMod.FOX_GIRL.get(), FoxGirlEntity.createAttributes().build());
		}
	}

	@Mod.EventBusSubscriber(modid = FoxGirlMod.MOD_ID)
	public static class ForgeBus {
		/** Пластинка в проигрыватель — Фурритян слышит и идёт на звук (30 с). */
		@SubscribeEvent
		public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
			Level level = event.getLevel();
			if (level.isClientSide) return;
			BlockState st = level.getBlockState(event.getPos());
			ItemStack held = event.getItemStack();
			if (st.is(Blocks.JUKEBOX) && held.getItem() instanceof RecordItem && !st.getValue(JukeboxBlock.HAS_RECORD)) {
				Lures.add(level, event.getPos(), 600);
			}
		}

		/** Нотный блок — короткий шум (10 с). */
		@SubscribeEvent
		public static void onNote(NoteBlockEvent.Play event) {
			if (event.getLevel() instanceof Level level && !level.isClientSide()) {
				Lures.add(level, event.getPos(), 200);
			}
		}

		/** Новый мир: строим квартиру на спавне. Старые миры не трогаем (для них есть /apartment). */
		@SubscribeEvent
		public static void onServerStarted(ServerStartedEvent event) {
			ServerLevel overworld = event.getServer().overworld();
			ApartmentData d = Apartment.data(overworld);
			if (d.built || overworld.getGameTime() > 200) return;
			d.spawn = Apartment.build(overworld, overworld.getSharedSpawnPos());
			d.built = true;
			d.setDirty();
			overworld.setDefaultSpawnPos(d.spawn, 0f);
		}

		/** Каждый новый игрок первый раз появляется в квартире. */
		@SubscribeEvent
		public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
			if (!(event.getEntity() instanceof ServerPlayer sp)) return;
			ServerLevel overworld = sp.server.overworld();
			ApartmentData d = Apartment.data(overworld);
			if (!d.built || !d.greeted.add(sp.getUUID())) return;
			d.setDirty();
			BlockPos p = d.spawn;
			sp.teleportTo(overworld, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0f, 0f);
			sp.setRespawnPosition(net.minecraft.world.level.Level.OVERWORLD, p, 0f, true, false);
		}

		@SubscribeEvent
		public static void onServerStopping(ServerStoppingEvent event) { Lures.clear(); }

		/** /apartment — построить квартиру там, где стоишь (для старых миров). */
		@SubscribeEvent
		public static void onApartmentCommand(RegisterCommandsEvent event) {
			event.getDispatcher().register(Commands.literal("apartment")
				.requires(src -> src.hasPermission(2))
				.executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					ServerLevel level = player.serverLevel();
					BlockPos spawn = Apartment.build(level, player.blockPosition());
					player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
					player.displayClientMessage(Component.translatable("message.foxgirl.apartment_built"), true);
					return 1;
				}));
		}

		/** /backrooms — телепорт туда и обратно (для операторов, удобно для проверки). */
		@SubscribeEvent
		public static void onCommands(RegisterCommandsEvent event) {
			event.getDispatcher().register(Commands.literal("backrooms")
				.requires(src -> src.hasPermission(2))
				.executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					Backrooms.toggle(player);
					return 1;
				}));
		}
	}
}
