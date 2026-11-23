package com.keyframesmoothing;

import java.util.Arrays;

/**
 * What an animation does, learnt from its keyframe poses (vertex positions of the model that played it): how fast each
 * keyframe step moves, where its blows are and where they hit, and which steps the client blends a strange way round.
 * No game data needed, so new weapons and NPCs work the first time they are learnt.
 */
final class MotionAnalysis
{
	/** Below this (model units per tick) a step counts as standing still. */
	static final double STILL = 0.4;

	private MotionAnalysis()
	{
	}

	/** The result for one animation. Steps are numbered by the keyframe they start from; the last keyframe has none. */
	static final class Motion
	{
		/** How far the model moves in each step, per tick of its original length (model units). */
		final double[] speed;
		/** Keyframes that are a hit: one of the animation's fastest moves ends in them and the motion slows there. */
		final boolean[] hit;
		/** Per keyframe a blow ends in (a hit, or a slower strike), the keyframe it starts from (else -1). */
		final int[] blowStart;
		/** Steps whose blend takes a part a strange way round (a flip, a turn the wrong way): not to be blended. */
		final boolean[] glitch;
		/** Its blows stop dead where they land, nothing carrying on past it (a hammer on the anvil, see SkillSwings). */
		final boolean stops;
		/** Per keyframe a skill's swing lands in, the later keyframe its sound comes at (else -1); null: none (see SkillSwings). */
		final int[] heard;
		/** Per keyframe a skill's swing lands in, how many ticks of the drawn time its strike takes (else 0); null: none (see SkillSwings). */
		final double[] struck;

		Motion(double[] speed, boolean[] hit, int[] blowStart, boolean[] glitch)
		{
			this(speed, hit, blowStart, glitch, null, null, false);
		}

		Motion(double[] speed, boolean[] hit, int[] blowStart, boolean[] glitch, int[] heard, double[] struck, boolean stops)
		{
			this.speed = speed;
			this.hit = hit;
			this.blowStart = blowStart;
			this.glitch = glitch;
			this.stops = stops;
			this.heard = heard;
			this.struck = struck;
		}
	}

	/**
	 * @param poses   per keyframe, the sampled vertices as x, y, z, x, y, z...
	 * @param mids    per keyframe step, the same vertices as the client blended them part of the way (null: not seen)
	 * @param midAt   how far along each of those blends was taken (0..1)
	 * @param lengths the keyframes' original lengths in ticks
	 */
	static Motion analyze(float[][] poses, float[][] mids, double[] midAt, int[] lengths)
	{
		int n = lengths.length;
		int steps = n - 1;
		int samples = poses[0].length / 3;
		double[] speed = new double[Math.max(0, steps)];
		float[][] moves = new float[Math.max(0, steps)][];
		for (int i = 0; i < steps; i++)
		{
			float[] a = poses[i], b = poses[i + 1];
			float[] move = new float[a.length];
			double[] lengthsOfMoves = new double[samples];
			for (int v = 0; v < samples; v++)
			{
				float dx = b[3 * v] - a[3 * v], dy = b[3 * v + 1] - a[3 * v + 1], dz = b[3 * v + 2] - a[3 * v + 2];
				move[3 * v] = dx;
				move[3 * v + 1] = dy;
				move[3 * v + 2] = dz;
				lengthsOfMoves[v] = Math.sqrt(dx * dx + dy * dy + dz * dz);
			}
			moves[i] = move;
			Arrays.sort(lengthsOfMoves);
			// the parts that move most (a weapon, an arm) count, not the average of the whole body
			speed[i] = lengthsOfMoves[(int) (0.9 * (samples - 1))] / Math.max(1, lengths[i]);
		}

		boolean[] hit = new boolean[n];
		double[] rise = new double[Math.max(0, steps)];
		// per step, whether it goes on the way the step before went (1) or turns back (-1)
		double[] onward = new double[Math.max(0, steps)];
		double[] ahead = new double[Math.max(0, steps)];
		for (int i = 0; i < steps; i++)
		{
			rise[i] = rise(moves[i]);
			ahead[i] = ahead(moves[i], poses[i + 1]);
			onward[i] = i == 0 ? -1 : cosine(moves[i - 1], moves[i]);
		}
		int[] blowStart = new int[n];
		Arrays.fill(blowStart, -1);
		double top = Double.MAX_VALUE, bottom = -Double.MAX_VALUE;
		for (int v = 1; v < poses[0].length; v += 3)
		{
			top = Math.min(top, poses[0][v]);
			bottom = Math.max(bottom, poses[0][v]);
		}
		// how far each keyframe is from the starting pose, measured as speed is: the parts that move most
		double[] fromStart = new double[n];
		for (int k = 0; k < n; k++)
		{
			fromStart[k] = apart(poses[k], poses[0]);
		}
		strikes(speed, rise, onward, ahead, lengths, fromStart, (a, b) -> apart(poses[a], poses[b]),
			(a, b) -> reach(poses, a, b), Math.max(1, bottom - top), hit, blowStart);
		return new Motion(speed, hit, blowStart, glitches(poses, mids, midAt));
	}

