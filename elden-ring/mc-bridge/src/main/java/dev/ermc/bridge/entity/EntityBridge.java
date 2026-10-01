package dev.ermc.bridge.entity;

import dev.ermc.bridge.CoordMap;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.link.EntityInfo;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.Protocol;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps one {@link ErEntity} per character the host game publishes (Elden Ring: every
 * character within 80 m), matching its position and hitbox every tick, and forwards Minecraft
 * hits on enemies to the host game as damage.
 *
 * <p>Damage goes out in Minecraft damage points, exactly as Minecraft's {@code hurt()} got it
 * (after critical hits, enchantments, Strength, explosion falloff), whoever dealt it: the
 * player, a zombie, a skeleton's arrow, a creeper, TNT, an iron golem. The DLL converts points
 * to Elden Ring HP by the enemy's max HP (see {@link Protocol#OFF_DAMAGE}).
 */
public final class EntityBridge {
	private EntityBridge() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	/** Hitbox half extents (x, y = half height, z) in metres when the host game gives none. */
	private static final double[] SMALL_BOX = {0.4, 0.95, 0.4};
	private static final double[] LARGE_BOX = {1.3, 1.8, 1.3};
	/** Hits by anything but a player are logged at most this often (a fight with many mobs). */
	private static final long OTHER_HIT_LOG_MS = 1000;

	private static final Long2ObjectOpenHashMap<ErEntity> PROXIES = new Long2ObjectOpenHashMap<>();
	/** Proxies of live enemies (bosses and hostile characters, never NPCs), rebuilt every tick; server thread only. */
	private static final List<ErEntity> ENEMIES = new ArrayList<>();
	private static final List<EntityInfo> ENTITIES = new ArrayList<>();
	private static final LongOpenHashSet SEEN = new LongOpenHashSet();
	private static final LongOpenHashSet DIED = new LongOpenHashSet();
	private static long lastOtherHitLog;
	private static int unloggedHits;

	public static void reset() {
		PROXIES.clear();
		ENEMIES.clear();
	}

	public static void onServerTick(MinecraftServer server) {
		if (!TerrainManager.isBridgeWorld()) {
			return;
		}
		CoordMap.Mapping map = CoordMap.get();
		ServerLevel level = server.overworld();
		if (map == null || map.provisional() || !ErLink.get().readEntities(ENTITIES)) {
			removeAll();
			return;
		}
		SEEN.clear();
		DIED.clear();
		for (EntityInfo e : ENTITIES) {
			if (e.dead()) {
				// Dying, or a corpse still in the host game's list: the proxy goes, so mobs drop it.
				DIED.add(e.id);
				continue;
			}
			SEEN.add(e.id);
			ErEntity proxy = PROXIES.get(e.id);
			if (proxy == null || proxy.isRemoved()) {
				proxy = ErBridgeEntities.ER_ENTITY.create(level);
				if (proxy == null) {
					continue;
				}
				apply(proxy, e, map);
				level.addFreshEntity(proxy);
				PROXIES.put(e.id, proxy);
				LOG.info("Elden Ring {} appeared: {} (hp {}/{})", kindName(e.kind), proxy.getName().getString(), e.hp, e.maxHp);
			} else {
				apply(proxy, e, map);
			}
		}
		PROXIES.long2ObjectEntrySet().removeIf(en -> {
			if (SEEN.contains(en.getLongKey())) {
				return false;
			}
			ErEntity proxy = en.getValue();
			if (DIED.contains(en.getLongKey())) {
				LOG.info("Elden Ring {} died: {}", kindName(proxy.kind()), proxy.getName().getString());
			}
			proxy.discard();
			return true;
		});
		ENEMIES.clear();
		for (ErEntity proxy : PROXIES.values()) {
			if (proxy.isTargetableEnemy()) {
				ENEMIES.add(proxy);
			}
		}
	}

	private static void removeAll() {
		for (ErEntity e : PROXIES.values()) {
			e.discard();
		}
		PROXIES.clear();
		ENEMIES.clear();
	}

