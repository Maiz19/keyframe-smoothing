package com.keyframesmoothing;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Smooths animations between their keyframes, the way RuneLite's own smoothing does, but with timing: in actions
 * (attacks, skilling, emotes) a pose the animation holds stays put a moment, and each move speeds up into the next pose,
 * so hits land hard while the motion stays smooth. Keyframes that smoothing would turn into a glitch are not blended.
 *
 * The timing works through the client's own blend: keyframe f is blended into f + 1 by the time spent on it divided by
 * its frame length. Just before a frame is drawn (BeforeRender), the plugin sets the frame length of each character's
 * current keyframe so the blend lands on the curve. The game also counts with those lengths when it moves animations on,
 * so every length is put back by a task on the client thread's queue: the client runs that queue at the start of its
 * next cycle (Hooks.tick), before it moves players and NPCs on (checked in the injected client 1.13.3; ClientTick comes
 * after that move, too late).
 *
 * The game asks the client's animation filter, for each character and animated object it draws, whether to blend the
 * current keyframe into the next. The filter only gets the animation id, so before each frame is drawn this works out the
 * answer for every animation a character is playing right now.
 *
 * The GPU plugin builds animated objects on its render threads, several at once, and they ask the filter too. So the
 * filter only reads a snapshot made on the client thread: it never calls the client or changes anything.
 */
@Slf4j
@PluginDescriptor(
	name = "Keyframe Smoothing",
	description = "Smooth animations that keep their keyframes: held poses and hits stay sharp, smoothing glitches are fixed",
	tags = {"animation", "smoothing", "interpolation", "keyframes", "tween", "players", "npcs"}
)
public class KeyframeSmoothingPlugin extends Plugin
{
	/** Length of a client tick in nanoseconds, the unit of animation frame lengths. */
	private static final long TICK_NANOS = 20_000_000L;
	/** Ticks a big turn in an action takes to whip into the next pose. */
	private static final int QUICK_TICKS = 2;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyframeSmoothingConfig config;

	private volatile KeyframeTable table;

	// client thread only
	/** Per animation id: what we know of it, loaded once. */
	private final Map<Integer, AnimationInfo> animations = new HashMap<>();
	/** Per character: since when it shows its current keyframe. */
	private final Map<Actor, KeyframeStart> starts = new IdentityHashMap<>();
	/** The answers being worked out for the next frame. */
	private Map<Integer, Boolean> decisions = new HashMap<>();
	/** The animation whose answer comes from the local player, which no other character may change. */
	private int localAnimation = -1;
	private int drawnFrames;

	/** Frame lengths changed for the frame being drawn: array, index, value to put back. */
	private final List<int[]> changedArrays = new ArrayList<>();
	private final List<int[]> changedSlots = new ArrayList<>();
	/** Keyframes (animation id << 16 | frame) whose length was set this frame, shared by characters showing them. */
	private final Set<Integer> timedKeyframes = new HashSet<>();
	private long lastTickNanos;
	private boolean restoreQueued;
	/** Time source for the part of a tick that has passed; tests replace it. */
	LongSupplier clock = System::nanoTime;

	/** What the filter reads, on any thread: replaced as a whole before each frame is drawn. */
	private volatile Snapshot snapshot = Snapshot.NONE;

	/** The filter that was set when we installed ours (RuneLite's own smoothing, or another plugin's); asked too. */
	private volatile IntPredicate previous;
	/** Set while this thread asks the previous filter, in case that filter wraps ours and asks us back. */
	private final ThreadLocal<boolean[]> askingPrevious = ThreadLocal.withInitial(() -> new boolean[1]);
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
			restoreFrameLengths();
			snapshot = Snapshot.NONE;
			animations.clear();
			starts.clear();
			decisions = new HashMap<>();
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
	public void onClientTick(ClientTick event)
	{
		// the drawn frames after this cycle count the part of a tick from here
		lastTickNanos = clock.getAsLong();
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		restoreFrameLengths();
		install();
		decide();
		if (!changedArrays.isEmpty() && !restoreQueued)
		{
			// runs at the start of the next client cycle, before the game moves animations on
			restoreQueued = true;
			clientThread.invokeLater(() ->
			{
				restoreQueued = false;
				restoreFrameLengths();
			});
		}
	}

	private void restoreFrameLengths()
	{
		for (int i = changedArrays.size() - 1; i >= 0; i--)
		{
			int[] slot = changedSlots.get(i);
			changedArrays.get(i)[slot[0]] = slot[1];
		}
		changedArrays.clear();
		changedSlots.clear();
		timedKeyframes.clear();
	}

	/** The filter. Called on the client thread and on the GPU plugin's render threads: read only. */
	private boolean shouldBlend(int animationId)
	{
		boolean blend = decision(animationId);
		IntPredicate other = previous;
		boolean[] asking = askingPrevious.get();
		if (!blend || other == null || asking[0])
		{
			// asking[0]: a filter that wraps ours asked us back
			return blend;
		}
		asking[0] = true;
		try
		{
			return other.test(animationId);
		}
		catch (RuntimeException e)
		{
			log.debug("Another animation filter failed; leaving it out", e);
			previous = null;
			return true;
		}
		finally
		{
			asking[0] = false;
		}
	}

