package foxgirl;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Фурритян — компаньон для выживания (живёт в Закулисье).
 *  - В Закулисье дикая охотится на игрока; её можно отвлечь пластинкой в проигрывателе / нотным блоком.
 *  - Если у игрока мало здоровья, дикая подхватывает его и относит к себе домой (в её комнату),
 *    где превращает в своего клона (игрок погибает, а в комнате появляется котёнок с его именем).
 *  - Приручается едой (шанс 1/3 за кормление).
 *  - Сытость: пока сыта — помогает, голодная — слабеет и не помогает.
 *  - Защищает хозяина, лечит его, ночью подсвечивает монстров.
 *  - РАЗМЕР (сама, без команд игрока): уменьшается, чтобы протиснуться в узкий/низкий проход
 *    или спрятаться (мало здоровья, рядом крипер); увеличивается, чтобы перешагнуть уступ и в бою.
 *  - ХВОСТ: отдельное оружие — мощный размашистый удар по площади с отбрасыванием.
 *  - ПКМ пустой рукой — сесть / идти следом.
 */
public class FoxGirlEntity extends TamableAnimal {
	private static final int MAX_HUNGER = 20;
	private static final int HUNGER_TICKS = 1200; // -1 сытости в минуту
	private static final int TAIL_COOLDOWN = 50;
	private static final int TAIL_ANIM_TICKS = 14;

	/** Обычный размер Фурритян = 1.6 от размера модели (хитбокс в FoxGirlMod задан уже под него). */
	public static final float NORMAL_RENDER = 1.6f;
	/** Множители ОТНОСИТЕЛЬНО обычного размера: 0 = маленькая, 1 = обычная, 2 = большая */
	private static final float[] SCALES = {0.3f, 1.0f, 1.25f};
	private static final EntityDataAccessor<Integer> SIZE_STATE =
		SynchedEntityData.defineId(FoxGirlEntity.class, EntityDataSerializers.INT);
	/** Котята ("котяты") — маленькие Фурритян: не охотятся, ходят за мамой/хозяином. */
	private static final EntityDataAccessor<Boolean> KITTEN =
		SynchedEntityData.defineId(FoxGirlEntity.class, EntityDataSerializers.BOOLEAN);
	private static final float KITTEN_FACTOR = 0.4f;
	/** Превращение («дикая форма»): крупнее, быстрее, сильнее, красная, с ударной волной. */
	private static final EntityDataAccessor<Boolean> RAGE =
		SynchedEntityData.defineId(FoxGirlEntity.class, EntityDataSerializers.BOOLEAN);
	private static final float RAGE_FACTOR = 1.3f;
	private static final int RAGE_TICKS = 600;      // 30 с
	private static final UUID RAGE_SPEED_ID = UUID.fromString("0c1d2a7e-6b43-4f0e-8a51-3f2f5d9b7a12");
	private static final UUID RAGE_DAMAGE_ID = UUID.fromString("7e4a9b10-1c55-4c2d-9d3e-b0a1c6f2d843");
	private static final UUID BIG_DAMAGE_ID = UUID.fromString("5b7f6a40-2d1c-4a77-9a50-0c4f0a1b9e11");