	private static String kindName(int kind) {
		return kind == Protocol.ENT_LARGE_MONSTER ? "boss" : kind == Protocol.ENT_SMALL_MONSTER ? "enemy" : "NPC";
	}

	/** Host-game hitbox (host units) -> axis-aligned Minecraft box (blocks). */
	private static void apply(ErEntity proxy, EntityInfo e, CoordMap.Mapping map) {
		proxy.setHostId(e.id, e.kind);
		double upm = map.unitsPerMeter();
		double[] h = {e.boxHalf[0], e.boxHalf[1], e.boxHalf[2]};
		double[] c = {e.boxCenter[0], e.boxCenter[1], e.boxCenter[2]};
		boolean worldAligned = e.worldAlignedBox();
		if (!(h[0] > 0) || !(h[1] > 0) || !(h[2] > 0)) {
			// No hitbox from the host game: an upright box standing on the feet.
			double[] d = e.kind == Protocol.ENT_LARGE_MONSTER ? LARGE_BOX : SMALL_BOX;
			h = new double[] {d[0] * upm, d[1] * upm, d[2] * upm};
			c = new double[] {e.pos[0], e.pos[1] + h[1], e.pos[2]};
			worldAligned = true;
		}
		double[] w = new double[3];
		if (worldAligned) {
			System.arraycopy(h, 0, w, 0, 3);
		} else {
			// Box along the character's axes: the world-aligned box around it.
			float[] r = rotation(e.quat);
			for (int i = 0; i < 3; i++) {
				w[i] = Math.abs(r[i * 3]) * h[0] + Math.abs(r[i * 3 + 1]) * h[1] + Math.abs(r[i * 3 + 2]) * h[2];
			}
		}
		double s = 1.0 / upm;
		Vec3 center = map.toMc(c[0], c[1], c[2]);
		float hx = (float) (w[0] * s);
		float hy = (float) (w[1] * s);
		float hz = (float) (w[2] * s);
		proxy.setBox(hx, hy, hz);
		proxy.setPos(center.x, center.y - hy, center.z);
		String name = e.name.isEmpty() ? "Elden Ring " + kindName(e.kind) : e.name;
		Component shown = proxy.getCustomName();
		if (shown == null || !shown.getString().equals(name)) {
			proxy.setCustomName(Component.literal(name));
		}
		if (e.maxHp > 0) {
			proxy.setHp(e.hp, e.maxHp);
		}
	}

	static boolean hasEnemies() {
		return !ENEMIES.isEmpty();
	}

	/** The live enemy proxy (never an NPC's) whose hitbox is nearest to {@code pos}, within {@code maxDist} blocks, or null. */
	static ErEntity nearestEnemy(Vec3 pos, double maxDist) {
		ErEntity best = null;
		double bestSq = maxDist * maxDist;
		for (ErEntity e : ENEMIES) {
			if (!e.isTargetableEnemy()) {
				continue;
			}
			double d = e.distanceToSqr(pos);
			if (d < bestSq) {
				best = e;
				bestSq = d;
			}
		}
		return best;
	}

	/** The nearest live enemy proxy {@code mob} can see, its hitbox within {@code range} blocks of the mob's feet, or null. */
	static ErEntity nearestVisibleEnemy(Mob mob, double range) {
		ErEntity best = null;
		double bestSq = range * range;
		Vec3 feet = mob.position();
		for (ErEntity e : ENEMIES) {
			if (e.level() != mob.level() || !e.isTargetableEnemy()) {
				continue;
			}
			double d = e.distanceToSqr(feet);
			if (d < bestSq && mob.getSensing().hasLineOfSight(e)) {
				best = e;
				bestSq = d;
			}
		}
		return best;
	}

	/** Point of {@code box} nearest to {@code p}. */
	static Vec3 closestPoint(AABB box, Vec3 p) {
		return new Vec3(Mth.clamp(p.x, box.minX, box.maxX), Mth.clamp(p.y, box.minY, box.maxY), Mth.clamp(p.z, box.minZ, box.maxZ));
	}

