package com.keyframesmoothing;

/**
 * What the plugin follows of one player or NPC: the animation and keyframe it shows and since when (updated each client
 * cycle), and how it is drawn this frame (set before each frame).
 */
final class Track
{
	/** The animation the client blends: the action when there is one, else the pose (standing, walking). */
	int animationId = -1;
	int frame = -1;
	boolean action;
	/** Game cycle the current keyframe started on. */
	int startCycle;
	/** An attack on a player or NPC, decided when the animation starts. */
	boolean attack;
	/** An action striking with a skill's tool (see SkillSwings), decided when the animation starts: its swings are drawn as an attack's blows from the first. */
	boolean skill;
	/**
	 * The action has started over since it began (its keyframe went back): something it repeats, whose blows are drawn
	 * as an attack's; not a one-off (eating, a teleport, an emote).
	 */
	boolean repeats;
	/**
	 * The action has just started over (set as its keyframe goes back, taken up as it is next drawn): the game shows its
	 * first keyframe at once, from wherever it was; drawn from the pose it was drawn in last (ActorDrawer.RESTART_TICKS).
	 */
	boolean restarting;
	/** It started over a while before that was seen (see sentInFirstKeyframe): drawn from where it was from now on. */
	boolean restartSeenLate;
	/**
	 * Where the action was (ticks from its start, as the game has it) as the game last started it over, NaN before: one
	 * started over while a swing still wound up goes on winding up from there (ActorDrawer.held).
	 */
	double cutTime = Double.NaN;
	/** The action starts over at its first keyframe when the game sends it again (see sentInFirstKeyframe). */
	boolean restartsWhenSent;
	/**
	 * The game cycle the game is to send the action again on with its sound, its first swing landed by then (the Gauntlet's
	 * chop, a game tick after it starts: KeyframeSmoothingPlugin.GAUNTLET_CHOP), -1: not expected. A second chop lands
	 * then, with that sound (ActorDrawer.twice).
	 */
	int againCycle = -1;
	/** An action's pose as last drawn (all vertices), and the game cycle it was drawn on; null before. */
	float[] drawnPose;
	int drawnCycle = -1;
	/** What the action's pose was off by as it started over (last drawn minus the new), let go of from restartNanos. */
	float[] restartOffset;
	long restartNanos;
	/** A special attack of the local player (see KeyframeSmoothingPlugin.SPEC_CYCLES). */
	boolean special;
	/** The combat style of a local player's attack (see KeyframeSmoothingPlugin.attackStyle), else null. */
	KeyframeSmoothingPlugin.CombatStyle style;
	/** Last client cycle the character was seen. */
	int seenCycle;

	Smoothing mode = Smoothing.PLAIN;
	/** Not walking: the action has the whole body. */
	boolean standing;
	/** The model last drawn (Learning.shape), 0 before the first. */
	long shape;
	/** The animation time last drawn (ticks from its start, as paced), or NaN: drawn as the game has it. */
	double drawnTime = Double.NaN;
	/** The keyframe its animation was last tried at (its start cycle; see KeyframeSmoothingPlugin.work): one try each. */
	int triedCycle = -1;
	/**
	 * The keyframe has lasted longer than it should: the client doesn't play the action yet (an animation can start
	 * with a delay, during which the pose animation shows) and nothing of it is drawn or worked out until the next keyframe.
	 */
	boolean overdue;
	/**
	 * Whether the client should blend this character's keyframe (set before each frame), or null before the first: the
	 * client is told one answer per animation, so one whose answer differs is drawn again with its own (ActorDrawer).
	 */
	Boolean blend;

	/**
	 * The keyframe the game shows this client cycle. An action whose keyframe goes back has started over: on its own, at
	 * the end of a loop, or because the game sent it again, which puts some back at their first keyframe from wherever
	 * they were (chopping, every few game ticks: in the middle of a swing).
	 *
	 * @param lengths the animation's keyframe lengths in ticks, or null: not known
	 * @return whether the action started over
	 */
	boolean shows(int frame, int cycle, int[] lengths)
	{
		if (frame == this.frame)
		{
			return false;
		}
		int last = this.frame;
		boolean again = action && frame < last;
		if (again)
		{
			repeats = true;
			restarting = true;
			cutTime = Double.NaN;
			if (lengths != null && last < lengths.length)
			{
				cutTime = Math.min(lengths[last], cycle - startCycle);
				for (int k = 0; k < last; k++)
				{
					cutTime += lengths[k];
				}
			}
		}
		this.frame = frame;
		startCycle = cycle;
		overdue = false;
		return again;
	}

	/**
	 * Whether the game sent the action again within its first keyframe, unseen: the keyframe doesn't change, it just lasts
	 * longer than its length (picking a herb in the Gauntlet: its first keyframe is 40 ticks, the game sends it again a game
	 * tick, 30, after the start, and it stays 70). The game sends on a game tick, so it started over on the last one within
	 * the keyframe's length: followed from there, the keyframe again from its start, drawn from where it was (restarting),
	 * not held at its end until the game gets there. Nothing when no game tick came within it: a start delay, or not known.
	 *
	 * @param ticks the client cycles of the last game ticks, latest first, -1: none
	 * @param lengths the animation's keyframe lengths in ticks, or null: not known
	 */
	boolean sentInFirstKeyframe(int cycle, int[] ticks, int[] lengths)
	{
		if (!action || frame != 0 || lengths == null || lengths.length < 2 || cycle - startCycle <= lengths[0])
		{
			return false;
		}
		int end = startCycle + lengths[0], sent = -1;
		for (int tick : ticks)
		{
			if (tick > startCycle && tick <= end)
			{
				sent = Math.max(sent, tick);
			}
		}
		if (sent < 0)
		{
			return false;
		}
		cutTime = sent - startCycle;
		startCycle = sent;
		repeats = true;
		restarting = true;
		restartSeenLate = true;
		overdue = false;
		return true;
	}
}
