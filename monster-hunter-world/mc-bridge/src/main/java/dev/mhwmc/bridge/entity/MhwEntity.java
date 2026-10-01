package dev.mhwmc.bridge.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Invisible stand-in for something hittable in MHW (a monster, ...). It carries MHW's hitbox
 * so Minecraft's crosshair, swords and arrows can target it; hits are forwarded to MHW by
 * {@link EntityBridge}. Its hitbox is an arbitrary axis-aligned box (not Minecraft's usual
 * square footprint), because MHW monsters are long and low.
 *
 * <p>A living entity, so Minecraft's melee rules apply to it as to any mob: the swing charge
 * scales the damage, a fully charged swing while falling is a critical hit (x1.5, crit
 * particles and sound, and MHW's critical damage number), and a shield blocks its blows. Its
 * life stays MHW's: Minecraft never lowers its health.
 */
public class MhwEntity extends LivingEntity {
	private static final EntityDataAccessor<Long> DATA_MHW_ID = SynchedEntityData.defineId(MhwEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Integer> DATA_KIND = SynchedEntityData.defineId(MhwEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_HX = SynchedEntityData.defineId(MhwEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_HY = SynchedEntityData.defineId(MhwEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_HZ = SynchedEntityData.defineId(MhwEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_HP = SynchedEntityData.defineId(MhwEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_MAX_HP = SynchedEntityData.defineId(MhwEntity.class, EntityDataSerializers.FLOAT);
	private static final List<ItemStack> NO_ARMOR = List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

	/** Whether the {@code Player.attack} on this proxy in progress (see PlayerMixin) is a critical hit. */
	private boolean attackIsCritical;

	public MhwEntity(EntityType<? extends MhwEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return LivingEntity.createLivingAttributes()
			.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
			.add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_MHW_ID, 0L);
		builder.define(DATA_KIND, 0);
		builder.define(DATA_HX, 0.5F);
		builder.define(DATA_HY, 0.5F);
		builder.define(DATA_HZ, 0.5F);
		builder.define(DATA_HP, 0.0F);
		builder.define(DATA_MAX_HP, 0.0F);
	}

	public long mhwId() {
		return this.entityData.get(DATA_MHW_ID);
	}

	public int kind() {
		return this.entityData.get(DATA_KIND);
	}

	public void setMhwId(long id, int kind) {
		this.entityData.set(DATA_MHW_ID, id);
		this.entityData.set(DATA_KIND, kind);
	}

	/** MHW HP, for the debug view's tag. */
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
	public EntityDimensions getDefaultDimensions(Pose pose) {
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
	 * Distance to the nearest point of the hitbox, not to the box's bottom center: MHW
	 * monsters are long, and TNT at a head or tail must still count as close (explosions
	 * measure their reach and damage with this).
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
	 * Blows from players, mobs, projectiles and explosions go to MHW. The rest is Minecraft's
	 * environment (the proxy sits inside terrain, may stand in lava or water...), and MHW's
	 * monster doesn't feel it. Minecraft's hurt cooldown applies as on any mob: for half a second
	 * after a hit, only what a bigger hit adds counts, so a stack of TNT doesn't multiply.
	 */
	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (this.level().isClientSide || this.isRemoved()) {
			return false;
		}
		boolean blow = source.getEntity() != null || source.getDirectEntity() instanceof Projectile
			|| source.is(DamageTypeTags.IS_EXPLOSION);
		if (!blow || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return false;
		}
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
		boolean critical = this.attackIsCritical && source.getDirectEntity() instanceof Player;
		EntityBridge.onHit(this, source, dealt, critical);
		return true;
	}

	/** Set by PlayerMixin around {@code Player.attack} on this proxy. */
	public void noteAttack(boolean critical) {
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

	@Override
	public Iterable<ItemStack> getArmorSlots() {
		return NO_ARMOR;
	}

	@Override
	public ItemStack getItemBySlot(EquipmentSlot slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void setItemSlot(EquipmentSlot slot, @Nullable ItemStack stack) {
	}

	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean isAttackable() {
		return true;
	}

	@Override
	public boolean canBeHitByProjectile() {
		return true;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(net.minecraft.world.entity.Entity entity) {
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
	}
}
