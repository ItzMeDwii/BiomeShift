package com.biomeshift.command;

import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * Permission nodes, checked through Fabric API's permission API so a server
 * permission plugin (LuckPerms implements fabric-permission-api v1) can grant
 * or deny them. LuckPerms exposes each node as {@code namespace.path}.
 *
 * <p>When no permission provider is installed, each node falls back to the
 * given vanilla permission level.
 */
public final class Perms {
	public static final PermissionNode<Boolean> COMMAND_RELOAD =
			PermissionNode.of("biomeshift", "command.reload");
	public static final PermissionNode<Boolean> COMMAND_STATUS =
			PermissionNode.of("biomeshift", "command.status");
	public static final PermissionNode<Boolean> COMMAND_INSPECT =
			PermissionNode.of("biomeshift", "command.inspect");
	public static final PermissionNode<Boolean> COMMAND_WAND =
			PermissionNode.of("biomeshift", "command.wand");

	/** Vanilla fallback: op level 2, like /gamemode and /time. */
	public static final PermissionLevel FALLBACK = PermissionLevel.GAMEMASTERS;

	private Perms() {
	}
}