	/** A strike is at least this part of the fastest strike. */
	static final double STRIKE = 0.7;
	/** A blow is at least this part of the fastest strike, and (if no hit) moves at least BLOW_SPEED heights a tick. */
	static final double BLOW = 0.4;
	static final double BLOW_SPEED = 0.15;
	/** Steps around a strike's fastest step that are at least this part of its speed belong to the strike. */
	static final double STRIKE_RUN = 0.5;
	/** After the hit the motion must drop below this part of the strike's speed (or the animation ends there). */
	static final double STRIKE_STOP = 0.6;
	/** A move whose fastest parts get this far (in heights) further in front of the body than they were reaches out. */
	static final double REACH = 0.1;
	/** Moves out of the stance and back into it this close in speed mirror each other (see strikes). */
	static final double MIRROR = 1.25;
	/** A pose at most this part of the farthest pose from the starting one is nearly back in the stance. */
	static final double SETTLED = 0.25;
	/** A strike whose last step moves the weapon up this much (see rise) is a lift: a wind-up, or a blow upwards. */
	static final double RAISE = 0.5;

	/**
	 * How much a step moves the parts that move most (the weapon) upwards: 1 straight up, -1 straight down, 0 sideways.
	 * Model y points down.
	 */
	static double rise(float[] move)
	{
		int n = move.length / 3;
		double most = 0;
		for (int v = 0; v < n; v++)
		{
			most = Math.max(most, Math.sqrt(move[3 * v] * move[3 * v] + move[3 * v + 1] * move[3 * v + 1] + move[3 * v + 2] * move[3 * v + 2]));
		}
		double up = 0, total = 0;
		for (int v = 0; v < n; v++)
		{
			double length = Math.sqrt(move[3 * v] * move[3 * v] + move[3 * v + 1] * move[3 * v + 1] + move[3 * v + 2] * move[3 * v + 2]);
			if (length >= 0.5 * most && length > 0)
			{
				up += -move[3 * v + 1];
				total += length;
			}
		}
		return total > 0 ? up / total : 0;
	}

	/**
	 * From one keyframe to a later one, how much further in front of the body the parts that move most between them get
	 * than they were at the first (model z points backwards): 0 when they only come back towards the body.
	 */
	static double reach(float[][] poses, int from, int to)
	{
		int samples = poses[from].length / 3;
		double[] travel = new double[samples];
		for (int v = 0; v < samples; v++)
		{
			for (int k = from; k < to; k++)
			{
				float dx = poses[k + 1][3 * v] - poses[k][3 * v], dy = poses[k + 1][3 * v + 1] - poses[k][3 * v + 1];
				float dz = poses[k + 1][3 * v + 2] - poses[k][3 * v + 2];
				travel[v] += Math.sqrt(dx * dx + dy * dy + dz * dz);
			}
		}
		double[] sorted = travel.clone();
		Arrays.sort(sorted);
		double least = sorted[(int) (0.9 * (samples - 1))];
		double most = 0;
		for (int k = from + 1; k <= to; k++)
		{
			double front = 0;
			int count = 0;
			for (int v = 0; v < samples; v++)
			{
				if (travel[v] >= least)
				{
					front += poses[from][3 * v + 2] - poses[k][3 * v + 2];
					count++;
				}
			}
			most = Math.max(most, count > 0 ? front / count : 0);
		}
		return most;
	}

