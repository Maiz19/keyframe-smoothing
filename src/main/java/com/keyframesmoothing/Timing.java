package com.keyframesmoothing;

/**
 * Timing curves for one keyframe, and how to draw them with the client's blend.
 *
 * The client blends keyframe f into f + 1 by n / frameLengths[f], where n is the time spent on the keyframe in client
 * ticks (whole ticks plus the part of the current tick). Changing frameLengths[f] for one drawn frame changes how far
 * along the blend is, so any curve can be followed as long as the divisor stays a whole number above n.
 */
final class Timing
{
	/** How far past the next pose a blend may go rather than step back. */
	static final double MAX_OVERSHOOT = 1.1;

	private Timing()
	{
	}

	/**
	 * How far along keyframe f the pose should be, for x = time spent / keyframe length (0..1).
	 *
	 * @param hold  part of the keyframe the pose stays put (0..1)
	 * @param power 1 = straight; above 1 the move starts slow and speeds up into the next pose
	 */
	static double curve(double x, double hold, double power)
	{
		if (x <= hold)
		{
			return 0;
		}
		if (x >= 1)
		{
			return 1;
		}
		return Math.pow((x - hold) / (1 - hold), power);
	}

	/**
	 * Like {@link #curve}, but the move also slows down as it reaches the next pose (ease in and out), so both poses
	 * stay in view a moment.
	 *
	 * @param power 1 = straight; above 1 the move lingers longer near both poses
	 */
	static double settle(double x, double hold, double power)
	{
		if (x <= hold)
		{
			return 0;
		}
		if (x >= 1)
		{
			return 1;
		}
		double s = (x - hold) / (1 - hold);
		double a = Math.pow(s, power);
		return a / (a + Math.pow(1 - s, power));
	}

	/**
	 * The frame length to give the client for this drawn frame, so the blend lands as close to the target as it can
	 * without going back from what was drawn last. 0 means don't blend at all (the pose holds).
	 *
	 * @param n      ticks spent on the keyframe, with the part of the current tick
	 * @param target where the pose should be (0..1)
	 * @param last   where it was drawn last (0..1)
	 */
	static int divisor(double n, double target, double last)
	{
		if (target <= 0.005 && last <= 0)
		{
			return 0;
		}
		// below 1: the divisor must be above n
		int lowest = Math.max(1, (int) n + 1);
		int centre = Math.max(lowest, (int) (n / Math.max(target, 1e-6)));
		int best = 0;
		double bestError = Double.MAX_VALUE;
		for (int d = Math.max(lowest, centre - 2); d <= centre + 2; d++)
		{
			double drawn = n / d;
			if (drawn + 1e-9 < last)
			{
				continue;
			}
			double error = Math.abs(drawn - target);
			if (error < bestError)
			{
				best = d;
				bestError = error;
			}
		}
		if (best == 0)
		{
			// every choice near the target moves back: take the smallest step forward
			best = Math.max(lowest, (int) (n / Math.max(last, 1e-6)));
			if (n / best + 1e-9 < last && lowest > 1 && n / (lowest - 1) <= MAX_OVERSHOOT)
			{
				// just past a tick the divisor can't stay above n without going back; a hair past the next pose instead
				best = lowest - 1;
			}
		}
		return best;
	}
}
