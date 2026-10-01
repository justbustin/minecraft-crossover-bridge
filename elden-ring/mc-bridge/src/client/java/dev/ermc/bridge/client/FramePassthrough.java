package dev.ermc.bridge.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.ermc.bridge.link.ControlState;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.Protocol;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Sends Minecraft's frames to the host game, which draws them into its own frame (see
 * elden-ring/er-bridge). Per frame:
 * <ol>
 *   <li>after the world is drawn (before the hand): async readback of world color + depth,
 *   then the color buffer is cleared so the hand draws onto a transparent layer;</li>
 *   <li>after the hand: async readback of the hand layer (the host game lights it like the
 *   world, but never hides it behind its walls), then the color buffer is cleared again;</li>
 *   <li>at the end of the frame: async readback of the HUD layer (drawn as is);</li>
 *   <li>next frame: the readbacks are copied into a frames.shm slot, and only then is that
 *   frame's camera pose handed to the host game, so it always has the matching pixels for the
 *   pose it renders.</li>
 * </ol>
 * Minecraft's own window then shows nothing (it only keeps the input focus).
 */
public final class FramePassthrough {
	private FramePassthrough() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
	private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
	private static final String PATH = "/tmp/ermc/frames.shm";
	private static final int MAGIC = 0x524D484D;
	private static final int MAX_W = 1920;
	private static final int MAX_H = 1200;
	private static final int SLOTS = 3;
	private static final int HDR = 0x100;
	/** Layout version 2: room for four layers (world color, depth, HUD, hand) at the largest size. */
	private static final int VERSION = 2;
	private static final long SLOT_SIZE = HDR + (long) MAX_W * MAX_H * 16;
	private static final long FILE_SIZE = 0x1000 + SLOTS * SLOT_SIZE;

	/**
	 * User toggle (F6). Effective only once the host game confirms it is compositing (its
	 * D3D12 Present hook, elden-ring/er-bridge/src/compositor.cpp); until then the separate
	 * overlay window (Overlay.java) shows Minecraft.
	 */
	private static boolean enabled = true;
	private static MappedByteBuffer frames;
	private static boolean failed;

	// Two sets of PBOs (world color, world depth, HUD color, hand color), alternating per frame.
	private static final int[][] PBO = new int[2][4];
	private static int pboW, pboH;
	private static final boolean[] PENDING = new boolean[2];
	/** The set's hand layer was captured (else the hand, if any, is in the HUD layer). */
	private static final boolean[] HAND = new boolean[2];
	private static final long[] FRAME_ID = new long[2];
	private static final ControlState[] POSE = {new ControlState(), new ControlState()};
	private static final float[][] CLIP = new float[2][2];
	private static boolean worldCaptured;
	private static long frameCounter;
	/**
	 * Host-game frame counter at the last capture. The host game takes one pose per frame and
	 * presents it a frame later, so capturing faster than it runs (60 vs ~40 fps in big areas)
	 * would overwrite the slot it still needs, and it would show a frame with the wrong pose.
	 */
	private static long capturedAtHostFrame = Long.MIN_VALUE;
	private static int cur;
	private static long lastOffer;

	public static boolean enabled() {
		return enabled;
	}

	public static void toggle() {
		enabled = !enabled;
	}

	/** True while the host game is drawing our frames, so our own window should stay empty. */
	public static boolean activeInHost() {
		return enabled && Overlay.active() && CameraSync.mode() == CameraSync.Mode.DRIVE_HOST && CameraSync.hostCompositing();
	}

	/** Whether frames are being captured (then CameraSync leaves pose publishing to us). */
	public static boolean wanted() {
		return enabled && !failed && Overlay.active() && CameraSync.mode() == CameraSync.Mode.DRIVE_HOST && CameraSync.driving()
			&& fits(Minecraft.getInstance().getMainRenderTarget());
	}

	private static boolean tooBigLogged;

	/**
	 * Frames larger than a frames.shm slot can't be sent. Then nothing is captured and the
	 * camera pose goes to the host game directly (overlay window mode) instead of never at all.
	 */
	private static boolean fits(RenderTarget main) {
		boolean fits = main.width <= MAX_W && main.height <= MAX_H;
		if (!fits && !tooBigLogged) {
			tooBigLogged = true;
			LOG.warn("Frame {}x{} is larger than {}x{}; showing Minecraft in its own window instead of inside Elden Ring",
				main.width, main.height, MAX_W, MAX_H);
		}
		return fits;
	}