	/**
	 * The strikes: the fastest moves of the animation, each a fastest step with the steps around it nearly as fast, that
	 * land: brake hard, or slow down where they end. The others are no blow:
	 * <ul>
	 * <li>a move landing in the last keyframe (or nearly back in the starting pose, with only slow steps after it and not
	 * reaching out on the way: REACH), when it brings the pose back towards the starting pose and is not the strongest:
	 * the way back into the stance (a kick that stays out is a blow);</li>
	 * <li>a move going up as it ends (raising a sword) when a blow follows within WINDUP_GAP ticks: its wind-up (without
	 * one it is a blow upwards, a claw's uppercut);</li>
	 * <li>the first move out of the starting pose (no fast move before it) when a faster blow comes later, or one nearly
	 * as fast (STRIKE) that goes further while this one ends behind the body: getting ready for it (a halberd drawn back
	 * before its swipe, Dharok's axe swung down behind him before it comes through);</li>
	 * <li>the weapon coming back up within RECOIL_GAP ticks after a stronger blow down;</li>
	 * <li>the first move out of the stance and the last back into it, when they are as fast as each other (MIRROR) and
	 * the way back goes through where the way out went to: the same move backwards.</li>
	 * </ul>
	 * Strikes at least STRIKE of the strongest end in a hit. Blows: the hits and the slower strikes, down to BLOW of the
	 * strongest, that are still fast themselves (BLOW_SPEED): a claw's later swipes, not the recoil after a heavy blow
	 * or the swing before it; afterimages follow them.
	 *
	 * @param rise        per step, how much it moves the weapon up (see rise)
	 * @param onward      per step, how much it goes on the way the step before went: 1 on, -1 back
	 * @param ahead       per step, how far in front of the body it leaves the parts moving most (see ahead)
	 * @param reach       from one keyframe to a later one, how much further in front the parts moving most get (see reach)
	 * @param lengths     the keyframes' lengths in ticks
	 * @param fromStart   per keyframe, how far its pose is from the starting pose
	 * @param apart       how far two keyframes' poses are from each other
	 * @param height      the model's height
	 * @param hit         out: per keyframe, whether a strike ends in it
	 * @param blowStart   out: per keyframe a blow ends in, the keyframe it starts from
	 */
	static void strikes(double[] speed, double[] rise, double[] onward, double[] ahead, int[] lengths, double[] fromStart,
		Apart apart, Apart reach, double height, boolean[] hit, int[] blowStart)
	{
		int steps = speed.length;
		double fastest = Arrays.stream(speed).max().orElse(0);
		if (fastest < 4 * STILL)
		{
			return;
		}
		int[] owner = new int[steps];
		Arrays.fill(owner, -1);
		java.util.List<Run> runs = new java.util.ArrayList<>();
		addRuns(speed, rise, STRIKE_RUN * fastest, false, owner, runs);
		addRuns(speed, rise, BLOW * fastest, true, owner, runs);
		for (Run run : runs)
		{
			run.landing = landing(speed, rise, run);
			// judged on the steps up to where it lands: a blow that throws the weapon back up is no lift
			run.lift = rise[run.landing > 0 ? run.landing - 1 : run.last] >= RAISE;
		}
		for (Run run : runs)
		{
			run.none = run.landing < 0 || runs.stream().noneMatch(other -> other.first < run.first)
				&& runs.stream().anyMatch(blow -> blow.landing > 0 && blow.first > run.last && (blow.speed > run.speed
				// or nearly as fast and longer, when this one swings back behind the body (Dharok's axe before its swing)
				|| ahead[run.landing - 1] < 0 && blow.speed >= STRIKE * run.speed
				&& travel(speed, lengths, runs, owner, blow) > travel(speed, lengths, runs, owner, run)));
			if (run.landing < 0)
			{
				// a move that doesn't land flows on: its steps are free for the blow after it to begin in (a swing
				// coming over the top into its slam)
				for (int j = run.first; j <= run.last; j++)
				{
					owner[j] = -1;
				}
			}
		}
		double farthest = Arrays.stream(fromStart).max().orElse(0);
		double fastestLanding = runs.stream().filter(run -> !run.none).mapToDouble(run -> run.speed).max().orElse(0);
		for (Run run : runs)
		{
			// or only slow steps after it, landing nearly back in the starting pose without reaching out on the way: they
			// settle into the stance (a halberd's swipe), not a second stab that stops short of it or a swing that comes
			// through in front and up into the stance (Dharok's axe)
			// (from where its motion began: the swing it flows on from, see landing)
			int from = run.first;
			while (from > 0 && owner[from - 1] < 0 && speed[from - 1] >= BLOW_ONSET * run.speed)
			{
				from--;
			}
			boolean last = run.landing == steps || run.landing > 0 && fromStart[run.landing] < SETTLED * farthest
				&& runs.stream().noneMatch(other -> other.first >= run.landing)
				&& reach.between(from, run.landing) < REACH * height;
			run.none |= last && run.speed < fastestLanding && fromStart[run.landing] < fromStart[run.first];
		}
		// out of the stance and back into it at the same pace (a spear lowered, poked forward and put back up): neither is
		// the blow, though each is as fast as the other
		Run out = null, back = null;
		for (Run run : runs)
		{
			if (run.landing > 0)
			{
				out = out == null || run.first < out.first ? run : out;
				back = back == null || run.landing > back.landing ? run : back;
			}
		}
		if (out != null && back != out && fromStart[back.landing] < SETTLED * farthest
			&& Math.max(out.speed, back.speed) <= MIRROR * Math.min(out.speed, back.speed))
		{
			int backLanding = back.landing;
			// the way back passes where the way out went to: the same move backwards (a blow lands elsewhere); and in
			// between the pose goes elsewhere, the poke: else the way out was the blow, held and pulled back (a lunge)
			boolean retraced = false, between = false;
			for (int k = back.first; k < backLanding && !retraced; k++)
			{
				retraced = apart.between(out.landing, k) < SETTLED * farthest;
			}
			for (int k = out.landing + 1; k < back.first && !between; k++)
			{
				between = apart.between(out.landing, k) >= SETTLED * farthest;
			}
			if (retraced && between && (backLanding == steps || runs.stream().noneMatch(other -> other.first >= backLanding)))
			{
				out.none = true;
				back.none = true;
			}
		}
		java.util.List<Run> strikes = new java.util.ArrayList<>();
		for (Run run : runs)
		{
			boolean windUp = run.lift && runs.stream().anyMatch(blow -> !blow.none && !blow.lift && blow.first > run.last
				&& blow.speed >= BLOW * run.speed && ticks(lengths, run.last + 1, blow.first) <= WINDUP_GAP);
			if (!run.none && !windUp)
			{
				strikes.add(run);
			}
		}
		double strongest = strikes.stream().mapToDouble(run -> run.speed).max().orElse(0);
		// the weapon coming back up right after a stronger blow down
		strikes.removeIf(run -> strikes.stream().anyMatch(blow -> blow.speed > run.speed && blow.landing <= run.first
			&& ticks(lengths, blow.landing, run.first) <= RECOIL_GAP && rise[blow.landing - 1] <= -RAISE_START
			&& rise[run.landing - 1] >= BOUNCE));
		for (Run run : strikes)
		{
			boolean strike = !run.slower && run.speed >= STRIKE * strongest;
			if (!strike && (run.speed < BLOW * strongest || run.speed < BLOW_SPEED * height))
			{
				continue;
			}
			int from = run.first;
			boolean down = !run.lift && rise[run.landing - 1] < -RAISE_START;
			// a blow downwards starts where the weapon stops going up: the swing up before it is the wind-up
			while (down && from < run.landing - 1 && rise[from] >= RAISE_START)
			{
				from++;
			}
			if (from == run.first)
			{
				// else from where its motion began: the steps before it going the same way, speeding up into it (a
				// slash from the top of its wind-up), not across a turn back
				while (from > 0 && owner[from - 1] < 0 && onward[from] >= 0 && speed[from - 1] >= BLOW_ONSET * run.speed
					&& speed[from - 1] <= ONSET_DIP * speed[from] && !(down && rise[from - 1] >= RAISE_START))
				{
					from--;
				}
			}
			hit[run.landing] |= strike;
			blowStart[run.landing] = blowStart[run.landing] < 0 ? from : Math.min(from, blowStart[run.landing]);
		}
		flowOn(speed, hit, blowStart);
	}