	private boolean decision(int animationId)
	{
		Snapshot now = snapshot;
		Boolean decided = now.decisions.get(animationId);
		if (decided != null)
		{
			return decided;
		}
		// objects and effects: the filter doesn't say which keyframe they show, so leave out animations with glitches
		if (!now.objects)
		{
			return false;
		}
		KeyframeTable keyframes = table;
		return !now.fixGlitches || keyframes == null || !keyframes.hasGlitches(animationId);
	}

	private void decide()
	{
		decisions = new HashMap<>();
		localAnimation = -1;
		drawnFrames++;
		WorldView top = client.getTopLevelWorldView();
		if (top == null)
		{
			publish();
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
		publish();
	}

	private void publish()
	{
		snapshot = new Snapshot(decisions, config.objects(), config.fixGlitches());
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
		boolean action = animationId != -1;
		if (action)
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

		boolean blend = enabled && blendsNow(actor, animationId, frame, action, cycle);
		// characters sharing an animation share the answer: one that must stay sharp wins
		decisions.merge(animationId, blend, Boolean::logicalAnd);
		return animationId;
	}

	private boolean blendsNow(Actor actor, int animationId, int frame, boolean action, int cycle)
	{
		KeyframeStart start = starts.computeIfAbsent(actor, a -> new KeyframeStart());
		if (start.animationId != animationId || start.frame != frame)
		{
			start.animationId = animationId;
			start.frame = frame;
			start.cycle = cycle;
			start.drawn = 0;
		}
		start.seen = drawnFrames;

		AnimationInfo info = info(animationId);
		if (info == null || info.frameLengths == null || frame < 0 || frame >= info.frameLengths.length)
		{
			return true;
		}
		byte letter = info.keyframes == null ? (byte) '.' : info.keyframes[frame];
		double impact = config.impact() / 100.0;
		boolean glitch = config.fixGlitches() && (letter == KeyframeTable.TURN || letter == KeyframeTable.JUMP);
		// walking and standing keep an even speed; so does everything at 0 impact
		if (!action || impact <= 0)
		{
			return !glitch;
		}

		int length = info.frameLengths[frame];
		// every keyframe pose stays in view a moment, and the move into the next one slows down as it gets there
		double hold = 0.3 * impact;
		double power = 1 + impact;
		boolean settle = true;
		if (glitch)
		{
			// a big turn or jump in an action is mostly the strike itself: keep the pose, then whip into the next one
			// in the last ticks, so a turn the wrong way only shows for a moment (loops starting over, where jumps
			// matter most, are walking, standing and objects: never blended above)
			hold = Math.max(0, 1 - QUICK_TICKS / (double) length);
			power = 2;
			settle = false;
		}
		else if (letter == KeyframeTable.HOLD || letter == KeyframeTable.PUNCH && impact >= 0.6)
		{
			// a pose the animation holds: stays longer, then speeds into the next one
			hold = (letter == KeyframeTable.HOLD ? 0.5 : 0.4) * impact;
			power = 1 + 1.6 * impact;
			settle = false;
		}

		double tickPart = Math.min(0.99, Math.max(0, (clock.getAsLong() - lastTickNanos) / (double) TICK_NANOS));
		double spent = Math.min(length - 0.01, Math.max(0, cycle - start.cycle) + tickPart);
		double target = settle ? Timing.settle(spent / length, hold, power) : Timing.curve(spent / length, hold, power);
		int divisor = Timing.divisor(spent, target, start.drawn);
		if (divisor == 0)
		{
			return false;
		}
		start.drawn = spent / divisor;
		setFrameLength(animationId, frame, divisor);
		return true;
	}

	/** Gives the client a frame length for this drawn frame only (put back by {@link #restoreFrameLengths}). */
	private void setFrameLength(int animationId, int frame, int length)
	{
		if (!timedKeyframes.add(animationId << 16 | frame))
		{
			// another character shows this keyframe and set it already
			return;
		}
		Animation animation = client.loadAnimation(animationId);
		int[] live = animation == null ? null : animation.getFrameLengths();
		if (live == null || frame >= live.length)
		{
			return;
		}
		changedArrays.add(live);
		changedSlots.add(new int[]{frame, live[frame]});
		live[frame] = length;
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

	/** What the filter reads; never changed once made. */
	private static final class Snapshot
	{
		static final Snapshot NONE = new Snapshot(Map.of(), true, true);

		final Map<Integer, Boolean> decisions;
		final boolean objects;
		final boolean fixGlitches;

		Snapshot(Map<Integer, Boolean> decisions, boolean objects, boolean fixGlitches)
		{
			this.decisions = decisions;
			this.objects = objects;
			this.fixGlitches = fixGlitches;
		}
	}

	private static final class AnimationInfo
	{
		/** Letters from the keyframe table, or null when it has none that fit (every keyframe is an ordinary one). */
		final byte[] keyframes;
		/** The real frame lengths (a copy taken before any change), or null for skeletal animations, which never blend. */
		final int[] frameLengths;

		AnimationInfo(Animation animation, byte[] letters)
		{
			int[] lengths = animation.isMayaAnim() ? null : animation.getFrameLengths();
			frameLengths = lengths == null ? null : lengths.clone();
			// a table made from an older game cache: only trust it when the keyframes still line up
			keyframes = letters != null && lengths != null && lengths.length == letters.length ? letters : null;
		}
	}

	private static final class KeyframeStart
	{
		int animationId = -1;
		int frame = -1;
		int cycle;
		int seen;
		/** How far along the blend was drawn last (0..1), so it never moves back. */
		double drawn;
	}
}
