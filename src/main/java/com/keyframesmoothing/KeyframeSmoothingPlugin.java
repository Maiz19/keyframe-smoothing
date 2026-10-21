package com.keyframesmoothing;

import com.google.inject.Provides;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
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
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.PostAnimation;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Smooths animations between their keyframes, the way RuneLite's own smoothing does, and works on the timing of actions
 * (attacks, specials, skilling, emotes) the way an animator would: fast moves faster, slow ones slower, every swing paced
 * as one fluid motion through its keyframes, and, if wanted, a short stop on each hit and a trail behind fast swings.
 *
 * It learns each action from the game itself: the first time a character plays it, the plugin takes the model's vertex
 * positions at every keyframe (Actor.getModel with smoothing off) and works out how fast each step moves and where it
 * hits ({@link MotionAnalysis}). New weapons and NPCs need nothing else. Until an action is learnt it is plainly smoothed.
 *
 * Two ways in, both the client's own:
 * <ul>
 * <li>Timing: the keyframe lengths of a learnt action are replaced by retimed ones with the same total
 * (Animation.getFrameLengths() is the live array), so the action still ends when the game expects it to. Put back on
 * shutdown and set again when the client loads the animation anew (PostAnimation).</li>
 * <li>Pacing: keyframe f is blended into f + 1 by the time spent on it divided by its length. Just before a frame is
 * drawn (BeforeRender), the length of each character's current keyframe is set so the blend follows the pace, and put
 * back by a task on the client thread's queue, which the client runs at the start of its next cycle (Hooks.tick), before
 * it moves players and NPCs on (checked in the injected client 1.13.3; ClientTick comes after that move, too late).</li>
 * </ul>
 *
 * The game asks the client's animation filter, for each character and animated object it draws, whether to blend the
 * current keyframe into the next. The filter only gets the animation id, so before each frame is drawn this works out the
 * answer for every animation a character is playing right now. The GPU plugin builds animated objects on its render
 * threads, several at once, and they ask the filter too, so the filter only reads a snapshot made on the client thread.
 */
@Slf4j
@PluginDescriptor(
	name = "Keyframe Smoothing",
	description = "Smooth animations with an animator's timing: fast moves faster, swings as one fluid motion, hits that land",
	tags = {"animation", "smoothing", "interpolation", "keyframes", "tween", "players", "npcs", "impact"}
)
public class KeyframeSmoothingPlugin extends Plugin
{
	/** Length of a client tick in nanoseconds, the unit of animation frame lengths. */
	static final long TICK_NANOS = 20_000_000L;
	/** Pace through a keyframe that is a glitch when smoothed: slow out of the pose, fast into the next. */
	private static final double GLITCH_START = 0.2;
	private static final double GLITCH_END = 2.5;
	/** Vertices sampled per model when learning an action. */
	private static final int SAMPLES = 300;
	/** How long a trail point stays. */
	static final long TRAIL_NANOS = 140_000_000L;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyframeSmoothingConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TrailOverlay trailOverlay;

	private volatile KeyframeTable table;

