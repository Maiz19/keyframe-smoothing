package com.keyframesmoothing;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The swings of the skills a tool strikes with (mining, chopping, a harpoon, a hammer): the keyframe each starts from
 * (the top of its wind-up) and the one it lands in, worked out ahead from the game's animations. MotionAnalysis can't
 * tell them: it goes by how fast the parts that move most go, so what it finds depends on what the player wears (three
 * of the four swings of mining a wall are lost in some gear), and repeated strokes run into each other as one blow.
 * <p>
 * Every tool of a skill plays the same keyframes, so a family is known by its keyframe lengths: that covers all its
 * tools (some 80 pickaxe animations), also a new one, and nothing else in the game has these lengths.
 * <p>
 * A swing lands on its keyframe, which is when its sound comes, there being no hitsplat to time it by; where the game's
 * sound comes a keyframe later (a third number), it is shown landing with the sound (see ActorDrawer.heard), except the first swing
 * of an action, before it has started over: that lands as the game has it (mining: 1.5 game ticks in), as close to the
 * original as it can be. The later swings keep their rhythm.
 * <p>
 * A pickaxe's swing gets the time of its last step as a strike, and with mining's sound more: too long for a blow that
 * short. It is struck in STRIKE ticks, and until then stays at the top, where the game holds it.
 * It slows into the top over SETTLE of that wait, and all through it rises on past the top by LIFT of the step that led
 * there, so it is never quite still, and the strike comes out of a last bit of wind-up. That lift keeps both hands on the
 * handle (see PartSpline.keepGrip): each arm goes on as it turned, and the pick hangs on only one.
 * <p>
 * A hammer stops dead on the anvil: its stroke carries on past nothing (Motion.stops). A swing is known to go where it
 * goes, so it is never held and then jumped into its landing as a step the client's blend gets wrong would be (see clean).
 */
final class SkillSwings
{
	static final double STRIKE = 3;
	static final double TWO_HANDED_STRIKE = 4;
	static final double HARPOON_STRIKE = 4;
	static final double SETTLE = 0.5;
	static final double LIFT = 1;
	private static final int NO_SOUND = -1;
	private static final Map<List<Integer>, int[][]> FAMILIES = new HashMap<>();
	/** Families struck in a time of their own, in ticks; the others in the game's (see ActorDrawer.sweep). */
	private static final Map<List<Integer>, Double> STRIKES = new HashMap<>();
	/** Families landing on something that stops them dead. */
	private static final Set<List<Integer>> STOPPED = new HashSet<>();

	static
	{
		// mining: raised to the top, the 16-tick sweep down and the 3-tick strike into the rock are one swing
		family(new int[]{5, 3, 3, 3, 3, 3, 3, 3, 16, 3, 5, 5}, new int[][]{{8, 10, 11}}, STRIKE);
		// mining a wall (the Motherlode Mine): four swings in one go
		family(new int[]{3, 3, 2, 2, 3, 3, 3, 3, 1, 5, 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 5, 5, 5, 5, 5, 5, 5, 12, 5, 5, 5, 5,
			5, 5, 5, 5, 5, 5, 5, 5, 5, 13, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 13, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
			5, 5, 5, 5, 5, 5, 5, 5}, new int[][]{{7, 8, NO_SOUND}, {28, 29, NO_SOUND}, {42, 43, NO_SOUND}, {62, 63, NO_SOUND}}, STRIKE);
		// chopping: from the axe drawn back into the tree
		family(new int[]{4, 4, 10, 12, 6, 4}, new int[][]{{2, 3}}, STRIKE);
		// a two-handed axe: down from high up behind the shoulder
		family(new int[]{5, 5, 5, 6, 6, 8, 12, 2, 2, 5, 8, 5, 5, 8, 10, 4, 7, 6, 6, 5}, new int[][]{{6, 9}}, TWO_HANDED_STRIKE);
		// a harpoon (a barbtailed one is shorter): thrust down from the top, through the splash (kf 7) into the water
		family(new int[]{4, 4, 4, 4, 4, 4, 2, 2, 2, 2, 2, 4, 4, 2, 2, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
			4, 4, 4, 4, 4}, new int[][]{{5, 9}}, HARPOON_STRIKE);
		family(new int[]{4, 4, 4, 4, 4, 4, 2, 2, 2, 2, 2, 4, 4, 2, 2, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
			4}, new int[][]{{5, 9}}, HARPOON_STRIKE);
		// smithing: the hammer lifted slowly, each stroke down onto the anvil
		family(new int[]{4, 4, 4, 4, 4, 15, 6, 15, 6, 15, 6, 15, 4, 4, 4, 4, 4}, new int[][]{{4, 5}, {6, 7}, {8, 9}, {10, 11}}, STRIKE);
		STOPPED.add(key(new int[]{4, 4, 4, 4, 4, 15, 6, 15, 6, 15, 6, 15, 4, 4, 4, 4, 4}));
		// building: four strokes
		family(new int[]{4, 4, 3, 4, 8, 3, 5, 5, 8, 3, 5, 5, 8, 3, 5, 5, 8, 3, 5, 5, 5}, new int[][]{{4, 6}, {8, 10}, {12, 14}, {16, 18}});
	}