	/**
	 * A blow the motion doesn't come to rest after (below REST of its speed) before the next one starts is no hit: one
	 * swing flows on through it into the next (the Emberlight's special swings three times and hits once, at the end).
	 */
	static final double REST = 0.25;

	/** Joins blows that flow on into the next (see REST): one blow from where the first starts to where the last lands. */
	static void flowOn(double[] speed, boolean[] hit, int[] blowStart)
	{
		int last = -1;
		for (int k = 1; k < blowStart.length; k++)
		{
			if (blowStart[k] < 0)
			{
				continue;
			}
			if (last >= 0 && blowStart[k] >= last && !rests(speed, blowStart[last], last, blowStart[k]))
			{
				blowStart[k] = Math.min(blowStart[k], blowStart[last]);
				hit[k] |= hit[last];
				blowStart[last] = -1;
				hit[last] = false;
			}
			last = k;
		}
	}

	/** Whether the motion comes to rest between a blow (from its start to where it lands) and the step it ends before. */
	private static boolean rests(double[] speed, int from, int landing, int until)
	{
		double top = 0;
		for (int j = from; j < landing; j++)
		{
			top = Math.max(top, speed[j]);
		}
		for (int j = landing; j < until; j++)
		{
			if (speed[j] < REST * top)
			{
				return true;
			}
		}
		return false;
	}