	private static boolean open() {
		if (frames != null) {
			return true;
		}
		try {
			Path p = Path.of(PATH);
			Files.createDirectories(p.getParent());
			try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
				if (ch.size() < FILE_SIZE) {
					ch.write(ByteBuffer.wrap(new byte[1]), FILE_SIZE - 1);
				}
				frames = ch.map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
				frames.order(ByteOrder.LITTLE_ENDIAN);
			}
			frames.putInt(4, VERSION);
			INT.setRelease(frames, 0, MAGIC);
			LOG.info("Frame passthrough: mapped {} ({} MB)", PATH, FILE_SIZE >> 20);
			return true;
		} catch (IOException | RuntimeException e) {
			LOG.warn("Frame passthrough unavailable: {}", e.toString());
			failed = true;
			return false;
		}
	}

	private static void ensurePbos(int w, int h) {
		if (w == pboW && h == pboH && PBO[0][0] != 0) {
			return;
		}
		for (int[] set : PBO) {
			for (int i = 0; i < set.length; i++) {
				if (set[i] != 0) {
					GL15.glDeleteBuffers(set[i]);
				}
				set[i] = GL15.glGenBuffers();
				GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, set[i]);
				GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
			}
		}
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		PENDING[0] = PENDING[1] = false;
		pboW = w;
		pboH = h;
	}

	private static void readInto(int pbo, int format, int type) {
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
		GL11.glReadPixels(0, 0, pboW, pboH, format, type, 0L);
	}

	/** GameRenderer.renderLevel, right after the level (before the hand). */
	public static void afterWorld(Minecraft mc) {
		worldCaptured = false;
		if (!wanted() || !open()) {
			return;
		}
		long hostFrame = ErLink.get().hostFrame();
		if (hostFrame == capturedAtHostFrame) {
			return;  // the host game hasn't shown a frame since the last capture
		}
		capturedAtHostFrame = hostFrame;
		RenderTarget main = mc.getMainRenderTarget();
		ensurePbos(main.width, main.height);
		cur = (int) (frameCounter & 1);
		HAND[cur] = false;
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
		readInto(PBO[cur][0], GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV);
		readInto(PBO[cur][1], GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		// The hand and screen effects, then the HUD, go onto transparent layers over the world.
		clearColor(main);
		CameraSync.copyCurrentPose(POSE[cur]);
		CLIP[cur][0] = 0.05F;
		CLIP[cur][1] = mc.gameRenderer.getDepthFar();
		worldCaptured = true;
	}

	private static void clearColor(RenderTarget main) {
		main.bindWrite(false);
		GlStateManager._colorMask(true, true, true, true);
		GlStateManager._clearColor(0, 0, 0, 0);
		GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
	}

	/** GameRenderer.renderLevel, right after the hand: read back the hand layer. */
	public static void afterHand(Minecraft mc) {
		if (!worldCaptured) {
			return;
		}
		RenderTarget main = mc.getMainRenderTarget();
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
		readInto(PBO[cur][3], GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV);
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		clearColor(main);
		HAND[cur] = true;
	}

	/** End of frame, before the main target is shown: read back the overlay layer, publish last frame. */
	public static void endFrame(Minecraft mc) {
		boolean captured = worldCaptured;
		if (worldCaptured) {
			RenderTarget main = mc.getMainRenderTarget();
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
			readInto(PBO[cur][2], GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV);
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
			FRAME_ID[cur] = ++frameCounter;
			POSE[cur].mcFrame = FRAME_ID[cur];
			PENDING[cur] = true;
			worldCaptured = false;
		}
		// Publish the capture from an earlier frame (its readback had a frame to finish), even
		// if this frame captured nothing.
		for (int set = 0; set < 2; set++) {
			if (PENDING[set] && !(captured && set == cur)) {
				// Once Minecraft stops driving the host game (F8, map change...), stale frames
				// must not re-pin its camera and hunter.
				if (wanted()) {
					publish(set);
				}
				PENDING[set] = false;
			}
		}
	}

	private static void publish(int set) {
		int w = pboW;
		int h = pboH;
		long frameId = FRAME_ID[set];
		int slot = (int) (frameId % SLOTS);
		int base = (int) (0x1000 + slot * SLOT_SIZE);
		// Odd while the slot is being written: the host game skips it (and a copy it started
		// before sees the sequence change).
		int s = (int) INT.getOpaque(frames, base);
		INT.setOpaque(frames, base, s | 1);
		VarHandle.releaseFence();
		long layer = (long) w * h * 4;
		boolean hand = HAND[set];
		for (int i = 0; i < (hand ? 4 : 3); i++) {
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, PBO[set][i]);
			ByteBuffer src = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, layer, GL30.GL_MAP_READ_BIT);
			if (src != null) {
				ByteBuffer dst = frames.duplicate().order(ByteOrder.LITTLE_ENDIAN);
				dst.position((int) (base + HDR + layer * i));
				dst.put(src);
				GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
			}
		}
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		frames.putInt(base + 0x04, w);
		frames.putInt(base + 0x08, h);
		frames.putInt(base + 0x0C, hand ? 7 : 3);  // world, HUD, hand layers valid
		frames.putLong(base + 0x10, frameId);
		frames.putLong(base + 0x18, frameId);
		frames.putFloat(base + 0x20, CLIP[set][0]);
		frames.putFloat(base + 0x24, CLIP[set][1]);
		frames.putFloat(base + 0x28, POSE[set].fovYDeg);
		frames.putFloat(base + 0x2C, (float) w / h);
		int even = ((int) INT.getOpaque(frames, base) | 1) + 1;
		INT.setRelease(frames, base, even);
		frames.putInt(0x08, slot);
		LONG.setRelease(frames, 0x10, frameId);
		// Only now may the host game render this pose: the pixels for it are in place.
		ErLink.get().writeControl(POSE[set]);
	}
}
