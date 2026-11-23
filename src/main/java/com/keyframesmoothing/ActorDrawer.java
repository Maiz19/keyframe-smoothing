package com.keyframesmoothing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;
import net.runelite.api.Actor;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Draws a player or NPC (client thread, from {@link DrawHook}): changes the pose the client made for this frame, lets
 * the renderer draw it, and puts the model back.
 * <ul>
 * <li>Keyframes: parts the animation moves follow the learnt {@link PartSpline}; the client's blend stays for the rest.</li>
 * <li>Weapon ghosts: the renderer is handed a model of the weapon alone a few times, see-through, where the weapon was in
 * the last moments.</li>
 * </ul>
 * The model is the client's copy for this frame: everything changed is put back right after the renderer has read it.
 */
final class ActorDrawer
{
	/**
	 * Strike timing, with an attack's keyframes kept: how far along its way a blow is drawn (from where it starts to where
	 * it lands, see along) runs as this power of the game's time: slower at first, faster towards the hit, there on time (1
	 * would be even).
	 */
	static final double STRIKE_EASE = 2;
	/**
	 * Exaggerated blows (a setting, see moment), past their keyframes where that hits harder:
	 * <ul>
	 * <li>the wind-up: where it turns round (see Learnt.windUp) the weapon goes on further, PULL_BACK of a step. That
	 * takes PULL_BACK_PACE of the game's pace (at least WIND_UP_LEAST and at most WIND_UP_MOST of the blow), while the
	 * game's pose slows into that keyframe over SLOWING times as long before it (at most PULL_BACK_EARLY ticks): one
	 * motion on further, slowing to the furthest point, and straight round into the strike;</li>
	 * <li>the strike: from there to where the blow lands, leaving at STRIKE_START of the game's pace, at the hit
	 * EXAGGERATED_EASE times it, faster and faster along its way (see along) towards the end, there on time; the pull let go
	 * as quickly as it was taken up;</li>
	 * <li>follow-through: it carries on past where it lands, FOLLOW_THROUGH of the blow's last step, taken up on the way
	 * and reached right as it lands, and springs back over up to SETTLE_TICKS.</li>
	 * </ul>
	 */
	static final double EXAGGERATED_EASE = 3.5;
	static final double PULL_BACK = 0.35;
	static final double PULL_BACK_PACE = 0.67;
	static final double WIND_UP_LEAST = 0.1;
	static final double WIND_UP_MOST = 0.4;
	static final double SLOWING = 1.67;
	static final double PULL_BACK_EARLY = 3;
	static final double STRIKE_START = 0.6;
	/**
	 * Where a blow winds up (see Learnt.windUp): pushing the pose on goes back against the blow and on the way it arrives
	 * (the pose ARRIVING of a step before the keyframe: cosines over WIND_UP_AGREE), or it arrives moving less than
	 * ARRIVING_STILL as much as the push.
	 */
	static final double ARRIVING = 0.1, WIND_UP_AGREE = 0.2, ARRIVING_STILL = 0.25;
	static final double FOLLOW_THROUGH = 0.3;
	static final double SETTLE_TICKS = 3;
	/**
	 * A blow is paced along its way, not its keyframes' time (see along): a slow long step before a short fast one (a
	 * halberd's swipe) would else crawl and then be over in a flash, and the spline slowing into a keyframe would show
	 * as a stop. Every moment counts as moving at least this part of the blow's mean pace, so a held pose in a blow still
	 * takes some time.
	 */
	static final double WAY_LEAST = 0.2;
	/**
	 * A blow most of whose time (at least this part) is one step slower than its mean, before a shorter faster one (a
	 * halberd's swipe: 16 slow ticks into 3 fast ones), is struck as one fast motion: its whole way in the time the game
	 * takes for the rest, the slow step's time spent winding up.
	 */
	static final double SWEEP = 0.75;
	/** A strike lands at least this many times as fast as it goes on average (a swept one, see SWEEP, would else not speed up). */
	static final double HIT_LEAST = 2.5;
	/** Ghosts: how far back the trail reaches, and how solid it is near the weapon. */
	static final long GHOST_TRAIL_NANOS = 120_000_000L;
	/** A skill's tool trails shorter: it strikes down in one quick stroke. */
	static final long SKILL_TRAIL_NANOS = 70_000_000L;
	static final double GHOST_STRENGTH = 0.6;
	/**
	 * Ghosts: afterimages of the weapon during a blow only (see MotionAnalysis.strikes), from where it starts to where it
	 * lands (not the wind-up or the wind-down), as one smear coming off the weapon: a faint copy every GHOST_STEP ticks
	 * of drawn time, back as far as GHOST_TRAIL_NANOS, so close together that they flow into each other. Where
	 * GHOST_SOLID ticks of copies lie on top of each other they are together as solid as GHOST_STRENGTH; older
	 * parts of the trail are fainter. After it lands no new ones: the trail stays and fades out over GHOST_FADE_NANOS.
	 * Per face as solid as it moves: the blade leaves a streak, the grip almost nothing.
	 */
	static final double GHOST_STEP = 0.125;
	static final double GHOST_SOLID = 0.5;
	static final long GHOST_FADE_NANOS = 240_000_000L;
	/** Faces moving less than this part of the weapon's fastest move over the trail show nothing; full from GHOST_FULL. */
	static final double GHOST_GRIP = 0.4;
	static final double GHOST_FULL = 0.9;
	/** A ghost face fainter than this is left out (and a ghost with nothing left is not drawn). */
	static final double GHOST_LEAST = 0.01;
	/**
	 * Drawn time going back more than this (ticks) is the attack starting over. Less is the clock wavering: the game's
	 * time stands still for a moment when a client cycle comes late.
	 */
	static final double RESTART_TICKS = 2;
	/**
	 * An action starting over (a skill repeated, an attack again) the game shows at its first keyframe at once, from
	 * wherever it was: drawn from the pose it was in, what that is off by let go of over this long (ticks), while the
	 * action plays on underneath.
	 */
	static final double RESTART_BLEND_TICKS = 4;
	/** A keyframe lasting this much longer (ticks) than its length is overdue: the action isn't playing yet (Track). */
	static final double OVERDUE_TICKS = 1.5;
	/**
	 * A model drawn along a learnt spline must be about as big as the keyframes it was learnt from: within this factor
	 * (a blend can reach a little further than either keyframe), and FIT_UNITS (model units) either way.
	 */
	static final double FIT_SIZE = 1.3;
	static final double FIT_UNITS = 8;
	private final KeyframeSmoothingPlugin plugin;
	/** Off once the plugin stops: a hook that another one keeps in its chain then only passes calls on. */
	volatile boolean enabled = true;
	/** Time source (nanoseconds); tests replace it. */
	LongSupplier clock = System::nanoTime;

	// client thread only
	/** Set while a model is being drawn through this drawer. */
	private boolean drawing;
	private float[] pose = new float[0];
	/** The pose before a push that keeps both hands on a tool; reused. */
	private float[] gripped = new float[0];
	/** A rope's head at a moment of its own (see Moment.headKey). */
	private float[] headPose = new float[0];
	private float[] savedX = new float[0], savedY = new float[0], savedZ = new float[0];
	/** The pose last worked out for another plugin (see posed). */
	private float[] posedX = new float[0], posedY = new float[0], posedZ = new float[0];
	/** The local player's weapon during the current blow, and the trails of blows that landed, fading out. */
	private Trail trail;
	private final List<Trail> fading = new ArrayList<>();
	/** What smooth() worked out for the model being drawn: the learnt animation and the drawn time, else null and NaN. */
	private Learning.Learnt drawnLearnt;
	private double drawnTime = Double.NaN;

	ActorDrawer(KeyframeSmoothingPlugin plugin)
	{
		this.plugin = plugin;
	}

