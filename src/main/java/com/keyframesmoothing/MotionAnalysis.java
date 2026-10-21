package com.keyframesmoothing;

import java.util.Arrays;

/**
 * What an animation does, learnt from its keyframe poses (vertex positions of the model that played it): how fast each
 * keyframe step moves, where it hits, and how to pace and time it. No game data needed, so new weapons and NPCs work
 * the first time they are seen.
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
		/** Speed at the start and the end of each step's blend, relative to an even blend (1). */
		final double[] startSpeed;
		final double[] endSpeed;
		/** Keyframes that are a hit: a fast step ends in them and a much slower one leaves them. */
		final boolean[] hit;
		/** The sampled vertex that moves fastest in the fastest step: the tip of the weapon or limb. */
		final int tip;
		/** Steps at least this fast get a trail. */
		final double fast;

		Motion(double[] speed, double[] startSpeed, double[] endSpeed, boolean[] hit, int tip, double fast)
		{
			this.speed = speed;
			this.startSpeed = startSpeed;
			this.endSpeed = endSpeed;
			this.hit = hit;
			this.tip = tip;
			this.fast = fast;
		}
	}

	/**
	 * @param poses   per keyframe, the sampled vertices as x, y, z, x, y, z...
	 * @param lengths the keyframes' original lengths in ticks
	 */
	static Motion analyze(float[][] poses, int[] lengths)
	{
		int n = lengths.length;
		int steps = n - 1;
		int samples = poses[0].length / 3;
		double[] distance = new double[Math.max(0, steps)];
		double[] speed = new double[Math.max(0, steps)];
		float[][] moves = new float[Math.max(0, steps)][];
		int tip = 0;
		double tipMove = -1;
		int fastest = -1;
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
			double[] sorted = lengthsOfMoves.clone();
			Arrays.sort(sorted);
			// the parts that move most (a weapon, an arm) count, not the average of the whole body
			distance[i] = sorted[(int) (0.9 * (samples - 1))];
			speed[i] = distance[i] / Math.max(1, lengths[i]);
			if (fastest < 0 || speed[i] > speed[fastest])
			{
				fastest = i;
			}
		}
		if (fastest >= 0)
		{
			float[] move = moves[fastest];
			for (int v = 0; v < samples; v++)
			{
				double l = Math.sqrt(move[3 * v] * move[3 * v] + move[3 * v + 1] * move[3 * v + 1] + move[3 * v + 2] * move[3 * v + 2]);
				if (l > tipMove)
				{
					tipMove = l;
					tip = v;
				}
			}
		}

		double[] start = new double[Math.max(0, steps)], end = new double[Math.max(0, steps)];
		boolean[] hit = new boolean[n];
		Arrays.fill(start, 1);
		Arrays.fill(end, 1);
		if (steps > 0)
		{
			// the animation starts from whatever came before and comes to rest on its last keyframe
			end[steps - 1] = speed[steps - 1] >= STILL ? 0.5 : 1;
		}
		for (int k = 1; k < steps; k++)
		{
			int a = k - 1, b = k;
			double va = speed[a], vb = speed[b];
			if (va < STILL && vb < STILL)
			{
				continue;
			}
			if (va < STILL || vb < STILL)
			{
				// leaving or reaching a still pose: ease
				if (va >= STILL)
				{
					end[a] = 0.4;
				}
				if (vb >= STILL)
				{
					start[b] = 0.4;
				}
				continue;
			}
			double cos = cosine(moves[a], moves[b]);
			if (va >= 3 * vb || va >= 2 * vb && cos < 0.3)
			{
				// a fast move stopped by a slow one: a hit; full speed into the pose, on from rest
				end[a] = 1.6;
				start[b] = 0.2;
				hit[k] = true;
			}
			else if (cos >= 0.3)
			{
				// goes on: the speed carries over the keyframe
				double carried = (va * lengths[b] + vb * lengths[a]) / (double) (lengths[a] + lengths[b]);
				carried *= 0.6 + 0.4 * cos;
				end[a] = carried / va;
				start[b] = carried / vb;
			}
			else
			{
				// turns back: ease into and out of the pose
				end[a] = 0.35;
				start[b] = 0.35;
			}
		}
		for (int i = 0; i < steps; i++)
		{
			start[i] = Math.min(3, start[i]);
			end[i] = Math.min(3, end[i]);
		}

		double[] moving = Arrays.stream(speed).filter(s -> s >= STILL).sorted().toArray();
		double fast = moving.length == 0 ? Double.MAX_VALUE : Math.max(moving[(int) (0.7 * (moving.length - 1))], 4 * STILL);
		return new Motion(speed, start, end, hit, tip, fast);
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

	/**
	 * New keyframe lengths with the same total, so the animation still ends when the game expects it to.
	 *
	 * @param exaggeration 0 = as made; higher makes fast steps faster and slow ones slower
	 * @param hitStop      ticks a hit pose is held, taken from the slower steps
	 */
	static int[] retime(int[] lengths, Motion motion, double exaggeration, int hitStop)
	{
		int n = lengths.length;
		int steps = n - 1;
		double[] wanted = new double[n];
		for (int i = 0; i < n; i++)
		{
			wanted[i] = lengths[i];
		}
		double[] moving = Arrays.stream(motion.speed).filter(s -> s >= STILL).sorted().toArray();
		if (steps <= 0 || moving.length == 0)
		{
			return lengths.clone();
		}
		double reference = moving[moving.length / 2];
		double total = 0, newTotal = 0;
		for (int i = 0; i < steps; i++)
		{
			if (motion.speed[i] >= STILL)
			{
				double factor = Math.pow(motion.speed[i] / reference, -exaggeration);
				wanted[i] = lengths[i] * Math.min(2.5, Math.max(0.4, factor));
				total += lengths[i];
				newTotal += wanted[i];
			}
		}
		for (int i = 0; i < steps; i++)
		{
			if (motion.speed[i] >= STILL)
			{
				wanted[i] *= total / newTotal;
			}
		}

		if (hitStop > 0)
		{
			for (int k = 1; k < steps; k++)
			{
				if (!motion.hit[k])
				{
					continue;
				}
				// hold the hit pose longer, paid for by the slower steps (never the strike into it)
				double spare = 0;
				for (int i = 0; i < steps; i++)
				{
					if (i != k && i != k - 1)
					{
						spare += Math.max(0, wanted[i] - 1);
					}
				}
				double take = Math.min(hitStop, spare);
				if (take <= 0)
				{
					continue;
				}
				for (int i = 0; i < steps; i++)
				{
					if (i != k && i != k - 1)
					{
						wanted[i] -= take * Math.max(0, wanted[i] - 1) / spare;
					}
				}
				wanted[k] += take;
			}
		}
		return round(wanted, lengths);
	}

	/** Whole ticks, at least 1, with the same total as the original lengths. */
	private static int[] round(double[] wanted, int[] lengths)
	{
		int n = wanted.length;
		int total = Arrays.stream(lengths).sum();
		int[] out = new int[n];
		int sum = 0;
		for (int i = 0; i < n; i++)
		{
			out[i] = Math.max(1, (int) Math.floor(wanted[i]));
			sum += out[i];
		}
		while (sum < total)
		{
			// the largest remainder gets the next tick
			int best = 0;
			double bestRest = -Double.MAX_VALUE;
			for (int i = 0; i < n; i++)
			{
				double rest = wanted[i] - out[i];
				if (rest > bestRest)
				{
					bestRest = rest;
					best = i;
				}
			}
			out[best]++;
			sum++;
		}
		while (sum > total)
		{
			int best = -1;
			double bestRest = Double.MAX_VALUE;
			for (int i = 0; i < n; i++)
			{
				double rest = wanted[i] - out[i];
				if (out[i] > 1 && rest < bestRest)
				{
					bestRest = rest;
					best = i;
				}
			}
			if (best < 0)
			{
				return lengths.clone();
			}
			out[best]--;
			sum--;
		}
		return out;
	}
}
