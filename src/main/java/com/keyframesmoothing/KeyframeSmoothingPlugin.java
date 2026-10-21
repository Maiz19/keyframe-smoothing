package com.keyframesmoothing;

import com.google.inject.Provides;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.IntPredicate;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Smooths animations between their keyframes, the way RuneLite's own smoothing does, but per keyframe: held poses stay
 * put until just before the next one, and keyframes that smoothing would turn into a glitch are left unsmoothed.
 *
 * The game asks the client's animation filter, for each character it draws, whether to blend the current keyframe into
 * the next. The filter only gets the animation id, so before each frame is drawn this works out the answer for every
 * animation a character is playing right now.
 */
@Slf4j
@PluginDescriptor(
	name = "Keyframe Smoothing",
	description = "Smooth animations that keep their keyframes: held poses and hits stay sharp, smoothing glitches are fixed",
	tags = {"animation", "smoothing", "interpolation", "keyframes", "tween", "players", "npcs"}
)
public class KeyframeSmoothingPlugin extends Plugin
{
	/** Length of a client tick, the unit of animation frame lengths. */
	private static final int TICK_MS = 20;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyframeSmoothingConfig config;

	private KeyframeTable table;

	/** Per animation id: what we know of it, loaded once. */
	private final Map<Integer, AnimationInfo> animations = new HashMap<>();
	/** Per character: since when it shows its current keyframe. */
	private final Map<Actor, KeyframeStart> starts = new IdentityHashMap<>();
	/** The answer for each animation a character plays in the frame being drawn. */
	private final Map<Integer, Boolean> decisions = new HashMap<>();
	/** Animations whose answer comes from the local player, which no other character may change. */
	private int localAnimation = -1;
	private int drawnFrames;

	/** The filter that was set when we installed ours (RuneLite's own smoothing, or another plugin's); asked too. */
	private IntPredicate previous;
	private boolean askingPrevious;
	private final IntPredicate filter = this::shouldBlend;