	void draw(DrawCallbacks next, Actor actor, Projection projection, Scene scene, GameObject object, Model model,
		int orientation, int x, int y, int z)
	{
		if (!enabled || drawing)
		{
			// off, or a second hook of ours further down the renderer's chain: once is enough
			next.drawTemp(projection, scene, object, model, orientation, x, y, z);
			return;
		}
		drawing = true;
		boolean saved = false;
		try
		{
			try
			{
				Track track = plugin.track(actor);
				long now = clock.getAsLong();
				drawnLearnt = null;
				drawnTime = Double.NaN;
				if (track != null && track.blend != null && track.animationId >= 0
					&& plugin.decides(track.animationId) != track.blend)
				{
					// the client was told how the others showing this animation are best blended: drawn again with its
					// own answer (one at a keyframe that blends badly while they aren't, or the other way round)
					Model own = plugin.remodel(actor, track.animationId, track.blend);
					if (own != null)
					{
						model = own;
					}
				}
				if (track != null)
				{
					saved = smooth(actor, track, model);
				}
				if (track != null && track.action)
				{
					saved |= restart(track, model, now, saved);
				}
			}
			catch (RuntimeException e)
			{
				plugin.failed("smoothing", e);
			}
			next.drawTemp(projection, scene, object, model, orientation, x, y, z);
			if (actor == plugin.localPlayer() && plugin.ghosts())
			{
				try
				{
					// an attack's blows only: not a weapon swinging along while walking, nor blocking or eating with a target
					// set (see KeyframeSmoothingPlugin.strikes); with the setting, a special's only; and of a style ghosts are on for
					Track track = plugin.track(actor);
					KeyframeSmoothingPlugin.Settings settings = plugin.settings();
					boolean trailed = track != null && track.attack && plugin.strikes(track.animationId)
						&& (track.special || !settings.ghostsSpecOnly) && settings.ghosts(track.style);
					// a skill's tool strikes: its own toggle, never with specials only
					trailed |= track != null && track.skill && settings.skilling && settings.ghostsSkilling && !settings.ghostsSpecOnly;
					int attack = trailed ? track.animationId : -1;
					ghosts(next, projection, scene, object, model, orientation, x, y, z, attack, track != null && track.skill);
				}
				catch (RuntimeException e)
				{
					plugin.failed("ghosts", e);
				}
			}
		}
		finally
		{
			if (saved)
			{
				restoreVertices(model);
			}
			drawing = false;
		}
	}

	/**
	 * The pose the actor will be drawn in this frame, worked out ahead for another plugin (the tile-marker plugin cuts the
	 * player's silhouette out of its marks before the scene is drawn): the vertices draw() hands the renderer for this
	 * model, or null when that is the model as the client posed it. Nothing of the drawing changes: the model is put
	 * back and the track and drawer are left as they were. The arrays are reused by the next call.
	 */
	float[][] posed(Actor actor, Model model)
	{
		if (!enabled || drawing || model == null)
		{
			return null;
		}
		Track track = plugin.track(actor);
		if (track == null)
		{
			return null;
		}
		drawing = true;
		Learning.Learnt keptLearnt = drawnLearnt;
		double keptTime = drawnTime, keptTrackTime = track.drawnTime;
		long keptShape = track.shape;
		boolean keptOverdue = track.overdue;
		boolean saved = false;
		Model own = model;
		try
		{
			if (track.blend != null && track.animationId >= 0 && plugin.decides(track.animationId) != track.blend)
			{
				Model other = plugin.remodel(actor, track.animationId, track.blend);
				if (other != null)
				{
					own = other;
				}
			}
			saved = smooth(actor, track, own);
			int count = own.getVerticesCount();
			float[] offset = null;
			double w = 0;
			if (track.action)
			{
				long now = clock.getAsLong();
				offset = track.restarting ? restartOffset(track, own, plugin.gameCycle()) : track.restartOffset;
				long nanos = track.restarting ? restartNanos(track, now) : track.restartNanos;
				w = offset == null ? 0 : restartWeight(offset, nanos, now, count);
			}
			if (!saved && w <= 0 && own == model)
			{
				return null;
			}
			if (posedX.length < count)
			{
				posedX = new float[count];
				posedY = new float[count];
				posedZ = new float[count];
			}
			float[] x = own.getVerticesX(), y = own.getVerticesY(), z = own.getVerticesZ();
			for (int v = 0; v < count; v++)
			{
				posedX[v] = x[v] + (w > 0 ? (float) (offset[3 * v] * w) : 0);
				posedY[v] = y[v] + (w > 0 ? (float) (offset[3 * v + 1] * w) : 0);
				posedZ[v] = z[v] + (w > 0 ? (float) (offset[3 * v + 2] * w) : 0);
			}
			return new float[][]{posedX, posedY, posedZ};
		}
		catch (RuntimeException e)
		{
			plugin.failed("pose for another plugin", e);
			return null;
		}
		finally
		{
			if (saved)
			{
				restoreVertices(own);
			}
			drawnLearnt = keptLearnt;
			drawnTime = keptTime;
			track.drawnTime = keptTrackTime;
			track.shape = keptShape;
			track.overdue = keptOverdue;
			drawing = false;
		}
	}

	// ---- keyframes ----

	/** Poses the model along the learnt spline; true when its vertices were changed (and saved first). */
	private boolean smooth(Actor actor, Track track, Model model)
	{
		track.drawnTime = Double.NaN;
		if (track.animationId < 0)
		{
			return false;
		}
		long shape = Learning.shape(actor, model);
		track.shape = shape;
		int[] lengths = plugin.lengths(track.animationId);
		int k = track.frame;
		if (lengths == null || k < 0 || k >= lengths.length)
		{
			return false;
		}
		double into = plugin.ticksInto(track);
		// an action that plays on, standing, and stays in its first keyframe was sent again by the game within that
		// keyframe, which starts the keyframe over unseen: held at its end until the game gets there, not a start delay
		boolean sentAgain = k == 0 && track.action && track.repeats && track.standing;
		if (k < lengths.length - 1 && into > lengths[k] + OVERDUE_TICKS && !sentAgain)
		{
			// the client doesn't play the action yet (its start delay): what it draws is shown as it is
			track.overdue = true;
			return false;
		}
		double ticks = Math.min(lengths[k], into);
		double s = ticks / lengths[k];
		if (track.mode != Smoothing.KEYFRAMES)
		{
			return false;
		}
		Learning.Learnt learnt = plugin.learnt(track.animationId, shape);
		if (learnt == null || learnt.vertices != model.getVerticesCount() || learnt.full != lengths.length)
		{
			return false;
		}
		if (learnt.cut() && k >= learnt.keyframes() - 1)
		{
			// past where the game always starts it over: the client's own blend
			return false;
		}
		if (!fits(learnt, k, model))
		{
			// not the model it was learnt on, as big
			return false;
		}
		double time = learnt.starts[k] + ticks;
		KeyframeSmoothingPlugin.Settings settings = plugin.settings();
		// the local player's attack landing when its hitsplat comes, then its blows, or a skill's or repeated action's, paced
		// to land with force, exaggerated or with their keyframes kept
		double synced = track.attack && settings.syncHits && actor == plugin.localPlayer()
			? synced(learnt, time, plugin.hitAt(track.animationId)) : time;
		boolean blows = track.attack || settings.skilling && (track.skill || track.repeats);
		// the first swing lands as the game has it; the ones after it with the sound (see SkillSwings)
		boolean first = !track.repeats;
		// past the last keyframe's start on the way into the next swing (see held): how far into that keyframe, else NaN
		double looping = Double.NaN;
		if (blows && track.skill && !first)
		{
			synced = heard(learnt, synced);
			// started over by the game while its swing still wound up: two quick swings, the new one on time (see held)
			synced = held(learnt, synced, heard(learnt, track.cutTime), settings.strikeEase, settings.exaggerate, settings.hitOnSound);
			int last = learnt.keyframes() - 1;
			double end = loopEnd(learnt);
			if (synced >= end)
			{
				synced -= end;
			}
			else if (synced > learnt.starts[last])
			{
				looping = (synced - learnt.starts[last]) / learnt.lengths[last];
			}
		}
		else if (blows && track.skill && settings.hitOnSound && track.againCycle >= 0)
		{
			// its first swing: the game sends it again with its sound once that has landed, a second chop lands then (see twice)
			synced = twice(learnt, synced, track.againCycle - track.startCycle + learnt.starts[k], settings.strikeEase, settings.exaggerate);
		}
		Moment at = !blows || settings.strikeEase <= 1 ? plain(learnt, synced)
			: settings.exaggerate ? moment(learnt, synced, settings.strikeEase, first) : paced(learnt, synced, settings.strikeEase);
		if (!Double.isNaN(looping) && at.key == learnt.keyframes() - 1)
		{
			// the client's blend from the last keyframe into the first, which the spline doesn't have
			at.s = looping;
		}
		drawnLearnt = learnt;
		drawnTime = at.drawn;
		track.drawnTime = at.drawn;
		int shownKey = at.key;
		double shownS = at.s;
		PartSpline spline = learnt.spline;
		boolean pushed = at.pull != 0 || at.follow != 0;
		// drawn at another time than the game's (not the last keyframe, which plain() puts at its start: from there the
		// client blends a loop into its first keyframe, and holds the end of an animation that doesn't loop)
		boolean warped = Math.abs(at.drawn - time) > 1e-6 || pushed;
		if (!spline.smooths(shownKey) && !warped && !spline.roped())
		{
			// (a rope is laid in the client's own pose too, see PartSpline.rope: its blend tears the rope off its head)
			return false;
		}
		int count = model.getVerticesCount();
		if (pose.length < 3 * count)
		{
			pose = new float[3 * count];
		}
		save(model);
		float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
		for (int v = 0; v < count; v++)
		{
			pose[3 * v] = x[v];
			pose[3 * v + 1] = y[v];
			pose[3 * v + 2] = z[v];
		}
		// a rope's head at a moment of its own (see Moment.headKey), also where the rest is in a step the client draws
		boolean own = at.headKey >= 0 && spline.smooths(at.headKey);
		if (own)
		{
			headPose = headPose.length < pose.length ? new float[pose.length] : headPose;
			System.arraycopy(pose, 0, headPose, 0, 3 * count);
		}
		if (own)
		{
			spline.pose(headPose, at.headKey, at.headS, track.standing);
		}
		if (spline.smooths(shownKey))
		{
			// the head put in where its stub of rope lies in the hand
			spline.pose(pose, shownKey, shownS, track.standing, own ? headPose : null);
		}
		else if (warped && !spline.clientPose(pose, shownKey, shownS, track.standing))
		{
			// a step the client was never seen blending: no way to show that moment
			return false;
		}
		else if (own)
		{
			spline.headFrom(pose, headPose);
		}
		if (at.grip && at.pull != 0)
		{
			gripped = gripped.length < pose.length ? new float[pose.length] : gripped;
			System.arraycopy(pose, 0, gripped, 0, 3 * count);
			spline.push(pose, at.pullKey, at.pull, track.standing);
			spline.keepGrip(pose, gripped, at.pullKey + 1, track.standing);
		}
		else
		{
			spline.push(pose, at.pullKey, at.pull, track.standing);
		}
		if (at.flung)
		{
			spline.fling(pose, at.pullKey, at.pull);
		}
		spline.push(pose, at.followKey, at.follow, track.standing);
		// a rope laid again between its hand and its head as they are now (pulled, flung, or as the client blends them)
		spline.rope(pose);
		for (int v = 0; v < count; v++)
		{
			x[v] = pose[3 * v];
			y[v] = pose[3 * v + 1];
			z[v] = pose[3 * v + 2];
		}
		model.translate(0, 0, 0);
		model.calculateBoundsCylinder();
		return true;
	}