	/** A run of fast steps around a peak (see strikes). */
	private static final class Run
	{
		final int first, last, peak;
		final double speed;
		/** Found among the slower runs: a blow at most, no hit. */
		final boolean slower;
		/** The keyframe it lands in, or -1. */
		int landing = -1;
		/** Its last step before it lands moves the weapon up (see RAISE). */
		boolean lift;
		/** No blow, whatever follows: it doesn't land, it gets ready out of the starting pose, or it goes back at the end. */
		boolean none;

		Run(int first, int last, int peak, double speed, boolean slower)
		{
			this.first = first;
			this.last = last;
			this.peak = peak;
			this.speed = speed;
			this.slower = slower;
		}
	}

	/** Adds every run around a peak at least this fast among the steps no run has yet (owner: the run's number). */
	private static void addRuns(double[] speed, double[] rise, double least, boolean slower, int[] owner, java.util.List<Run> runs)
	{
		int steps = speed.length;
		for (int p = 0; p < steps; p++)
		{
			boolean peak = speed[p] >= least && (p == 0 || speed[p - 1] <= speed[p])
				&& (p == steps - 1 || speed[p + 1] < speed[p]);
			if (!peak || owner[p] >= 0)
			{
				continue;
			}
			int first = p, last = p;
			while (first > 0 && owner[first - 1] < 0 && speed[first - 1] >= STRIKE_RUN * speed[p])
			{
				first--;
			}
			while (last < steps - 1 && owner[last + 1] < 0 && speed[last + 1] >= STRIKE_RUN * speed[p] && speed[last + 1] <= speed[p])
			{
				last++;
			}
			for (int j = first; j <= last; j++)
			{
				owner[j] = runs.size();
			}
			runs.add(new Run(first, last, p, speed[p], slower));
		}
	}

	/** Where a run lands: where it brakes hardest, if it brakes hard; else where it ends and slows (or the animation ends). */
	private static int landing(double[] speed, double[] rise, Run run)
	{
		int steps = speed.length;
		int landing = -1;
		double hardest = BRAKE * run.speed;
		for (int j = run.peak; j <= run.last; j++)
		{
			double brake = speed[j] - (j + 1 < steps ? speed[j + 1] : 0);
			// a blow stops the weapon or throws it back up; a dip between two fast steps is none
			boolean stops = j + 1 == steps || speed[j + 1] < STRIKE_STOP * run.speed || rise[j + 1] >= BOUNCE && rise[j] < BOUNCE;
			if (brake >= hardest && stops)
			{
				hardest = brake;
				landing = j + 1;
			}
		}
		if (landing < 0 && (run.last == steps - 1 || speed[run.last + 1] < STRIKE_STOP * run.speed))
		{
			landing = run.last + 1;
		}
		return landing;
	}

	/** How far two keyframes' poses are from each other (see apart). */
	interface Apart
	{
		double between(int a, int b);
	}