	@Provides
	KeyframeSmoothingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(KeyframeSmoothingConfig.class);
	}

	@Override
	protected void startUp()
	{
		table = KeyframeTable.load();
		clientThread.invoke(this::install);
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			if (client.getAnimationInterpolationFilter() == filter)
			{
				client.setAnimationInterpolationFilter(previous);
			}
			previous = null;
			animations.clear();
			starts.clear();
			decisions.clear();
		});
	}

	/** Puts our filter in front of whatever filter is set now, also after another plugin replaced it. */
	private void install()
	{
		IntPredicate current = client.getAnimationInterpolationFilter();
		if (current != filter)
		{
			previous = current;
			client.setAnimationInterpolationFilter(filter);
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		install();
		decide();
	}

	private boolean shouldBlend(int animationId)
	{
		boolean blend = decision(animationId);
		if (!blend || previous == null || askingPrevious)
		{
			// askingPrevious: a filter that wraps ours asked us back
			return blend;
		}
		askingPrevious = true;
		try
		{
			return previous.test(animationId);
		}
		catch (RuntimeException e)
		{
			log.debug("Another animation filter failed; leaving it out", e);
			previous = null;
			return true;
		}
		finally
		{
			askingPrevious = false;
		}
	}

	private boolean decision(int animationId)
	{
		Boolean decided = decisions.get(animationId);
		if (decided != null)
		{
			return decided;
		}
		// objects and effects: the filter doesn't say which keyframe they show, so leave out animations with glitches
		if (!config.objects())
		{
			return false;
		}
		AnimationInfo info = info(animationId);
		return info == null || !info.glitches || !config.fixGlitches();
	}

	private void decide()
	{
		decisions.clear();
		localAnimation = -1;
		drawnFrames++;
		WorldView top = client.getTopLevelWorldView();
		if (top == null)
		{
			return;
		}

		int cycle = client.getGameCycle();
		Player local = client.getLocalPlayer();
		if (local != null)
		{
			localAnimation = decide(local, config.players(), cycle);
		}
		decide(top, local, cycle);
		for (WorldView worldView : top.worldViews())
		{
			decide(worldView, local, cycle);
		}
		starts.values().removeIf(start -> start.seen != drawnFrames);
	}

	private void decide(WorldView worldView, Player local, int cycle)
	{
		for (Player player : worldView.players())
		{
			if (player != local)
			{
				decide(player, config.players(), cycle);
			}
		}
		for (NPC npc : worldView.npcs())
		{
			decide(npc, config.npcs(), cycle);
		}
	}

	/** Works out whether the actor's current keyframe blends, and returns the animation the game will ask about. */
	private int decide(Actor actor, boolean enabled, int cycle)
	{
		// the game asks about the action animation when there is one, else the pose (standing, walking)
		int animationId = actor.getAnimation();
		int frame;
		if (animationId != -1)
		{
			frame = actor.getAnimationFrame();
		}
		else
		{
			animationId = actor.getPoseAnimation();
			frame = actor.getPoseAnimationFrame();
		}
		if (animationId == -1 || animationId == localAnimation)
		{
			return animationId;
		}

		boolean blend = enabled && blendsNow(actor, animationId, frame, cycle);
		// characters sharing an animation share the answer: one that must stay sharp wins
		decisions.merge(animationId, blend, Boolean::logicalAnd);
		return animationId;
	}

	private boolean blendsNow(Actor actor, int animationId, int frame, int cycle)
	{
		KeyframeStart start = starts.computeIfAbsent(actor, a -> new KeyframeStart());
		if (start.animationId != animationId || start.frame != frame)
		{
			start.animationId = animationId;
			start.frame = frame;
			start.cycle = cycle;
		}
		start.seen = drawnFrames;

		AnimationInfo info = info(animationId);
		if (info == null || info.keyframes == null || frame < 0 || frame >= info.keyframes.length)
		{
			return true;
		}

		KeyframeSmoothingConfig.Style style = config.style();
		switch (info.keyframes[frame])
		{
			case KeyframeTable.TURN:
			case KeyframeTable.JUMP:
				return !config.fixGlitches();
			case KeyframeTable.PUNCH:
				if (style != KeyframeSmoothingConfig.Style.PUNCHY)
				{
					return true;
				}
				// fall through
			case KeyframeTable.HOLD:
				if (style == KeyframeSmoothingConfig.Style.SMOOTH)
				{
					return true;
				}
				// hold the pose, then blend over the last part of the keyframe into the next one
				int blendTicks = (config.blendTime() + TICK_MS / 2) / TICK_MS;
				return cycle - start.cycle >= info.frameLengths[frame] - blendTicks;
			default:
				return true;
		}
	}

	private AnimationInfo info(int animationId)
	{
		AnimationInfo info = animations.get(animationId);
		if (info == null && !animations.containsKey(animationId))
		{
			Animation animation = client.loadAnimation(animationId);
			info = animation == null ? null : new AnimationInfo(animation, table.get(animationId));
			animations.put(animationId, info);
		}
		return info;
	}

	private static final class AnimationInfo
	{
		/** Letters from the keyframe table, or null when every keyframe blends (also for skeletal animations). */
		final byte[] keyframes;
		final int[] frameLengths;
		/** Whether any keyframe is a glitch when smoothed. */
		final boolean glitches;

		AnimationInfo(Animation animation, byte[] letters)
		{
			int[] lengths = animation.isMayaAnim() ? null : animation.getFrameLengths();
			// a table made from an older game cache: only trust it when the keyframes still line up
			boolean fits = letters != null && lengths != null && lengths.length == letters.length;
			keyframes = fits ? letters : null;
			frameLengths = fits ? lengths.clone() : null;
			boolean glitch = false;
			if (fits)
			{
				for (byte letter : letters)
				{
					glitch |= letter == KeyframeTable.TURN || letter == KeyframeTable.JUMP;
				}
			}
			glitches = glitch;
		}
	}

	private static final class KeyframeStart
	{
		int animationId = -1;
		int frame = -1;
		int cycle;
		int seen;
	}
}