	/**
	 * An action that just started over: drawn from the pose it was drawn in last, what that is off by let go of over
	 * RESTART_BLEND_TICKS; and the pose drawn now kept for the next time it starts over. True when the vertices were
	 * changed (and saved first, unless saved already).
	 */
	private boolean restart(Track track, Model model, long now, boolean saved)
	{
		int count = model.getVerticesCount();
		int cycle = plugin.gameCycle();
		float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
		if (track.restarting)
		{
			track.restarting = false;
			track.restartOffset = restartOffset(track, model, cycle);
			track.restartNanos = restartNanos(track, now);
			track.restartSeenLate = false;
		}
		boolean changed = false;
		float[] offset = track.restartOffset;
		if (offset != null)
		{
			double w = restartWeight(offset, track.restartNanos, now, count);
			if (w <= 0)
			{
				track.restartOffset = null;
			}
			else
			{
				if (!saved)
				{
					save(model);
				}
				for (int v = 0; v < count; v++)
				{
					x[v] += (float) (offset[3 * v] * w);
					y[v] += (float) (offset[3 * v + 1] * w);
					z[v] += (float) (offset[3 * v + 2] * w);
				}
				model.translate(0, 0, 0);
				model.calculateBoundsCylinder();
				changed = true;
			}
		}
		if (track.drawnPose == null || track.drawnPose.length != 3 * count)
		{
			track.drawnPose = new float[3 * count];
		}
		float[] pose = track.drawnPose;
		for (int v = 0; v < count; v++)
		{
			pose[3 * v] = x[v];
			pose[3 * v + 1] = y[v];
			pose[3 * v + 2] = z[v];
		}
		track.drawnCycle = cycle;
		return changed;
	}

	/**
	 * What an action that just started over is off by (the pose drawn just before minus the model's), or null when that
	 * was a while ago (off screen) or on a model of another size.
	 */
	private static float[] restartOffset(Track track, Model model, int cycle)
	{
		int count = model.getVerticesCount();
		float[] last = track.drawnPose;
		if (last == null || last.length != 3 * count || cycle - track.drawnCycle > 2)
		{
			return null;
		}
		float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
		float[] offset = new float[3 * count];
		for (int v = 0; v < count; v++)
		{
			offset[3 * v] = last[3 * v] - x[v];
			offset[3 * v + 1] = last[3 * v + 1] - y[v];
			offset[3 * v + 2] = last[3 * v + 2] - z[v];
		}
		return offset;
	}

	/** When the offset of an action starting over is let go of from: when the game started it over, a moment before it is drawn. */
	private long restartNanos(Track track, long now)
	{
		if (track.restartSeenLate)
		{
			// started over unseen, the keyframe going on longer than its length: let go of from when that was seen
			return now;
		}
		return now - (long) (Math.min(plugin.ticksInto(track), RESTART_BLEND_TICKS) * KeyframeSmoothingPlugin.TICK_NANOS);
	}

	/** How much of a restart's offset is still drawn now, 0 once it is let go of (or no longer fits the model). */
	private static double restartWeight(float[] offset, long nanos, long now, int count)
	{
		double u = (now - nanos) / (double) KeyframeSmoothingPlugin.TICK_NANOS / RESTART_BLEND_TICKS;
		return u >= 1 || offset.length != 3 * count ? 0 : 1 - smooth(u);
	}

	/**
	 * Whether the model is about as big as the learnt keyframes either side of where it is (within FIT_SIZE, plus
	 * FIT_UNITS for small models): drawn along the spline otherwise, it would change size.
	 */
	static boolean fits(Learning.Learnt learnt, int k, Model model)
	{
		int last = learnt.keyframes() - 1;
		double a = learnt.sizes[Math.min(k, last)], b = learnt.sizes[Math.min(k + 1, last)];
		double size = Learning.size(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(), model.getVerticesCount());
		return size >= Math.min(a, b) / FIT_SIZE - FIT_UNITS && size <= Math.max(a, b) * FIT_SIZE + FIT_UNITS;
	}

	private static int keyframeAt(Learning.Learnt learnt, double time)
	{
		int k = 0;
		while (k < learnt.keyframes() - 1 && time >= learnt.starts[k + 1])
		{
			k++;
		}
		return k;
	}

	/** What is drawn at a moment: see moment. */
	static final class Moment
	{
		/** The game's pose drawn: keyframe step and how far into it. */
		int key;
		double s;
		/** Pushed on past it (see PartSpline.push): along step pullKey by pull (below 0: back), along followKey by follow. */
		int pullKey = -1, followKey = -1;
		double pull, follow;
		/** The pull keeps what is held in two hands in both (see PartSpline.keepGrip). */
		boolean grip;
		/** The drawn time for the rest (ghosts, a hit landing): the game's time as drawn. */
		double drawn;
		/** The blow (the keyframe it lands in) this is before, winding up for or striking in, and when (ticks) it starts to strike; -1 and NaN: none. */
		int blow = -1;
		double strikeFrom = Double.NaN;
		/** A rope's head drawn at a moment of its own (see PartSpline.headFrom): keyframe step and how far into it; -1: none. */
		int headKey = -1;
		double headS;
		/** The pull is a rope's head flung out further (see PartSpline.fling), with what push does of it. */
		boolean flung;
	}

	/** A moment of the animation as the game has it. */
	static Moment plain(Learning.Learnt learnt, double time)
	{
		Moment at = new Moment();
		at.key = keyframeAt(learnt, time);
		at.s = at.key >= learnt.keyframes() - 1 ? 0 : (time - learnt.starts[at.key]) / learnt.lengths[at.key];
		at.drawn = time;
		return at;
	}

