package dev.ermc.bridge.entity;

import dev.ermc.bridge.link.Protocol;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Invisible stand-in for a character in the host game (an Elden Ring enemy, boss or NPC). It
 * carries the character's hitbox, so Minecraft's crosshair, swords, arrows, explosions and mobs
 * can hit it; {@link EntityBridge} forwards the hits to the host game as damage. Its hitbox is
 * an arbitrary axis-aligned box, not Minecraft's usual square footprint.
 *
 * <p>It is a {@link LivingEntity} so that Minecraft mobs can target it with their own AI
 * (melee, bows, creeper fuses...). Nothing in Minecraft moves it or ends it: EntityBridge
 * places it where the character is every tick, and the host game's HP decides whether it lives
 * (dead characters' proxies are removed). Only enemies ({@link Protocol#ENT_LARGE_MONSTER},
 * {@link Protocol#ENT_SMALL_MONSTER}) take hits; an NPC's proxy only shows in the debug view.
 */
public class ErEntity extends LivingEntity {
	private static final EntityDataAccessor<Long> DATA_HOST_ID = SynchedEntityData.defineId(ErEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Integer> DATA_KIND = SynchedEntityData.defineId(ErEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_HX = SynchedEntityData.defineId(ErEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_HY = SynchedEntityData.defineId(ErEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_HZ = SynchedEntityData.defineId(ErEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_HP = SynchedEntityData.defineId(ErEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_MAX_HP = SynchedEntityData.defineId(ErEntity.class, EntityDataSerializers.FLOAT);
	private static final List<ItemStack> NO_ARMOR = List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
	/**
	 * Terrain blocks this close to the body (blocks) don't hide or shield it from Minecraft mobs
	 * and explosions: Minecraft's terrain is the host game's collision in whole 1x1 columns, so a
	 * character standing at a wall or on a slope overlaps terrain blocks a little.
	 */
	private static final double BODY_MARGIN_XZ = 1.0;
	private static final double BODY_MARGIN_Y = 0.5;

	/** The player whose {@code Player.attack} on this proxy is in progress (see PlayerMixin), and whether it is a critical hit. */
	@Nullable
	private Player attackingPlayer;
	private boolean attackIsCritical;

	public ErEntity(EntityType<? extends ErEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
		// The character isn't physically in Minecraft's world: blocks (TNT at a boss's feet) can
		// still be placed inside its box. LivingEntity turns this on.
		this.blocksBuilding = false;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return LivingEntity.createLivingAttributes()
			.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
			.add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_HOST_ID, 0L);
		builder.define(DATA_KIND, 0);
		builder.define(DATA_HX, 0.5F);
		builder.define(DATA_HY, 0.5F);
		builder.define(DATA_HZ, 0.5F);
		builder.define(DATA_HP, 0.0F);
		builder.define(DATA_MAX_HP, 0.0F);
	}

	public long hostId() {
		return this.entityData.get(DATA_HOST_ID);
	}

	/** {@link Protocol#ENT_LARGE_MONSTER}, {@link Protocol#ENT_SMALL_MONSTER} or {@link Protocol#ENT_OTHER}. */
	public int kind() {
		return this.entityData.get(DATA_KIND);
	}

	/** The kind changes when the host game turns an NPC hostile. */
	public void setHostId(long id, int kind) {
		this.entityData.set(DATA_HOST_ID, id);
		this.entityData.set(DATA_KIND, kind);
	}

	/** Host-game HP, for the debug view's tag. */
	public float hp() {
		return this.entityData.get(DATA_HP);
	}

	public float maxHp() {
		return this.entityData.get(DATA_MAX_HP);
	}

	public void setHp(float hp, float maxHp) {
		if (Math.abs(hp - hp()) > 0.5F || Math.abs(maxHp - maxHp()) > 0.5F) {
			this.entityData.set(DATA_HP, hp);
			this.entityData.set(DATA_MAX_HP, maxHp);
		}
	}

	/** A hostile character (enemy or boss), not a friendly or neutral NPC. */
	public boolean isEnemy() {
		int kind = kind();
		return kind == Protocol.ENT_LARGE_MONSTER || kind == Protocol.ENT_SMALL_MONSTER;
	}

	/** Something Minecraft mobs may fight: a live enemy, as of the host game's last report. */
	public boolean isTargetableEnemy() {
		return !this.isRemoved() && isEnemy() && !(maxHp() > 0.0F && hp() <= 0.0F);
	}

	/** Half extents of the hitbox in blocks. The entity position is the box's bottom center. */
	public void setBox(float hx, float hy, float hz) {
		if (Math.abs(hx - this.entityData.get(DATA_HX)) > 0.01F || Math.abs(hy - this.entityData.get(DATA_HY)) > 0.01F
			|| Math.abs(hz - this.entityData.get(DATA_HZ)) > 0.01F) {
			this.entityData.set(DATA_HX, hx);
			this.entityData.set(DATA_HY, hy);
			this.entityData.set(DATA_HZ, hz);
			this.refreshDimensions();
		}
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (DATA_HX.equals(accessor) || DATA_HY.equals(accessor) || DATA_HZ.equals(accessor)) {
			this.refreshDimensions();
		}
	}

	@Override
	protected EntityDimensions getDefaultDimensions(Pose pose) {
		float hx = this.entityData.get(DATA_HX);
		float hy = this.entityData.get(DATA_HY);
		float hz = this.entityData.get(DATA_HZ);
		return EntityDimensions.fixed(2.0F * Math.max(hx, hz), 2.0F * hy);
	}

	@Override
	protected AABB makeBoundingBox() {
		double hx = this.entityData.get(DATA_HX);
		double hy = this.entityData.get(DATA_HY);
		double hz = this.entityData.get(DATA_HZ);
		return new AABB(getX() - hx, getY(), getZ() - hz, getX() + hx, getY() + 2.0 * hy, getZ() + hz);
	}

	/**
	 * Distance to the nearest point of the hitbox, not to the box's bottom center, so TNT at a
	 * boss's side counts as close (explosions measure their reach and damage with this).
	 */
	@Override
	public double distanceToSqr(Vec3 p) {
		AABB b = this.getBoundingBox();
		double dx = Math.max(Math.max(b.minX - p.x, 0.0), p.x - b.maxX);
		double dy = Math.max(Math.max(b.minY - p.y, 0.0), p.y - b.maxY);
		double dz = Math.max(Math.max(b.minZ - p.z, 0.0), p.z - b.maxZ);
		return dx * dx + dy * dy + dz * dz;
	}

	/**
	 * EntityBridge places the proxy every server tick and the host game decides whether the
	 * character lives, so there is no physics, AI, pushing, fluid, fire, air, portal or dying
	 * here. Only the hurt cooldown and effects tick (poison and wither damage is forwarded like
	 * any hit).
	 */
	@Override
	public void tick() {
		this.firstTick = false;
		if (this.invulnerableTime > 0) {
			this.invulnerableTime--;
		}
		this.tickEffects();
	}

	/** Snap to the server's position (as a plain entity would); LivingEntity's interpolation runs in the skipped aiStep. */
	@Override
	public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
		this.setPos(x, y, z);
		this.setRot(yRot, xRot);
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (this.level().isClientSide || !isTargetableEnemy()) {
			return false;
		}
		// The host game owns the character's life: "/kill" and the void don't reach it.
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return false;
		}
		// Minecraft's hurt cooldown, as on any mob (LivingEntity.hurt): for half a second after a
		// hit, only what a bigger hit adds counts, so multishot, a stack of TNT or a crowd of
		// zombies don't multiply. The DLL's HP conversion assumes Minecraft mob damage.
		float dealt = amount;
		if (this.invulnerableTime > 10 && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
			if (amount <= this.lastHurt) {
				return false;
			}
			dealt = amount - this.lastHurt;
			this.lastHurt = amount;
		} else {
			this.lastHurt = amount;
			this.invulnerableTime = 20;
		}
		EntityBridge.onHit(this, source, dealt, isCritical(source));
		return true;
	}

	private boolean isCritical(DamageSource source) {
		Entity direct = source.getDirectEntity();
		if (this.attackingPlayer != null && direct == this.attackingPlayer && source.is(DamageTypes.PLAYER_ATTACK)) {
			return this.attackIsCritical;
		}
		return direct instanceof AbstractArrow arrow && arrow.isCritArrow() && arrow.getOwner() instanceof Player;
	}

	/** Around {@code Player.attack} on this proxy (PlayerMixin): who swings and whether the swing is critical; null when it ends. */
	public void noteAttack(@Nullable Player player, boolean critical) {
		this.attackingPlayer = player;
		this.attackIsCritical = critical;
	}

	/**
	 * Minecraft's critical-hit test from {@code Player.attack} (1.21.1): a fully charged swing
	 * while falling, not on a ladder or vine, not in water, not blind, not riding, not sprinting.
	 * It has to be evaluated before the attack, which resets the swing charge.
	 */
	public static boolean isCriticalSwing(Player player) {
		return player.getAttackStrengthScale(0.5F) > 0.9F && player.fallDistance > 0.0F && !player.onGround()
			&& !player.onClimbable() && !player.isInWater() && !player.hasEffect(MobEffects.BLINDNESS)
			&& !player.isPassenger() && !player.isSprinting();
	}

	/**
	 * How much of the body a blast at {@code center} reaches (0..1), in place of Minecraft's
	 * {@code Explosion.getSeenPercent} (see ExplosionMixin): a fixed 3x3x3 lattice over the box
	 * plus the box point nearest the blast, so the cost doesn't grow with the box. Terrain
	 * right at the body doesn't shield it (see {@link #BODY_MARGIN_XZ}).
	 */
	public float explosionExposure(Vec3 center) {
		AABB box = this.getBoundingBox();
		AABB body = box.inflate(BODY_MARGIN_XZ, BODY_MARGIN_Y, BODY_MARGIN_XZ);
		if (body.contains(center)) {
			return 1.0F;
		}
		int seen = 0;
		int total = 0;
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				for (int k = 0; k < 3; k++) {
					Vec3 p = new Vec3(Mth.lerp((i + 0.5) / 3.0, box.minX, box.maxX), Mth.lerp((j + 0.5) / 3.0, box.minY, box.maxY),
						Mth.lerp((k + 0.5) / 3.0, box.minZ, box.maxZ));
					total++;
					if (clearPath(center, p, body)) {
						seen++;
					}
				}
			}
		}
		total++;
		if (clearPath(center, EntityBridge.closestPoint(box, center), body)) {
			seen++;
		}
		return (float) seen / total;
	}

	/**
	 * Whether {@code observer} can see the body, used when Minecraft's own line-of-sight test
	 * (eye to eye) fails (see LivingEntityMixin): the eye point, the center or the point nearest
	 * the observer, ignoring terrain right at the body.
	 */
	public boolean visibleFrom(Entity observer) {
		if (observer.level() != this.level()) {
			return false;
		}
		Vec3 eye = observer.getEyePosition();
		AABB box = this.getBoundingBox();
		AABB body = box.inflate(BODY_MARGIN_XZ, BODY_MARGIN_Y, BODY_MARGIN_XZ);
		if (body.contains(eye)) {
			return true;
		}
		Vec3 center = box.getCenter();
		if (center.distanceToSqr(eye) > 128.0 * 128.0) {
			return false;
		}
		return clearPath(eye, new Vec3(this.getX(), this.getEyeY(), this.getZ()), body) || clearPath(eye, center, body)
			|| clearPath(eye, EntityBridge.closestPoint(box, eye), body);
	}

	/** No terrain between {@code from} (outside {@code body}) and where the line to {@code bodyPoint} enters {@code body}. */
	private boolean clearPath(Vec3 from, Vec3 bodyPoint, AABB body) {
		Vec3 end = body.clip(from, bodyPoint).orElse(bodyPoint);
		if (end.distanceToSqr(from) < 1.0E-6) {
			return true;
		}
		return this.level().clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType()
			== HitResult.Type.MISS;
	}

	/** Mobs don't see NPCs or characters that died: they can't be targeted. */
	@Override
	public boolean canBeSeenByAnyone() {
		return isTargetableEnemy() && super.canBeSeenByAnyone();
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved() && isEnemy();
	}

	@Override
	public boolean isAttackable() {
		return isEnemy();
	}

	@Override
	public boolean canBeHitByProjectile() {
		return isPickable();
	}

	@Override
	public boolean ignoreExplosion(Explosion explosion) {
		return !isEnemy();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	/**
	 * Effects that deal damage (poison, wither, harming: a cave spider's bite, a wither
	 * skeleton's, tipped arrows, potions), forwarded as hits, plus slowness and weakness, which
	 * change nothing here but let a witch move on to its damaging potions.
	 */
	@Override
	public boolean canBeAffected(MobEffectInstance effect) {
		return isEnemy() && (effect.is(MobEffects.POISON) || effect.is(MobEffects.WITHER) || effect.is(MobEffects.HARM)
			|| effect.is(MobEffects.MOVEMENT_SLOWDOWN) || effect.is(MobEffects.WEAKNESS));
	}

	@Override
	public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
		return false;
	}

	@Override
	public boolean canUsePortal(boolean allowPassengers) {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public Iterable<ItemStack> getArmorSlots() {
		return NO_ARMOR;
	}

	@Override
	public ItemStack getItemBySlot(EquipmentSlot slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
	}

	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
	}
}
