package foxgirl;

import net.minecraft.core.particles.ParticleTypes;
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

import java.util.UUID;

/**
 * Фурритян — компаньон для выживания (живёт в Закулисье).
 *  - В Закулисье дикая охотится на игрока; её можно отвлечь пластинкой в проигрывателе / нотным блоком.
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
	private static final UUID BIG_DAMAGE_ID = UUID.fromString("5b7f6a40-2d1c-4a77-9a50-0c4f0a1b9e11");

	private int hunger = 12;
	private int sizeHold = 0;      // сколько тиков держать размер, чтобы не дёргалась
	private int tailCooldown = 0;
	private int tailSwing = 0;     // для анимации (клиент)
	private float renderScale = NORMAL_RENDER;
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
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		if (SIZE_STATE.equals(key) || KITTEN.equals(key)) this.refreshDimensions();
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
	public boolean isKitten() { return this.entityData.get(KITTEN); }
	private float kittenFactor() { return this.ready && this.isKitten() ? KITTEN_FACTOR : 1f; }

	public void setKitten(boolean kitten) {
		this.entityData.set(KITTEN, kitten);
		if (kitten) {
			this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10.0);
			this.setHealth(10.0f);
		}
		this.refreshDimensions();
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(1, new FloatGoal(this));
		this.goalSelector.addGoal(2, new SitWhenOrderedToGoal(this));
		this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, Creeper.class, 8.0f, 1.1, 1.6));
		this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.2, true));
		this.goalSelector.addGoal(5, new FollowOwnerGoal(this, 1.1, 8.0f, 2.0f, false));
		this.goalSelector.addGoal(5, new InvestigateLureGoal(this));
		this.goalSelector.addGoal(6, new FollowMotherGoal(this));
		this.goalSelector.addGoal(6, new TemptGoal(this, 1.1,
			Ingredient.of(Items.SWEET_BERRIES, Items.COOKED_CHICKEN, Items.COOKED_BEEF, Items.COOKED_PORKCHOP), false));
		this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.9));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
		this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

		this.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this));
		this.targetSelector.addGoal(2, new OwnerHurtTargetGoal(this));
		this.targetSelector.addGoal(3, new HurtByTargetGoal(this).setAlertOthers());
		// В Закулисье дикая Фурритян охотится на игрока
		this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
			e -> !this.isKitten() && !this.isTame() && this.level().dimension().equals(Backrooms.DIMENSION)));
		this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, 5, false, false,
			e -> !this.isKitten() && this.isTame() && this.hunger > 0 && !(e instanceof Creeper)));
	}

	@Override
	public void setTame(boolean tame) {
		super.setTame(tame);
		if (tame) {
			double hp = this.isKitten() ? 14.0 : 30.0;
			this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);
			this.setHealth((float) hp);
		}
	}

	// ---------- размер ----------

	private boolean canFit(int state) {
		EntityDimensions d = this.getType().getDimensions().scale(SCALES[state] * this.kittenFactor());
		AABB box = d.makeBoundingBox(this.position());
		return this.level().noCollision(this, box);
	}

	private void setSizeState(int target) {
		int current = this.getSizeState();
		if (target == current) return;
		if (target > current && !this.canFit(target)) return; // расти можно, только если помещаешься

		this.entityData.set(SIZE_STATE, target);
		this.refreshDimensions();

		this.setMaxUpStep(target == 2 ? 1.25f : 0.6f); // большая перешагивает блок

		AttributeInstance dmg = this.getAttribute(Attributes.ATTACK_DAMAGE);
		if (dmg != null) {
			dmg.removeModifier(BIG_DAMAGE_ID);
			if (target == 2) dmg.addTransientModifier(new AttributeModifier(BIG_DAMAGE_ID, "foxgirl_big", 4.0, AttributeModifier.Operation.ADDITION));
		}
		if (this.level() instanceof ServerLevel sl) {
			sl.sendParticles(ParticleTypes.POOF, this.getX(), this.getY() + this.getBbHeight() / 2, this.getZ(), 14, 0.3, 0.3, 0.3, 0.03);
		}
		this.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, target > current ? 0.7f : 1.5f);
	}

	/**
	 * Смотрит, что мешает пройти вперёд (куда она идёт по пути).
	 * 0 = надо стать маленькой (проход узкий/низкий), 2 = надо стать большой (перешагнуть блок), 1 = всё ок.
	 */
	private int obstacleAhead() {
		if (!this.getNavigation().isInProgress()) return 1;
		Path path = this.getNavigation().getPath();
		if (path == null) return 1;
		Vec3 dest = path.getNextEntityPos(this);
		double dx = dest.x - this.getX(), dz = dest.z - this.getZ();
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 0.1) return 1;
		dx = dx / len * 0.9;
		dz = dz / len * 0.9;

		AABB ahead = this.getBoundingBox().move(dx, 0, dz);
		if (this.level().noCollision(this, ahead)) return 1; // дорога свободна

		// Пролезет, если станет маленькой?
		EntityDimensions small = this.getType().getDimensions().scale(SCALES[0]);
		if (this.level().noCollision(this, small.makeBoundingBox(this.position().add(dx, 0, dz)))) return 0;

		// Перед ней уступ в 1 блок — большая просто перешагнёт
		if (this.level().noCollision(this, ahead.move(0, 1.0, 0))) return 2;
		return 1;
	}

	/** Размер выбирает сама Фурритян — по ситуации. */
	private void updateSize() {
		if (this.isKitten()) { this.setSizeState(1); return; }
		if (this.sizeHold > 0) { this.sizeHold -= 10; return; }

		int want = 1;
		int hold = 0;
		LivingEntity t = this.getTarget();
		boolean lowHealth = this.getHealth() < this.getMaxHealth() * 0.3f;
		boolean creeperNear = !this.level().getEntitiesOfClass(Creeper.class, this.getBoundingBox().inflate(8.0)).isEmpty();

		if (lowHealth || creeperNear) {                  // прячется
			want = 0; hold = 60;
		} else {
			int obstacle = this.obstacleAhead();
			if (obstacle == 0) { want = 0; hold = 80; }  // протиснуться в узкий проход
			else if (obstacle == 2) { want = 2; hold = 80; } // перешагнуть через уступ
			else if (t != null && t.isAlive()) want = 2; // в бою — большая
		}
		while (want > this.getSizeState() && !this.canFit(want)) want--;
		this.setSizeState(want);
		this.sizeHold = hold;
	}

	// ---------- хвост ----------

	private void tailAttack() {
		if (this.isKitten()) return;
		LivingEntity target = this.getTarget();
		if (target == null || !target.isAlive() || this.tailCooldown > 0) return;
		if (this.isTame() && this.hunger <= 0) return;

		float s = this.getScaleFactor();
		double reach = 3.3 * s + 0.6;
		if (this.distanceToSqr(target) > reach * reach) return;

		this.tailCooldown = TAIL_COOLDOWN;
		this.level().broadcastEntityEvent(this, (byte) 60);
		this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0f, 0.8f);

		float dmg = 5.0f + (this.getSizeState() == 2 ? 3.0f : 0.0f);
		double r = reach * 0.8;
		for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(r, 0.5, r))) {
			if (e == this || !e.isAlive()) continue;
			boolean hit = e == target || (e instanceof Monster && !(e instanceof Creeper));
			if (!hit) continue;
			e.hurt(this.damageSources().mobAttack(this), dmg);
			e.knockback(0.9, this.getX() - e.getX(), this.getZ() - e.getZ());
		}
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
