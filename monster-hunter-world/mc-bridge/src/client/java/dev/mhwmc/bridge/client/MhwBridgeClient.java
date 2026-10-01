package dev.mhwmc.bridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mhwmc.bridge.CoordMap;
import dev.mhwmc.bridge.link.GameState;
import dev.mhwmc.bridge.link.MhwLink;
import dev.mhwmc.bridge.link.Protocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import dev.mhwmc.bridge.entity.MhwBridgeEntities;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class MhwBridgeClient implements ClientModInitializer {
	private static boolean heldForDeath;
	private static final GameState HUD_STATE = new GameState();
	private static KeyMapping cameraModeKey;
	private static KeyMapping overlayKey;
	private static KeyMapping hudKey;
	private static KeyMapping hunterKey;
	private static KeyMapping passthroughKey;
	private static boolean showStatus = false;

	@Override
	public void onInitializeClient() {
		cameraModeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mhwbridge.camera_mode",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.mhwbridge"));
		overlayKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mhwbridge.overlay",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.mhwbridge"));
		hudKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mhwbridge.status",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.mhwbridge"));
		hunterKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mhwbridge.hunter",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "category.mhwbridge"));
		passthroughKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mhwbridge.passthrough",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "category.mhwbridge"));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			// Steve died: keep MHW's camera and hunter where he fell until the server has put the
			// respawned player back at the hunter (LifeBridge), not at the world's spawn point.
			boolean dead = mc.player != null && mc.player.isDeadOrDying();
			if (dead && !heldForDeath) {
				CameraSync.holdForRecall();
			}
			heldForDeath = dead;
			while (cameraModeKey.consumeClick()) {
				CameraSync.toggleMode();
				toast(mc, "Camera: " + CameraSync.mode());
			}
			while (overlayKey.consumeClick()) {
				// Hand control to MHW (menus, quest board, travelling...). F8 in MHW comes back.
				Overlay.switchToMhw(mc);
			}
			while (hudKey.consumeClick()) {
				// Debug view: bridge status line, hitbox outlines, every HP tag.
				showStatus = !showStatus;
				MhwEntityRenderer.showHitboxes = showStatus;
				toast(mc, "Debug view " + (showStatus ? "on" : "off"));
			}
			while (passthroughKey.consumeClick()) {
				FramePassthrough.toggle();
				toast(mc, "Draw inside MHW (occlusion): " + (FramePassthrough.enabled() ? "on" : "off"));
			}
			while (hunterKey.consumeClick()) {
				toast(mc, CameraSync.toggleStandIn()
					? "Hunter stands in for you (monsters can hit you)" : "Hunter stays put and visible");
			}
			WorldBootstrap.tick(mc);
			DevCommands.tick(mc);
		});

		HudRenderCallback.EVENT.register((graphics, tickCounter) -> renderStatus(graphics));
		EntityRendererRegistry.register(MhwBridgeEntities.MHW_ENTITY, MhwEntityRenderer::new);
	}

	private static void toast(Minecraft mc, String msg) {
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal("[MHW Bridge] " + msg), true);
		}
	}

	private static void renderStatus(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (!showStatus || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		MhwLink link = MhwLink.get();
		String line1;
		String line2 = null;
		if (!link.alive()) {
			line1 = "MHW: not connected";
		} else {
			boolean have = link.snapshot(HUD_STATE);
			line1 = String.format("MHW: live  frame %d  %s  camera %s%s", have ? HUD_STATE.frame : 0,
				!Overlay.active() ? "overlay off" : FramePassthrough.activeInMhw() ? "drawn inside MHW" : "overlay window", CameraSync.mode(),
				CameraSync.mode() == CameraSync.Mode.DRIVE_MHW && !CameraSync.mhwFollowing() ? " (MHW not following yet)" : "");
			if (have && HUD_STATE.has(Protocol.STATE_PLAYER_VALID)) {
				line2 = String.format("hunter %.0f %.0f %.0f", HUD_STATE.playerPos[0], HUD_STATE.playerPos[1], HUD_STATE.playerPos[2]);
			} else if (CoordMap.get() != null && CoordMap.get().provisional()) {
				line2 = "hunter position unknown (provisional anchor)";
			}
		}
		g.drawString(mc.font, line1, 4, 4, 0xFFFFFF, true);
		if (line2 != null) {
			g.drawString(mc.font, line2, 4, 14, 0xFFFFFF, true);
		}
	}
}
