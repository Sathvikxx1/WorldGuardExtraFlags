package net.goldtreeservers.worldguardextraflags.wg.handlers;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.session.handler.FlagValueChangeHandler;
import com.sk89q.worldguard.session.handler.Handler;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;

import lombok.Getter;
import net.goldtreeservers.worldguardextraflags.flags.Flags;
import net.goldtreeservers.worldguardextraflags.flags.data.PotionEffectDetails;
import net.goldtreeservers.worldguardextraflags.wg.WorldGuardUtils;

public class GiveEffectsFlagHandler extends FlagValueChangeHandler<Set<PotionEffect>> {
	public static Factory FACTORY() {
		return new Factory();
	}

	public static class Factory extends Handler.Factory<GiveEffectsFlagHandler> {
		@Override
		public GiveEffectsFlagHandler create(Session session) {
			return new GiveEffectsFlagHandler(session);
		}
	}

	private final Map<PotionEffectType, PotionEffectDetails> removedEffects;
	private final Set<PotionEffectType> givenEffects;

	@Getter private boolean suppressRemovePotionPacket;

	protected GiveEffectsFlagHandler(Session session) {
		super(session, Flags.GIVE_EFFECTS);
		this.removedEffects = new ConcurrentHashMap<>();
		this.givenEffects = ConcurrentHashMap.newKeySet();
	}

	@Override
	protected void onInitialValue(LocalPlayer player, ApplicableRegionSet set, Set<PotionEffect> value) {
		this.handleValue(player, player.getWorld(), value);
	}

	@Override
	protected boolean onSetValue(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet,
		Set<PotionEffect> currentValue, Set<PotionEffect> lastValue, MoveType moveType) {
		this.handleValue(player, (World) to.getExtent(), currentValue);
		return true;
	}

	@Override
	protected boolean onAbsentValue(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet,
		Set<PotionEffect> lastValue, MoveType moveType) {
		this.handleValue(player, (World) to.getExtent(), null);
		return true;
	}

	@Override
	public void tick(LocalPlayer player, ApplicableRegionSet set) {
		this.handleValue(player, player.getWorld(), set.queryValue(player, Flags.GIVE_EFFECTS));
	}

	private void handleValue(LocalPlayer player, World world, Set<PotionEffect> value) {
		Player bukkitPlayer = ((BukkitPlayer) player).getPlayer();

		if (this.getSession().getManager().hasBypass(player, world)) {
			return;
		}

		WorldGuardUtils.getScheduler().getScheduler().runAtEntity(bukkitPlayer, task -> {
			try {
				if (value != null) {
					for (PotionEffect effect : value) {
						PotionEffect activeEffect = null;
						for (PotionEffect current : bukkitPlayer.getActivePotionEffects()) {
							if (current.getType().equals(effect.getType())) {
								activeEffect = current;
								break;
							}
						}

						this.suppressRemovePotionPacket = activeEffect != null && activeEffect.getAmplifier() == effect.getAmplifier();

						if (this.givenEffects.add(effect.getType()) && activeEffect != null) {
							this.removedEffects.put(activeEffect.getType(), new PotionEffectDetails(
								System.nanoTime() + (long)(activeEffect.getDuration() / 20D * TimeUnit.SECONDS.toNanos(1L)),
								activeEffect.getAmplifier(),
								activeEffect.isAmbient(),
								activeEffect.hasParticles()
							));

							bukkitPlayer.removePotionEffect(activeEffect.getType());
						}

						bukkitPlayer.addPotionEffect(effect, true);
					}
				}
			}
			finally {
				this.suppressRemovePotionPacket = false;
			}

			Iterator<PotionEffectType> iter = this.givenEffects.iterator();
			while (iter.hasNext()) {
				PotionEffectType type = iter.next();

				boolean skip = false;
				if (value != null) {
					for (PotionEffect effect : value) {
						if (effect.getType().equals(type)) {
							skip = true;
							break;
						}
					}
				}

				if (!skip) {
					bukkitPlayer.removePotionEffect(type);
					iter.remove();
				}
			}

			Iterator<Entry<PotionEffectType, PotionEffectDetails>> removedIter = this.removedEffects.entrySet().iterator();
			while (removedIter.hasNext()) {
				Entry<PotionEffectType, PotionEffectDetails> entry = removedIter.next();
				if (!this.givenEffects.contains(entry.getKey())) {
					PotionEffectDetails details = entry.getValue();
					int timeLeft = details.getTimeLeftInTicks();
					if (timeLeft > 0) {
						bukkitPlayer.addPotionEffect(new PotionEffect(
							entry.getKey(),
							timeLeft,
							details.amplifier(),
							details.ambient(),
							details.particles()
						), true);
					}
					removedIter.remove();
				}
			}
		});
	}

	public void drinkMilk(Player bukkitPlayer) {
		this.removedEffects.clear();

		LocalPlayer player = WorldGuardPlugin.inst().wrapPlayer(bukkitPlayer);
		Set<PotionEffect> value = WorldGuard.getInstance()
			.getPlatform().getRegionContainer()
			.createQuery()
			.getApplicableRegions(player.getLocation())
			.queryValue(player, Flags.GIVE_EFFECTS);

		this.handleValue(player, player.getWorld(), value);
	}

	public void drinkPotion(Player bukkitPlayer, Collection<PotionEffect> effects) {
		WorldGuardUtils.getScheduler().getScheduler().runAtEntity(bukkitPlayer, task -> {
			for (PotionEffect effect : effects) {
				this.removedEffects.put(effect.getType(), new PotionEffectDetails(
					System.nanoTime() + (long)(effect.getDuration() / 20D * TimeUnit.SECONDS.toNanos(1L)),
					effect.getAmplifier(),
					effect.isAmbient(),
					effect.hasParticles()
				));
			}

			LocalPlayer player = WorldGuardPlugin.inst().wrapPlayer(bukkitPlayer);
			Set<PotionEffect> value = WorldGuard.getInstance()
				.getPlatform().getRegionContainer()
				.createQuery()
				.getApplicableRegions(player.getLocation())
				.queryValue(player, Flags.GIVE_EFFECTS);

			this.handleValue(player, player.getWorld(), value);
		});
	}
}
