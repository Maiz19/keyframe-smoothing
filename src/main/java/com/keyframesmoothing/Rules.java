package com.keyframesmoothing;

/**
 * The rules a smoothed keyframe step must keep against the client's own blend; a step that breaks one is left to the
 * client, so the result is never worse than plain smoothing:
 * <ol>
 * <li>keyframes: at a keyframe every vertex is exactly at its keyframe position;</li>
 * <li>no bending: vertices of one part keep their distance within what the two keyframes give it, no worse than the
 * client does;</li>
 * <li>joints hold: touching vertices of different parts keep their distance, no worse than the client does;</li>
 * <li>no detours: no vertex travels further over the step than with the client or in a straight line.</li>
 * </ol>
 * Poses are float[3 * vertices]; pairs are vertex index pairs.
 */
final class Rules
{
	static final double MISS = 0.05;
	static final double BEND = 0.02;
	/** Bending under this (model units) is too small to see: a chain's links, a rope's twists. */
	static final double BEND_UNITS = 1.0;
	static final double GAP = 2.0;
	/** ... plus this part of what the client opens the joint by. */
	static final double GAP_PART = 0.2;
	static final double PATH = 1.25;
	static final double PATH_UNITS = 2.0;

	private Rules()
	{
	}

	static double distance(float[] a, float[] b, int v)
	{
		double dx = a[3 * v] - b[3 * v], dy = a[3 * v + 1] - b[3 * v + 1], dz = a[3 * v + 2] - b[3 * v + 2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	static double pair(float[] pose, int a, int b)
	{
		double dx = pose[3 * a] - pose[3 * b], dy = pose[3 * a + 1] - pose[3 * b + 1], dz = pose[3 * a + 2] - pose[3 * b + 2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Rule 1: how far the pose misses the keyframe, at worst. */
	static double miss(float[] pose, float[] key)
	{
		double worst = 0;
		for (int v = 0; v < key.length / 3; v++)
		{
			worst = Math.max(worst, distance(pose, key, v));
		}
		return worst;
	}

	/**
	 * Rule 2: how far the distance of pairs that keyframes a and b both give the same length (rigid over the step) leaves
	 * that length, as a part of it.
	 */
	static double bend(float[] pose, float[] a, float[] b, int[][] pairs)
	{
		return bend(pose, a, b, pairs, null);
	}

	/** As bend; worstPair (when given) gets the pair that bends most. */
	static double bend(float[] pose, float[] a, float[] b, int[][] pairs, int[] worstPair)
	{
		double worst = 0;
		for (int[] p : pairs)
		{
			double da = pair(a, p[0], p[1]), db = pair(b, p[0], p[1]);
			if (Math.min(da, db) <= 8 || Math.abs(da - db) > Math.max(0.5, 0.01 * Math.max(da, db)))
			{
				continue;
			}
			double d = pair(pose, p[0], p[1]);
			double outside = Math.max(0, Math.max(d - Math.max(da, db), Math.min(da, db) - d) - BEND_UNITS);
			if (outside / Math.min(da, db) > worst)
			{
				worst = outside / Math.min(da, db);
				if (worstPair != null)
				{
					worstPair[0] = p[0];
					worstPair[1] = p[1];
				}
			}
		}
		return worst;
	}

	/** Rule 3: how far touching pairs move apart or together against a straight blend of their distance, s of the way. */
	static double gap(float[] pose, float[] a, float[] b, double s, int[][] pairs)
	{
		return gap(pose, a, b, s, pairs, null);
	}

	/** As gap; worstPair (when given) gets the pair that opens most. */
	static double gap(float[] pose, float[] a, float[] b, double s, int[][] pairs, int[] worstPair)
	{
		double worst = 0;
		for (int[] p : pairs)
		{
			double expected = pair(a, p[0], p[1]) * (1 - s) + pair(b, p[0], p[1]) * s;
			double off = Math.abs(pair(pose, p[0], p[1]) - expected);
			if (off > worst)
			{
				worst = off;
				if (worstPair != null)
				{
					worstPair[0] = p[0];
					worstPair[1] = p[1];
				}
			}
		}
		return worst;
	}

	/**
	 * Rule 4: how much further than allowed any vertex goes along a path (poses in order), at worst. A vertex on a part
	 * that hangs on a turning parent swings along an arc with it: arc (when given) is per vertex how much longer than
	 * straight that arc is (1 for no parent, (a/2)/sin(a/2) for a parent turning by a).
	 */
	static double detour(float[][] path, float[][] clientPath, double[] arc)
	{
		return detour(path, clientPath, arc, null);
	}

	/** As detour; worstVertex (when given) gets the vertex that goes furthest over. */
	static double detour(float[][] path, float[][] clientPath, double[] arc, int[] worstVertex)
	{
		int vertices = path[0].length / 3;
		double worst = 0;
		float[] first = path[0], last = path[path.length - 1];
		for (int v = 0; v < vertices; v++)
		{
			double ours = 0, theirs = 0;
			for (int i = 1; i < path.length; i++)
			{
				ours += distance(path[i], path[i - 1], v);
			}
			for (int i = 1; i < clientPath.length; i++)
			{
				theirs += distance(clientPath[i], clientPath[i - 1], v);
			}
			double straight = distance(last, first, v);
			double allowed = Math.max(PATH * Math.max(theirs, straight), (arc == null ? 1 : arc[v]) * straight) + PATH_UNITS;
			if (ours - allowed > worst)
			{
				worst = ours - allowed;
				if (worstVertex != null)
				{
					worstVertex[0] = v;
				}
			}
		}
		return worst;
	}

	/** Points per step the smoothed pose is checked at (it costs nothing to work out, unlike the client's). */
	static final int DENSE = 16;

	/**
	 * Where the client spins a part the long way round (see PartSpline.spins), the client is the glitch: the spline may
	 * then bend and open joints up to these limits (bend as a part of the length; joints as a part of the model's height,
	 * at least SPIN_GAP_UNITS), and its path is not held to the client's spin.
	 */
	static final double SPIN_BEND = 0.08;
	static final double SPIN_GAP = 0.04;
	static final double SPIN_GAP_UNITS = 8;
	/**
	 * There the spline is only turned down for bending or opening joints this many times as far as the client does
	 * (or the SPIN_ limits): what is shown instead is no blend at all, the pose held and then a jump into the next.
	 */
	static final double SPIN_SLACK = 1.5;

	/**
	 * Whether a smoothed step keeps the rules: null when it does, else which rule it breaks first and by how much. The
	 * client's blend is known at a few points only; the smoothed pose is checked at many, so nothing it does between those
	 * points goes unseen: its bending and joint gaps may not exceed the client's, taken straight between the known points
	 * around it (at a keyframe the client is exact), and its path may not be longer than the client's path through the
	 * known points.
	 *
	 * @param keyA, keyB keyframe poses at the start and the end of the step
	 * @param clientAt   how far along each client sample is (0..1, in order, without the ends)
	 * @param client     the client's blend at those points
	 * @param ours       the smoothed pose at i / DENSE for i = 0 .. DENSE
	 * @param spin   the client spins a part the long way round in this step: the SPIN_ limits apply
	 * @param height the model's height, for SPIN_GAP
	 */
	static String broken(float[] keyA, float[] keyB, double[] clientAt, float[][] client, float[][] ours, int[][] same,
		int[][] near, boolean spin, double height)
	{
		return broken(keyA, keyB, clientAt, client, ours, same, near, spin, height, null);
	}

	/** As broken; arc (when given) is per vertex how much longer than straight it may swing, see detour. */
	static String broken(float[] keyA, float[] keyB, double[] clientAt, float[][] client, float[][] ours, int[][] same,
		int[][] near, boolean spin, double height, double[] arc)
	{
		double missA = miss(ours[0], keyA), missB = miss(ours[DENSE], keyB);
		if (missA > MISS || missB > MISS)
		{
			return String.format("keyframe missed by %.2f", Math.max(missA, missB));
		}
		// what the client does at the known points, the keyframes included (where it is exact)
		int known = clientAt.length + 2;
		double[] knownAt = new double[known], bendKnown = new double[known], gapKnown = new double[known];
		knownAt[known - 1] = 1;
		for (int i = 0; i < clientAt.length; i++)
		{
			knownAt[i + 1] = clientAt[i];
			bendKnown[i + 1] = bend(client[i], keyA, keyB, same);
			gapKnown[i + 1] = gap(client[i], keyA, keyB, clientAt[i], near);
		}
		double spinGap = Math.max(SPIN_GAP_UNITS, SPIN_GAP * height);
		for (int i = 1; i < DENSE; i++)
		{
			double s = i / (double) DENSE;
			// no worse than the client, taken straight between the known points around s
			int after = 1;
			while (after < known - 1 && knownAt[after] < s)
			{
				after++;
			}
			double span = knownAt[after] - knownAt[after - 1];
			double w = span > 0 ? (s - knownAt[after - 1]) / span : 0;
			double bendAllowed = bendKnown[after - 1] + (bendKnown[after] - bendKnown[after - 1]) * w + BEND;
			double gapAllowed = (gapKnown[after - 1] + (gapKnown[after] - gapKnown[after - 1]) * w) * (1 + GAP_PART) + GAP;
			if (spin)
			{
				bendAllowed = SPIN_SLACK * Math.max(bendAllowed, SPIN_BEND);
				gapAllowed = SPIN_SLACK * Math.max(gapAllowed, spinGap);
			}
			int[] pair = new int[2];
			double bend = bend(ours[i], keyA, keyB, same, pair);
			if (bend > bendAllowed)
			{
				return String.format("bends %.3f at %.2f (allowed %.3f) pair %d-%d", bend, s, bendAllowed, pair[0], pair[1]);
			}
			double gap = gap(ours[i], keyA, keyB, s, near, pair);
			if (gap > gapAllowed)
			{
				return String.format("joint gap %.1f at %.2f (allowed %.1f) pair %d-%d", gap, s, gapAllowed, pair[0], pair[1]);
			}
		}
		if (spin)
		{
			return null;
		}
		float[][] clientPath = new float[clientAt.length + 2][];
		clientPath[0] = keyA;
		System.arraycopy(client, 0, clientPath, 1, clientAt.length);
		clientPath[clientAt.length + 1] = keyB;
		int[] vertex = new int[1];
		double detour = detour(ours, clientPath, arc, vertex);
		return detour <= 0 ? null : String.format("detour %.1f vertex %d", detour, vertex[0]);
	}

	/**
	 * Steps (per animation) kept smoothed though they break the bend or joint rules a little, because the client's own
	 * blend is the bigger glitch there (still turned down where they stretch a rope, see PartSpline.stretched): the
	 * Obligator's slow attack (15123), whose client blend zigzags and dives the ball.
	 */
	private static final java.util.Map<Integer, int[]> ACCEPTED = java.util.Map.of(15123, new int[]{3, 4, 5, 6, 7, 9, 10, 11, 12, 14, 15});

	/** Per step whether the spline is kept whatever the bend and joint rules say (see ACCEPTED), or null: none is. */
	static boolean[] accepted(int animationId, int steps)
	{
		int[] list = ACCEPTED.get(animationId);
		if (list == null)
		{
			return null;
		}
		boolean[] out = new boolean[steps];
		for (int k : list)
		{
			if (k < steps)
			{
				out[k] = true;
			}
		}
		return out;
	}

	/**
	 * Animations whose weapon is a rope or chain (the Obligator's ball on its chain, 15123): steps whose spline stretches
	 * its links past what the keyframes and the client's blend do are the client's, and blows are not pushed on or back
	 * past where the rope stays whole.
	 */
	static boolean ropes(int animationId)
	{
		return animationId == 15123;
	}

	/**
	 * The Obligator's slow attack (15123): MotionAnalysis reads the jerk 9->10 as the hit and the swing 11->15 only after
	 * it, but the swing 9->15 is one blow that hits at keyframe 15 (tick 43): so that one is the hit, started from 9.
	 */
	static MotionAnalysis.Motion swing(int animationId, MotionAnalysis.Motion motion)
	{
		if (animationId != 15123 || motion.hit.length <= 15 || motion.blowStart.length <= 15)
		{
			return motion;
		}
		boolean[] hit = motion.hit.clone();
		int[] blowStart = motion.blowStart.clone();
		java.util.Arrays.fill(hit, 9, 15, false);
		java.util.Arrays.fill(blowStart, 9, 15, -1);
		hit[15] = true;
		// from where the ball is flung furthest back, the rope taut (10): that fling is the wind-up's end, slowing into
		// its furthest point, and from there it is struck in one go
		blowStart[15] = 10;
		return new MotionAnalysis.Motion(motion.speed, hit, blowStart, motion.glitch, motion.heard, motion.struck, motion.stops);
	}
}