	// client thread only
	/** Per animation id: what we know of it, loaded once. */
	private final Map<Integer, AnimationInfo> animations = new HashMap<>();
	/** Actions learnt from their keyframe poses. */
	final Map<Integer, MotionAnalysis.Motion> motions = new HashMap<>();
	/** Actions being learnt: their keyframe poses so far. */
	private final Map<Integer, Capture> captures = new HashMap<>();
	/** Retimed keyframe lengths per animation, and the original lengths of every live array changed for them. */
	private final Map<Integer, int[]> retimed = new HashMap<>();
	private final Map<int[], int[]> originals = new IdentityHashMap<>();
	/** Per character: since when it shows its current keyframe. */
	private final Map<Actor, KeyframeStart> starts = new IdentityHashMap<>();
	/** Trail points behind fast swings, per character. */
	final Map<Actor, Deque<TrailPoint>> trails = new IdentityHashMap<>();
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
	/** Set while the plugin takes a keyframe pose: no blend for that model. */
	private volatile boolean capturing;

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
		overlayManager.add(trailOverlay);
		clientThread.invoke(this::install);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(trailOverlay);
		clientThread.invoke(() ->
		{
			if (client.getAnimationInterpolationFilter() == filter)
			{
				client.setAnimationInterpolationFilter(previous);
			}
			previous = null;
			restoreFrameLengths();
			restoreRetimes();
			snapshot = Snapshot.NONE;
			animations.clear();
			motions.clear();
			captures.clear();
			starts.clear();
			trails.clear();
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
	public void onConfigChanged(ConfigChanged event)
	{
		if (KeyframeSmoothingConfig.GROUP.equals(event.getGroup()))
		{
			clientThread.invoke(() ->
			{
				restoreRetimes();
				motions.keySet().forEach(this::retime);
			});
		}
	}

	@Subscribe
	public void onPostAnimation(PostAnimation event)
	{
		// the client loaded an animation anew (its cache dropped the old one): it has the original lengths again
		Animation animation = event.getAnimation();
		int[] timed = animation == null ? null : retimed.get(animation.getId());
		if (timed != null)
		{
			setLengths(animation, timed);
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
		if (capturing)
		{
			return false;
		}
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
		long now = clock.getAsLong();
		trails.values().removeIf(points ->
		{
			points.removeIf(p -> now - p.nanos > TRAIL_NANOS);
			return points.isEmpty();
		});
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

		if (enabled && action)
		{
			learn(actor, animationId, frame);
		}
		boolean blend = enabled && blendsNow(actor, animationId, frame, action, cycle);
		// characters sharing an animation share the answer: one that must stay sharp wins
		decisions.merge(animationId, blend, Boolean::logicalAnd);
		if (enabled && action && config.weaponTrails())
		{
			trail(actor, animationId, frame);
		}
		return animationId;
	}

	// ---- learning ----

	/** Takes the actor's pose at each keyframe the first time it shows it, until the whole action is known. */
	private void learn(Actor actor, int animationId, int frame)
	{
		if (motions.containsKey(animationId))
		{
			return;
		}
		AnimationInfo info = info(animationId);
		if (info == null || info.frameLengths == null || frame < 0 || frame >= info.frameLengths.length)
		{
			return;
		}
		// walking legs would count as part of the action
		if (actor.getPoseAnimation() != actor.getIdlePoseAnimation())
		{
			return;
		}
		Capture capture = captures.computeIfAbsent(animationId, id -> new Capture(info.frameLengths.length));
		if (capture.poses[frame] != null)
		{
			return;
		}
		Model model;
		capturing = true;
		try
		{
			model = actor.getModel();
		}
		finally
		{
			capturing = false;
		}
		if (model == null)
		{
			return;
		}
		int count = model.getVerticesCount();
		if (capture.vertexCount != count)
		{
			// another model (other gear, another NPC): start over with this one
			capture.start(count);
		}
		float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
		float[] pose = new float[3 * capture.samples.length];
		for (int i = 0; i < capture.samples.length; i++)
		{
			int v = capture.samples[i];
			pose[3 * i] = x[v];
			pose[3 * i + 1] = y[v];
			pose[3 * i + 2] = z[v];
		}
		capture.poses[frame] = pose;
		if (capture.complete())
		{
			MotionAnalysis.Motion motion = MotionAnalysis.analyze(capture.poses, info.frameLengths);
			motions.put(animationId, motion);
			tipVertices.put(animationId, capture.samples[motion.tip]);
			captures.remove(animationId);
			retime(animationId);
		}
	}

	/** Model vertex of each learnt action's tip, for trails. */
	private final Map<Integer, Integer> tipVertices = new HashMap<>();

	/** Gives a learnt action its new keyframe lengths (same total), or leaves it as made when the settings say so. */
	private void retime(int animationId)
	{
		MotionAnalysis.Motion motion = motions.get(animationId);
		AnimationInfo info = animations.get(animationId);
		if (motion == null || info == null || info.frameLengths == null)
		{
			return;
		}
		double exaggeration = config.exaggerate() / 100.0 * 0.6;
		int hitStop = config.hitStop() ? Math.max(1, (config.hitStopTime() + 10) / 20) : 0;
		if (exaggeration <= 0 && hitStop == 0)
		{
			return;
		}
		int[] timed = MotionAnalysis.retime(info.frameLengths, motion, exaggeration, hitStop);
		if (Arrays.equals(timed, info.frameLengths))
		{
			return;
		}
		retimed.put(animationId, timed);
		Animation animation = client.loadAnimation(animationId);
		if (animation != null)
		{
			setLengths(animation, timed);
		}
	}

	/** The keyframe lengths the game counts with for an animation now: retimed, or null when as made. */
	int[] retimedLengths(int animationId)
	{
		return retimed.get(animationId);
	}

	private void setLengths(Animation animation, int[] timed)
	{
		int[] live = animation.getFrameLengths();
		if (live == null || live.length != timed.length)
		{
			return;
		}
		originals.computeIfAbsent(live, l -> l.clone());
		System.arraycopy(timed, 0, live, 0, live.length);
	}

	private void restoreRetimes()
	{
		originals.forEach((live, original) -> System.arraycopy(original, 0, live, 0, live.length));
		originals.clear();
		retimed.clear();
	}

	// ---- pacing ----

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
		double pacing = config.pacing() / 100.0;
		boolean glitch = config.fixGlitches() && (letter == KeyframeTable.TURN || letter == KeyframeTable.JUMP);
		MotionAnalysis.Motion motion = motions.get(animationId);
		// walking and standing keep an even speed, and so does an action until it is learnt or without pacing
		if (!action || motion == null || frame >= motion.speed.length || glitch && pacing <= 0)
		{
			return !glitch;
		}

		// the pace a spline through the keyframes would have: speed carries over where the motion goes on, eases where
		// it turns back, and hits arrive at full speed
		double startSpeed = 1 + pacing * (motion.startSpeed[frame] - 1);
		double endSpeed = 1 + pacing * (motion.endSpeed[frame] - 1);
		if (glitch)
		{
			// a big turn or jump in an action is mostly the strike itself: through it fast, so a turn the wrong way only
			// shows for a moment (loops starting over, where jumps matter most, are never blended above)
			startSpeed = Math.min(startSpeed, GLITCH_START);
			endSpeed = Math.max(endSpeed, GLITCH_END);
		}
		// the cubic never moves back for speeds up to 3
		startSpeed = Math.min(3, startSpeed);
		endSpeed = Math.min(3, endSpeed);

		int[] timed = retimed.get(animationId);
		int length = timed != null ? timed[frame] : info.frameLengths[frame];
		double tickPart = Math.min(0.99, Math.max(0, (clock.getAsLong() - lastTickNanos) / (double) TICK_NANOS));
		double spent = Math.min(length - 0.01, Math.max(0, cycle - start.cycle) + tickPart);
		double x = spent / length;
		int stop = config.hitStop() && motion.hit[frame] ? Math.max(1, (config.hitStopTime() + 10) / 20) : 0;
		if (stop > 0 && stop < length)
		{
			// the hit pose stands still for the stop, then goes on
			x = Math.max(0, (spent - stop) / (length - stop));
		}
		if (stop == 0 && Math.abs(startSpeed - 1) < 1e-6 && Math.abs(endSpeed - 1) < 1e-6)
		{
			// an even pace is the client's own blend
			return true;
		}
		double target = Timing.pace(x, startSpeed, endSpeed);
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

	// ---- trails ----

	/** Adds the tip of the weapon or limb to the actor's trail while a fast step plays. */
	private void trail(Actor actor, int animationId, int frame)
	{
		MotionAnalysis.Motion motion = motions.get(animationId);
		Integer tip = tipVertices.get(animationId);
		if (motion == null || tip == null || frame >= motion.speed.length || motion.speed[frame] < motion.fast)
		{
			return;
		}
		Model model = actor.getModel();
		LocalPoint location = actor.getLocalLocation();
		if (model == null || location == null || tip >= model.getVerticesCount())
		{
			return;
		}
		int height = Perspective.getTileHeight(client, location, actor.getWorldView().getPlane());
		trails.computeIfAbsent(actor, a -> new ArrayDeque<>()).addLast(new TrailPoint(location.getX(), location.getY(), height,
			actor.getCurrentOrientation(), model.getVerticesX()[tip], model.getVerticesY()[tip], model.getVerticesZ()[tip],
			clock.getAsLong()));
	}

	/** A point of a trail: a model vertex, and where and how the model stood when it was taken. */
	static final class TrailPoint
	{
		final int x, y, z, orientation;
		final float vx, vy, vz;
		final long nanos;

		TrailPoint(int x, int y, int z, int orientation, float vx, float vy, float vz, long nanos)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.orientation = orientation;
			this.vx = vx;
			this.vy = vy;
			this.vz = vz;
			this.nanos = nanos;
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

	/** Keyframe poses of an action being learnt. */
	private static final class Capture
	{
		final float[][] poses;
		int vertexCount = -1;
		int[] samples = new int[0];

		Capture(int keyframes)
		{
			poses = new float[keyframes][];
		}

		void start(int count)
		{
			vertexCount = count;
			Arrays.fill(poses, null);
			int n = Math.min(SAMPLES, count);
			samples = new int[n];
			for (int i = 0; i < n; i++)
			{
				samples[i] = (int) ((long) i * count / n);
			}
		}

		boolean complete()
		{
			for (float[] pose : poses)
			{
				if (pose == null)
				{
					return false;
				}
			}
			return samples.length > 0;
		}
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
