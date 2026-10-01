package dev.mhwmc.bridge.client;

import dev.mhwmc.bridge.link.GameState;
import dev.mhwmc.bridge.link.MhwLink;
import dev.mhwmc.bridge.link.Protocol;
import dev.mhwmc.bridge.TerrainManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Platform;
import org.lwjgl.system.macosx.ObjCRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overlay mode: Minecraft's window becomes a borderless, always-on-top, transparent layer
 * glued to MHW's window. Only Minecraft's own content (blocks, entities, hand, HUD) is
 * opaque; everywhere else MHW shows through, composited by macOS.
 *
 * <p>The window is always created with a transparent framebuffer so overlay mode can turn
 * on/off at runtime (e.g. when MHW starts after Minecraft). While off, the final blit forces
 * alpha to 1 so the window looks completely normal.
 */
public final class Overlay {
	private Overlay() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("mhwbridge");

	private static boolean transparentWindow;
	private static boolean active;
	/** The player handed control to MHW (F8): our window is hidden and Minecraft is paused. */
	private static boolean mhwMode;
	private static int lastSwitchReq = Integer.MIN_VALUE;
	private static long window;
	private static int savedX, savedY, savedW, savedH;
	private static int appliedX = Integer.MIN_VALUE, appliedY, appliedW, appliedH;
	private static long lastInWorldMs;
	private static long lastWantMs;
	private static final GameState STATE = new GameState();

	public static boolean active() {
		return active;
	}

	public static boolean transparentWindow() {
		return transparentWindow;
	}

	public static boolean mhwMode() {
		return mhwMode;
	}

	/** F8 in Minecraft: hide and pause Minecraft, give MHW its camera and the keyboard/mouse. */
	public static void switchToMhw(Minecraft mc) {
		if (mhwMode || window == 0L) {
			return;
		}
		mhwMode = true;
		if (mc.level != null && mc.screen == null) {
			mc.pauseGame(false);
		}
		mc.mouseHandler.releaseMouse();
		GLFW.glfwHideWindow(window);
		MhwLink.get().requestMhwFocus();
		LOG.info("Control -> MHW");
	}

	/** F8 in MHW (reported by the DLL): bring Minecraft back on top. */
	public static void switchToMc(Minecraft mc) {
		if (!mhwMode) {
			return;
		}
		mhwMode = false;
		GLFW.glfwShowWindow(window);
		GLFW.glfwFocusWindow(window);
		if (mc.screen instanceof PauseScreen) {
			mc.setScreen(null);
		}
		LOG.info("Control -> Minecraft");
	}

	/** Called right before GLFW creates Minecraft's window. */
	public static void applyWindowHints() {
		if (Boolean.getBoolean("mhwbridge.disableOverlay")) {
			return;
		}
		GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
		transparentWindow = true;
		if (Platform.get() == Platform.MACOSX) {
			// Match MHW's pixel density (CrossOver renders it at 1x) and save 4x fill rate.
			// Always, not only when MHW is already up: a Retina framebuffer is twice MHW's
			// size, too big to be drawn inside MHW's frame.
			GLFW.glfwWindowHint(GLFW.GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW.GLFW_FALSE);
		}
	}

	public static void onWindowCreated(long handle) {
		window = handle;
		if (handle == 0L) {
			transparentWindow = false;
		}
	}

	/** Once per frame on the render thread, before anything is drawn. */
	public static void onFrame(Minecraft mc) {
		MhwLink link = MhwLink.get();
		boolean alive = link.poll();
		link.bumpMcHeartbeat();
		int req = link.mcSwitchRequests();
		if (lastSwitchReq == Integer.MIN_VALUE) {
			lastSwitchReq = req;
		} else if (req != lastSwitchReq) {
			lastSwitchReq = req;
			switchToMc(mc);
		}
		boolean haveState = alive && link.snapshot(STATE);
		// Only take over once a hunter is actually in the world, so MHW's title screen and
		// menus stay usable. Short gaps (area loads) don't toggle the window.
		long now = System.currentTimeMillis();
		if (haveState && STATE.has(Protocol.STATE_PLAYER_VALID)) {
			lastInWorldMs = now;
		}
		boolean inWorld = haveState && now - lastInWorldMs < 3000;
		boolean want = transparentWindow && window != 0L && inWorld && !mhwMode && !mc.getWindow().isFullscreen();
		if (want) {
			lastWantMs = now;
		}
		// Turn on immediately, but only turn off after a sustained reason (or a user toggle).
		if (want && !active) {
			setActive(mc, true);
		} else if (!want && active && (mhwMode || now - lastWantMs > 1500)) {
			setActive(mc, false);
		}
		if (active && STATE.has(Protocol.STATE_WINDOW_VALID)) {
			follow(STATE.winX, STATE.winY, STATE.winW, STATE.winH);
		}
	}

	private static void setActive(Minecraft mc, boolean on) {
		active = on;
		LOG.info("Overlay mode {}", on ? "ON" : "OFF");
		if (on) {
			// Taking over (startup, back from MHW with F8, after a loading screen): MHW's hunter
			// is where the player really is, so Steve starts there, and nothing drives MHW's
			// camera or hunter until he has been moved.
			CameraSync.holdForRecall();
			TerrainManager.requestRecall();
			int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
			GLFW.glfwGetWindowPos(window, x, y);
			GLFW.glfwGetWindowSize(window, w, h);
			savedX = x[0];
			savedY = y[0];
			savedW = w[0];
			savedH = h[0];
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
			setShadow(false);
			// When MHW draws our frames, this window is fully transparent; macOS would then
			// let clicks fall through to MHW unless told otherwise.
			objcBool("setIgnoresMouseEvents:", false);
			appliedX = Integer.MIN_VALUE;
		} else if (!mhwMode) {
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FLOATING, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
			setShadow(true);
			if (savedW > 0 && savedH > 0) {
				GLFW.glfwSetWindowSize(window, savedW, savedH);
				GLFW.glfwSetWindowPos(window, savedX, savedY);
			}
		}
	}

	/**
	 * MHW reports its client area in Windows screen coordinates. CrossOver (Retina mode off)
	 * maps one Windows pixel to one macOS point with the same top-left origin as GLFW.
	 */
	private static void follow(int x, int y, int w, int h) {
		if (w < 64 || h < 64) {
			return;
		}
		if (x == appliedX && y == appliedY && w == appliedW && h == appliedH) {
			return;
		}
		GLFW.glfwSetWindowSize(window, w, h);
		GLFW.glfwSetWindowPos(window, x, y);
		appliedX = x;
		appliedY = y;
		appliedW = w;
		appliedH = h;
		LOG.info("Overlay glued to MHW client area {}x{} at {},{}", w, h, x, y);
	}

	/** Borderless transparent windows would otherwise cast a shadow around every block. */
	private static void setShadow(boolean shadow) {
		objcBool("setHasShadow:", shadow);
	}

	/** Calls an NSWindow setter taking a BOOL. */
	private static void objcBool(String selector, boolean value) {
		if (Platform.get() != Platform.MACOSX) {
			return;
		}
		try {
			long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(window);
			long msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
			if (nsWindow != 0L && msgSend != 0L) {
				JNI.invokePPV(nsWindow, ObjCRuntime.sel_getUid(selector), value, msgSend);
			}
		} catch (Throwable t) {
			LOG.warn("NSWindow {} failed: {}", selector, t.toString());
		}
	}
}