	/** Row-major 3x3 rotation matrix of quaternion (x, y, z, w). */
	private static float[] rotation(float[] q) {
		float x = q[0], y = q[1], z = q[2], w = q[3];
		float n = x * x + y * y + z * z + w * w;
		if (n < 1e-6F) {
			return new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
		}
		float s = 2.0F / n;
		return new float[] {
			1 - s * (y * y + z * z), s * (x * y - z * w), s * (x * z + y * w),
			s * (x * y + z * w), 1 - s * (x * x + z * z), s * (y * z - x * w),
			s * (x * z - y * w), s * (y * z + x * w), 1 - s * (x * x + y * y)
		};
	}

	/**
	 * A Minecraft hit on an enemy's proxy ({@link ErEntity#hurt}): queue it for the host game.
	 * {@code amount} goes out as is, in Minecraft damage points. The hit point is where the blow
	 * met the body: nearest the blast (explosions) or the projectile/other thing that struck
	 * ({@link Protocol#DAMAGE_OUTWARD}); for melee, the attacker's aim through the box (a
	 * player's crosshair; a mob's line to the body).
	 */
	static void onHit(ErEntity proxy, DamageSource source, float amount, boolean critical) {
		CoordMap.Mapping map = CoordMap.get();
		if (map == null || !(amount > 0.0F) || !Float.isFinite(amount)) {
			return;  // no damage (a snowball, an egg): the DLL ignores amounts <= 0
		}
		AABB box = proxy.getBoundingBox();
		Vec3 center = box.getCenter();
		Entity direct = source.getDirectEntity();
		Entity attacker = source.getEntity();
		Vec3 from = source.getSourcePosition();
		Vec3 hit;
		int flags = critical ? Protocol.DAMAGE_CRITICAL : 0;
		if (!(attacker instanceof Player)) {
			flags |= Protocol.DAMAGE_NOT_BY_PLAYER;
		}
		String kind;
		if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			hit = from != null ? closestPoint(box, from) : center;
			flags |= Protocol.DAMAGE_OUTWARD;
			kind = "explosion";
		} else if (direct instanceof LivingEntity && direct == attacker) {
			Vec3 eye = attacker.getEyePosition();
			Vec3 aim = attacker instanceof Player ? eye.add(attacker.getLookAngle().scale(8.0)) : center;
			hit = box.clip(eye, aim).or(() -> box.clip(eye, center)).orElseGet(() -> closestPoint(box, eye));
			kind = "melee";
		} else if (direct != null) {
			// Arrow, trident, fireball, snowball, potion, falling anvil...
			hit = closestPoint(box, direct.position());
			flags |= Protocol.DAMAGE_OUTWARD;
			kind = direct instanceof Projectile ? "projectile" : "hit";
		} else {
			// Poison, wither, lightning, /damage.
			hit = from != null ? closestPoint(box, from) : center;
			kind = source.getMsgId();
		}
		double[] m = map.toHost(hit.x, hit.y, hit.z);
		boolean ok = ErLink.get().pushDamage(proxy.hostId(), amount, (float) m[0], (float) m[1], (float) m[2], flags);
		if (proxy.level() instanceof ServerLevel level) {
			level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, hit.x, hit.y, hit.z, Math.max(1, (int) (amount / 2)), 0.2, 0.2, 0.2, 0.2);
		}
		String by = attacker != null ? attacker.getName().getString() : direct != null ? direct.getName().getString() : source.getMsgId();
		long now = System.currentTimeMillis();
		if (attacker instanceof Player || now - lastOtherHitLog >= OTHER_HIT_LOG_MS) {
			if (!(attacker instanceof Player)) {
				lastOtherHitLog = now;
			}
			LOG.info("{} hit {} ({}{}) for {} Minecraft damage ({}){}", by, proxy.getName().getString(), kind,
				critical ? ", critical" : "", amount, ok ? "sent" : "queue full",
				unloggedHits > 0 ? " [+" + unloggedHits + " more hits]" : "");
			unloggedHits = 0;
		} else {
			unloggedHits++;
		}
	}
}
