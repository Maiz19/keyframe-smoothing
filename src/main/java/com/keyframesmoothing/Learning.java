package com.keyframesmoothing;

import java.util.Arrays;
import net.runelite.api.Actor;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;

/**
 * An animation on one model, worked out before it plays (see {@link Poses}): its exact pose at every keyframe, and a few
 * poses part of the way between keyframes as the client blends them. From those a {@link PartSpline} is made, off the
 * client thread, and checked against the client's blend (see Rules).
 */
final class Learning
{
	/** Where between two keyframes the client's blend is taken, and how near to it a drawn frame must be. */
	static final double[] SAMPLE_AT = {0.25, 0.5, 0.75};
	static final double SAMPLE_NEAR = 0.1;
	/** Passes after which steps without samples are left out (a worked-out lesson has them all: see Poses). */
	static final int PASSES = 2;

	private Learning()
	{
	}

	/** Which model a pose belongs to: its numbers of vertices and faces, and the corners of its first faces. */
	static long shape(Model model)
	{
		int faces = model.getFaceCount();
		int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
		long hash = 17;
		for (int f = 0; a != null && b != null && c != null && f < Math.min(64, faces); f++)
		{
			hash = (hash * 31 + a[f]) * 31 * 31 + b[f] * 31 + c[f];
		}
		return ((long) model.getVerticesCount() << 44) ^ ((long) faces << 24) ^ (hash & 0xFFFFFFL) ^ (hash >>> 24) * 0x9E3779B97F4A7C15L;
	}

	/**
	 * Which model a character's pose belongs to: the model, and for an NPC the scale the game sizes it by after posing
	 * it (a big and a small Muttadile share model and animations: learnt on one, the other would be drawn its size).
	 */
	static long shape(Actor actor, Model model)
	{
		long shape = shape(model);
		NPCComposition composition = actor instanceof NPC ? ((NPC) actor).getTransformedComposition() : null;
		if (composition != null)
		{
			shape ^= ((long) composition.getWidthScale() << 16 | composition.getHeightScale()) * 0xC2B2AE3D27D4EB4FL;
		}
		return shape;
	}

