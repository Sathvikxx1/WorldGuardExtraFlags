package net.goldtreeservers.worldguardextraflags.wg.handlers;

import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.session.handler.FlagValueChangeHandler;
import com.sk89q.worldguard.session.handler.Handler;

import net.goldtreeservers.worldguardextraflags.flags.Flags;
import net.goldtreeservers.worldguardextraflags.flags.data.PotionEffectDetails;
import net.goldtreeservers.worldguardextraflags.wg.WorldGuardUtils;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class BlockedEffectsFlagHandler extends FlagValueChangeHandler<Set<PotionEffectType>> {
	public static Factory FACTORY() {
		return new Factory();
	}

	public static class Factory extends Handler.Factory<BlockedEffectsFlagHandler> {
		@Override
		public BlockedEffectsFlagHandler create(Session session) {
			return new BlockedEffectsFlagHandler(session);
		}
	}

	private final ConcurrentHashMap<PotionEffectType, PotionEffectDetails> removedEffects;

	protected BlockedEffectsFlagHandler(Session session) {
		super(session, Flags.BLOCKED_EFFECTS);
		this.removedEffects = new ConcurrentHashMap<>();
	}

	@Override
	protected void onInitialValue(LocalPlayer player, ApplicableRegionSet set, Set<PotionEffectType> value) {
		this.handleValue(player, player.getWorld(), value);
	}

	@Override
	protected boolean onSetValue(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet,
		Set<PotionEffectType> currentValue, Set<PotionEffectType> lastValue, MoveType moveType) {
		this.handleValue(player, (World) to.getExtent(), currentValue);
		return true;
	}

	@Override
	protected boolean onAbsentValue(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet,
		Set<PotionEffectType> lastValue, MoveType moveType) {
		this.handleValue(player, (World) to.getExtent(), null);
		return true;
	}

	@Override
	public void tick(LocalPlayer player, ApplicableRegionSet set) {
		this.handleValue(player, player.getWorld(), set.queryValue(player, Flags.BLOCKED_EFFECTS));
	}

	private void handleValue(LocalPlayer player, World world, Set<PotionEffectType> value) {
		Player bukkitPlayer = ((BukkitPlayer) player).getPlayer();

		if (this.getSession().getManager().hasBypass(player, world)) {
			return;
		}

		if (value != null) {
			WorldGuardUtils.getScheduler().getScheduler().runAtEntity(bukkitPlayer, task -> {
				for (PotionEffect activeEffect : bukkitPlayer.getActivePotionEffects()) {
					PotionEffectType type = activeEffect.getType();
					if (value.contains(type)) {
						removedEffects.put(type, new PotionEffectDetails(
							System.nanoTime() + (long) (activeEffect.getDuration() / 20D * TimeUnit.SECONDS.toNanos(1L)),
							activeEffect.getAmplifier(),
							activeEffect.isAmbient(),
							activeEffect.hasParticles()
						));
						bukkitPlayer.removePotionEffect(type);
					}
				}
			});
		}

		WorldGuardUtils.getScheduler().getScheduler().runAtEntity(bukkitPlayer, task -> {
			Iterator<Entry<PotionEffectType, PotionEffectDetails>> iterator = this.removedEffects.entrySet().iterator();

			while (iterator.hasNext()) {
				Entry<PotionEffectType, PotionEffectDetails> entry = iterator.next();
				PotionEffectType type = entry.getKey();

				if (value == null || !value.contains(type)) {
					PotionEffectDetails details = entry.getValue();
					int timeLeft = details.getTimeLeftInTicks();

					if (timeLeft > 0) {
						PotionEffect effect = new PotionEffect(type, timeLeft, details.amplifier(), details.ambient(), details.particles());
						bukkitPlayer.addPotionEffect(effect, true);
					}

					iterator.remove();
				}
			}
		});
	}
}