	/**
	 * The time (ticks from its start) an attack is shown at for its first hit to land when its hitsplat comes (hitAt, see
	 * KeyframeSmoothingPlugin.hitAt): up to there faster than the game, from there to its last keyframe slower. As the game
	 * has it while the hitsplat is not known, or comes after the hit (a projectile's).
	 */
	static double synced(Learning.Learnt learnt, double time, double hitAt)
	{
		int n = learnt.keyframes(), hit = 1;
		while (hit < Math.min(n, learnt.motion.hit.length) && !learnt.motion.hit[hit])
		{
			hit++;
		}
		double land = hit < Math.min(n, learnt.motion.hit.length) ? learnt.starts[hit] : Double.NaN, end = learnt.starts[n - 1];
		if (!(hitAt > 0 && hitAt < land && land < end) || time >= end)
		{
			return time;
		}
		return time <= hitAt ? time * land / hitAt : land + (time - hitAt) * (end - land) / (end - hitAt);
	}

	/**
	 * The time a skill's swing is shown at for it to land when its sound comes, where that is a keyframe after it lands
	 * (see SkillSwings): slower than the game from where the swing starts, then on at the game's pace, that far behind until
	 * the sound's keyframe ends. As the game has it elsewhere.
	 */
	static double heard(Learning.Learnt learnt, double time)
	{
		int[] heard = learnt.motion.heard;
		for (int b = 1; heard != null && b < heard.length; b++)
		{
			if (heard[b] < 0)
			{
				continue;
			}
			double from = learnt.starts[learnt.blowStart(b)], land = learnt.starts[b], sound = learnt.starts[heard[b]];
			if (time > from && time < sound)
			{
				return from + (time - from) * (land - from) / (sound - from);
			}
			if (time >= sound && time <= sound + learnt.lengths[heard[b]])
			{
				return land + time - sound;
			}
		}
		return time;
	}

	/**
	 * The time a skill's swing is shown at after the game started the action over while that swing still wound up (cut:
	 * where it was then; the game sends chopping again every few game ticks, in the Gauntlet a few ticks into every third
	 * swing, and that puts it back at its first keyframe). Shown as the game has it, it would drop out of its wind-up and
	 * start it again; held up there until the new swing strikes, it stalls. Two quick swings instead, the new one striking
	 * and landing on time:
	 * <ul>
	 * <li>drawn back already (at the top): it strikes now and the way back up after it and the new swing's wind-up
	 * are gone through in the same time, the speed rising smoothly from the game's to a peak and falling back to it
	 * as the new swing strikes (see soft; no jump between the game's pace and a faster one).
	 * A time past the animation's end is in the new swing: that much past its start (see loopTime);</li>
	 * <li>still drawing back: quickly back down the way it came and wound up again, there as the new swing strikes.</li>
	 * </ul>
	 * As the game has it when the swing was already striking or had landed (that is drawn from the pose it was in, see
	 * restart), and with the blows paced evenly.
	 *
	 * @param time the time since it started over
	 */
	static double held(Learning.Learnt learnt, double time, double cut, double ease, boolean exaggerate)
	{
		return held(learnt, time, cut, ease, exaggerate, false);
	}

	/**
	 * @param onSound the game plays the chop's sound as it sends the swing again, so a swing cut before it lands strikes at
	 * once, in the time its strike takes, and lands with that sound (then drawn back a little as else, see rebound)
	 */
	static double held(Learning.Learnt learnt, double time, double cut, double ease, boolean exaggerate, boolean onSound)
	{
		double strike = Double.isNaN(cut) || ease <= 1 ? Double.NaN : striking(learnt, cut, ease, exaggerate);
		if (Double.isNaN(strike))
		{
			return time;
		}
		int first = firstBlow(learnt);
		double top = learnt.starts[learnt.blowStart(first)], land = learnt.starts[first];
		if (onSound && !learnt.cut() && cut < land)
		{
			return rebound(time, cut, land, land - REBOUND * (land - strike), land - strike);
		}
		if (cut < top && time < strike)
		{
			double back = (cut + strike) * time / strike;
			return back < cut ? cut - back : back - cut;
		}
		if (cut < top)
		{
			return time;
		}
		if (learnt.cut())
		{
			// no room for two: one longer wind-up, struck and landing on time
			return time < strike ? cut + (strike - cut) * time / strike : time;
		}
		// the cut swing strikes on at the game's pace and lands, then is drawn back only a little for the new one
		return rebound(time, cut, land, land - REBOUND * (land - strike));
	}

	/** How far back a swing that was cut is drawn after it landed, before the new one strikes (the share of the strike). */
	static final double REBOUND = 0.5;

	/**
	 * Where a cut swing is at a time since the start over: on from the cut at the game's pace to where it lands (the
	 * target is hit), then drawn smoothly back (from and to a standstill, a smoothstep) only to a time before the new swing
	 * lands, from where it strikes again at the game's pace: the new swing lands when the game has it.
	 *
	 * @param back the time (from the animation's start) it is drawn back to, and the game's time then; no time to land and
	 * be drawn back before it (at most the time it takes to land): as the game has it
	 */
	static double rebound(double time, double cut, double land, double back)
	{
		return rebound(time, cut, land, back, land - cut);
	}

	/** As rebound, but the cut swing takes the given time to land (a quick strike when cut early: then at once, evenly). */
	static double rebound(double time, double cut, double land, double back, double landed)
	{
		if (time <= landed)
		{
			return cut + (land - cut) * time / landed;
		}
		if (time >= back || back <= landed)
		{
			return time;
		}
		double s = (time - landed) / (back - landed);
		return land + (back - land) * s * s * (3 - 2 * s);
	}

	/** Not sent again when it was expected (see twice): caught up with the game over this many ticks. */
	static final double CATCH_UP = 6;
	/**
	 * How long before the game sends it again the second chop has landed (see twice), ticks: the strike's last part is
	 * its fastest, and the frame it lands in must be drawn before the action starts over (30 ms: seen as on the sound).
	 */
	static final double TWICE_EARLY = 1.5;

	/**
	 * The time a skill's first swing is shown at when the game is about to send it again, with its sound, after that swing
	 * landed (the Gauntlet's chop: a game tick after it starts, 12 ticks after its first chop landed with the animation's
	 * own sound; see Track.againCycle): two sounds, so two chops (drawn as the game has it, the second sound
	 * comes with the axe at rest in the tree). Landed, it is
	 * drawn back as a cut swing is (smoothly, REBOUND of the strike) and strikes again at the game's pace, landed
	 * TWICE_EARLY before the game sends it again and at rest there until then. Not sent again then after all: on from where
	 * it landed, caught up with the game in CATCH_UP ticks. As the game has it until it lands, with too little time to be
	 * drawn back (twice the strike back), or with the blows paced evenly.
	 *
	 * @param again when the game sends it again (ticks from the animation's start)
	 */
	static double twice(Learning.Learnt learnt, double time, double again, double ease, boolean exaggerate)
	{
		int first = firstBlow(learnt);
		if (first >= learnt.keyframes() || ease <= 1 || learnt.cut())
		{
			return time;
		}
		double land = learnt.starts[first], strike = learnt.starts[learnt.blowStart(first)];
		if (exaggerate)
		{
			// where the first swing's strike starts (not yet started over: see moment)
			Moment at = moment(learnt, land, ease, true);
			strike = at.blow == first ? at.strikeFrom : Double.NaN;
		}
		if (!(strike < land) || time <= land || time >= again + CATCH_UP)
		{
			return time;
		}
		double back = land - REBOUND * (land - strike), hit = again - TWICE_EARLY, up = hit - (land - back);
		if (up - land < 2 * (land - back))
		{
			return time;
		}
		if (time >= again)
		{
			return land + (time - again) * (again + CATCH_UP - land) / CATCH_UP;
		}
		if (time >= up)
		{
			return Math.min(land, land - (hit - time));
		}
		double s = (time - land) / (up - land);
		return land + (back - land) * s * s * (3 - 2 * s);
	}

	/** The animation's end (ticks from its start): where a skill's swing loops back to its first keyframe. */
	static double loopEnd(Learning.Learnt learnt)
	{
		int last = learnt.keyframes() - 1;
		return learnt.starts[last] + learnt.lengths[last];
	}

	/** The keyframe the animation's first blow lands in, keyframes() when it has none. */
	private static int firstBlow(Learning.Learnt learnt)
	{
		int first = 1;
		while (first < learnt.keyframes() && learnt.blowStart(first) < 0)
		{
			first++;
		}
		return first;
	}