	/** How big a pose is: the diagonal of the box around its vertices. */
	static double size(float[] x, float[] y, float[] z, int count)
	{
		if (count == 0)
		{
			return 0;
		}
		float lowX = Float.MAX_VALUE, lowY = Float.MAX_VALUE, lowZ = Float.MAX_VALUE;
		float highX = -Float.MAX_VALUE, highY = -Float.MAX_VALUE, highZ = -Float.MAX_VALUE;
		for (int v = 0; v < count; v++)
		{
			lowX = Math.min(lowX, x[v]);
			highX = Math.max(highX, x[v]);
			lowY = Math.min(lowY, y[v]);
			highY = Math.max(highY, y[v]);
			lowZ = Math.min(lowZ, z[v]);
			highZ = Math.max(highZ, z[v]);
		}
		double dx = highX - lowX, dy = highY - lowY, dz = highZ - lowZ;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** How big a pose (x, y, z, ...) is, see size. */
	static double size(float[] pose)
	{
		int count = pose.length / 3;
		float[] x = new float[count], y = new float[count], z = new float[count];
		for (int v = 0; v < count; v++)
		{
			x[v] = pose[3 * v];
			y[v] = pose[3 * v + 1];
			z[v] = pose[3 * v + 2];
		}
		return size(x, y, z, count);
	}

	/** The model's vertices as x, y, z, x, y, z, ... */
	static float[] vertices(Model model)
	{
		int count = model.getVerticesCount();
		float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
		float[] out = new float[3 * count];
		for (int v = 0; v < count; v++)
		{
			out[3 * v] = x[v];
			out[3 * v + 1] = y[v];
			out[3 * v + 2] = z[v];
		}
		return out;
	}

	/** An animation on one model, as worked out (Poses) or made offline. */
	static final class Lesson
	{
		final int animationId;
		final long shape;
		final int[] lengths;
		final int vertices;
		final int[] faces;
		final float[][] keys;
		/** Per keyframe step and sample point: the client's blend there and how far along it really was. */
		final float[][][] samples;
		final double[][] sampleAt;
		int passes;
		/** The furthest keyframe taken. */
		int reached = -1;
		/**
		 * Times it started over before its last keyframe: the game repeats a skill's animation, or a fast attack's, before
		 * it ends, so its last keyframes may never show.
		 */
		int cutShort;
		/** Taken from the local player: once learnt, kept longest. */
		boolean yours;
		/** Per keyframe, whether the client's blend into the next goes wrong (the keyframe table), or null: see PartSpline.check. */
		boolean[] glitches;

		Lesson(int animationId, long shape, int[] lengths, Model model)
		{
			this.animationId = animationId;
			this.shape = shape;
			this.lengths = lengths.clone();
			vertices = model.getVerticesCount();
			int count = model.getFaceCount();
			int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
			faces = new int[3 * count];
			for (int f = 0; f < count; f++)
			{
				faces[3 * f] = a[f];
				faces[3 * f + 1] = b[f];
				faces[3 * f + 2] = c[f];
			}
			keys = new float[lengths.length][];
			int steps = Math.max(0, lengths.length - 1);
			samples = new float[steps][SAMPLE_AT.length][];
			sampleAt = new double[steps][SAMPLE_AT.length];
		}

		boolean needsKey(int k)
		{
			return k >= 0 && k < keys.length && keys[k] == null;
		}

		void key(int k, Model model)
		{
			if (needsKey(k) && model.getVerticesCount() == vertices)
			{
				key(k, vertices(model));
			}
		}

		/** As key(k, model), with the pose taken before (x, y, z, ...). */
		void key(int k, float[] pose)
		{
			if (needsKey(k) && pose.length == 3 * vertices)
			{
				keys[k] = pose;
				reached = Math.max(reached, k);
			}
		}

		/** The sample point a blend s of the way through step k would fill, or -1. */
		int sampleSlot(int k, double s)
		{
			if (k < 0 || k >= samples.length)
			{
				return -1;
			}
			for (int i = 0; i < SAMPLE_AT.length; i++)
			{
				if (samples[k][i] == null && Math.abs(s - SAMPLE_AT[i]) <= SAMPLE_NEAR)
				{
					return i;
				}
			}
			return -1;
		}

		void sample(int k, int slot, double s, Model model)
		{
			if (model.getVerticesCount() == vertices && samples[k][slot] == null)
			{
				sample(k, slot, s, vertices(model));
			}
		}

		/** As sample(k, slot, s, model), with the pose taken before (x, y, z, ...). */
		void sample(int k, int slot, double s, float[] pose)
		{
			if (pose.length == 3 * vertices && samples[k][slot] == null)
			{
				samples[k][slot] = pose;
				sampleAt[k][slot] = s;
			}
		}

		boolean keysComplete()
		{
			return Arrays.stream(keys).allMatch(key -> key != null);
		}

		/** Every keyframe taken, and every step sampled or the animation seen often enough. */
		boolean complete()
		{
			if (!keysComplete())
			{
				return false;
			}
			if (passes >= PASSES)
			{
				return true;
			}
			for (float[][] step : samples)
			{
				if (Arrays.stream(step).allMatch(sample -> sample == null))
				{
					return false;
				}
			}
			return true;
		}

		/**
		 * How many keyframes can be learnt now, from the first: all of them once complete; for an animation the game
		 * keeps starting over before its end, as many as it reaches once it has done so PASSES times (the rest stays the
		 * client's); else 0.
		 */
		int ready()
		{
			if (complete())
			{
				return keys.length;
			}
			if (cutShort < PASSES || reached < 1)
			{
				return 0;
			}
			for (int k = 0; k <= reached; k++)
			{
				if (keys[k] == null)
				{
					return 0;
				}
			}
			return reached + 1;
		}
	}

	/** A learnt animation: the spline, where its hits are, and when each keyframe starts. */
	static final class Learnt
	{
		final PartSpline spline;
		final MotionAnalysis.Motion motion;
		final int[] lengths;
		final int[] starts;
		final int vertices;
		/**
		 * The animation's keyframes: more than learnt (keyframes()) when the game always started it over before its end;
		 * from the last learnt keyframe on it is the client's.
		 */
		final int full;
		/** Per keyframe learnt, how big its pose is (see Learning.size). */
		final double[] sizes;
		/** Roughly the memory it holds. */
		final long bytes;
		/** The client cycle it was last drawn in (or learnt in), to keep what a crowd draws (client thread only). */
		int drawn;
		/**
		 * Per keyframe a blow ends in, for exaggerated blows (see ActorDrawer.moment): the keyframe its wind-up turns
		 * round at (see windUp), the step it is pushed on past that along (-1: none) and how far (a part of that step),
		 * and how far it can carry on past where it lands (a part of its last step).
		 */
		final int[] windUp, pullStep;
		/**
		 * Per keyframe a rope's blow lands in: the keyframe its head's swing round into the blow starts at (see sweep),
		 * -1: none.
		 */
		final int[] sweep;
		/**
		 * Per keyframe a rope's blow lands in: how far its head is flung on past the point it turns round at, along the
		 * step into that (a part of the step; see ActorDrawer.ROPE_FLING); 0: not.
		 */
		final double[] fling;
		final double[] pull, follow;
		/**
		 * How far the drawn pose has moved from the animation's start, per TRAVEL_SAMPLES of a tick, over the steps blows
		 * and their wind-ups go through (elsewhere it stays put): the way a blow is paced along (see ActorDrawer.along).
		 * Measured as MotionAnalysis measures speed, the parts that move most. Null without blows.
		 */
		final float[] travel;
		/** As travel, of all but a rope's head (see moved): the arms and the rope go their own way in its blow; else null. */
		final float[] bodyTravel;

		Learnt(PartSpline spline, MotionAnalysis.Motion motion, int[] lengths, int vertices)
		{
			this(spline, motion, lengths, vertices, lengths.length);
		}

		Learnt(PartSpline spline, MotionAnalysis.Motion motion, int[] lengths, int vertices, int full)
		{
			this.spline = spline;
			this.motion = motion;
			this.lengths = lengths;
			this.vertices = vertices;
			this.full = full;
			sizes = new double[lengths.length];
			for (int k = 0; k < sizes.length && spline != null; k++)
			{
				sizes[k] = size(spline.key(k));
			}
			bytes = spline == null ? 0 : spline.bytes();
			starts = new int[lengths.length + 1];
			for (int k = 0; k < lengths.length; k++)
			{
				starts[k + 1] = starts[k] + lengths[k];
			}
			int n = lengths.length;
			windUp = new int[n];
			pullStep = new int[n];
			pull = new double[n];
			follow = new double[n];
			Arrays.fill(pullStep, -1);
			sweep = new int[n];
			Arrays.fill(sweep, -1);
			fling = new double[n];
			int earliest = 0;
			double farthest = 0;
			for (int k = 1; k < n && spline != null; k++)
			{
				farthest = Math.max(farthest, MotionAnalysis.apart(spline.key(k), spline.key(0)));
			}
			for (int b = 1; b < n && spline != null; b++)
			{
				int a = blowStart(b);
				if (a < 0)
				{
					continue;
				}
				windUp(b, a, earliest);
				sweep[b] = sweep(b, a, earliest);
				fling[b] = sweep[b] >= 0 ? spline.room(a - 1, true, ActorDrawer.ROPE_FLING) : 0;
				pull[b] = pullStep[b] < 0 ? 0 : spline.room(pullStep[b], pullStep[b] != windUp[b], ActorDrawer.PULL_BACK);
				// on past the hit along its last step (not along a step the client spins: its turn isn't the way it goes;
				// nor past the stance it lands back in, which would spring back like a hitch: Dharok's axe coming upright)
				boolean settles = MotionAnalysis.apart(spline.key(b), spline.key(0)) < MotionAnalysis.SETTLED * farthest;
				follow[b] = b < n - 1 && goes(b - 1) && !settles && !motion.stops ? spline.room(b - 1, true, ActorDrawer.FOLLOW_THROUGH) : 0;
				earliest = b;
			}
			travel = travel(false);
			bodyTravel = spline != null && spline.head().length > 0 ? travel(true) : null;
			headWay = bodyTravel != null ? headWay() : null;
		}

		/** Samples per tick of headWay. */
		static final int HEAD_SAMPLES = 16;
		/**
		 * How far a rope's head (the middle of it) has gone through the world from the animation's start, per
		 * HEAD_SAMPLES of a tick; null without one. Its blow goes along this evenly (see ActorDrawer.moment), arms, rope
		 * and head at one moment: the head's speed, as the eye follows it, then only rises into the hit.
		 */
		final float[] headWay;

		private float[] headWay()
		{
			int n = lengths.length;
			int[] head = spline.head();
			float[] way = new float[starts[n - 1] * HEAD_SAMPLES + 1];
			double[] before = null;
			int i = 0;
			for (int k = 0; k < n - 1; k++)
			{
				int samples = lengths[k] * HEAD_SAMPLES;
				for (int j = before == null ? 0 : 1; j <= samples; j++)
				{
					float[] pose = spline.drawnPose(k, j / (double) samples);
					double[] middle = new double[3];
					for (int v : head)
					{
						for (int axis = 0; axis < 3; axis++)
						{
							middle[axis] += (double) pose[3 * v + axis] / head.length;
						}
					}
					if (before != null)
					{
						way[i + 1] = way[i] + (float) Math.sqrt(Math.pow(middle[0] - before[0], 2) + Math.pow(middle[1] - before[1], 2) + Math.pow(middle[2] - before[2], 2));
						i++;
					}
					before = middle;
				}
			}
			return way;
		}

		/** Samples per tick of travel. */
		static final int TRAVEL_SAMPLES = 4;

		/** Works out travel. */
		private float[] travel(boolean body)
		{
			int n = lengths.length;
			boolean[] inBlow = new boolean[n];
			boolean any = false;
			for (int b = 1; b < n && spline != null; b++)
			{
				int a = blowStart(b);
				for (int k = a < 0 ? b : sweep[b] >= 0 ? sweep[b] : pullStep[b] >= 0 ? Math.min(a, windUp[b]) : a; k < b; k++)
				{
					inBlow[k] = true;
					any = true;
				}
			}
			if (!any)
			{
				return null;
			}
			float[] travel = new float[starts[n - 1] * TRAVEL_SAMPLES + 1];
			int i = 0;
			for (int k = 0; k < n - 1; k++)
			{
				int samples = lengths[k] * TRAVEL_SAMPLES;
				float[] before = inBlow[k] ? spline.drawnPose(k, 0) : null;
				for (int j = 1; j <= samples; j++, i++)
				{
					if (!inBlow[k])
					{
						travel[i + 1] = travel[i];
						continue;
					}
					float[] pose = spline.drawnPose(k, j / (double) samples);
					travel[i + 1] = travel[i] + (float) moved(pose, before, body);
					before = pose;
				}
			}
			return travel;
		}

		/**
		 * How far the pose moves between two moments, for a blow's way: by the parts that move most (see
		 * MotionAnalysis.apart). In a rope's blow its head (see PartSpline.solid; as it moves on the end of its rope) and
		 * the rest (body: the arms that pull it, the rope) are counted apart and each goes its own way evenly (see ActorDrawer.moment): counted together,
		 * the head stands still in the air each time the arms move and it doesn't.
		 */
		private double moved(float[] pose, float[] before, boolean body)
		{
			int[] head = spline.head();
			if (head.length == 0)
			{
				return MotionAnalysis.apart(pose, before);
			}
			boolean[] of = new boolean[pose.length / 3];
			for (int v : head)
			{
				of[v] = true;
			}
			// the head's way is how it moves on the end of its rope: where that end goes is the hand's doing, drawn at
			// the rest's own moment (see PartSpline.headFrom)
			double[] shift = new double[3];
			int[] held = spline.headHeld();
			for (int v : held)
			{
				for (int axis = 0; axis < 3 && !body; axis++)
				{
					shift[axis] += (pose[3 * v + axis] - before[3 * v + axis]) / held.length;
				}
			}
			if (!body)
			{
				// the head as the eye follows it: where its middle goes, not how it spins about that
				double[] middle = new double[3];
				for (int v : head)
				{
					for (int axis = 0; axis < 3; axis++)
					{
						middle[axis] += (pose[3 * v + axis] - before[3 * v + axis]) / head.length;
					}
				}
				return Math.sqrt(Math.pow(middle[0] - shift[0], 2) + Math.pow(middle[1] - shift[1], 2) + Math.pow(middle[2] - shift[2], 2));
			}
			double[] away = new double[body ? of.length - head.length : head.length];
			int n = 0;
			for (int v = 0; v < of.length; v++)
			{
				if (of[v] != body)
				{
					double dx = pose[3 * v] - before[3 * v] - shift[0], dy = pose[3 * v + 1] - before[3 * v + 1] - shift[1],
						dz = pose[3 * v + 2] - before[3 * v + 2] - shift[2];
					away[n++] = Math.sqrt(dx * dx + dy * dy + dz * dz);
				}
			}
			Arrays.sort(away);
			return away[(int) (0.9 * (away.length - 1))];
		}

		/** A step is a hold when the pose moves less than this part of what the step into the blow's start moves it. */
		private static final double SWEEP_HOLD = 0.1;

		/**
		 * A rope's blow b (starting at keyframe a) whose head is flung out in the step before a, out of a hold, after
		 * being swung round: the keyframe that swing starts at. The game stops the head in that hold between the swing
		 * and the fling; drawn, the swing runs on into the fling (see ActorDrawer.moment). -1: none, or a step of it is
		 * the client's.
		 */
		private int sweep(int b, int a, int earliest)
		{
			double[] speed = motion.speed;
			if (!spline.roped() || pullStep[b] >= 0 || a < 3 || a - 1 >= speed.length)
			{
				return -1;
			}
			double fling = speed[a - 1] * lengths[a - 1];
			int k = a - 2;
			while (k > earliest && speed[k] * lengths[k] <= SWEEP_HOLD * fling)
			{
				k--;
			}
			if (k == a - 2 || k < earliest || speed[k] * lengths[k] <= SWEEP_HOLD * fling)
			{
				return -1;
			}
			for (int j = k; j < a; j++)
			{
				if (!spline.smooths(j))
				{
					return -1;
				}
			}
			return k;
		}

		/**
		 * Where blow b (starting at keyframe a) winds up: the last keyframe, from a back as far as the blow is long (not
		 * into the blow before), where pushing the pose on past it goes back against the blow and on the way the pose
		 * arrives there (or it arrives standing still): the wind-up turns round there. Pushed on along the step before
		 * it, else back along the step after it.
		 */
		private void windUp(int b, int a, int earliest)
		{
			windUp[b] = a;
			float[] landed = spline.key(b);
			for (int t = a; t >= earliest && starts[a] - starts[t] <= starts[b] - starts[a]; t--)
			{
				if (t < a && (!spline.smooths(t) || spline.roped()))
				{
					// not back past a step the client draws: its blend can't be shown at another time than the game's,
					// so a wind-up slowed over it would stand still and then jump; nor back along a rope's swing: its
					// head goes round on the way to the blow, which reads as turning round at every keyframe of it
					break;
				}
				float[] turning = spline.key(t);
				float[] arriving = t > 0 ? spline.drawn(t - 1, 1 - ActorDrawer.ARRIVING) : null;
				for (int j = Math.max(0, t - 1); j <= t; j++)
				{
					if (!goes(j))
					{
						continue;
					}
					float[] pushed = turning.clone();
					spline.push(pushed, j, j == t ? -ActorDrawer.ARRIVING : ActorDrawer.ARRIVING, true);
					double against = PartSpline.agree(turning, pushed, turning, landed)[0];
					double[] on = arriving == null ? new double[]{1, 0} : PartSpline.agree(arriving, turning, turning, pushed);
					if (against < -ActorDrawer.WIND_UP_AGREE && (on[0] > ActorDrawer.WIND_UP_AGREE || on[1] < ActorDrawer.ARRIVING_STILL))
					{
						windUp[b] = t;
						pullStep[b] = j;
						return;
					}
				}
			}
		}

		/** Whether step k goes the way its turn does: not where the client spins it and draws it. */
		private boolean goes(int k)
		{
			return spline.smooths(k) || !glitch(k);
		}

		int keyframes()
		{
			return lengths.length;
		}

		/** Learnt up to a keyframe before the animation's last (see full). */
		boolean cut()
		{
			return lengths.length < full;
		}

		/** Whether the client's blend of step k takes a part a strange way round (a flip, or a spin the long way). */
		boolean glitch(int k)
		{
			return k >= 0 && k < motion.glitch.length && motion.glitch[k] || spline.spins(k);
		}

		/** For a keyframe a blow ends in (a hit, or a slower strike), the keyframe the blow starts from; else -1. */
		int blowStart(int k)
		{
			return k > 0 && k < motion.blowStart.length ? motion.blowStart[k] : -1;
		}
	}

	/**
	 * Makes the spline from a finished lesson (up to the keyframe it reached, see Lesson.ready) and checks it against the
	 * client's blend. Pure maths: any thread.
	 */
	static Learnt learn(Lesson lesson)
	{
		int n = lesson.keysComplete() ? lesson.keys.length : lesson.ready();
		if (n < 2)
		{
			throw new IllegalStateException("animation " + lesson.animationId + " is not learnt yet");
		}
		float[][] keys = Arrays.copyOf(lesson.keys, n);
		int[] lengths = Arrays.copyOf(lesson.lengths, n);
		int steps = n - 1;
		float[][] mids = new float[steps][];
		double[] midAt = new double[steps];
		float[][][] client = new float[steps][][];
		double[][] at = new double[steps][];
		for (int k = 0; k < steps; k++)
		{
			int count = 0;
			for (float[] sample : lesson.samples[k])
			{
				count += sample != null ? 1 : 0;
			}
			if (count == 0)
			{
				continue;
			}
			client[k] = new float[count][];
			at[k] = new double[count];
			int i = 0;
			for (int slot = 0; slot < SAMPLE_AT.length; slot++)
			{
				if (lesson.samples[k][slot] != null)
				{
					client[k][i] = lesson.samples[k][slot];
					at[k][i] = lesson.sampleAt[k][slot];
					// the sample nearest half way tells MotionAnalysis how the client blends the step
					if (mids[k] == null || Math.abs(at[k][i] - 0.5) < Math.abs(midAt[k] - 0.5))
					{
						mids[k] = client[k][i];
						midAt[k] = at[k][i];
					}
					i++;
				}
			}
		}
		MotionAnalysis.Motion motion = Rules.swing(lesson.animationId, SkillSwings.onto(MotionAnalysis.analyze(keys, mids, midAt, lengths), lesson.lengths, n));
		double[] continuity = new double[n];
		for (int k = 0; k < continuity.length; k++)
		{
			// a hit is a corner: the strike arrives at full speed
			continuity[k] = k < motion.hit.length && motion.hit[k] ? -1 : 0;
		}
		// a rope's head is one part and its rope tied at both ends (see PartSpline.solid, tie)
		boolean hung = Rules.ropes(lesson.animationId);
		PartSpline spline = new PartSpline(keys, lengths, lesson.faces, continuity, 0, hung);
		// check lets go of the client's blend where the spline draws: a copy, the blend may be needed again
		boolean[] glitches = lesson.glitches;
		spline.accept(Rules.accepted(lesson.animationId, n - 1));
		spline.ropeGuard(Rules.ropes(lesson.animationId));
		spline.check(client.clone(), at, glitches);
		boolean[] clean = SkillSwings.clean(glitches, lesson.lengths, spline);
		if (clean != glitches)
		{
			glitches = clean;
			spline.check(client.clone(), at, glitches);
		}
		// a part that twists half round in a step (held, then a jump): its roll spread over the swing before it, if the
		// spline then draws all of it and still every step it drew before
		PartSpline.Spread spread = spline.spread();
		if (spread != null)
		{
			PartSpline spreadOut = new PartSpline(spread.keys(), lengths, lesson.faces, continuity, 0, hung);
			spreadOut.accept(Rules.accepted(lesson.animationId, n - 1));
			spreadOut.ropeGuard(Rules.ropes(lesson.animationId));
			spreadOut.check(spread.samples(client, at), at, glitches);
			boolean keeps = spread.drawnBy(spreadOut);
			for (int k = 0; keeps && k < spline.keyframes - 1; k++)
			{
				keeps = !spline.smooths(k) || spreadOut.smooths(k);
			}
			if (keeps)
			{
				spline = spreadOut;
			}
		}
		return new Learnt(spline, motion, lengths, lesson.vertices, lesson.lengths.length);
	}
}
