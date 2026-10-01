package dev.ermc.bridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ermc.bridge.CoordMap;
import dev.ermc.bridge.link.GameState;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.Protocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import dev.ermc.bridge.entity.ErBridgeEntities;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class ErBridgeClient implements ClientModInitializer {
	private static final GameState HUD_STATE = new GameState();
	private static KeyMapping cameraModeKey;
	private static KeyMapping overlayKey;
	private static KeyMapping hudKey;
	private static KeyMapping hunterKey;
	private static KeyMapping passthroughKey;
	private static KeyMapping actionKey;
	/** Last Elden Ring action request still waiting for its result (0 = none). */
	private static int pendingAction;
	private static long pendingActionSince;
	private static boolean showStatus = false;

	@Override
	public void onInitializeClient() {
		cameraModeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.erbridge.camera_mode",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.erbridge"));
		overlayKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.erbridge.overlay",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.erbridge"));
		hudKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.erbridge.status",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.erbridge"));
		hunterKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.erbridge.hunter",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "category.erbridge"));
		// E is Minecraft's inventory, so Elden Ring's "action" (open doors, pull levers, pick up
		// items and lost runes, touch graces) gets its own key.
		actionKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.erbridge.action",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.erbridge"));
		passthroughKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.erbridge.passthrough",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "category.erbridge"));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (cameraModeKey.consumeClick()) {
				CameraSync.toggleMode();
				toast(mc, "Camera: " + CameraSync.mode());
			}
			while (overlayKey.consumeClick()) {
				// Hand control to the host game (menus, quest board, travelling...). F8 there comes back.
				Overlay.switchToHost(mc);
			}
			while (hudKey.consumeClick()) {
				// Debug view: bridge status line, hitbox outlines, every HP tag.
				showStatus = !showStatus;
				ErEntityRenderer.showHitboxes = showStatus;
				toast(mc, "Debug view " + (showStatus ? "on" : "off"));
			}
			while (passthroughKey.consumeClick()) {
				FramePassthrough.toggle();
				toast(mc, "Draw inside Elden Ring (occlusion): " + (FramePassthrough.enabled() ? "on" : "off"));
			}
			while (actionKey.consumeClick()) {
				if (Overlay.active() && !Overlay.hostBusy() && ErLink.get().alive()) {
					pendingAction = ErLink.get().requestAction();
					pendingActionSince = System.currentTimeMillis();
				}
			}
			if (pendingAction != 0) {
				ErLink link = ErLink.get();
				if (link.actionAck() == pendingAction) {
					int r = link.actionResult();
					if (r == 0) {
						toast(mc, "Nothing to do here in Elden Ring");
					} else if (r == -2) {
						toast(mc, "Ladders need Elden Ring's controls: press F8, climb, F8 back");
					} else if (r < 0) {
						toast(mc, "Elden Ring actions aren't available in this game version");
					} else if (mc.player != null) {
						// A door or gate may be moving: Minecraft's copy of the ground there goes stale.
						dev.ermc.bridge.TerrainManager.resampleAround(mc.player.getX(), mc.player.getZ(), 4);
					}
					pendingAction = 0;
				} else if (System.currentTimeMillis() - pendingActionSince > 2000) {
					pendingAction = 0;
				}
			}
			while (hunterKey.consumeClick()) {
				toast(mc, CameraSync.toggleStandIn()
					? "Tarnished stands in for you (enemies can hit you)" : "Tarnished stays put and visible");
			}
			WorldBootstrap.tick(mc);
			DevCommands.tick(mc);
		});

		HudRenderCallback.EVENT.register((graphics, tickCounter) -> {
			renderPrompt(graphics);
			renderStatus(graphics);
		});
		EntityRendererRegistry.register(ErBridgeEntities.ER_ENTITY, ErEntityRenderer::new);
	}

	private static void toast(Minecraft mc, String msg) {
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal("[ER Bridge] " + msg), true);
		}
	}

	/** Elden Ring's current action ("Open", "Pick up"...) under the crosshair, with the key to press. */
	private static void renderPrompt(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || !Overlay.active() || Overlay.hostBusy() || mc.screen != null) {
			return;
		}
		String prompt = ErLink.get().hostPrompt();
		if (prompt.isEmpty()) {
			return;
		}
		String text = "[" + actionKey.getTranslatedKeyMessage().getString() + "] " + prompt;
		int w = mc.font.width(text);
		int x = (g.guiWidth() - w) / 2;
		int y = g.guiHeight() / 2 + 14;
		g.fill(x - 3, y - 2, x + w + 3, y + 10, 0x80000000);
		g.drawString(mc.font, text, x, y, 0xFFE8D8A0, true);
	}

	private static void renderStatus(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (!showStatus || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		ErLink link = ErLink.get();
		String line1;
		String line2 = null;
		if (!link.alive()) {
			line1 = "Elden Ring: not connected";
		} else {
			boolean have = link.snapshot(HUD_STATE);
			line1 = String.format("Elden Ring: live  frame %d  %s  camera %s%s", have ? HUD_STATE.frame : 0,
				!Overlay.active() ? "overlay off" : FramePassthrough.activeInHost() ? "drawn inside Elden Ring" : "overlay window", CameraSync.mode(),
				CameraSync.mode() == CameraSync.Mode.DRIVE_HOST && !CameraSync.hostFollowing() ? " (Elden Ring not following yet)" : "");
			if (have && HUD_STATE.has(Protocol.STATE_PLAYER_VALID)) {
				line2 = String.format("tarnished %.0f %.0f %.0f", HUD_STATE.playerPos[0], HUD_STATE.playerPos[1], HUD_STATE.playerPos[2]);
			} else if (CoordMap.get() != null && CoordMap.get().provisional()) {
				line2 = "Tarnished position unknown (provisional anchor)";
			}
		}
		g.drawString(mc.font, line1, 4, 4, 0xFFFFFF, true);
		if (line2 != null) {
			g.drawString(mc.font, line2, 4, 14, 0xFFFFFF, true);
		}
	}
}