	/**
	 * When the animation's first blow starts to strike (ticks from the animation's start), for a time before that: while
	 * it winds up, or before; NaN at a time it strikes or has struck, or in a later blow, or without a blow.
	 */
	static double striking(Learning.Learnt learnt, double time, double ease, boolean exaggerate)
	{
		int first = firstBlow(learnt);
		if (first >= learnt.keyframes())
		{
			return Double.NaN;
		}
		double from = learnt.starts[learnt.blowStart(first)];
		if (exaggerate)
		{
			Moment at = moment(learnt, time, ease, false);
			from = at.blow == first ? at.strikeFrom : Double.NaN;
		}
		return time < from ? from : Double.NaN;
	}

	/**
	 * What an attack shows at a time with its keyframes kept, each blow paced to land with force (as the ease power, see
	 * STRIKE_EASE): slower at first, faster towards where it lands, there on time. A blow swung on from where the one
	 * before it lands (the Emberlight special's second) goes as the game has it: paced, it would stop dead and start over.
	 */
	static Moment paced(Learning.Learnt learnt, double time, double ease)
	{
		double[] blow = strike(learnt, time);
		if (blow != null && time < blow[1] && blow[1] > blow[0] && !swungOn(learnt, blow[0]))
		{
			double u = (time - blow[0]) / (blow[1] - blow[0]);
			time = along(learnt, blow[0], blow[1], Math.pow(u, ease));
		}
		return plain(learnt, time);
	}

	/**
	 * The time (ticks from the animation's start) a blow from one time to another is at when it has gone a part (0..1)
	 * of its way: how far the drawn pose moves (see Learnt.travel), each moment counted as moving at least WAY_LEAST of
	 * the blow's mean pace. Even in time without travel.
	 */
	static double along(Learning.Learnt learnt, double from, double land, double part)
	{
		return along(learnt, learnt.travel, from, land, part);
	}

	/** The time between from and land by which a rope's head has gone this part of its way through the world (see Learnt.headWay). */
	static double alongHead(Learning.Learnt learnt, double from, double land, double part)
	{
		float[] way = learnt.headWay;
		double start = headWay(way, from), whole = headWay(way, land) - start;
		if (!(whole > 0))
		{
			return from + part * (land - from);
		}
		double target = start + part * whole, low = from, high = land;
		for (int round = 0; round < 40; round++)
		{
			double middle = (low + high) / 2;
			if (headWay(way, middle) < target)
			{
				low = middle;
			}
			else
			{
				high = middle;
			}
		}
		return high;
	}

	private static double headWay(float[] way, double time)
	{
		double i = Math.max(0, Math.min(way.length - 1, time * Learning.Learnt.HEAD_SAMPLES));
		int below = (int) Math.floor(i);
		int above = Math.min(way.length - 1, below + 1);
		return way[below] + (i - below) * (way[above] - way[below]);
	}

	/** As along, by a travel of its own (see Learnt.bodyTravel). */
	static double along(Learning.Learnt learnt, float[] travel, double from, double land, double part)
	{
		double way = way(learnt, travel, from, land, land);
		if (!(way > 0))
		{
			return from + part * (land - from);
		}
		double target = part * way;
		int r = Learning.Learnt.TRAVEL_SAMPLES;
		int i = (int) Math.floor(from * r);
		int last = (int) Math.ceil(land * r);
		double at = from, wayAt = 0;
		for (; i < last; i++)
		{
			double next = Math.min(land, (i + 1) / (double) r);
			if (next <= at)
			{
				continue;
			}
			double wayNext = way(learnt, travel, from, land, next);
			if (wayNext >= target)
			{
				return wayNext > wayAt ? at + (next - at) * (target - wayAt) / (wayNext - wayAt) : at;
			}
			at = next;
			wayAt = wayNext;
		}
		return land;
	}

	/**
	 * How many ticks of the drawn time blow b's own strike takes, 0: none (see SkillSwings). Where the sound stretches a swing
	 * (see heard) a drawn tick lasts longer, but not in the first swing, shown as the game has it.
	 */
	static double struck(Learning.Learnt learnt, int b, boolean first)
	{
		double[] struck = learnt.motion.struck;
		if (struck == null || struck[b] <= 0)
		{
			return 0;
		}
		int[] heard = learnt.motion.heard;
		if (first && heard != null && b < heard.length && heard[b] >= 0)
		{
			double from = learnt.starts[learnt.blowStart(b)];
			return struck[b] * (learnt.starts[heard[b]] - from) / (learnt.starts[b] - from);
		}
		return struck[b];
	}

	/** How long blow b, from one time to where it lands, is struck in (see SWEEP, SkillSwings): the game's time, or less. */
	static double sweep(Learning.Learnt learnt, double from, double land, int b, boolean first)
	{
		double length = land - from;
		double strike = struck(learnt, b, first);
		if (strike > 0)
		{
			return Math.min(length, strike);
		}
		for (int k = 0; k < b - 1; k++)
		{
			if (learnt.starts[k] >= from && learnt.lengths[k] >= SWEEP * length
				&& pace(learnt, from, land, learnt.starts[k], learnt.starts[k + 1]) < 1)
			{
				return length - learnt.lengths[k];
			}
		}
		return length;
	}

	/**
	 * A rope's blow (the Obligator's ball, see Rules.ropes) along its way: most of it at power ROPE_HAUL of the time (off
	 * quickly from the point it turns round at, then ever faster), the part ROPE_WHIP at power ROPE_WHIP_POWER (the whip
	 * into the hit).
	 */
	static final double ROPE_HAUL = 2, ROPE_WHIP = 0.5, ROPE_WHIP_POWER = 4;
	/** The last part of the time of a rope's head's swing into the blow in which it slows to where it turns round. */
	static final double ROPE_STOP = 0.5;
	/** A rope's head is swung round and flung out for this part of its blow's time at least (see moment): a long wind-up, a short strike. */
	static final double ROPE_WIND_UP = 0.7;
	/**
	 * A rope's head flung out (see Learnt.sweep) goes on past the point it turns round at, this part of the step into it
	 * at most (see Learnt.fling): out over the last ROPE_FLING_OUT of the time it is swung round and flung in, and let
	 * go over the first ROPE_LET_GO of the blow.
	 */
	static final double ROPE_FLING = 0.5, ROPE_FLING_OUT = 0.4, ROPE_LET_GO = 0.6;
	/** WAY_LEAST for a rope's blow. */
	static final double ROPE_WAY_LEAST = 0.05;

	/** A blow starts out of a hold when its first steps move the pose less than this part of the blow's way. */
	static final double HOLD = 0.02;
	/** Ticks such a hold is passed in. */
	static final double HOLD_PASS = 0.25;

	/**
	 * Where a blow from keyframe a (at time from) to a time starts moving: there, or where the hold it starts out of
	 * ends (the Obligator's ball, held stretched for two ticks where it is flung furthest back). The strike goes along
	 * the way from there: a hold waited out at the strike's slow start hangs the weapon in the air.
	 */
	static double moving(Learning.Learnt learnt, int a, double from, double land)
	{
		double[] speed = learnt.motion.speed;
		if (learnt.travel == null || a < 0 || learnt.starts[a] != from)
		{
			return from;
		}
		double moved = travel(learnt.travel, land) - travel(learnt.travel, from), held = 0;
		int k = a;
		while (k < speed.length && k + 1 < learnt.starts.length && learnt.starts[k + 1] < land)
		{
			held += speed[k] * learnt.lengths[k];
			if (held > HOLD * moved)
			{
				break;
			}
			k++;
		}
		return learnt.starts[k];
	}

	/** How much faster than its mean a blow from one time to another goes in its fastest step (see pace). */
	static double fastest(Learning.Learnt learnt, double from, double land)
	{
		double most = 1;
		for (int k = 0; k < learnt.keyframes() - 1; k++)
		{
			double a = Math.max(from, learnt.starts[k]), b = Math.min(land, learnt.starts[k + 1]);
			if (b > a)
			{
				most = Math.max(most, pace(learnt, from, land, a, b));
			}
		}
		return most;
	}

	/** How much faster than its mean a blow from one time to another goes along its way (see along) between a and b. */
	static double pace(Learning.Learnt learnt, double from, double land, double a, double b)
	{
		double way = way(learnt, from, land, land);
		b = Math.min(land, b);
		double part = b > a ? way(learnt, from, land, b) - way(learnt, from, land, a) : 0;
		return way > 0 && part > 0 ? part / (b - a) / (way / (land - from)) : 1;
	}

