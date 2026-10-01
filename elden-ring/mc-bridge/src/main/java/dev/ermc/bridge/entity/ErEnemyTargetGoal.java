package dev.ermc.bridge.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Makes a Minecraft mob fight Elden Ring enemies: it targets the nearest live enemy proxy (a
 * boss or hostile character, never an NPC) that it can see within its follow range, clamped to
 * {@value #MIN_RANGE}..{@value #MAX_RANGE} blocks. The mob's own attack goals then treat the
 * proxy like any Minecraft target (a zombie's swing, a skeleton's bow, a creeper's fuse, an iron
 * golem's slam), and their hits reach Elden Ring through {@link ErEntity#hurt}.
 *
 * <p>Hostile mobs keep attacking players too, and take whichever is closer: the goal outranks
 * their player goals, so it switches a mob from a player to a closer enemy, and it lets go of the
 * enemy when a player they would attack comes clearly closer. Iron golems, snow golems and
 * tamed wolves (not sitting) only add Elden Ring enemies to what they already fight; their
 * village and owner defence comes first.
 */
public final class ErEnemyTargetGoal extends TargetGoal {
	private static final double MIN_RANGE = 16.0;
	private static final double MAX_RANGE = 24.0;
	/** A player has to be this much closer (blocks) than the enemy to win a hostile mob back. */
	private static final double PLAYER_MARGIN = 2.0;

	private final boolean alsoFightsPlayers;
	private final Predicate<Mob> willing;
	private ErEntity candidate;
	private int unseenTicks;

	private ErEnemyTargetGoal(Mob mob, boolean alsoFightsPlayers, Predicate<Mob> willing) {
		super(mob, true);
		this.alsoFightsPlayers = alsoFightsPlayers;
		this.willing = willing;
		this.setFlags(EnumSet.of(Goal.Flag.TARGET));
	}

	/**
	 * Adds the goal to the mobs that fight Elden Ring enemies (MobMixin: every new server-side
	 * mob, after its own goals). Lower priority numbers win.
	 */
	public static void addTo(Mob mob, GoalSelector targets) {
		if (mob instanceof Enemy) {
			// Hostile mobs whose targeting is goal-driven (brain-driven ones, like piglins,
			// hoglins, wardens and breezes, have no target goals). Slimes only hurt players and
			// iron golems, by touching them.
			if (!(mob instanceof Slime) && !targets.getAvailableGoals().isEmpty()) {
				targets.addGoal(0, new ErEnemyTargetGoal(mob, true, m -> true));
			}
		} else if (mob instanceof IronGolem) {
			targets.addGoal(2, new ErEnemyTargetGoal(mob, false, m -> true));  // after defending its village
		} else if (mob instanceof SnowGolem) {
			targets.addGoal(1, new ErEnemyTargetGoal(mob, false, m -> true));
		} else if (mob instanceof Wolf) {
			// Tamed and not told to sit; after defending its owner.
			targets.addGoal(2, new ErEnemyTargetGoal(mob, false, m -> m instanceof TamableAnimal t && t.isTame() && !t.isOrderedToSit()));
		}
	}

	@Override
	protected double getFollowDistance() {
		return Mth.clamp(super.getFollowDistance(), MIN_RANGE, MAX_RANGE);
	}

	@Override
	public boolean canUse() {
		if (!EntityBridge.hasEnemies() || this.mob.getRandom().nextInt(reducedTickDelay(5)) != 0 || !this.willing.test(this.mob)) {
			return false;
		}
		ErEntity enemy = EntityBridge.nearestVisibleEnemy(this.mob, this.getFollowDistance());
		if (enemy == null) {
			return false;
		}
		double dist = Math.sqrt(enemy.distanceToSqr(this.mob.position()));
		LivingEntity current = this.mob.getTarget();
		if (current != null && current.isAlive() && !(current instanceof ErEntity) && this.mob.distanceTo(current) < dist) {
			return false;  // already after something closer
		}
		if (this.alsoFightsPlayers && this.playerClearlyCloser(dist)) {
			return false;  // leave the player to the mob's own goals
		}
		this.candidate = enemy;
		return true;
	}

	/**
	 * TargetGoal's own test, but with the range measured to the hitbox like {@link #canUse}
	 * (Minecraft measures to the proxy's feet, which would drop a boss picked near the range's
	 * edge right away).
	 */
	@Override
	public boolean canContinueToUse() {
		if (!(this.mob.getTarget() instanceof ErEntity enemy) || !this.mob.canAttack(enemy) || !this.willing.test(this.mob)) {
			return false;  // canAttack: gone, dead, or no longer hostile
		}
		double range = this.getFollowDistance();
		double distSq = enemy.distanceToSqr(this.mob.position());
		if (distSq > range * range) {
			return false;
		}
		if (this.mob.getSensing().hasLineOfSight(enemy)) {
			this.unseenTicks = 0;
		} else if (++this.unseenTicks > reducedTickDelay(this.unseenMemoryTicks)) {
			return false;
		}
		return !(this.alsoFightsPlayers && this.playerClearlyCloser(Math.sqrt(distSq)));
	}

	@Override
	public void start() {
		this.mob.setTarget(this.candidate);
		this.unseenTicks = 0;
		super.start();
	}

	/** Clears the mob's target only if it is still the enemy (another goal or an alert may have given it a new one). */
	@Override
	public void stop() {
		if (this.mob.getTarget() instanceof ErEntity) {
			super.stop();
		}
		this.candidate = null;
	}

	/** A player this mob would attack is in sight and at least {@link #PLAYER_MARGIN} closer than an enemy {@code enemyDist} away. */
	private boolean playerClearlyCloser(double enemyDist) {
		double within = enemyDist - PLAYER_MARGIN;
		if (within <= 0.0) {
			return false;
		}
		Player player = this.mob.level().getNearestPlayer(this.mob.getX(), this.mob.getY(), this.mob.getZ(), within,
			EntitySelector.NO_CREATIVE_OR_SPECTATOR);
		return player != null && this.wouldFight(player) && this.mob.getSensing().hasLineOfSight(player);
	}

	/**
	 * Whether the mob's own goals go after {@code player} at all right now: neutral mobs (endermen,
	 * zombified piglins) only when angry at them, spiders only in the dark, drowned by their own
	 * rule. Otherwise it keeps fighting the enemy rather than stand idle next to the player.
	 */
	@SuppressWarnings("deprecation")  // the light test Spider.SpiderTargetGoal itself uses
	private boolean wouldFight(Player player) {
		if (!this.mob.canAttack(player)) {
			return false;
		}
		if (this.mob instanceof NeutralMob neutral && !neutral.isAngryAt(player)) {
			return false;
		}
		if (this.mob instanceof Spider && this.mob.getLightLevelDependentMagicValue() >= 0.5F) {
			return false;
		}
		return !(this.mob instanceof Drowned drowned) || drowned.okTarget(player);
	}
}
