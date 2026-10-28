package foxgirl;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(FoxGirlMod.MOD_ID)
public class FoxGirlMod {
	public static final String MOD_ID = "foxgirl";

	public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
	public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
	public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
	public static final DeferredRegister<Codec<? extends ChunkGenerator>> GENERATORS = DeferredRegister.create(Registries.CHUNK_GENERATOR, MOD_ID);

	public static final RegistryObject<EntityType<FoxGirlEntity>> FOX_GIRL = ENTITIES.register("fox_girl",
		() -> EntityType.Builder.of(FoxGirlEntity::new, MobCategory.CREATURE)
			.sized(0.96f, 3.04f)
			.clientTrackingRange(10)
			.build("fox_girl"));

	public static final RegistryObject<Item> SPAWN_EGG = ITEMS.register("fox_girl_spawn_egg",
		() -> new ForgeSpawnEggItem(FOX_GIRL, 0x7A2E3C, 0xF0A030, new Item.Properties()));

	public static final RegistryObject<Item> ALMOND_WATER = ITEMS.register("almond_water",
		() -> new AlmondWaterItem(new Item.Properties().stacksTo(16)));

	public static final RegistryObject<CreativeModeTab> TAB = TABS.register("foxgirl",
		() -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup.foxgirl"))
			.icon(() -> new ItemStack(SPAWN_EGG.get()))
			.displayItems((params, output) -> {
				output.accept(SPAWN_EGG.get());
				output.accept(ALMOND_WATER.get());
			})
			.build());

	public static final RegistryObject<Codec<? extends ChunkGenerator>> BACKROOMS_GENERATOR =
		GENERATORS.register("backrooms", () -> BackroomsGenerator.CODEC);

	public FoxGirlMod() {
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
		ENTITIES.register(bus);
		ITEMS.register(bus);
		TABS.register(bus);
		GENERATORS.register(bus);
	}
}