	private int hunger = 12;
	private int sizeHold = 0;      // сколько тиков держать размер, чтобы не дёргалась
	private int tailCooldown = 0;
	private int tailSwing = 0;     // для анимации (клиент)
	private float renderScale = NORMAL_RENDER;
	public static final float LOW_HP = 8.0f;   // 4 сердца: тут она хватает игрока
	private static final ResourceKey<DamageType> CLONE_DAMAGE =
		ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FoxGirlMod.MOD_ID, "clone"));
	private UUID ritualPlayer = null;  // кого сейчас превращают в клона
	private int ritualTicks = 0;
	private int rageTicks = 0;
	private int rageCooldown = 0;
	private int huntTicks = 0;
	private boolean carrying = false;
	private int carryTicks = 0;
	private int hunterCooldown = 0;  // после доставки домой какое-то время не охотится
	private boolean ready = false; // защита от вызова getDimensions внутри конструктора

	public FoxGirlEntity(EntityType<? extends FoxGirlEntity> type, Level level) {
		super(type, level);
		this.ready = true;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 24.0)
			.add(Attributes.MOVEMENT_SPEED, 0.28)
			.add(Attributes.ATTACK_DAMAGE, 4.0)
			.add(Attributes.FOLLOW_RANGE, 24.0);
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		this.entityData.define(SIZE_STATE, 1);
		this.entityData.define(KITTEN, false);
		this.entityData.define(RAGE, false);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		if (SIZE_STATE.equals(key) || KITTEN.equals(key) || RAGE.equals(key)) this.refreshDimensions();
		if (KITTEN.equals(key)) this.renderScale = SCALES[this.getSizeState()] * NORMAL_RENDER * this.kittenFactor();
		super.onSyncedDataUpdated(key);
	}

	@Override
	public EntityDimensions getDimensions(Pose pose) {
		EntityDimensions base = super.getDimensions(pose);
		return this.ready ? base.scale(SCALES[this.getSizeState()] * this.kittenFactor()) : base;
	}

	public int getSizeState() { return Math.max(0, Math.min(2, this.entityData.get(SIZE_STATE))); }
	public float getScaleFactor() { return SCALES[this.getSizeState()]; }
	public float getRenderScale() { return this.renderScale; }
	public int getTailSwing() { return this.tailSwing; }
	public boolean isCarrying() { return this.carrying; }
	public int getCarryTicks() { return this.carryTicks; }

	@Override
	public double getPassengersRidingOffset() {
		return this.getBbHeight() * 0.5; // игрок «на руках» примерно на уровне груди
	}

	@Override
	public boolean canAddPassenger(Entity passenger) {
		return this.carrying && this.getPassengers().isEmpty();
	}

	private void tryGrab() {
		if (this.isTame() || this.isKitten() || this.hunterCooldown > 0) return;
		if (!this.level().dimension().equals(Backrooms.DIMENSION)) return;
		if (this.getTarget() instanceof ServerPlayer sp && sp.isAlive() && !sp.isCreative() && !sp.isSpectator()
			&& sp.getHealth() <= LOW_HP) {
			double r = 3.4 * this.getScaleFactor();
			if (this.distanceToSqr(sp) < r * r) this.grabPlayer(sp);
		}
	}

	public void grabPlayer(ServerPlayer sp) {
		if (this.carrying || this.isTame() || this.isKitten() || this.hunterCooldown > 0) return;
		if (!sp.isAlive() || sp.isCreative() || sp.isSpectator()) return;
		this.carrying = true; // до startRiding, чтобы canAddPassenger пропустил
		if (!sp.startRiding(this, true)) { this.carrying = false; return; }
		this.carryTicks = 0;
		this.setTarget(null);
		this.navigation.stop();
		this.playSound(SoundEvents.FOX_AMBIENT, 1.0f, 0.7f);
		sp.displayClientMessage(Component.translatable("message.foxgirl.grabbed"), true);
	}

	/** Идёт к дому; если путь не находится — идёт напрямую, а через 15 с переносится. */
	private void carryStep() {
		double dx = Backrooms.HOME_X - this.getX(), dz = Backrooms.HOME_Z - this.getZ();
		if (dx * dx + dz * dz < 9.0) { this.deliverPassenger(false); return; }
		if (this.carryTicks > 300) { this.deliverPassenger(true); return; }
		if (this.carryTicks % 10 == 1) {
			boolean ok = this.getNavigation().moveTo(Backrooms.HOME_X, Backrooms.HOME_Y, Backrooms.HOME_Z, 1.1);
			if (!ok) this.getMoveControl().setWantedPosition(Backrooms.HOME_X, Backrooms.HOME_Y, Backrooms.HOME_Z, 1.1);
		}
	}

	// ---------- превращение ----------

	private void updateRage() {
		if (this.isKitten()) return;
		LivingEntity t = this.getTarget();
		if (!this.isTame() && t instanceof Player) this.huntTicks++; else this.huntTicks = 0;

		if (this.rageTicks > 0) {
			this.rageTicks--;
			if (this.tickCount % 5 == 0 && this.level() instanceof ServerLevel sl) {
				sl.sendParticles(ParticleTypes.ANGRY_VILLAGER, this.getX(), this.getY() + this.getBbHeight() * 0.85, this.getZ(), 2, 0.4, 0.3, 0.4, 0.0);
			}
			if (this.rageTicks == 0) this.endRage();
			return;
		}
		if (this.rageCooldown > 0) { this.rageCooldown--; return; }

		boolean wild = !this.isTame() && t instanceof Player
			&& (this.getHealth() < this.getMaxHealth() * 0.5f || this.huntTicks > 600); // ранена или долго гоняется
		LivingEntity owner = this.getOwner();
		boolean guard = this.isTame() && this.hunger > 0 && owner != null && t != null && owner.getHealth() <= 6.0f; // хозяин в беде
		if (wild || guard) this.startRage();
	}

	private void startRage() {
		EntityDimensions d = this.getType().getDimensions().scale(SCALES[this.getSizeState()] * RAGE_FACTOR);
		if (!this.level().noCollision(this, d.makeBoundingBox(this.position()))) return; // негде вырасти

		this.rageTicks = RAGE_TICKS;
		this.huntTicks = 0;
		this.entityData.set(RAGE, true);
		this.refreshDimensions();
		AttributeInstance speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
		AttributeInstance dmg = this.getAttribute(Attributes.ATTACK_DAMAGE);
		if (speed != null) { speed.removeModifier(RAGE_SPEED_ID); speed.addTransientModifier(new AttributeModifier(RAGE_SPEED_ID, "foxgirl_rage", 0.3, AttributeModifier.Operation.MULTIPLY_TOTAL)); }
		if (dmg != null) { dmg.removeModifier(RAGE_DAMAGE_ID); dmg.addTransientModifier(new AttributeModifier(RAGE_DAMAGE_ID, "foxgirl_rage", 3.0, AttributeModifier.Operation.ADDITION)); }
		this.heal(6.0f);
		this.playSound(SoundEvents.RAVAGER_ROAR, 1.6f, 0.8f);

		// ударная волна: всех вокруг отбрасывает
		LivingEntity owner = this.getOwner();
		for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(6.0))) {
			if (e == this || e == owner || e instanceof FoxGirlEntity || !e.isAlive()) continue;
			e.knockback(1.3, this.getX() - e.getX(), this.getZ() - e.getZ());
			e.hurtMarked = true;
		}
		if (this.level() instanceof ServerLevel sl) {
			sl.sendParticles(ParticleTypes.LARGE_SMOKE, this.getX(), this.getY() + this.getBbHeight() * 0.5, this.getZ(), 40, 0.8, 0.8, 0.8, 0.05);
			sl.sendParticles(ParticleTypes.EXPLOSION, this.getX(), this.getY() + this.getBbHeight() * 0.5, this.getZ(), 1, 0, 0, 0, 0);
		}
	}

	private void endRage() {
		this.rageTicks = 0;
		this.rageCooldown = 2400; // 2 минуты до следующего превращения
		this.entityData.set(RAGE, false);
		this.refreshDimensions();
		AttributeInstance speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
		AttributeInstance dmg = this.getAttribute(Attributes.ATTACK_DAMAGE);
		if (speed != null) speed.removeModifier(RAGE_SPEED_ID);
		if (dmg != null) dmg.removeModifier(RAGE_DAMAGE_ID);
		this.playSound(SoundEvents.FOX_SCREECH, 1.0f, 1.2f);
		if (this.level() instanceof ServerLevel sl) {
			sl.sendParticles(ParticleTypes.POOF, this.getX(), this.getY() + this.getBbHeight() * 0.5, this.getZ(), 20, 0.5, 0.5, 0.5, 0.03);
		}
	}

	/** Доставила домой: начинается превращение игрока в клона. */
	public void deliverPassenger(boolean forceTeleport) {
		this.carrying = false;
		this.carryTicks = 0;
		this.hunterCooldown = 600;
		this.setTarget(null);
		this.navigation.stop();
		List<Entity> riders = new ArrayList<>(this.getPassengers());
		this.ejectPassengers();
		if (forceTeleport) this.moveTo(Backrooms.HOME_X, Backrooms.HOME_Y, Backrooms.HOME_Z, this.getYRot(), 0f);
		for (Entity e : riders) {
			if (e instanceof ServerPlayer sp) {
				if (forceTeleport && this.level() instanceof ServerLevel sl) {
					sp.teleportTo(sl, Backrooms.HOME_X + 1.0, Backrooms.HOME_Y, Backrooms.HOME_Z, sp.getYRot(), 0f);
				}
				this.beginRitual(sp);
			}
		}
	}

	// ---------- превращение игрока в клона ----------

	private void beginRitual(ServerPlayer sp) {
		this.ritualPlayer = sp.getUUID();
		this.ritualTicks = 100; // 5 секунд
		sp.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 6, false, false)); // не двинуться
		sp.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 140, 0, false, false));
		sp.displayClientMessage(Component.translatable("message.foxgirl.ritual_start"), true);
		this.playSound(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.5f, 0.6f);
	}

	private void ritualStep() {
		if (this.ritualPlayer == null) return;
		if (!(this.level() instanceof ServerLevel sl) || !(sl.getPlayerByUUID(this.ritualPlayer) instanceof ServerPlayer sp)
			|| !sp.isAlive() || this.distanceToSqr(sp) > 144.0) {
			this.ritualPlayer = null;
			return;
		}
		this.navigation.stop();
		this.getLookControl().setLookAt(sp, 30.0f, 30.0f);
		this.ritualTicks--;

		if (this.ritualTicks % 5 == 0) {
			sl.sendParticles(ParticleTypes.ENCHANT, sp.getX(), sp.getY() + 1.0, sp.getZ(), 25, 0.5, 0.9, 0.5, 0.6);
			sl.sendParticles(ParticleTypes.POOF, sp.getX(), sp.getY() + 0.5, sp.getZ(), 4, 0.3, 0.3, 0.3, 0.01);
		}
		if (this.ritualTicks == 50) {
			sp.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false));
			sp.displayClientMessage(Component.translatable("message.foxgirl.ritual_mid"), true);
			this.playSound(SoundEvents.FOX_AMBIENT, 1.0f, 0.5f);
		}
		if (this.ritualTicks <= 0) {
			this.ritualPlayer = null;
			this.finishRitual(sl, sp);
		}
	}

	private void finishRitual(ServerLevel sl, ServerPlayer sp) {
		// клон — котёнок с именем игрока
		FoxGirlEntity clone = FoxGirlMod.FOX_GIRL.get().create(sl);
		if (clone != null) {
			clone.setKitten(true);
			clone.moveTo(sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), 0f);
			clone.setCustomName(sp.getName());
			clone.setCustomNameVisible(true);
			clone.setPersistenceRequired();
			sl.addFreshEntity(clone);
		}
		sl.sendParticles(ParticleTypes.POOF, sp.getX(), sp.getY() + 0.8, sp.getZ(), 40, 0.5, 0.7, 0.5, 0.08);
		this.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0f, 0.5f);

		// сам игрок погибает (превращён); предметы падают в её комнате
		DamageSource src = new DamageSource(sl.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(CLONE_DAMAGE), this);
		sp.setHealth(0.0f);
		sp.die(src);
	}

	@Override
	public void handleEntityEvent(byte id) {
		if (id == 60) this.tailSwing = TAIL_ANIM_TICKS;
		else super.handleEntityEvent(id);
	}

	// ---------- взаимодействие ----------

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);

		if (stack.isEdible()) {
			boolean needsFood = !this.isTame() || this.hunger < MAX_HUNGER || this.getHealth() < this.getMaxHealth();
			if (needsFood) {
				if (!this.level().isClientSide) {
					FoodProperties food = stack.getItem().getFoodProperties();
					int value = food != null ? food.getNutrition() : 1;
					if (!player.getAbilities().instabuild) stack.shrink(1);
					this.hunger = Math.min(MAX_HUNGER, this.hunger + value * 2);
					this.heal(value);
					if (!this.isTame()) {
						if (this.random.nextInt(3) == 0) {
							this.tame(player);
							for (FoxGirlEntity k : this.level().getEntitiesOfClass(FoxGirlEntity.class, this.getBoundingBox().inflate(24.0), x -> x.isKitten() && !x.isTame())) {
								k.tame(player);
								k.setOrderedToSit(false);
							}
							this.navigation.stop();
							this.setTarget(null);
							this.setOrderedToSit(false);
							this.setInSittingPose(false);
							this.level().broadcastEntityEvent(this, (byte) 7); // сердечки
							player.displayClientMessage(Component.translatable("message.foxgirl.tamed"), true);
						} else {
							this.level().broadcastEntityEvent(this, (byte) 6); // дымок
						}
					} else {
						this.level().broadcastEntityEvent(this, (byte) 7);
					}
				}
				return InteractionResult.sidedSuccess(this.level().isClientSide);
			}
		}

		boolean owner = this.isTame() && this.isOwnedBy(player) && hand == InteractionHand.MAIN_HAND && stack.isEmpty();

		if (owner) {
			if (!this.level().isClientSide) {
				boolean sit = !this.isOrderedToSit();
				this.setOrderedToSit(sit);
				this.setInSittingPose(sit);
				this.jumping = false;
				this.navigation.stop();
				this.setTarget(null);
				player.displayClientMessage(Component.translatable(sit ? "message.foxgirl.sit" : "message.foxgirl.follow"), true);
			}
			return InteractionResult.sidedSuccess(this.level().isClientSide);
		}
		return InteractionResult.PASS;
	}

	@Override
	public void tick() {
		super.tick();

		if (this.level().isClientSide) {
			if (this.tailSwing > 0) this.tailSwing--;
			this.renderScale += (SCALES[this.getSizeState()] * NORMAL_RENDER * this.kittenFactor() - this.renderScale) * 0.25f;
			return;
		}

		if (this.tailCooldown > 0) this.tailCooldown--;
		if (this.hunterCooldown > 0) this.hunterCooldown--;
		if (this.carrying) {
			this.setTarget(null);
			if (!this.isVehicle()) { this.carrying = false; this.carryTicks = 0; this.hunterCooldown = 600; }
			else { this.carryTicks++; this.carryStep(); }
		} else {
			this.tryGrab();
		}
		this.ritualStep();
		this.updateRage();
		if (this.tickCount % 10 == 0) this.updateSize();
		this.tailAttack();

		if (!this.isTame() || this.isKitten()) return;

		// Голод
		if (this.tickCount % HUNGER_TICKS == 0 && this.hunger > 0) {
			this.hunger--;
			if (this.hunger == 0 && this.getOwner() instanceof ServerPlayer sp) {
				sp.displayClientMessage(Component.translatable("message.foxgirl.hungry"), true);
			}
		}
		if (this.hunger == 0) {
			if (this.tickCount % 100 == 0) {
				this.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 140, 0, true, false));
			}
			return;
		}

		// Помощь хозяину
		if (this.tickCount % 100 == 0) {
			LivingEntity owner = this.getOwner();
			if (owner == null || this.distanceToSqr(owner) > 12 * 12) return;

			if (owner.getHealth() <= owner.getMaxHealth() / 2.0f) {
				owner.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, true, true));
			}
			if (this.level().isNight()) {
				for (Monster m : this.level().getEntitiesOfClass(Monster.class, this.getBoundingBox().inflate(16.0))) {
					m.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0, true, false));
				}
			}
		}
	}

	@Override
	public boolean wantsToAttack(LivingEntity target, LivingEntity owner) {
		if (target instanceof Creeper || target instanceof Ghast) return false;
		if (target instanceof FoxGirlEntity other) return other.getOwner() != owner;
		if (target instanceof Player p && owner instanceof Player po && !po.canHarmPlayer(p)) return false;
		return !(target instanceof TamableAnimal t && t.isTame());
	}

	@Override
	public boolean isFood(ItemStack stack) { return false; }

	@Override
	public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob other) { return null; }

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putInt("FoxHunger", this.hunger);
		tag.putInt("SizeState", this.getSizeState());
		tag.putBoolean("Kitten", this.isKitten());
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		if (tag.contains("Kitten")) this.entityData.set(KITTEN, tag.getBoolean("Kitten"));
		if (tag.contains("FoxHunger")) this.hunger = tag.getInt("FoxHunger");
		if (tag.contains("SizeState")) {
			int s = Math.max(0, Math.min(2, tag.getInt("SizeState")));
			this.entityData.set(SIZE_STATE, s);
			this.renderScale = SCALES[s] * NORMAL_RENDER * this.kittenFactor();
			this.refreshDimensions();
		}
	}

	@Override protected SoundEvent getAmbientSound() { return SoundEvents.FOX_AMBIENT; }
	@Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.FOX_HURT; }
	@Override protected SoundEvent getDeathSound() { return SoundEvents.FOX_DEATH; }
}