	private SkillSwings()
	{
	}

	private static void family(int[] lengths, int[][] swings)
	{
		FAMILIES.put(key(lengths), swings);
	}

	private static void family(int[] lengths, int[][] swings, double strike)
	{
		family(lengths, swings);
		STRIKES.put(key(lengths), strike);
	}

	private static List<Integer> key(int[] lengths)
	{
		return Arrays.stream(lengths).boxed().collect(Collectors.toList());
	}

	/** The swings of an animation with these keyframe lengths, as {from, land[, sound (-1: none)]} keyframes in order, or null: not one of these. */
	static int[][] of(int[] lengths)
	{
		return lengths == null ? null : FAMILIES.get(key(lengths));
	}

	/**
	 * What an animation's motion is with its swings as blows, for an animation that has them: else the motion as it is.
	 * Keyframes past the n reached are left out (learnt up to where the game starts it over).
	 *
	 * @param lengths the animation's full keyframe lengths
	 */
	static MotionAnalysis.Motion onto(MotionAnalysis.Motion motion, int[] lengths, int n)
	{
		int[][] swings = of(lengths);
		if (swings == null)
		{
			return motion;
		}
		boolean[] hit = new boolean[n];
		int[] blowStart = new int[n];
		Arrays.fill(blowStart, -1);
		int[] heard = new int[n];
		Arrays.fill(heard, -1);
		double strike = STRIKES.getOrDefault(key(lengths), 0.0);
		double[] struck = new double[n];
		boolean any = false;
		for (int[] swing : swings)
		{
			if (swing[1] < n)
			{
				hit[swing[1]] = true;
				blowStart[swing[1]] = swing[0];
				struck[swing[1]] = strike;
				if (swing.length > 2 && swing[2] >= 0 && swing[2] < n)
				{
					heard[swing[1]] = swing[2];
					any = true;
					// the sound's stretch (see ActorDrawer.heard) makes a tick of drawn time last longer
					struck[swing[1]] *= (start(lengths, swing[1]) - start(lengths, swing[0])) / (double) (start(lengths, swing[2]) - start(lengths, swing[0]));
				}
			}
		}
		return new MotionAnalysis.Motion(motion.speed, hit, blowStart, motion.glitch, any ? heard : null, strike > 0 ? struck : null, STOPPED.contains(key(lengths)));
	}

	/**
	 * The steps the client's blend is known to get wrong, without those of a swing the spline couldn't draw: else its
	 * strike would be held and then jump into where it lands. The same array when there are none.
	 *
	 * @param glitches per step whether the blend goes wrong (see KeyframeTable), or null
	 * @param lengths the animation's full keyframe lengths
	 */
	static boolean[] clean(boolean[] glitches, int[] lengths, PartSpline spline)
	{
		int[][] swings = of(lengths);
		boolean[] clean = glitches;
		for (int s = 0; glitches != null && swings != null && s < swings.length; s++)
		{
			for (int k = swings[s][0]; k < swings[s][1] && k < glitches.length; k++)
			{
				if (glitches[k] && !spline.smooths(k))
				{
					clean = clean == glitches ? glitches.clone() : clean;
					clean[k] = false;
				}
			}
		}
		return clean;
	}

	private static int start(int[] lengths, int keyframe)
	{
		return Arrays.stream(lengths, 0, keyframe).sum();
	}
}
