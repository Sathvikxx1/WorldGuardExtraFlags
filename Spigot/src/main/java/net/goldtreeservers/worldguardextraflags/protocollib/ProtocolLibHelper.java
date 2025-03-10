package net.goldtreeservers.worldguardextraflags.protocollib;

import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.ProtocolLibrary;

import net.goldtreeservers.worldguardextraflags.WorldGuardExtraFlagsPlugin;

public record ProtocolLibHelper(WorldGuardExtraFlagsPlugin plugin, Plugin protocolLibPlugin) {

	public void onEnable() {
		ProtocolLibrary.getProtocolManager().addPacketListener(new RemoveEffectPacketListener());
	}
}