	/** How far two poses are from each other, measured as speed is: by the parts that move most (a weapon, an arm). */
	static double apart(float[] a, float[] b)
	{
		int samples = a.length / 3;
		double[] away = new double[samples];
		for (int v = 0; v < samples; v++)
		{
			float dx = a[3 * v] - b[3 * v], dy = a[3 * v + 1] - b[3 * v + 1], dz = a[3 * v + 2] - b[3 * v + 2];
			away[v] = Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
		Arrays.sort(away);
		return away[(int) (0.9 * (samples - 1))];
	}

	/**
	 * How far a run moves the parts that move most, over all its steps and the swing it flows on from (fast steps of no
	 * run, or of a run that doesn't land: Dharok's axe coming through in front before its last step up).
	 */
	private static double travel(double[] speed, int[] lengths, java.util.List<Run> runs, int[] owner, Run run)
	{
		int from = run.first;
		while (from > 0 && (owner[from - 1] < 0 || runs.get(owner[from - 1]).landing < 0)
			&& speed[from - 1] >= BLOW_ONSET * run.speed)
		{
			from--;
		}
		double travel = 0;
		for (int j = from; j <= run.last; j++)
		{
			travel += speed[j] * Math.max(1, lengths[j]);
		}
		return travel;
	}

	/**
	 * How far in front of the body a step leaves the parts that move most (the weapon): the mean position, along the way
	 * the model faces (model z points backwards), of the tenth of the vertices that move furthest. Negative: behind.
	 */
	static double ahead(float[] move, float[] end)
	{
		int samples = move.length / 3;
		double[] lengths = new double[samples];
		for (int v = 0; v < samples; v++)
		{
			lengths[v] = Math.sqrt(move[3 * v] * move[3 * v] + move[3 * v + 1] * move[3 * v + 1] + move[3 * v + 2] * move[3 * v + 2]);
		}
		double[] sorted = lengths.clone();
		Arrays.sort(sorted);
		double least = sorted[(int) (0.9 * (samples - 1))];
		double front = 0;
		int count = 0;
		for (int v = 0; v < samples; v++)
		{
			if (lengths[v] >= least)
			{
				front -= end[3 * v + 2];
				count++;
			}
		}
		return count > 0 ? front / count : 0;
	}

	/** Ticks from one keyframe to another. */
	private static int ticks(int[] lengths, int from, int to)
	{
		int ticks = 0;
		for (int k = from; k < to; k++)
		{
			ticks += lengths[k];
		}
		return ticks;
	}

	/** A lift is a wind-up when a blow starts within this many ticks after it (and is at least BLOW as fast). */
	static final int WINDUP_GAP = 15;
	/** Ticks after a blow down within which the weapon going back up is its recoil. */
	static final int RECOIL_GAP = 6;
	/** A blow begins where its motion does: the steps before it at least this part as fast, going the same way. */
	static final double BLOW_ONSET = 0.25;
	/** ... and no faster than this times the step after (speeding up into the blow, a small dip allowed). */
	static final double ONSET_DIP = 1.25;
	/** Steps at the start of a blow downwards moving the weapon up this much (see rise) are still the wind-up. */
	static final double RAISE_START = 0.3;
	/** A strike braking by this part of its speed from one step to the next lands its blow there. */
	static final double BRAKE = 0.35;
	/** A weapon going up this much (see rise) after going less up bounces back from a blow. */
	static final double BOUNCE = 0.1;

	/** Part of the model's height a vertex must stray from the straight path before it can count as a glitch. */
	static final double GLITCH_REACH = 0.08;
	/** How far a vertex may stray from the straight path, relative to that path's length (a turn of 180 degrees: 0.5). */
	static final double GLITCH_STRAY = 0.9;

	/**
	 * Steps the client blends a strange way round. Turning a part about one axis, a vertex strays from the straight line
	 * between its two positions by at most half that line's length (half way through a turn of 180 degrees). Blending
	 * each turn axis on its own can take a part through a flip or the wrong way round, far off that line.
	 */
	static boolean[] glitches(float[][] poses, float[][] mids, double[] midAt)
	{
		int steps = poses.length - 1;
		boolean[] glitch = new boolean[Math.max(0, steps)];
		if (mids == null)
		{
			return glitch;
		}
		float[] first = poses[0];
		double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (int v = 1; v < first.length; v += 3)
		{
			minY = Math.min(minY, first[v]);
			maxY = Math.max(maxY, first[v]);
		}
		double reach = GLITCH_REACH * Math.max(1, maxY - minY);
		int samples = first.length / 3;
		for (int i = 0; i < steps; i++)
		{
			float[] mid = mids[i];
			if (mid == null)
			{
				continue;
			}
			float[] a = poses[i], b = poses[i + 1];
			double t = midAt[i];
			int strays = 0;
			for (int v = 0; v < samples; v++)
			{
				double dx = b[3 * v] - a[3 * v], dy = b[3 * v + 1] - a[3 * v + 1], dz = b[3 * v + 2] - a[3 * v + 2];
				double line = Math.sqrt(dx * dx + dy * dy + dz * dz);
				double ox = mid[3 * v] - (a[3 * v] + t * dx);
				double oy = mid[3 * v + 1] - (a[3 * v + 1] + t * dy);
				double oz = mid[3 * v + 2] - (a[3 * v + 2] + t * dz);
				double off = Math.sqrt(ox * ox + oy * oy + oz * oz);
				if (off > reach && off > GLITCH_STRAY * line)
				{
					strays++;
				}
			}
			glitch[i] = strays >= Math.max(3, samples / 50);
		}
		return glitch;
	}

	private static double cosine(float[] a, float[] b)
	{
		double dot = 0, na = 0, nb = 0;
		for (int i = 0; i < a.length; i++)
		{
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
	}
}