	/** The way a blow from one time to another has gone by a time in it (see along); 0 without travel. */
	private static double way(Learning.Learnt learnt, double from, double land, double time)
	{
		return way(learnt, learnt.travel, from, land, time);
	}

	private static double way(Learning.Learnt learnt, float[] travel, double from, double land, double time)
	{
		if (travel == null || land <= from)
		{
			return 0;
		}
		double moved = travel(travel, land) - travel(travel, from);
		// a rope's way is its head's alone (see Learnt.moved): where the head holds, only the arms are left to pass
		double least = learnt.spline != null && learnt.spline.roped() ? ROPE_WAY_LEAST : WAY_LEAST;
		return travel(travel, time) - travel(travel, from) + least * moved * (time - from) / (land - from);
	}

	private static double travel(float[] travel, double time)
	{
		double i = Math.max(0, Math.min(travel.length - 1, time * Learning.Learnt.TRAVEL_SAMPLES));
		int below = (int) Math.floor(i);
		int above = Math.min(travel.length - 1, below + 1);
		return travel[below] + (i - below) * (travel[above] - travel[below]);
	}

	/** Whether a blow starting at this time (ticks) swings on from where another one lands. */
	static boolean swungOn(Learning.Learnt learnt, double start)
	{
		for (int b = 1; b < learnt.keyframes(); b++)
		{
			if (learnt.blowStart(b) >= 0 && learnt.starts[b] == start)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * What an attack shows at a time (ticks from its start, as the game has it), its blows exaggerated
	 * (see EXAGGERATED_EASE): wound up further, struck faster and faster, carried on past where they land and springing
	 * back, all in one fluid motion. Ease 1 or below: as the game has it. A blow swung on from where the one before it
	 * lands goes as the game has it (see paced).
	 */
	static Moment moment(Learning.Learnt learnt, double time, double ease)
	{
		return moment(learnt, time, ease, false);
	}

	/** @param first the first swing of a skill's action, not yet started over (see struck) */
	static Moment moment(Learning.Learnt learnt, double time, double ease, boolean first)
	{
		Moment plain = plain(learnt, time);
		if (ease <= 1)
		{
			return plain;
		}
		int n = learnt.keyframes();
		double free = 0;
		for (int b = 1; b < n; b++)
		{
			int a = learnt.blowStart(b);
			if (a < 0)
			{
				continue;
			}
			// where it winds up, worked out when it was learnt (see Learnt.windUp): pushed on along the step before
			// that keyframe or back along the step after it, as far as Learnt.pull
			int wound = learnt.windUp[b], pullKey = learnt.pullStep[b];
			// a swing struck in a time of its own out of a long wait starts from the top as the game holds it, not pulled
			double struckIn = struck(learnt, b, first);
			double whole = learnt.starts[b] - learnt.starts[a];
			boolean holds = struckIn > 0 && whole - struckIn >= SWEEP * whole;
			if (pullKey >= 0 && (holds || learnt.starts[wound] < free))
			{
				pullKey = -1;
			}
			double from = learnt.starts[pullKey >= 0 ? wound : a], land = learnt.starts[b];
			if (land <= from || from < free || swungOn(learnt, learnt.starts[a]))
			{
				continue;
			}
			double length = land - from;
			double pull = pullKey < 0 ? 0 : pullKey == wound ? -learnt.pull[b] : learnt.pull[b];
			double follow = learnt.follow[b];
			double nextFrom = learnt.starts[n - 1];
			for (int c = b + 1; c < n; c++)
			{
				if (learnt.blowStart(c) >= 0 && !swungOn(learnt, learnt.starts[learnt.blowStart(c)]))
				{
					nextFrom = Math.min(nextFrom, learnt.starts[learnt.pullStep[c] >= 0 ? learnt.windUp[c] : learnt.blowStart(c)]);
					break;
				}
			}
			double settle = follow > 0 ? Math.min(SETTLE_TICKS, Math.min(learnt.lengths[b], Math.max(0, nextFrom - land) / 2)) : 0;
			// the wind-up: the furthest point comes as long after the keyframe it turns round at (from) as the pull takes
			// at PULL_BACK_PACE; the game's pose slows into that keyframe over the time before it
			double pullTicks = pullKey < 0 ? 0 : learnt.pull[b] * learnt.lengths[pullKey];
			double windUp = Math.max(WIND_UP_LEAST * length, Math.min(WIND_UP_MOST * length, pullTicks / PULL_BACK_PACE));
			double slowing = Math.min(Math.min(PULL_BACK_EARLY, from - free), windUp * SLOWING), start = from - slowing;
			if (pullKey < 0)
			{
				// without a pull the wind-up is only the slowing into that keyframe: with no time before it to slow into
				// (the animation's start: a skill started over), none, not the pose held still
				windUp = Math.min(windUp, slowing / SLOWING);
			}
			// a slow sweep into a short fast step is struck as one fast motion, wound up until then (see SWEEP)
			windUp = Math.max(windUp, length - sweep(learnt, from, land, b, first));
			if (learnt.sweep[b] >= 0 && pullKey < 0)
			{
				// a rope's head swung round and flung out (see Learnt.sweep): that wind-up takes this part of the blow's
				// time, the strike only the rest, so it lands the harder
				windUp = Math.max(windUp, ROPE_WIND_UP * length);
			}
			if (holds && a > 0)
			{
				// up there it goes on rising a little, along the way it came up (see SkillSwings)
				pullKey = a - 1;
				pull = SkillSwings.LIFT;
			}
			double furthest = from + windUp;
			double rising = furthest - start;
			// the strike: leaving that keyframe at STRIKE_START of the game's pace, at the hit at ease times it, the way in
			// between taken faster and faster along how far the pose moves (see along); leaveWay and hit are those paces
			// along the way
			double strike = land - furthest, leave = Math.min(1, STRIKE_START * strike / length);
			double leaveWay = Math.min(1, leave * pace(learnt, from, land, from, from + 1.0 / Learning.Learnt.TRAVEL_SAMPLES));
			double hit = Math.max(HIT_LEAST, ease * strike / length * pace(learnt, from, land, Math.max(from, learnt.starts[b - 1]), land));
			double power = leaveWay < 1 ? Math.max(1, (hit - leaveWay) / (1 - leaveWay)) : 1;
			free = land + settle;
			int swept = learnt.sweep[b];
			if (swept >= 0 && pullKey < 0 && time >= learnt.starts[swept] && time < furthest)
			{
				// a rope's head swung round and flung out into the blow (see Learnt.sweep): one even way, the hold the
				// game has between the two left out, slowing only into the point it turns round at; the rest goes with
				// it up to the hold and through what little it does from there while the head is flung out
				double began = learnt.starts[swept], holdFrom = learnt.starts[swept + 1], holdTo = learnt.starts[a - 1];
				double u = (time - began) / (furthest - began), even = 1 / (1 - ROPE_STOP / 2);
				double gone = u <= 1 - ROPE_STOP ? even * u : 1 - even * (1 - u) * (1 - u) / (2 * ROPE_STOP);
				// (with the head's way through the world known: arms, rope and head at one moment, evenly along that way,
				// as in the strike; the game's hold moves nothing and is passed at once)
				boolean one = learnt.headWay != null;
				double head = one ? alongHead(learnt, began, from, gone) : along(learnt, learnt.travel, began, from, gone);
				double rest = one || head <= holdFrom ? head : head < holdTo ? holdFrom
					: holdFrom + (head - holdTo) * (from - holdFrom) / (from - holdTo);
				Moment at = plain(learnt, rest);
				if (!one && head > holdFrom)
				{
					Moment own = plain(learnt, head);
					at.headKey = own.key;
					at.headS = own.s;
				}
				at.pullKey = a - 1;
				at.pull = learnt.fling[b] * smooth((u - (1 - ROPE_FLING_OUT)) / ROPE_FLING_OUT);
				at.flung = true;
				at.blow = b;
				at.strikeFrom = furthest;
				return at;
			}
			if (time < start)
			{
				// before this blow (they come in order): as the game has it
				plain.blow = b;
				plain.strikeFrom = furthest;
				return plain;
			}
			if (time > land + settle)
			{
				continue;
			}
			if (time > land)
			{
				// springing back from the follow-through
				plain.followKey = b - 1;
				plain.follow = follow * (1 - smooth((time - land) / settle));
				return plain;
			}
			double shown, out, done = 0, head = Double.NaN;
			if (time < furthest && holds)
			{
				// slowing into the top, as the game holds it, and rising on a little until the strike (see SkillSwings)
				double settled = from + SkillSwings.SETTLE * (furthest - from);
				double left = 1 - Math.min(1, (time - start) / (settled - start));
				shown = from - slowing * left * left;
				out = smooth((time - start) / rising);
			}
			else if (time < furthest)
			{
				// the game's pose slowing into that keyframe (from its own pace to STRIKE_START of it), the weapon pulled
				// on further past it
				double span = furthest - start, u = (time - start) / span, u2 = u * u, u3 = u2 * u;
				double away = span, arrive = leave * length / strike * span;
				double most = 3 * slowing / Math.hypot(away, arrive);
				if (most < 1)
				{
					// never back: no faster than the way left allows
					away *= most;
					arrive *= most;
				}
				shown = start + (u3 - 2 * u2 + u) * away + (3 * u2 - 2 * u3) * slowing + (u3 - u2) * arrive;
				out = rising > 0 ? smooth(1 - (furthest - time) / rising) : 0;
			}
			else
			{
				// the strike: from the furthest point, through the blow, faster and faster along its way, the pull let go
				// and the follow-through taken up on the way
				// out of a hold (see moving) the way starts where the hold ends, the hold itself passed quickly
				double moving = moving(learnt, pullKey >= 0 ? wound : a, from, land);
				double pass = moving > from ? Math.min(HOLD_PASS, strike / 2) : 0;
				if (time < furthest + pass)
				{
					shown = from + (moving - from) * (time - furthest) / pass;
				}
				else
				{
					double w = (time - furthest - pass) / (strike - pass);
					// a rope's head is hauled round, not struck: off at once from where it turns, no hanging there
					done = learnt.spline != null && learnt.spline.roped()
						? (1 - ROPE_WHIP) * Math.pow(w, ROPE_HAUL) + ROPE_WHIP * Math.pow(w, ROPE_WHIP_POWER)
						: leaveWay * w + (1 - leaveWay) * Math.pow(w, power);
					shown = along(learnt, moving, land, done);
					if (learnt.headWay != null)
					{
						// a rope's head evenly along its way through the world, the arms and the rope at that same moment:
						// on two clocks the head's speed dipped where the arms' way and its own turned apart
						shown = alongHead(learnt, moving, land, done);
					}
					else if (learnt.bodyTravel != null)
					{
						// a rope's head goes its way and the arms and rope theirs, each evenly: the arms pull, the head comes
						head = shown;
						shown = along(learnt, learnt.bodyTravel, moving, land, done);
					}
				}
				double letGo = Math.min(strike, rising);
				out = letGo > 0 ? 1 - smooth((time - furthest) / letGo) : 0;
			}
			Moment at = plain(learnt, shown);
			if (!Double.isNaN(head))
			{
				Moment own = plain(learnt, head);
				at.headKey = own.key;
				at.headS = own.s;
			}
			at.pullKey = pullKey;
			at.pull = pull * out;
			if (swept >= 0 && pullKey < 0 && time >= furthest)
			{
				// a rope's head let go from where it was flung out to
				at.pullKey = a - 1;
				at.pull = learnt.fling[b] * (1 - smooth((time - furthest) / (ROPE_LET_GO * strike)));
				at.flung = true;
			}
			at.grip = holds;
			at.followKey = b - 1;
			at.follow = follow * done;
			at.blow = b;
			at.strikeFrom = furthest;
			return at;
		}
		return plain;
	}

	/** 0 to 1 at rest at both ends. */
	private static double smooth(double u)
	{
		u = Math.max(0, Math.min(1, u));
		return u * u * (3 - 2 * u);
	}

	// ---- weapon ghosts ----

	/** Where the weapon's vertices were drawn at a moment of drawn time (ticks from the animation's start). */
	private static final class WeaponFrame
	{
		final double time;
		final float[] positions;

		WeaponFrame(double time, float[] positions)
		{
			this.time = time;
			this.positions = positions;
		}
	}

	/**
	 * Draws the ghosts of the local player's weapon with the weapon's own model (see WeaponRun), posed where the weapon
	 * was, in the colours the player's model has now; the player's model itself is left as it is. A blow's trail that
	 * landed keeps fading out while the next blow's grows.
	 */
	private void ghosts(DrawCallbacks next, Projection projection, Scene scene, GameObject object, Model model,
		int orientation, int x, int y, int z, int animationId, boolean skill)
	{
		KeyframeSmoothingPlugin.WeaponRun run = plugin.weaponRun(model, animationId);
		Learning.Learnt learnt = drawnLearnt;
		double drawn = drawnTime;
		boolean blows = run != null && learnt != null && !Double.isNaN(drawn) && animationId >= 0;
		double[] strike = blows ? strike(learnt, drawn) : null;
		long now = clock.getAsLong();
		if (trail != null && (strike == null || trail.run != run || trail.animationId != animationId
			|| trail.hit != strike[1]
			|| !trail.frames.isEmpty() && Math.min(drawn, strike[1]) < trail.frames.peekFirst().time - RESTART_TICKS))
		{
			// another blow, weapon or animation, or the attack started over
			if (trail.hitNanos != 0)
			{
				fading.add(trail);
			}
			trail = null;
		}
		fading.removeIf(old -> old.run != run || old.fade(now) <= 0);
		if (strike != null)
		{
			if (trail == null)
			{
				trail = new Trail(run, animationId, strike[1], skill ? SKILL_TRAIL_NANOS : GHOST_TRAIL_NANOS);
			}
			double anchor = Math.min(drawn, strike[1]);
			WeaponFrame newest = trail.frames.peekFirst();
			boolean fast = !skill || striking(drawn, now);
			if (!fast && !trail.frames.isEmpty() && trail.hitNanos == 0)
			{
				// the strike is over (a skill's tool stays on what it hit): the trail fades from here, not when the keyframe it lands in comes
				trail.hitNanos = now;
			}
			// a skill's tool trails only through its strike, where the drawing runs ahead of the game, not its wait or lift
			if ((newest == null || anchor > newest.time) && fast)
			{
				float[] vx = model.getVerticesX(), vy = model.getVerticesY(), vz = model.getVerticesZ();
				int[] vertices = run.vertices;
				float[] current = new float[3 * vertices.length];
				for (int i = 0; i < vertices.length; i++)
				{
					current[3 * i] = vx[vertices[i]];
					current[3 * i + 1] = vy[vertices[i]];
					current[3 * i + 2] = vz[vertices[i]];
				}
				trail.frames.addFirst(new WeaponFrame(anchor, current));
				// frames further back than the trail reaches are let go
				double reach = trail.reach / (double) KeyframeSmoothingPlugin.TICK_NANOS + 1;
				while (trail.frames.size() > 2 && trail.frames.peekLast().time < anchor - reach)
				{
					trail.frames.removeLast();
				}
			}
			if (drawn >= strike[1] && trail.hitNanos == 0)
			{
				trail.hitNanos = now;
			}
		}
		if (!plugin.ghosts() || run == null || run.ghost == null || trail == null && fading.isEmpty())
		{
			return;
		}
		int[] c1 = model.getFaceColors1(), c2 = model.getFaceColors2();
		Model ghost = run.ghost;
		int[] g1 = ghost.getFaceColors1(), g2 = ghost.getFaceColors2(), g3 = ghost.getFaceColors3();
		if (c1 == null || c2 == null || model.getFaceColors3() == null || g1 == null || g2 == null || g3 == null
			|| ghost.getFaceTransparencies() == null)
		{
			return;
		}
		// the weapon as the player's model shows it now: its lighting, its recolours
		System.arraycopy(c1, run.start, g1, 0, run.count);
		System.arraycopy(c2, run.start, g2, 0, run.count);
		// but a face the weapon's own model shows plain where the player's has a texture: in that model's own colours
		run.own(g1, 1);
		run.own(g2, 2);
		for (Trail old : fading)
		{
			drawTrail(old, now, next, projection, scene, object, model, orientation, x, y, z);
		}
		if (trail != null)
		{
			drawTrail(trail, now, next, projection, scene, object, model, orientation, x, y, z);
		}
	}

	/** How much faster than the game's own pace the drawing must run for a skill's strike (ticks of drawn time per tick). */
	static final double SKILL_STRIKE_SPEED = 1.5;
	private double speedDrawn = Double.NaN;
	private long speedNanos;
	private boolean striking;

	/** Whether the drawn time is running at a strike's speed now (the last answer when no time has passed since the last frame). */
	private boolean striking(double drawn, long now)
	{
		double ticks = (now - speedNanos) / (double) KeyframeSmoothingPlugin.TICK_NANOS;
		if (Double.isNaN(speedDrawn) || ticks < 0 || ticks > 2)
		{
			striking = false;
		}
		else if (ticks > 1e-4)
		{
			striking = (drawn - speedDrawn) / ticks > SKILL_STRIKE_SPEED;
		}
		else
		{
			return striking;
		}
		speedDrawn = drawn;
		speedNanos = now;
		return striking;
	}

	/** Draws one trail's copies, oldest first so the newer ones lie on top. */
	private void drawTrail(Trail trail, long now, DrawCallbacks next,
		Projection projection, Scene scene, GameObject object, Model model, int orientation, int x, int y, int z)
	{
		double fade = trail.fade(now);
		WeaponFrame[] frames = trail.frames.toArray(NO_FRAMES);
		if (fade <= 0 || frames.length < 2)
		{
			return;
		}
		// copies GHOST_STEP ticks apart back from the newest frame, as far as the trail and the frames go
		WeaponFrame newest = frames[0];
		int count = (int) Math.round(trail.reach / (GHOST_STEP * KeyframeSmoothingPlugin.TICK_NANOS));
		int copies = Math.min(count, (int) ((newest.time - frames[frames.length - 1].time) / GHOST_STEP + 1e-9));
		if (copies == 0)
		{
			return;
		}
		KeyframeSmoothingPlugin.WeaponRun run = trail.run;
		double time = newest.time - copies * GHOST_STEP;
		int j = frameAt(frames, frames.length - 1, time);
		float w = towardNewer(frames, j, time);
		float[] older = frames[j].positions, newer = frames[Math.max(0, j - 1)].positions;
		if (oldest.length < older.length)
		{
			oldest = new float[older.length];
		}
		for (int i = 0; i < older.length; i++)
		{
			oldest[i] = older[i] + (newer[i] - older[i]) * w;
		}
		float[] weight = weights(run, newest.positions, oldest);
		if (weight == null)
		{
			return;
		}
		int[] c3 = model.getFaceColors3();
		byte[] own = model.getFaceTransparencies();
		Model ghost = run.ghost;
		int[] g3 = ghost.getFaceColors3();
		byte[] alpha = ghost.getFaceTransparencies();
		float[] gx = ghost.getVerticesX(), gy = ghost.getVerticesY(), gz = ghost.getVerticesZ();
		int[] slots = run.ghostSlots;
		for (int copy = copies; copy >= 1; copy--)
		{
			// where the weapon was, straight between the two frames around that moment, put into the ghost model
			time = newest.time - copy * GHOST_STEP;
			j = frameAt(frames, j, time);
			w = towardNewer(frames, j, time);
			older = frames[j].positions;
			newer = frames[Math.max(0, j - 1)].positions;
			for (int v = 0; v < slots.length; v++)
			{
				int i = 3 * slots[v];
				if (i >= 0)
				{
					gx[v] = older[i] + (newer[i] - older[i]) * w;
					gy[v] = older[i + 1] + (newer[i + 1] - older[i + 1]) * w;
					gz[v] = older[i + 2] + (newer[i + 2] - older[i + 2]) * w;
				}
			}
			double opacity = 1 - Math.pow(1 - GHOST_STRENGTH * fade * (1 - copy / (double) (count + 1)),
				GHOST_STEP / GHOST_SOLID);
			int showing = 0;
			for (int f = 0; f < run.count; f++)
			{
				int face = run.start + f;
				double solid = own == null ? 1 : 1 - (own[face] & 0xFF) / 255.0;
				double shown = solid * opacity * weight[f];
				g3[f] = shown <= GHOST_LEAST ? -2 : c3[face];
				alpha[f] = (byte) Math.min(254, Math.round(255 * (1 - shown)));
				showing += g3[f] != -2 ? 1 : 0;
			}
			run.own(g3, 3);
			if (showing > 0)
			{
				ghost.translate(0, 0, 0);
				ghost.calculateBoundsCylinder();
				next.drawTemp(projection, scene, object, ghost, orientation, x, y, z);
			}
		}
	}

	private static final WeaponFrame[] NO_FRAMES = new WeaponFrame[0];
	/** The oldest copy's weapon vertices (x, y, z, ...), for the weights; reused. */
	private float[] oldest = new float[0];

	/** The frame (newest first) at or before a drawn time, looking on from frame from towards the newer ones. */
	private static int frameAt(WeaponFrame[] frames, int from, double time)
	{
		int j = from;
		while (j > 0 && frames[j - 1].time <= time)
		{
			j--;
		}
		return j;
	}

	/** How far a drawn time is from frame j towards the next newer frame (0..1). */
	private static float towardNewer(WeaponFrame[] frames, int j, double time)
	{
		return j == 0 ? 0 : (float) ((time - frames[j].time) / (frames[j - 1].time - frames[j].time));
	}

	/** One blow's trail: where the weapon was drawn, by drawn time, newest first. */
	private static final class Trail
	{
		final KeyframeSmoothingPlugin.WeaponRun run;
		final int animationId;
		/** Where the blow lands (ticks of drawn time). */
		final double hit;
		final Deque<WeaponFrame> frames = new ArrayDeque<>();
		/** When the drawn time reached where the blow lands (nanos), or 0 before. */
		long hitNanos;

		/** How far back the trail reaches (nanos). */
		final long reach;

		Trail(KeyframeSmoothingPlugin.WeaponRun run, int animationId, double hit, long reach)
		{
			this.reach = reach;
			this.run = run;
			this.animationId = animationId;
			this.hit = hit;
		}

		/** How much of the trail is left: all of it until the blow lands, then less until it is gone. */
		double fade(long now)
		{
			return hitNanos == 0 ? 1 : 1 - (now - hitNanos) / (double) GHOST_FADE_NANOS;
		}
	}

	/**
	 * The blow a drawn time is in or last passed: {start, where it lands} (ticks from the animation's start); null
	 * before the first. After it lands its trail fades out (in real time, see ghosts).
	 */
	static double[] strike(Learning.Learnt learnt, double time)
	{
		double[] found = null;
		for (int k = 1; k < learnt.keyframes(); k++)
		{
			int start = learnt.blowStart(k);
			if (start >= 0 && time >= learnt.starts[start])
			{
				found = new double[]{learnt.starts[start], learnt.starts[k]};
			}
		}
		return found;
	}

	/**
	 * Per weapon face (from the run's start), how solid its ghosts are: by how far it moves over the trail against the
	 * weapon's fastest face; null when the weapon hardly moves.
	 */
	private static float[] weights(KeyframeSmoothingPlugin.WeaponRun run, float[] newest, float[] oldest)
	{
		double[] moved = new double[run.count];
		double most = 0;
		for (int f = 0; f < run.count; f++)
		{
			for (int corner = 0; corner < 3; corner++)
			{
				int i = 3 * run.corners[3 * f + corner];
				double dx = newest[i] - oldest[i], dy = newest[i + 1] - oldest[i + 1], dz = newest[i + 2] - oldest[i + 2];
				moved[f] = Math.max(moved[f], Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
			most = Math.max(most, moved[f]);
		}
		if (most < 1)
		{
			return null;
		}
		float[] weight = new float[run.count];
		for (int f = 0; f < run.count; f++)
		{
			double w = Math.max(0, Math.min(1, (moved[f] / most - GHOST_GRIP) / (GHOST_FULL - GHOST_GRIP)));
			weight[f] = (float) (w * w * (3 - 2 * w));
		}
		return weight;
	}

	// ---- saving and putting back ----

	private void save(Model model)
	{
		int count = model.getVerticesCount();
		if (savedX.length < count)
		{
			savedX = new float[count];
			savedY = new float[count];
			savedZ = new float[count];
		}
		System.arraycopy(model.getVerticesX(), 0, savedX, 0, count);
		System.arraycopy(model.getVerticesY(), 0, savedY, 0, count);
		System.arraycopy(model.getVerticesZ(), 0, savedZ, 0, count);
	}

	private void restoreVertices(Model model)
	{
		int count = model.getVerticesCount();
		System.arraycopy(savedX, 0, model.getVerticesX(), 0, count);
		System.arraycopy(savedY, 0, model.getVerticesY(), 0, count);
		System.arraycopy(savedZ, 0, model.getVerticesZ(), 0, count);
		model.translate(0, 0, 0);
		model.calculateBoundsCylinder();
	}

}
