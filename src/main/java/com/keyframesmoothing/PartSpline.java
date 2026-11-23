package com.keyframesmoothing;

import java.util.Arrays;

/**
 * An action rebuilt from its keyframe poses (vertex positions only), as one smooth motion:
 * <ol>
 * <li>the model is split into parts that move as one (an arm, a sword): edges that keep their length, pieces that move
 * together; animators also stretch parts a little (a sword in a fast swing), so a part may stretch as well as turn;</li>
 * <li>each part's turn, stretch and place at each keyframe are fitted (turn by Horn's quaternion method);</li>
 * <li>between keyframes the turn follows a spline of rotations (always the short way round, so no flips, and limbs keep
 * their length), stretch and place follow splines too, all timed in ticks: Kochanek-Bartels tangents for uneven
 * keyframe lengths, eased to a stop where a part turns back, with a corner (full speed in, slow out) at a hit.</li>
 * </ol>
 * Vertices in no part, or in a part the action doesn't move (legs while walking come from the walk), are left to the
 * client's own blend.
 */
final class PartSpline
{
	/** Edge length change (model units) always counted as keeping its length. */
	private static final double EDGE_UNITS = 0.75;
	/** Edge length change (part of the length) counted as one part when grouping loosely, and strictly. */
	private static final double EDGE_LOOSE = 0.12;
	private static final double EDGE_STRICT = 0.015;
	/** A fitted part may miss a vertex by this much (model units, plus part of its distance from the centre). */
	private static final double FIT_UNITS = 1.0;
	private static final double FIT_PART = 0.03;
	/** A part moved less than this part of the action's largest move is left to the client (driven by another animation). */
	private static final double DRIVEN = 0.04;
	/** Parts this close (model units, keyframe 0) may meet at a joint. */
	private static final double JOINT_NEAR = 12;
	/**
	 * A joint may drift apart this much over the keyframes (model units) and still count as one: parts that stretch a
	 * little (a sword) don't turn about one exact point; what a joint misses at a keyframe is made up exactly there.
	 * Parts that are not joined miss by 10 and more.
	 */
	private static final double JOINT_SLACK = 6;

	final int keyframes;
	final int vertices;
	private final float[][] keys;
	/** Keyframe steps the spline breaks a rule in (see Rules): left to the client. Until checked, all are. */
	final boolean[] fallback;
	/** Vertex pairs for the rules: within a part, and touching across parts (keyframe 0). */
	final int[][] samePairs, nearPairs;
	/** Part of each vertex, or -1: left to the client. */
	final int[] partOf;
	/**
	 * For a vertex in no part that can be fitted (a whip's lash, stretched and bent): the moving part that carries it
	 * best, else -1. Such a vertex is blended in that part's frame, so it stays on it.
	 */
	final int[] follows;
	/**
	 * Carried vertices of parts that hardly move in the action (near a joint, or legs while standing): carried only while
	 * the character stands still, as walking moves them with the walk.
	 */
	final boolean[] quiet;
	/**
	 * Carried pieces (each turns as a whole in its carrier's frame, and bends on top): per vertex its piece and place in
	 * the piece at keyframe 0 (relative to the piece's centre, carrier frame); per keyframe and piece the turn, centre and
	 * speeds; per keyframe what the turn misses of each vertex (the bending), eased between keyframes.
	 */
	final int[] pieceOf;
	private final float[] pieceRest;
	private final double[][][] pieceRotation, pieceCentre, pieceTurn, pieceSpinOut, pieceSpinIn, pieceMoveOut, pieceMoveIn;
	private final float[][] pieceBend;
	final int parts;
	/** Per keyframe, what is held in two hands, found when first asked (see grip). */
	private int[][] grips;
	/** The model's height in keyframe 0. */
	private final double height;
	private final int[] lengths;
	final Fit fit;
	/** Every vertex's part, also of parts left to the client (they can still carry a part that moves). */
	final int[] group;
	/** Per part, its vertices (by group). */
	private final int[][] members;
	/** Parts left to the client that pose() measures: a moving part hangs on them, or they carry vertices. */
	private final boolean[] measuredParts;
	/** Where pose() puts the parts' vertices before writing them out; reused, per thread (objects are drawn on render threads). */
	private final ThreadLocal<float[][]> placing = ThreadLocal.withInitial(() -> new float[][]{new float[0]});
	/** What the per-frame methods work in (pose, push, keepGrip, rope, fling, measure); reused, per thread as placing. */
	private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

	/**
	 * Arrays the per-frame methods reuse instead of making new ones each frame. Each method has its own (measure's are
	 * used only inside it), so one that calls another never finds its arrays overwritten.
	 */
	private static final class Scratch
	{
		// measure
		final double[] cross = new double[9], eigen = new double[32];
		// poseSpline: per part its turn, stretch and centre (posed: it has them now)
		double[][] turn = new double[0][], stretch = new double[0][], centre = new double[0][];
		boolean[] posed = new boolean[0];
		final double[] spin = new double[3], middle = new double[3], off = new double[3], step = new double[4], piece = new double[4];
		final double[] r = new double[3], where = new double[3], carried = new double[3], turned = new double[3], local = new double[3];
		final double[] held = new double[7], stub = new double[3];
		// push: per part its turn, the point it turns about now and where that goes (spun: it turns), its parent as now
		// (seen: 0 not measured yet, 1 measured, 2 too few vertices)
		double[][] pushSpin = new double[0][], anchor = new double[0][], moved = new double[0][], now = new double[0][];
		boolean[] spun = new boolean[0];
		byte[] seen = new byte[0];
		final double[] pushR = new double[3], to = new double[3];
		// keepGrip
		final double[] from = new double[7], toward = new double[7], body = new double[7], grasp = new double[3], hand = new double[3];
		final double[] undo = new double[4], move = new double[4], gripR = new double[3], gripS = new double[3], shoulder = new double[3];
		final double[] gripSpin = new double[4], gripTo = new double[3];
		// rope: the chain's joints, each link's turn
		double[][] joints = new double[0][], turns = new double[0][];
		final double[] ropeHeld = new double[7], ropeR = new double[3], was = new double[3], dir = new double[3], bend = new double[4];
		final double[] ropeMoved = new double[3];
		// fling
		final double[] flingNow = new double[7], flingThen = new double[7], flingBack = new double[4], flingTurn = new double[4];
		final double[] flingShift = new double[3], flingOn = new double[3];
		// clientPose: the client's pose (standing false), the spline's poses either side
		float[] client = new float[0], before = new float[0], after = new float[0];

		/** Sizes the per-part arrays for parts parts. */
		void parts(int parts)
		{
			if (posed.length >= parts)
			{
				return;
			}
			turn = new double[parts][4];
			stretch = new double[parts][9];
			centre = new double[parts][3];
			posed = new boolean[parts];
			pushSpin = new double[parts][4];
			anchor = new double[parts][3];
			moved = new double[parts][3];
			now = new double[parts][7];
			spun = new boolean[parts];
			seen = new byte[parts];
		}

		/** Sizes the rope's arrays for a chain of links links. */
		void links(int links)
		{
			if (joints.length >= links + 1)
			{
				return;
			}
			joints = new double[links + 1][3];
			turns = new double[links][4];
		}
	}
	final boolean[] driven;
	/** The joint tree: each part's parent (-1 for a root), the joint point (keyframe 0 coordinates), and the order to place them. */
	final int[] parent;
	private final double[][] pivot;
	final int[] order;
	/** Per keyframe and part: what the joint misses of the fitted centre, and its speeds, so keyframes are met exactly. */
	private final double[][][] offset, offsetOut, offsetIn;
	/** Per keyframe: what the fitted parts miss of each vertex (x, y, z, ...), blended in so keyframes are met exactly. */
	private final float[][] residual;
	/** Per keyframe step and part: the turn as a rotation vector (world space, short way). */
	private final double[][][] turn;
	/** Per keyframe and part: speeds per tick when leaving and when arriving, for turn and place. */
	private final double[][][] spinOut, spinIn, moveOut, moveIn;

	/**
	 * @param keys       per keyframe, all vertices as x, y, z, ...
	 * @param lengths    keyframe lengths in ticks
	 * @param faces      triangles as vertex index triples (a, b, c, a, b, c, ...)
	 * @param continuity per keyframe: 0 smooth, -1 a corner (a hit)
	 * @param tension    0 = Catmull-Rom, towards 1 tighter
	 */
	PartSpline(float[][] keys, int[] lengths, int[] faces, double[] continuity, double tension)
	{
		this(keys, lengths, faces, continuity, tension, false);
	}

	/**
	 * Parts further apart at a keyframe than a joint allows (up to this) still hang on each other in a rope's animation
	 * (see Rules.ropes): a flail's head is rigged apart from its rope, the two only laid end to end at each keyframe, a
	 * little apart where the rope is drawn stretched. What the joint misses at a keyframe is made up there (offset), so
	 * between keyframes the head stays on the rope's end instead of going its own way.
	 */
	private static final double ROPE_SLACK = 90;
	/** Whether this is a rope's animation (see ROPE_SLACK). */
	private final boolean rope;

	/** @param rope a rope or chain's animation (see Rules.ropes): its loose head hangs on the rope's end */
	PartSpline(float[][] keys, int[] lengths, int[] faces, double[] continuity, double tension, boolean rope)
	{
		this.rope = rope;
		keyframes = keys.length;
		vertices = keys[0].length / 3;
		this.keys = keys;
		int step = Math.max(1, faces.length / 3 / 3000);
		int[] sampled = new int[2 * 3 * (faces.length / 3 / step + 1)];
		int count = 0;
		for (int f = 0; f + 2 < faces.length; f += 3 * step)
		{
			for (int i = 0; i < 3; i++)
			{
				sampled[count++] = faces[f + i];
				sampled[count++] = faces[f + (i + 1) % 3];
			}
		}
		edges = java.util.Arrays.copyOf(sampled, count);
		double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
		for (int i = 1; i < keys[0].length; i += 3)
		{
			low = Math.min(low, keys[0][i]);
			high = Math.max(high, keys[0][i]);
		}
		height = Math.max(0, high - low);
		this.lengths = lengths.clone();
		fallback = new boolean[Math.max(0, keys.length - 1)];
		Arrays.fill(fallback, true);

		int[] loose = components(keys, faces, EDGE_LOOSE);
		int[] strict = components(keys, faces, EDGE_STRICT);
		// loose groups that don't fit one turn and stretch (two parts and the joint between them) fall apart into strict pieces
		Fit looseFit = new Fit(keys, loose, count(loose));
		boolean[] fits = looseFit.fits();
		int[] piece = new int[vertices];
		int strictBase = count(loose);
		for (int v = 0; v < vertices; v++)
		{
			piece[v] = fits[loose[v]] ? loose[v] : strictBase + strict[v];
		}
		piece = renumber(piece);
		Fit pieceFit = new Fit(keys, piece, count(piece));
		boolean[] pieceFits = pieceFit.fits();
		// a piece that doesn't move as one (a rope's links hinged to each other, a flail's head with the end of its rope,
		// by edges that keep their length) falls apart into the bodies that do: vertices that keep their distance from
		// every other vertex of the body over every keyframe; what is left over (a body that scales) is one piece
		{
			int pieces = count(piece), next = pieces;
			for (int pc = 0; pc < pieces; pc++)
			{
				if (pieceFit.members[pc] < 6 || pieceFits[pc])
				{
					continue;
				}
				int[] bodies = rigidBodies(keys, piece, pc);
				int found = 0;
				for (int v = 0; v < vertices; v++)
				{
					found = Math.max(found, bodies[v] + 1);
				}
				for (int v = 0; v < vertices; v++)
				{
					if (bodies[v] > 0)
					{
						piece[v] = next + bodies[v] - 1;
					}
				}
				splits.append(String.format("  piece %d (%d): %d rigid bodies%n", pc, pieceFit.members[pc], found));
				next += Math.max(0, found - 1);
			}
			if (next > pieces)
			{
				piece = renumber(piece);
				pieceFit = new Fit(keys, piece, count(piece));
				pieceFits = pieceFit.fits();
			}
		}
		// what still doesn't move as one but mostly does: the motion of its best-fitting half is fitted again, and what
		// that misses comes off as a piece of its own, looked at again
		for (int round = 0; round < SPLIT_ROUNDS; round++)
		{
			int before = count(piece), next = before;
			for (int pc = 0; pc < before; pc++)
			{
				if (pieceFit.members[pc] < 6 || pieceFits[pc])
				{
					continue;
				}
				// fitted again on what the fit takes along, until that settles: what was fitted with a hinged bit let go of
				Fit coreFit = new Fit(keys, pieceFit.core(pc), 1);
				int in = 0, out = 0;
				for (int iteration = 0; iteration < CORE_ITERATIONS; iteration++)
				{
					int[] inliers = new int[vertices];
					Arrays.fill(inliers, -1);
					int was = in;
					in = 0;
					out = 0;
					for (int v = 0; v < vertices; v++)
					{
						if (piece[v] == pc)
						{
							inliers[v] = coreFit.inlier(0, v) ? 0 : -1;
							in += inliers[v] == 0 ? 1 : 0;
							out += inliers[v] == 0 ? 0 : 1;
						}
					}
					if (in == was || in < 3)
					{
						break;
					}
					coreFit = new Fit(keys, inliers, 1);
				}
				splits.append(String.format("  round %d piece %d (%d): core fits %d, misses %d%n", round, pc, pieceFit.members[pc], in, out));
				if (in < 3 || out == 0 || in < out)
				{
					continue;
				}
				for (int v = 0; v < vertices; v++)
				{
					if (piece[v] == pc && !coreFit.inlier(0, v))
					{
						piece[v] = next;
					}
				}
				next++;
			}
			if (next == before)
			{
				break;
			}
			piece = renumber(piece);
			pieceFit = new Fit(keys, piece, count(piece));
			pieceFits = pieceFit.fits();
		}
		// a rope's head (see solid) is one piece, though a keyframe or two stretch it and its mesh fell apart above
		head = rope ? solid(keys) : new int[0];
		if (head.length > 0)
		{
			int whole = count(piece);
			for (int v : head)
			{
				piece[v] = whole;
			}
			piece = renumber(piece);
			pieceFit = new Fit(keys, piece, count(piece));
			pieceFits = pieceFit.fits();
			pieceFits[piece[head[0]]] = true;
			splits.append(String.format("  head: %d vertices%n", head.length));
		}
		// pieces that move as one (a blade and its handle are separate pieces of mesh) are one part
		int pieces = count(piece);
		int[] joined = new int[pieces];
		for (int i = 0; i < pieces; i++)
		{
			joined[i] = i;
		}
		for (int a = 0; a < pieces; a++)
		{
			if (pieceFit.members[a] < 3 || !pieceFits[a])
			{
				continue;
			}
			for (int b = 0; b < pieces; b++)
			{
				if (b != a && pieceFit.members[b] >= 3 && pieceFits[b] && find(joined, a) != find(joined, b)
					&& pieceFit.carries(a, b) && pieceFit.carries(b, a))
				{
					union(joined, a, b);
				}
			}
		}
		int[] part = new int[vertices];
		for (int v = 0; v < vertices; v++)
		{
			int pc = piece[v];
			part[v] = pieceFit.members[pc] >= 3 && pieceFits[pc] ? find(joined, pc) : -1;
		}
		part = renumber(part);
		Fit partFit = new Fit(keys, part, count(part));
		// loose vertices go with the part they keep still in
		for (int v = 0; v < vertices; v++)
		{
			if (part[v] < 0)
			{
				part[v] = partFit.holding(v);
			}
		}
		// a piece of mesh that mostly doesn't fit (a whip's lash, stretched and bent) stays in one piece with the client;
		// one that mostly does (a flail's head with the end of its rope) keeps its parts, the rest is carried along
		int[] free = new int[count(loose)], held = new int[free.length];
		for (int v = 0; v < vertices; v++)
		{
			if (part[v] < 0)
			{
				free[loose[v]]++;
			}
			else
			{
				held[loose[v]]++;
			}
		}
		for (int v = 0; v < vertices; v++)
		{
			int l = loose[v];
			if (free[l] > 0 && held[l] > 0 && !fits[l] && held[l] < MOSTLY_HELD * free[l])
			{
				part[v] = -1;
			}
		}
		part = renumber(part);
		int partCount = count(part);
		fit = new Fit(keys, part, partCount);
		boolean[] partFits = fit.fits();
		if (head.length > 0 && part[head[0]] >= 0)
		{
			// what the stretched keyframes miss of it is blended back in (residual)
			partFits[part[head[0]]] = true;
		}

		// parts the action doesn't move
		double[] motion = new double[partCount];
		double most = 0;
		for (int p = 0; p < partCount; p++)
		{
			for (int k = 1; k < keyframes; k++)
			{
				double angle = Quaternions.angle(fit.rotation[k][p], fit.rotation[0][p]);
				double[] c0 = fit.centre[0][p], ck = fit.centre[k][p];
				double shift = Math.sqrt(sq(ck[0] - c0[0]) + sq(ck[1] - c0[1]) + sq(ck[2] - c0[2]));
				motion[p] = Math.max(motion[p], shift + angle * fit.reach[p]);
			}
			most = Math.max(most, motion[p]);
		}
		group = part.clone();
		int[] sizes = new int[partCount];
		for (int g : group)
		{
			if (g >= 0)
			{
				sizes[g]++;
			}
		}
		members = new int[partCount][];
		for (int p = 0; p < partCount; p++)
		{
			members[p] = new int[sizes[p]];
			sizes[p] = 0;
		}
		for (int v = 0; v < vertices; v++)
		{
			if (group[v] >= 0)
			{
				members[group[v]][sizes[group[v]]++] = v;
			}
		}
		driven = new boolean[partCount];
		boolean[] usable = new boolean[partCount];
		for (int p = 0; p < partCount; p++)
		{
			usable[p] = partFits[p] && fit.members[p] >= 3;
			driven[p] = usable[p] && motion[p] >= DRIVEN * most;
		}
		for (int v = 0; v < vertices; v++)
		{
			int p = part[v];
			if (p >= 0 && !driven[p])
			{
				part[v] = -1;
			}
		}
		partOf = part;
		parts = partCount;
		quiet = new boolean[vertices];
		pieceOf = new int[vertices];
		follows = carriers(keys, usable, pieceOf);

		samePairs = rigidPairs(keys, group);
		nearPairs = nearPairs(keys, group);

		// joints: where two parts meet, the point both carry to the same place in every keyframe
		parent = new int[partCount];
		Arrays.fill(parent, -1);
		pivot = new double[partCount][];
		order = tree(keys, usable);
		tie(keys);
		measuredParts = new boolean[partCount];
		for (int p = 0; p < partCount; p++)
		{
			if (driven[p] && parent[p] >= 0)
			{
				measuredParts[parent[p]] = true;
			}
		}
		for (int carrier : follows)
		{
			if (carrier >= 0)
			{
				measuredParts[carrier] = true;
			}
		}

		// what the fit misses of the keyframes, to be blended back in: in the part's own frame, so it turns and stretches
		// with the part (left in the world, it would stay behind while the part turns and bend it)
		residual = new float[keyframes][3 * vertices];
		for (int k = 0; k < keyframes; k++)
		{
			for (int v = 0; v < vertices; v++)
			{
				int p = partOf[v];
				if (p < 0)
				{
					continue;
				}
				double[] local = frameLocal(p, k, keys[k][3 * v], keys[k][3 * v + 1], keys[k][3 * v + 2]);
				residual[k][3 * v] = (float) (local[0] - fit.rest[3 * v]);
				residual[k][3 * v + 1] = (float) (local[1] - fit.rest[3 * v + 1]);
				residual[k][3 * v + 2] = (float) (local[2] - fit.rest[3 * v + 2]);
			}
		}
		offset = new double[keyframes][partCount][3];
		for (int k = 0; k < keyframes; k++)
		{
			for (int p = 0; p < partCount; p++)
			{
				if (parent[p] >= 0)
				{
					double[] carried = carried(p, fit.rotation[k][p], fit.stretch[k][p],
						framePlace(parent[p], k, pivot[p][0] - fit.centre[0][parent[p]][0], pivot[p][1] - fit.centre[0][parent[p]][1],
							pivot[p][2] - fit.centre[0][parent[p]][2]));
					// in the parent's frame, so it turns with the parent (left in the world, the joint would drift apart
					// while the parent turns)
					offset[k][p] = Quaternions.rotate(Quaternions.conjugate(frameTurn(parent[p], k)),
						fit.centre[k][p][0] - carried[0], fit.centre[k][p][1] - carried[1], fit.centre[k][p][2] - carried[2]);
				}
			}
		}

		// carried pieces: their turn and centre in the carrier's frame per keyframe, and what that misses (bending)
		int pieceCount = count(pieceOf);
		pieceRest = new float[3 * vertices];
		pieceRotation = new double[keyframes][pieceCount][];
		pieceCentre = new double[keyframes][pieceCount][3];
		pieceBend = new float[keyframes][3 * vertices];
		float[][] local = new float[keyframes][3 * vertices];
		int[] size = new int[pieceCount];
		for (int v = 0; v < vertices; v++)
		{
			int pc = pieceOf[v];
			if (pc < 0)
			{
				continue;
			}
			size[pc]++;
			for (int k = 0; k < keyframes; k++)
			{
				double[] at = carrierLocal(follows[v], k, keys[k][3 * v], keys[k][3 * v + 1], keys[k][3 * v + 2]);
				local[k][3 * v] = (float) at[0];
				local[k][3 * v + 1] = (float) at[1];
				local[k][3 * v + 2] = (float) at[2];
			}
		}
		for (int k = 0; k < keyframes; k++)
		{
			for (int v = 0; v < vertices; v++)
			{
				int pc = pieceOf[v];
				if (pc >= 0)
				{
					for (int axis = 0; axis < 3; axis++)
					{
						pieceCentre[k][pc][axis] += local[k][3 * v + axis] / size[pc];
					}
				}
			}
		}
		for (int v = 0; v < vertices; v++)
		{
			int pc = pieceOf[v];
			if (pc >= 0)
			{
				for (int axis = 0; axis < 3; axis++)
				{
					pieceRest[3 * v + axis] = (float) (local[0][3 * v + axis] - pieceCentre[0][pc][axis]);
				}
			}
		}
		for (int k = 0; k < keyframes; k++)
		{
			double[][] cross = new double[pieceCount][9];
			for (int v = 0; v < vertices; v++)
			{
				int pc = pieceOf[v];
				if (pc >= 0)
				{
					double[] c = pieceCentre[k][pc];
					Fit.outer(cross[pc], pieceRest[3 * v], pieceRest[3 * v + 1], pieceRest[3 * v + 2],
						local[k][3 * v] - c[0], local[k][3 * v + 1] - c[1], local[k][3 * v + 2] - c[2]);
				}
			}
			for (int pc = 0; pc < pieceCount; pc++)
			{
				double[] q = size[pc] >= 3 ? Quaternions.fit(cross[pc]) : new double[]{1, 0, 0, 0};
				if (k > 0 && Quaternions.dot(q, pieceRotation[k - 1][pc]) < 0)
				{
					q = Quaternions.negate(q);
				}
				pieceRotation[k][pc] = q;
			}
			for (int v = 0; v < vertices; v++)
			{
				int pc = pieceOf[v];
				if (pc >= 0)
				{
					double[] r = Quaternions.rotate(pieceRotation[k][pc], pieceRest[3 * v], pieceRest[3 * v + 1], pieceRest[3 * v + 2]);
					double[] c = pieceCentre[k][pc];
					for (int axis = 0; axis < 3; axis++)
					{
						pieceBend[k][3 * v + axis] = (float) (local[k][3 * v + axis] - r[axis] - c[axis]);
					}
				}
			}
		}
		pieceTurn = new double[Math.max(0, keyframes - 1)][pieceCount][];
		for (int k = 0; k < keyframes - 1; k++)
		{
			for (int pc = 0; pc < pieceCount; pc++)
			{
				pieceTurn[k][pc] = Quaternions.log(Quaternions.multiply(pieceRotation[k + 1][pc], Quaternions.conjugate(pieceRotation[k][pc])));
			}
		}
		pieceSpinOut = new double[keyframes][pieceCount][3];
		pieceSpinIn = new double[keyframes][pieceCount][3];
		pieceMoveOut = new double[keyframes][pieceCount][3];
		pieceMoveIn = new double[keyframes][pieceCount][3];
		for (int k = 0; k < keyframes; k++)
		{
			int before = k > 0 ? lengths[k - 1] : 0, after = k < keyframes - 1 ? lengths[k] : 0;
			for (int pc = 0; pc < pieceCount; pc++)
			{
				for (int axis = 0; axis < 3; axis++)
				{
					double[] spin = tangents(k > 0 ? pieceTurn[k - 1][pc][axis] / before : Double.NaN,
						k < keyframes - 1 ? pieceTurn[k][pc][axis] / after : Double.NaN, before, after, 1, 1);
					pieceSpinOut[k][pc][axis] = spin[0];
					pieceSpinIn[k][pc][axis] = spin[1];
					double[] move = tangents(k > 0 ? (pieceCentre[k][pc][axis] - pieceCentre[k - 1][pc][axis]) / before : Double.NaN,
						k < keyframes - 1 ? (pieceCentre[k + 1][pc][axis] - pieceCentre[k][pc][axis]) / after : Double.NaN, before, after, 1, 1);
					pieceMoveOut[k][pc][axis] = move[0];
					pieceMoveIn[k][pc][axis] = move[1];
				}
			}
		}
		for (int k = 0; k < keyframes - 1; k++)
		{
			for (int pc = 0; pc < pieceCount; pc++)
			{
				for (int axis = 0; axis < 3; axis++)
				{
					monotone(pieceSpinOut[k][pc], pieceSpinIn[k + 1][pc], axis, pieceTurn[k][pc][axis] / lengths[k]);
					monotone(pieceMoveOut[k][pc], pieceMoveIn[k + 1][pc], axis,
						(pieceCentre[k + 1][pc][axis] - pieceCentre[k][pc][axis]) / lengths[k]);
				}
			}
		}

		// turns per step, speeds per keyframe
		int steps = keyframes - 1;
		turn = new double[Math.max(0, steps)][partCount][];
		for (int k = 0; k < steps; k++)
		{
			for (int p = 0; p < partCount; p++)
			{
				turn[k][p] = Quaternions.log(Quaternions.multiply(fit.rotation[k + 1][p], Quaternions.conjugate(fit.rotation[k][p])));
			}
		}
		spinOut = new double[keyframes][partCount][3];
		spinIn = new double[keyframes][partCount][3];
		moveOut = new double[keyframes][partCount][3];
		moveIn = new double[keyframes][partCount][3];
		offsetOut = new double[keyframes][partCount][3];
		offsetIn = new double[keyframes][partCount][3];
		for (int k = 0; k < keyframes; k++)
		{
			double a = (1 - tension) * (1 + continuity[k]), b = (1 - tension) * (1 - continuity[k]);
			int before = k > 0 ? lengths[k - 1] : 0, after = k < steps ? lengths[k] : 0;
			for (int p = 0; p < partCount; p++)
			{
				for (int axis = 0; axis < 3; axis++)
				{
					double[] spin = tangents(k > 0 ? turn[k - 1][p][axis] / before : Double.NaN,
						k < steps ? turn[k][p][axis] / after : Double.NaN, before, after, a, b);
					spinOut[k][p][axis] = spin[0];
					spinIn[k][p][axis] = spin[1];
					double[] move = tangents(k > 0 ? (fit.centre[k][p][axis] - fit.centre[k - 1][p][axis]) / before : Double.NaN,
						k < steps ? (fit.centre[k + 1][p][axis] - fit.centre[k][p][axis]) / after : Double.NaN, before, after, a, b);
					moveOut[k][p][axis] = move[0];
					moveIn[k][p][axis] = move[1];
					double[] off = tangents(k > 0 ? (offset[k][p][axis] - offset[k - 1][p][axis]) / before : Double.NaN,
						k < steps ? (offset[k + 1][p][axis] - offset[k][p][axis]) / after : Double.NaN, before, after, 1, 1);
					offsetOut[k][p][axis] = off[0];
					offsetIn[k][p][axis] = off[1];
				}
			}
		}
		// within each step, no overshoot on any axis
		for (int k = 0; k < steps; k++)
		{
			for (int p = 0; p < partCount; p++)
			{
				for (int axis = 0; axis < 3; axis++)
				{
					monotone(spinOut[k][p], spinIn[k + 1][p], axis, turn[k][p][axis] / lengths[k]);
					monotone(moveOut[k][p], moveIn[k + 1][p], axis, (fit.centre[k + 1][p][axis] - fit.centre[k][p][axis]) / lengths[k]);
					monotone(offsetOut[k][p], offsetIn[k + 1][p], axis, (offset[k + 1][p][axis] - offset[k][p][axis]) / lengths[k]);
				}
			}
		}
	}

	/**
	 * Checks each keyframe step against the client's blend at a few points (see Rules) and leaves the steps that break a
	 * rule to the client. Steps without samples stay with the client.
	 *
	 * @param client per step, the client's blend at the points in at (null: not seen)
	 * @param at     per step, how far along each sample is (0..1, in order)
	 * @return how many steps the spline keeps
	 */
	int check(float[][][] client, double[][] at)
	{
		return check(client, at, null);
	}

	/**
	 * As check(client, at); glitches (when given) per step whether the client's blend of it is known to go wrong (the
	 * keyframe table: a part turning the wrong way round, a jump), which the check may not see: there the client is the
	 * glitch, and the spline is held to the same limits as where the client spins a part the long way round.
	 */
	int check(float[][][] client, double[][] at, boolean[] glitches)
	{
		why = new String[Math.max(0, keyframes - 1)];
		spin = new boolean[Math.max(0, keyframes - 1)];
		twisted = new int[Math.max(0, keyframes - 1)];
		Arrays.fill(twisted, -1);
		clientSamples = client;
		clientAt = at;
		int kept = 0;
		for (int k = 0; k < keyframes - 1; k++)
		{
			fallback[k] = true;
			if (client[k] == null || at[k] == null)
			{
				continue;
			}
			fallback[k] = false;
			float[][] ours = new float[Rules.DENSE + 1][];
			for (int i = 0; i <= Rules.DENSE; i++)
			{
				double s = i / (double) Rules.DENSE;
				// parts left to the client: its blend, between the points it is known at
				ours[i] = clientBetween(keys[k], keys[k + 1], at[k], client[k], s);
				// the rope as its links hang, not laid (see rope): laid, it leaves the keyframes on purpose
				laying = false;
				pose(ours[i], k, s);
				laying = true;
			}
			int[] part = {-1};
			double twist = twist(keys[k], keys[k + 1], lengths[k], part);
			twisted[k] = part[0];
			spin[k] = clientSpin(keys[k], keys[k + 1], client[k]) > SPIN_DEGREES
				|| glitches != null && k < glitches.length && glitches[k] || twist > 0;
			why[k] = twist > 0 ? String.format("twist %.0f degrees", twist)
				: Rules.broken(keys[k], keys[k + 1], at[k], client[k], ours, unlaid(samePairs), unlaid(nearPairs), spin[k], height, arcs(k));
			if (why[k] != null && accepted != null && k < accepted.length && accepted[k])
			{
				// the client's blend is the worse here (see Rules.ACCEPTED): kept unless it tears a rope
				why[k] = null;
			}
			if (why[k] == null && ropeGuard)
			{
				why[k] = stretched(keys[k], keys[k + 1], client[k], ours);
			}
			fallback[k] = why[k] != null;
			kept += fallback[k] ? 0 : 1;
		}
		// the client's blend is only drawn from where a step is left to it (clientPose): the rest is let go
		for (int k = 0; k < keyframes - 1; k++)
		{
			if (!fallback[k])
			{
				clientSamples[k] = null;
			}
		}
		return kept;
	}

	/** Roughly the memory this holds (bytes), for the plugin's store of learnt animations. */
	long bytes()
	{
		// per keyframe the keys, what the parts miss and the pieces' bending, all vertices
		long floats = 3L * vertices * (3L * keyframes + 1);
		for (int k = 0; clientSamples != null && k < clientSamples.length; k++)
		{
			for (int i = 0; clientSamples[k] != null && i < clientSamples[k].length; i++)
			{
				floats += clientSamples[k][i] == null ? 0 : clientSamples[k][i].length;
			}
		}
		// small arrays (a turn, a centre, a speed): per keyframe 7 per carried piece and 11 per part, some 48 bytes each
		int pieces = pieceRotation.length == 0 ? 0 : pieceRotation[0].length;
		long small = (long) keyframes * (7L * pieces + 11L * parts);
		return 4 * floats + 48 * small + 24L * (samePairs.length + nearPairs.length) + 12L * vertices;
	}

	/**
	 * Per vertex, how much longer than straight it may swing over step k: on a part hanging on a parent that turns by a,
	 * an arc (a/2)/sin(a/2) times its chord; else 1. See Rules.detour.
	 */
	private double[] arcs(int k)
	{
		double[] arc = new double[vertices];
		Arrays.fill(arc, 1);
		for (int v = 0; v < vertices; v++)
		{
			int p = partOf[v] >= 0 ? partOf[v] : follows[v];
			int up = p >= 0 ? parent[p] : -1;
			if (up >= 0 && turn[k][up] != null)
			{
				double[] t = turn[k][up];
				double angle = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
				if (rope && turn[k][p] != null)
				{
					// a rope's head is far out from the joint it turns about: its own turn swings it along an arc too
					double[] own = turn[k][p];
					angle = Math.max(angle, Math.sqrt(own[0] * own[0] + own[1] * own[1] + own[2] * own[2]));
				}
				arc[v] = angle < 1e-6 ? 1 : (angle / 2) / Math.sin(Math.min(Math.PI, angle) / 2);
			}
		}
		return arc;
	}

	/** The client's blend at s, straight between the keyframes and the points it is known at. */
	private static float[] clientBetween(float[] keyA, float[] keyB, double[] at, float[][] client, double s)
	{
		double fromAt = 0, toAt = 1;
		float[] from = keyA, to = keyB;
		for (int i = 0; i < at.length; i++)
		{
			if (at[i] <= s && at[i] >= fromAt)
			{
				fromAt = at[i];
				from = client[i];
			}
			if (at[i] >= s && at[i] <= toAt)
			{
				toAt = at[i];
				to = client[i];
			}
		}
		double w = toAt > fromAt ? (s - fromAt) / (toAt - fromAt) : 0;
		float[] out = new float[from.length];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = (float) (from[i] + (to[i] - from[i]) * w);
		}
		return out;
	}

	/**
	 * A moving part turning at least TWIST_DEGREES in one step about its own length, the way it points hardly changing
	 * (TWIST_ALONG: the cosine of the turn's axis with the part's length), at least TWIST_PACE degrees a tick: Dharok's
	 * axe turned half round on its shaft in the last two ticks of its swing. Any blend of that is a sudden twirl of the
	 * blade, a spasm: such a step is not blended, the pose held and then a jump into the next, as the game shows it. (The
	 * same half turn over six ticks, as the axe comes round low, reads as a turn and stays.)
	 */
	static final double TWIST_DEGREES = 150, TWIST_ALONG = 0.7, TWIST_PACE = 60;

	/**
	 * The most a moving part twists about its own length in a step (degrees, see TWIST_DEGREES), else 0; part (when
	 * given) gets that part.
	 */
	private double twist(float[] keyA, float[] keyB, int ticks, int[] part)
	{
		double worst = 0;
		for (int p = 0; p < parts; p++)
		{
			double degrees = twistOf(keyA, keyB, ticks, p);
			if (degrees > worst)
			{
				worst = degrees;
				if (part != null)
				{
					part[0] = p;
				}
			}
		}
		return worst;
	}

	/** How far part p twists about its own length in a step (degrees, see TWIST_DEGREES), else 0. */
	private double twistOf(float[] keyA, float[] keyB, int ticks, int p)
	{
		if (!driven[p] || fit.members[p] < SPIN_PART)
		{
			return 0;
		}
		double[] first = measure(keyA, p), last = measure(keyB, p);
		if (first == null || last == null)
		{
			return 0;
		}
		double degrees = Math.toDegrees(Quaternions.angle(first, last));
		if (degrees < TWIST_DEGREES || degrees < TWIST_PACE * Math.max(1, ticks))
		{
			return 0;
		}
		double[] turn = Quaternions.log(Quaternions.multiply(last, Quaternions.conjugate(first)));
		double[] along = length(keyA, p);
		double size = Math.sqrt(turn[0] * turn[0] + turn[1] * turn[1] + turn[2] * turn[2]);
		return size > 0 && Math.abs(turn[0] * along[0] + turn[1] * along[1] + turn[2] * along[2]) / size >= TWIST_ALONG ? degrees : 0;
	}

	/** The way part p is longest in a pose: a unit vector (the main axis of its vertices' spread). */
	private double[] length(float[] pose, int p)
	{
		int[] own = members[p];
		double cx = 0, cy = 0, cz = 0;
		for (int v : own)
		{
			cx += pose[3 * v];
			cy += pose[3 * v + 1];
			cz += pose[3 * v + 2];
		}
		cx /= own.length;
		cy /= own.length;
		cz /= own.length;
		double[][] spread = new double[3][3];
		for (int v : own)
		{
			double[] d = {pose[3 * v] - cx, pose[3 * v + 1] - cy, pose[3 * v + 2] - cz};
			for (int i = 0; i < 3; i++)
			{
				for (int j = 0; j < 3; j++)
				{
					spread[i][j] += d[i] * d[j];
				}
			}
		}
		double[] axis = {1, 1, 1};
		for (int round = 0; round < 50; round++)
		{
			double[] next = new double[3];
			for (int i = 0; i < 3; i++)
			{
				next[i] = spread[i][0] * axis[0] + spread[i][1] * axis[1] + spread[i][2] * axis[2];
			}
			double size = Math.sqrt(next[0] * next[0] + next[1] * next[1] + next[2] * next[2]);
			if (size < 1e-12)
			{
				return new double[]{0, 0, 0};
			}
			axis = new double[]{next[0] / size, next[1] / size, next[2] / size};
		}
		return axis;
	}

	/** Per step, the part that twists in it (see twist), else -1 (seen in check). */
	private int[] twisted = new int[0];

	/**
	 * A swing goes on into the next step while its part's vertices keep going the same way: the cosine between their
	 * moves over the two steps (see goesOn).
	 */
	static final double SWING_ON = 0.3;

	/**
	 * Where check found a step that twists a part about its own length (left to the client: the pose held, then a jump),
	 * that twist spread evenly over the ticks of the swing the step ends, as a wrist rolls the weapon on its way: Dharok's
	 * axe turns half round on its shaft in the last two ticks of its swing; over the whole upswing that is some 22 degrees
	 * a tick and it arrives turned. The swing: back from the twisting step over the steps the spline draws for as long as
	 * the part goes on the same way (SWING_ON). Only the roll about the part's length is spread, about that length
	 * where it passes its joint (the hand): the swing stays where the keyframes have it, and the joint about holds. Null
	 * when no step twists a part, or none has a swing before it.
	 */
	Spread spread()
	{
		java.util.List<Twist> found = new java.util.ArrayList<>();
		for (int k = 0; k < twisted.length; k++)
		{
			int p = twisted[k];
			if (p < 0)
			{
				continue;
			}
			int from = k;
			// the step before always (the twisting step's own move is mostly the twist), on from there while it goes on
			while (from > 0 && smooths(from - 1) && twisted[from - 1] < 0 && (from == k || goesOn(from - 1, p)))
			{
				from--;
			}
			double[] first = measure(keys[k], p), last = measure(keys[k + 1], p);
			if (from == k || first == null || last == null)
			{
				continue;
			}
			// the part it hangs on that twists along with it (the hand with the axe): turned with all that hangs on it
			int root = p;
			while (parent[root] >= 0 && twistOf(keys[k], keys[k + 1], lengths[k], parent[root]) > 0)
			{
				root = parent[root];
			}
			// of the step's turn of the part only the roll about its length (in its rest frame): the swing of that length
			// stays where the keyframes have it (Dharok's axe still swings out wide before it turns)
			double[] step = Quaternions.multiply(Quaternions.conjugate(first), last);
			if (step[0] < 0)
			{
				step = Quaternions.negate(step);
			}
			double[] along = length(keys[k], p);
			double[] axis = Quaternions.rotate(Quaternions.conjugate(first), along[0], along[1], along[2]);
			double angle = 2 * Math.atan2(step[1] * axis[0] + step[2] * axis[1] + step[3] * axis[2], step[0]);
			// about the part's length line where it passes the root's joint with what it hangs on (keyframe 0 is the rest
			// frame, about the part's centre), so the part's length and that joint about hold; without one its centre
			double[] pivotAt = new double[3];
			if (parent[root] >= 0)
			{
				double[] centre = fit.centre[0][p];
				double out = (pivot[root][0] - centre[0]) * axis[0] + (pivot[root][1] - centre[1]) * axis[1]
					+ (pivot[root][2] - centre[2]) * axis[2];
				pivotAt = new double[]{axis[0] * out, axis[1] * out, axis[2] * out};
			}
			java.util.List<Integer> turned = new java.util.ArrayList<>();
			for (int v = 0; v < vertices; v++)
			{
				int own = group[v] >= 0 ? group[v] : follows[v];
				if (own >= 0 && under(own, root))
				{
					turned.add(v);
				}
			}
			found.add(new Twist(p, root, from, k, angle, axis, pivotAt, turned.stream().mapToInt(Integer::intValue).toArray()));
		}
		return found.isEmpty() ? null : new Spread(found);
	}

	/** Whether part p's vertices go on the same way from step k into step k + 1 (see SWING_ON). */
	private boolean goesOn(int k, int p)
	{
		double dot = 0, one = 0, other = 0;
		for (int v : members[p])
		{
			for (int axis = 0; axis < 3; axis++)
			{
				double a = keys[k + 1][3 * v + axis] - keys[k][3 * v + axis], b = keys[k + 2][3 * v + axis] - keys[k + 1][3 * v + axis];
				dot += a * b;
				one += a * a;
				other += b * b;
			}
		}
		return one > 0 && other > 0 && dot / Math.sqrt(one * other) >= SWING_ON;
	}

	/** Whether part q is part p or hangs on it (down the joint tree). */
	private boolean under(int q, int p)
	{
		for (int at = q; at >= 0; at = parent[at])
		{
			if (at == p)
			{
				return true;
			}
		}
		return false;
	}

	/** A part's twist spread back over a swing: see spread. */
	private static final class Twist
	{
		final int part, root, from, step;
		/**
		 * The step's roll of the part (radians, about axis, its length, in its rest frame), turning the root (the part,
		 * or what it hangs on that twists along) about pivot, on that length, in the part's rest frame.
		 */
		final double angle;
		final double[] axis, pivot;
		/** The root's vertices and those hanging on it. */
		final int[] turned;

		Twist(int part, int root, int from, int step, double angle, double[] axis, double[] pivot, int[] turned)
		{
			this.part = part;
			this.root = root;
			this.from = from;
			this.step = step;
			this.angle = angle;
			this.axis = axis;
			this.pivot = pivot;
			this.turned = turned;
		}
	}

	/** Twists spread back over their swings (see spread): applied to the keyframes, and to the client's blend between them. */
	final class Spread
	{
		private final java.util.List<Twist> twists;

		private Spread(java.util.List<Twist> twists)
		{
			this.twists = twists;
		}

		/** The keyframes with the twists spread. */
		float[][] keys()
		{
			float[][] out = new float[keyframes][];
			for (int k = 0; k < keyframes; k++)
			{
				out[k] = at(keys[k], k, 0);
			}
			return out;
		}

		/**
		 * The client's blend (per step, its poses at at) with the twists spread. In a twisting step itself it stays as
		 * it is: what the twist was spread from.
		 */
		float[][][] samples(float[][][] client, double[][] at)
		{
			float[][][] out = new float[client.length][][];
			for (int k = 0; k < client.length; k++)
			{
				if (client[k] == null)
				{
					continue;
				}
				out[k] = new float[client[k].length][];
				for (int i = 0; i < client[k].length; i++)
				{
					out[k][i] = at(client[k][i], k, at[k][i]);
				}
			}
			return out;
		}

		/** Whether a spline made from the spread keyframes draws every step a twist was spread over, the twisting one too. */
		boolean drawnBy(PartSpline spline)
		{
			for (Twist t : twists)
			{
				for (int k = t.from; k <= t.step; k++)
				{
					if (!spline.smooths(k))
					{
						return false;
					}
				}
			}
			return true;
		}

		/**
		 * A pose of step k, s of the way, with the twists turned as far as they are spread there (in a twisting step
		 * only at its start, its keyframe).
		 */
		private float[] at(float[] pose, int k, double s)
		{
			float[] out = pose.clone();
			for (Twist t : twists)
			{
				if (k < t.from || k > t.step || k == t.step && s > 0)
				{
					continue;
				}
				double into = lengths[k] * s, total = 0;
				for (int j = t.from; j <= t.step; j++)
				{
					into += j < k ? lengths[j] : 0;
					total += lengths[j];
				}
				double[] frame = measure(pose, t.part);
				if (frame == null)
				{
					continue;
				}
				double[] facing = {frame[0], frame[1], frame[2], frame[3]};
				double[] axis = Quaternions.rotate(facing, t.axis[0], t.axis[1], t.axis[2]);
				double[] arm = Quaternions.rotate(facing, t.pivot[0], t.pivot[1], t.pivot[2]);
				double px = frame[4] + arm[0], py = frame[5] + arm[1], pz = frame[6] + arm[2];
				double angle = t.angle * into / total;
				double[] turn = Quaternions.exp(new double[]{axis[0] * angle, axis[1] * angle, axis[2] * angle});
				for (int v : t.turned)
				{
					double[] r = Quaternions.rotate(turn, pose[3 * v] - px, pose[3 * v + 1] - py, pose[3 * v + 2] - pz);
					out[3 * v] = (float) (r[0] + px);
					out[3 * v + 1] = (float) (r[1] + py);
					out[3 * v + 2] = (float) (r[2] + pz);
				}
			}
			return out;
		}
	}

	/** A part turning this much (degrees) further than the direct turn between two keyframes: the client spins it. */
	static final double SPIN_DEGREES = 45;
	/** Per step, whether the client spins a moving part the long way round (seen in check). */
	private boolean[] spin = new boolean[0];

	/**
	 * Whether the client's blend of step k spins a moving part the long way round: turning each angle on its own, it
	 * can take a part through most of a turn more than the two keyframes need (the Voidwaker's special attack: 308
	 * degrees for a 170 degree swing). Where the spline does not take such a step, it is better not blended.
	 */
	boolean spins(int k)
	{
		return k >= 0 && k < spin.length && spin[k];
	}

	/** How much further (degrees) than directly the client turns the moving part that strays most, through its samples. */
	private double clientSpin(float[] keyA, float[] keyB, float[][] samples)
	{
		double worst = 0;
		for (int p = 0; p < parts; p++)
		{
			if (!driven[p] || fit.members[p] < SPIN_PART)
			{
				continue;
			}
			double[] first = measure(keyA, p), last = measure(keyB, p);
			if (first == null || last == null)
			{
				continue;
			}
			double way = 0;
			double[] before = first;
			for (int i = 0; i <= samples.length; i++)
			{
				double[] now = i < samples.length ? measure(samples[i], p) : last;
				if (now == null)
				{
					way = 0;
					break;
				}
				way += Quaternions.angle(before, now);
				before = now;
			}
			worst = Math.max(worst, Math.toDegrees(way - Quaternions.angle(first, last)));
		}
		return worst;
	}

	/** A part travelling at most this many times the median part's travel over the animation can be the root. */
	private static final double ROOT_TRAVEL = 2;
	/** A hand nearer a tool than this (model units) holds it (see grip). */
	private static final double GRIP_TOUCH = 6;
	private static final int[] NO_GRIP = {};
	/** A loose piece of mesh keeps its fitted parts when they hold at least this many times the vertices left loose. */
	private static final double MOSTLY_HELD = 2;
	/** Times a piece that doesn't move as one is split into what its best-fitting half carries and the rest. */
	private static final int SPLIT_ROUNDS = 3;
	/** Times the fit of that half is made again on what it takes along. */
	private static final int CORE_ITERATIONS = 4;

	/** Parts smaller than this (vertices) are not looked at for spins: a few vertices fit a turn poorly. */
	private static final int SPIN_PART = 12;

	/** Per step, whether the spline is kept though it breaks the bend or joint rules (see Rules.ACCEPTED), or null. */
	private boolean[] accepted;

	void accept(boolean[] steps)
	{
		accepted = steps;
	}

	/** Triangle edges (vertex pairs, a, b, a, b, ...) the stretch rule is measured on: a sample of the model's. */
	private int[] edges = new int[0];
	/** No edge may get longer than this many times its longest length at the keyframes and the client's samples. */
	static final double STRETCH = 4.0;
	static final double STRETCH_UNITS = 2;
	/** A pushed pose's edges may be this many times their keyframe length (see room). */
	static final double STRETCH_PUSH = 1.15;
	/** Whether ropes are guarded (see Rules.ROPES): steps and pushes that stretch them are left to the client / shortened. */
	private boolean ropeGuard;

	void ropeGuard(boolean on)
	{
		ropeGuard = on;
	}

	/**
	 * A rope or chain pulled apart by the spline (its links stretching far past what the keyframes and the client's blend
	 * make of them): which step breaks that, or null.
	 */
	private String stretched(float[] keyA, float[] keyB, float[][] client, float[][] ours)
	{
		for (int e = 0; e + 1 < edges.length; e += 2)
		{
			int a = edges[e], b = edges[e + 1];
			if (tieEnd.length > 0 && tiedEnd[a] && tiedEnd[b])
			{
				// the end of a tied rope is held where it is tied (see tie): it can't run off, and where the keyframes
				// fold it back on itself at the hand it opens a little more than the client's blend does
				continue;
			}
			double allowed = Math.max(length(keyA, a, b), length(keyB, a, b));
			for (float[] sample : client)
			{
				allowed = Math.max(allowed, length(sample, a, b));
			}
			allowed = allowed * STRETCH + STRETCH_UNITS;
			for (float[] pose : ours)
			{
				if (length(pose, a, b) > allowed)
				{
					return String.format("rope stretched %.1f (allowed %.1f) edge %d-%d", length(pose, a, b), allowed, a, b);
				}
			}
		}
		return null;
	}

	private static double length(float[] pose, int a, int b)
	{
		return Math.sqrt(sq(pose[3 * a] - pose[3 * b]) + sq(pose[3 * a + 1] - pose[3 * b + 1]) + sq(pose[3 * a + 2] - pose[3 * b + 2]));
	}

	/** Per step, the rule the spline breaks there (see Rules.broken), for checks. */
	private String[] why = new String[0];

	String why(int k)
	{
		return k < why.length ? why[k] : null;
	}

	/** The client's blend as given to check, kept for clientPose. */
	private float[][][] clientSamples;
	private double[][] clientAt;

	/**
	 * Writes the client's blend of step k at s into out (every vertex), along a curve through the points it was seen at
	 * (straight between them, a part turning fast would shrink as it turns), and on through the nearest ones of the
	 * steps either side, so the speed carries over at the keyframes; at the last keyframe that keyframe. Where the client
	 * spins a part the long way round or the step twists one (see spins) not that blend: keyframe k held, then the jump
	 * into the next (as the game shows it). False when step k was never seen.
	 */
	boolean clientPose(float[] out, int k, double s)
	{
		if (k >= keyframes - 1)
		{
			// held there, or (s above 0) as the client blends a loop back into the first keyframe
			float[] last = keys[keyframes - 1], into = keys[0];
			for (int i = 0; i < last.length; i++)
			{
				out[i] = s > 0 ? (float) (last[i] + (into[i] - last[i]) * Math.min(1, s)) : last[i];
			}
			return true;
		}
		if (spins(k))
		{
			System.arraycopy(keys[k], 0, out, 0, keys[k].length);
			return true;
		}
		if (k < 0 || clientSamples == null || clientSamples[k] == null)
		{
			return false;
		}
		// the points, in ticks from the step's start; of a step either side the spline draws, a pose of the spline
		Scratch work = scratch.get();
		if (work.before.length != keys[0].length)
		{
			work.before = new float[keys[0].length];
			work.after = new float[keys[0].length];
		}
		float[] before = k <= 0 ? null : !smooths(k - 1) ? last(clientSamples[k - 1]) : splinePose(k - 1, 1 - NEIGHBOUR, work.before);
		float[] after = k + 1 >= keyframes - 1 ? null : !smooths(k + 1) ? first(clientSamples[k + 1]) : splinePose(k + 1, NEIGHBOUR, work.after);
		int own = clientSamples[k].length, count = own + 2 + (before != null ? 1 : 0) + (after != null ? 1 : 0);
		double[] at = new double[count];
		float[][] seen = new float[count][];
		int i = 0;
		if (before != null)
		{
			at[i] = -(1 - (smooths(k - 1) ? 1 - NEIGHBOUR : clientAt[k - 1][clientAt[k - 1].length - 1])) * lengths[k - 1];
			seen[i++] = before;
		}
		int first = i;
		at[i] = 0;
		seen[i++] = keys[k];
		for (int j = 0; j < own; j++)
		{
			at[i] = clientAt[k][j] * lengths[k];
			seen[i++] = clientSamples[k][j];
		}
		at[i] = lengths[k];
		seen[i++] = keys[k + 1];
		int end = i - 1;
		if (after != null)
		{
			at[i] = lengths[k] + (smooths(k + 1) ? NEIGHBOUR : clientAt[k + 1][0]) * lengths[k + 1];
			seen[i] = after;
		}
		double tick = s * lengths[k];
		i = first;
		while (i < end - 1 && tick > at[i + 1])
		{
			i++;
		}
		double h = at[i + 1] - at[i];
		if (h <= 0)
		{
			System.arraycopy(seen[i], 0, out, 0, seen[i].length);
			return true;
		}
		double u = Math.max(0, Math.min(1, (tick - at[i]) / h)), u2 = u * u, u3 = u2 * u;
		double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
		for (int c = 0; c < seen[i].length; c++)
		{
			out[c] = (float) (h00 * seen[i][c] + h01 * seen[i + 1][c] + h * (h10 * slope(at, seen, i, c) + h11 * slope(at, seen, i + 1, c)));
		}
		return true;
	}

	/**
	 * As clientPose; standing false (the character walks) only what the action moves, as pose does: what the walk moves
	 * stays as the client draws it.
	 */
	boolean clientPose(float[] out, int k, double s, boolean standing)
	{
		if (standing)
		{
			return clientPose(out, k, s);
		}
		Scratch work = scratch.get();
		if (work.client.length != out.length)
		{
			work.client = new float[out.length];
		}
		float[] client = work.client;
		if (!clientPose(client, k, s))
		{
			return false;
		}
		for (int v = 0; v < vertices; v++)
		{
			if (acts(v))
			{
				System.arraycopy(client, 3 * v, out, 3 * v, 3);
			}
		}
		return true;
	}

	/** Whether the action moves vertex v also while the character walks (else the walk does). */
	boolean acts(int v)
	{
		return partOf[v] >= 0 || follows[v] >= 0 && !quiet[v];
	}

	/** Where a step the client blends meets one the spline draws, the spline's pose this far into its step is a point of the curve. */
	private static final double NEIGHBOUR = 0.25;

	private float[] splinePose(int k, double s)
	{
		float[] out = keys[k].clone();
		pose(out, k, s);
		return out;
	}

	/** As {@link #splinePose(int, double)}, into out (as long as a keyframe). */
	private float[] splinePose(int k, double s, float[] out)
	{
		System.arraycopy(keys[k], 0, out, 0, keys[k].length);
		pose(out, k, s);
		return out;
	}

	/**
	 * The pose drawn at step k, s of the way (0..1): the spline's where it draws the step, else the client's blend (see
	 * clientPose; straight between the keyframes where that was never seen).
	 */
	float[] drawnPose(int k, double s)
	{
		if (smooths(k))
		{
			return splinePose(k, s);
		}
		float[] out = keys[k].clone();
		if (!clientPose(out, k, s))
		{
			float[] next = keys[Math.min(k + 1, keyframes - 1)];
			for (int i = 0; i < out.length; i++)
			{
				out[i] += (float) (s * (next[i] - out[i]));
			}
		}
		return out;
	}

	private static float[] first(float[][] samples)
	{
		return samples == null || samples.length == 0 ? null : samples[0];
	}

	private static float[] last(float[][] samples)
	{
		return samples == null || samples.length == 0 ? null : samples[samples.length - 1];
	}

	/** How fast coordinate c goes through point i of a curve (the parabola through it and its neighbours). */
	private static double slope(double[] at, float[][] seen, int i, int c)
	{
		int last = at.length - 1;
		if (last < 2)
		{
			return (seen[last][c] - seen[0][c]) / Math.max(1e-9, at[last] - at[0]);
		}
		// the three points around i (at an end: the end one and the two after or before it)
		int m = Math.max(1, Math.min(last - 1, i));
		double h0 = at[m] - at[m - 1], h1 = at[m + 1] - at[m];
		if (h0 <= 0 || h1 <= 0)
		{
			return 0;
		}
		double d0 = (seen[m][c] - seen[m - 1][c]) / h0, d1 = (seen[m + 1][c] - seen[m][c]) / h1;
		if (i < m)
		{
			return d0 - h0 * (d1 - d0) / (h0 + h1);
		}
		if (i > m)
		{
			return d1 + h1 * (d1 - d0) / (h0 + h1);
		}
		return (d0 * h1 + d1 * h0) / (h0 + h1);
	}

	/** Whether keyframe step k is smoothed by the spline (else it is left to the client). */
	boolean smooths(int k)
	{
		return k < keyframes - 1 && !fallback[k];
	}

	/**
	 * Vertex pairs at least 8 model units apart that belong together: up to 1000 at random that keep their distance in
	 * every keyframe, and up to 8 in every part (small parts would hardly ever be picked at random). Per step their
	 * distance must stay within what the two keyframes give it, as far as the client keeps it there (see Rules).
	 */
	private static int[][] rigidPairs(float[][] keys, int[] group)
	{
		int n = keys[0].length / 3;
		java.util.Random random = new java.util.Random(1);
		java.util.List<int[]> out = new java.util.ArrayList<>();
		// at random: pairs that keep their distance in every keyframe (in one part of the skeleton, or parts moving as one)
		for (int i = 0; i < 4000 && n > 1 && out.size() < 1000; i++)
		{
			int a = random.nextInt(n), b = random.nextInt(n);
			if (a == b)
			{
				continue;
			}
			double min = Double.MAX_VALUE, max = 0;
			for (float[] key : keys)
			{
				double d = Rules.pair(key, a, b);
				min = Math.min(min, d);
				max = Math.max(max, d);
			}
			if (min > 8 && max - min <= Math.max(0.5, 0.01 * max))
			{
				out.add(new int[]{a, b});
			}
		}
		java.util.Map<Integer, java.util.List<Integer>> byGroup = new java.util.HashMap<>();
		for (int v = 0; v < n; v++)
		{
			byGroup.computeIfAbsent(group[v], g -> new java.util.ArrayList<>()).add(v);
		}
		for (java.util.List<Integer> members : byGroup.values())
		{
			int added = 0;
			for (int i = 0; i < members.size() && added < 8; i++)
			{
				for (int j = members.size() - 1; j > i && added < 8; j -= Math.max(1, members.size() / 4))
				{
					int a = members.get(i), b = members.get(j);
					if (Rules.pair(keys[0], a, b) > 8)
					{
						out.add(new int[]{a, b});
						added++;
					}
				}
			}
		}
		return out.toArray(new int[0][]);
	}

	/** Keyframes looked at for touching pairs: up to this many, spread over the animation; and how many pairs at most. */
	static final int TOUCH_KEYFRAMES = 6;
	private static final int NEAR_PAIRS = 1500;

	/** Vertex pairs of different parts within 6 model units of each other in a keyframe (up to TOUCH_KEYFRAMES of them). */
	private static int[][] nearPairs(float[][] keys, int[] group)
	{
		java.util.Set<Long> seen = new java.util.HashSet<>();
		java.util.List<int[]> out = new java.util.ArrayList<>();
		int n = group.length;
		for (int t = 0; t < Math.min(TOUCH_KEYFRAMES, keys.length); t++)
		{
			float[] pose = keys[(int) ((long) t * (keys.length - 1) / Math.max(1, Math.min(TOUCH_KEYFRAMES, keys.length) - 1))];
			Integer[] byX = new Integer[n];
			for (int v = 0; v < n; v++)
			{
				byX[v] = v;
			}
			Arrays.sort(byX, (a, b) -> Float.compare(pose[3 * a], pose[3 * b]));
			for (int i = 0; i < n; i++)
			{
				int a = byX[i];
				for (int j = i + 1; j < n && pose[3 * byX[j]] - pose[3 * a] < 6; j++)
				{
					int b = byX[j];
					if (group[a] != group[b] && Math.abs(pose[3 * a + 1] - pose[3 * b + 1]) < 6 && Math.abs(pose[3 * a + 2] - pose[3 * b + 2]) < 6
						&& seen.add((long) Math.min(a, b) << 32 | Math.max(a, b)))
					{
						out.add(new int[]{a, b});
					}
				}
			}
		}
		// at most NEAR_PAIRS, spread evenly
		if (out.size() > NEAR_PAIRS)
		{
			java.util.List<int[]> spread = new java.util.ArrayList<>();
			for (int i = 0; i < NEAR_PAIRS; i++)
			{
				spread.add(out.get((int) ((long) i * out.size() / NEAR_PAIRS)));
			}
			out = spread;
		}
		return out.toArray(new int[0][]);
	}

	/** How pieces that don't move as one were split, for checking. */
	final StringBuilder splits = new StringBuilder();

	/**
	 * A point in a carrier's frame at keyframe k: its turn and centre only. A carried piece is geometry of its own: it
	 * doesn't stretch with the carrier (which an animation may shrink to nothing to hide it).
	 */
	private double[] carrierLocal(int p, int k, double x, double y, double z)
	{
		double[] rotation, centre;
		if (driven[p])
		{
			rotation = fit.rotation[k][p];
			centre = fit.centre[k][p];
		}
		else
		{
			frameLocal(p, k, x, y, z);
			double[] m = measuredAt[k][p];
			rotation = m.length == 0 ? fit.rotation[k][p] : new double[]{m[0], m[1], m[2], m[3]};
			centre = m.length == 0 ? fit.centre[k][p] : new double[]{m[4], m[5], m[6]};
		}
		return Quaternions.rotate(Quaternions.conjugate(rotation), x - centre[0], y - centre[1], z - centre[2]);
	}

	/** Per keyframe and part the spline leaves to the client: its turn and centre as measure() finds them there. */
	private double[][][] measuredAt;

	/**
	 * A point in part p's frame at keyframe k: the fitted frame for a part the spline moves; for one it leaves to the
	 * client, the frame measure() finds in the keyframe, as pose() measures it in the client's pose (which is the
	 * keyframe there), so what hangs on it meets the keyframe exactly.
	 */
	private double[] frameLocal(int p, int k, double x, double y, double z)
	{
		double[] m = measuredAt(p, k);
		if (m == null)
		{
			return fit.local(p, k, x, y, z);
		}
		return Quaternions.rotate(Quaternions.conjugate(new double[]{m[0], m[1], m[2], m[3]}), x - m[4], y - m[5], z - m[6]);
	}

	/** Where part p's frame at keyframe k puts a point given relative to its rest centre: as frameLocal, turned around. */
	private double[] framePlace(int p, int k, double x, double y, double z)
	{
		double[] m = measuredAt(p, k);
		if (m == null)
		{
			return fit.place(p, k, x, y, z);
		}
		double[] r = Quaternions.rotate(new double[]{m[0], m[1], m[2], m[3]}, x, y, z);
		return new double[]{r[0] + m[4], r[1] + m[5], r[2] + m[6]};
	}

	/** The turn of part p's frame at keyframe k, as frameLocal has it. */
	private double[] frameTurn(int p, int k)
	{
		double[] m = measuredAt(p, k);
		return m == null ? fit.rotation[k][p] : new double[]{m[0], m[1], m[2], m[3]};
	}

	/** For a part the spline leaves to the client, what measure() finds of it in keyframe k; else null (the fitted frame). */
	private double[] measuredAt(int p, int k)
	{
		if (driven[p])
		{
			return null;
		}
		if (measuredAt == null)
		{
			measuredAt = new double[keyframes][parts][];
		}
		if (measuredAt[k][p] == null)
		{
			double[] measured = measure(keys[k], p);
			measuredAt[k][p] = measured != null ? measured : new double[0];
		}
		return measuredAt[k][p].length == 0 ? null : measuredAt[k][p];
	}

	/**
	 * The turn and centre of part p where the client has it now (in pose), fitted to its rest shape:
	 * {w, x, y, z, centre x, y, z}; null when the part has too few vertices.
	 */
	double[] measure(float[] pose, int p)
	{
		double[] out = new double[7];
		return measure(pose, p, out) ? out : null;
	}

	/** As {@link #measure(float[], int)}, into out (7 long); false (out as it was) when the part has too few vertices. */
	private boolean measure(float[] pose, int p, double[] out)
	{
		if (fit.members[p] < 3)
		{
			return false;
		}
		double cx = 0, cy = 0, cz = 0;
		int[] own = members[p];
		for (int v : own)
		{
			cx += pose[3 * v];
			cy += pose[3 * v + 1];
			cz += pose[3 * v + 2];
		}
		cx /= own.length;
		cy /= own.length;
		cz /= own.length;
		Scratch work = scratch.get();
		double[] cross = work.cross;
		java.util.Arrays.fill(cross, 0);
		for (int v : own)
		{
			Fit.outer(cross, fit.rest[3 * v], fit.rest[3 * v + 1], fit.rest[3 * v + 2], pose[3 * v] - cx, pose[3 * v + 1] - cy,
				pose[3 * v + 2] - cz);
		}
		Quaternions.fit(cross, work.eigen, out);
		out[4] = cx;
		out[5] = cy;
		out[6] = cz;
		return true;
	}

	/** Whether part p's motion moves every vertex of a piece onto its keyframe poses. */
	private boolean carriesAll(int p, java.util.List<Integer> piece)
	{
		for (int v : piece)
		{
			double[] c0 = fit.centre[0][p];
			double d = Math.sqrt(sq(fit.keys[0][3 * v] - c0[0]) + sq(fit.keys[0][3 * v + 1] - c0[1]) + sq(fit.keys[0][3 * v + 2] - c0[2]));
			if (fit.miss(p, v) > FIT_UNITS + FIT_PART * d)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Builds the joint tree (the stillest part first: the body, not a flail's head swung around on its rope; then always
	 * the joint that holds best) and returns the order to place parts in: every part after its parent.
	 */
	private int[] tree(float[][] keys, boolean[] usable)
	{
		int n = parents();
		int[] members = fit.members;
		double[] travel = new double[n];
		for (int p = 0; p < n; p++)
		{
			for (int k = 1; k < keyframes; k++)
			{
				double[] a = fit.centre[k - 1][p], b = fit.centre[k][p];
				travel[p] += Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
			}
		}
		java.util.List<double[]> joints = new java.util.ArrayList<>();
		java.util.List<double[]> ropeJoints = new java.util.ArrayList<>();
		for (int a = 0; a < n; a++)
		{
			for (int b = a + 1; b < n; b++)
			{
				if (!usable[a] || !usable[b] || (!driven[a] && !driven[b]))
				{
					continue;
				}
				double[] near = nearest(keys, a, b);
				if (near == null)
				{
					continue;
				}
				double[] point = jointPoint(a, b, near);
				double slack = jointSlack(a, b, point);
				if (slack <= JOINT_SLACK)
				{
					joints.add(new double[]{a, b, slack, point[0], point[1], point[2]});
				}
				else if (rope && driven[a] && driven[b] && slack <= ROPE_SLACK)
				{
					ropeJoints.add(new double[]{a, b, slack, point[0], point[1], point[2]});
				}
			}
		}
		boolean[] placed = new boolean[n];
		int[] out = new int[n];
		int count = 0;
		while (true)
		{
			// a new root: of the parts not placed yet, the largest that doesn't travel far over the animation (the
			// body; not a flail's head swung around on its rope, nor a still foot: errors add up along the chain from a
			// small root), else the largest
			double[] travels = java.util.stream.IntStream.range(0, n).filter(p -> !placed[p] && members[p] >= 3)
				.mapToDouble(p -> travel[p]).sorted().toArray();
			double still = travels.length == 0 ? 0 : ROOT_TRAVEL * travels[travels.length / 2];
			int root = -1;
			for (int p = 0; p < n; p++)
			{
				if (!placed[p] && members[p] >= 3 && travel[p] <= still && (root < 0 || members[p] > members[root]))
				{
					root = p;
				}
			}
			for (int p = 0; p < n && travels.length == 0; p++)
			{
				root = !placed[p] && (root < 0 || members[p] > members[root]) ? p : root;
			}
			if (root < 0)
			{
				break;
			}
			placed[root] = true;
			out[count++] = root;
			while (true)
			{
				double[] best = null;
				for (double[] j : joints)
				{
					boolean inA = placed[(int) j[0]], inB = placed[(int) j[1]];
					if (inA != inB && (best == null || j[2] < best[2]))
					{
						best = j;
					}
				}
				for (double[] j : best == null ? ropeJoints : java.util.Collections.<double[]>emptyList())
				{
					// a rope's loose head: on the rope's end, by the joint that misses least
					boolean inA = placed[(int) j[0]], inB = placed[(int) j[1]];
					if (inA != inB && (best == null || j[2] < best[2]))
					{
						best = j;
					}
				}
				if (best == null)
				{
					break;
				}
				if (best[2] > JOINT_SLACK)
				{
					splits.append(String.format("  rope joint %d-%d slack %.1f%n", (int) best[0], (int) best[1], best[2]));
				}
				int a = (int) best[0], b = (int) best[1];
				int child = placed[a] ? b : a, from = placed[a] ? a : b;
				parent[child] = from;
				pivot[child] = new double[]{best[3], best[4], best[5]};
				placed[child] = true;
				out[count++] = child;
			}
		}
		return out;
	}

	private int parents()
	{
		return fit.members.length;
	}

	/** The midpoint of the closest pair of vertices of parts a and b in keyframe 0, if they are near; else null. */
	private double[] nearest(float[][] keys, int a, int b)
	{
		double[] ca = fit.centre[0][a], cb = fit.centre[0][b];
		double apart = Math.sqrt(sq(ca[0] - cb[0]) + sq(ca[1] - cb[1]) + sq(ca[2] - cb[2]));
		if (apart - fit.reach[a] - fit.reach[b] > JOINT_NEAR)
		{
			return null;
		}
		double best = Double.MAX_VALUE;
		double[] mid = null;
		float[] k0 = keys[0];
		for (int u = 0; u < vertices; u++)
		{
			if (group[u] != a)
			{
				continue;
			}
			for (int v = 0; v < vertices; v++)
			{
				if (group[v] != b)
				{
					continue;
				}
				double d = sq(k0[3 * u] - k0[3 * v]) + sq(k0[3 * u + 1] - k0[3 * v + 1]) + sq(k0[3 * u + 2] - k0[3 * v + 2]);
				if (d < best)
				{
					best = d;
					mid = new double[]{(k0[3 * u] + k0[3 * v]) / 2, (k0[3 * u + 1] + k0[3 * v + 1]) / 2, (k0[3 * u + 2] + k0[3 * v + 2]) / 2};
				}
			}
		}
		return Math.sqrt(best) <= JOINT_NEAR ? mid : null;
	}

	/**
	 * The joint point (keyframe 0 coordinates) that parts a and b carry to the same place in every keyframe, by least
	 * squares; pulled towards the closest point between them where the joint leaves it free (a hinge turns about a line).
	 */
	private double[] jointPoint(int a, int b, double[] near)
	{
		double[] m = new double[9];
		double[] rhs = new double[3];
		for (int k = 1; k < keyframes; k++)
		{
			double[] ma = matrix(fit.rotation[k][a], fit.stretch[k][a]), mb = matrix(fit.rotation[k][b], fit.stretch[k][b]);
			double[] d = new double[9];
			for (int e = 0; e < 9; e++)
			{
				d[e] = ma[e] - mb[e];
			}
			// ma (P - ca0) + cak = mb (P - cb0) + cbk  ->  (ma - mb) P = ma ca0 - cak - mb cb0 + cbk
			double[] r = new double[3];
			double[] ca0 = fit.centre[0][a], cb0 = fit.centre[0][b], cak = fit.centre[k][a], cbk = fit.centre[k][b];
			for (int i = 0; i < 3; i++)
			{
				r[i] = ma[3 * i] * ca0[0] + ma[3 * i + 1] * ca0[1] + ma[3 * i + 2] * ca0[2] - cak[i]
					- (mb[3 * i] * cb0[0] + mb[3 * i + 1] * cb0[1] + mb[3 * i + 2] * cb0[2]) + cbk[i];
			}
			for (int i = 0; i < 3; i++)
			{
				for (int j = 0; j < 3; j++)
				{
					double sum = 0;
					for (int l = 0; l < 3; l++)
					{
						sum += d[3 * l + i] * d[3 * l + j];
					}
					m[3 * i + j] += sum;
				}
				rhs[i] += d[i] * r[0] + d[3 + i] * r[1] + d[6 + i] * r[2];
			}
		}
		double lambda = 1e-3 * (m[0] + m[4] + m[8]) + 1e-6;
		for (int i = 0; i < 3; i++)
		{
			m[4 * i] += lambda;
			rhs[i] += lambda * near[i];
		}
		double[] inv = inverse(m);
		return new double[]{
			inv[0] * rhs[0] + inv[1] * rhs[1] + inv[2] * rhs[2],
			inv[3] * rhs[0] + inv[4] * rhs[1] + inv[5] * rhs[2],
			inv[6] * rhs[0] + inv[7] * rhs[1] + inv[8] * rhs[2],
		};
	}

	/** How far apart parts a and b carry the joint point, at worst over the keyframes. */
	private double jointSlack(int a, int b, double[] point)
	{
		double worst = 0;
		for (int k = 0; k < keyframes; k++)
		{
			double[] pa = fit.place(a, k, point[0] - fit.centre[0][a][0], point[1] - fit.centre[0][a][1], point[2] - fit.centre[0][a][2]);
			double[] pb = fit.place(b, k, point[0] - fit.centre[0][b][0], point[1] - fit.centre[0][b][1], point[2] - fit.centre[0][b][2]);
			worst = Math.max(worst, Math.sqrt(sq(pa[0] - pb[0]) + sq(pa[1] - pb[1]) + sq(pa[2] - pb[2])));
		}
		return worst;
	}

	/** The centre part p must have so that, turned and stretched as given, its joint point lands on where. */
	private double[] carried(int p, double[] rotation, double[] stretch, double[] where)
	{
		double[] out = new double[3];
		carried(p, rotation, stretch, where, out);
		return out;
	}

	/** As {@link #carried(int, double[], double[], double[])}, into out (not where). */
	private void carried(int p, double[] rotation, double[] stretch, double[] where, double[] out)
	{
		double[] c0 = fit.centre[0][p];
		double x = pivot[p][0] - c0[0], y = pivot[p][1] - c0[1], z = pivot[p][2] - c0[2];
		Quaternions.rotate(rotation, stretch[0] * x + stretch[1] * y + stretch[2] * z,
			stretch[3] * x + stretch[4] * y + stretch[5] * z, stretch[6] * x + stretch[7] * y + stretch[8] * z, out);
		out[0] = where[0] - out[0];
		out[1] = where[1] - out[1];
		out[2] = where[2] - out[2];
	}

	/** Turn times stretch as a row-major 3x3 matrix. */
	private static double[] matrix(double[] q, double[] stretch)
	{
		double[] out = new double[9];
		for (int column = 0; column < 3; column++)
		{
			double[] r = Quaternions.rotate(q, stretch[column], stretch[3 + column], stretch[6 + column]);
			out[column] = r[0];
			out[3 + column] = r[1];
			out[6 + column] = r[2];
		}
		return out;
	}

	/**
	 * Kochanek-Bartels tangents with uneven key spacing (as speeds per tick), clamped like a monotone spline: where the
	 * motion turns back the key is reached at rest, so the pose is not overshot.
	 *
	 * @param a (1 - tension)(1 + continuity)
	 * @param b (1 - tension)(1 - continuity): at a corner (continuity -1) a key is left along the step after it and reached
	 *          along the step before it
	 * @return {leaving, arriving}
	 */
	private static double[] tangents(double before, double after, int lengthBefore, int lengthAfter, double a, double b)
	{
		if (Double.isNaN(before) && Double.isNaN(after))
		{
			return new double[]{0, 0};
		}
		if (Double.isNaN(before))
		{
			// the action starts from whatever came before: leave along the first step
			return new double[]{after, after};
		}
		if (Double.isNaN(after))
		{
			// comes to rest on its last keyframe
			return new double[]{0, 0.5 * before};
		}
		if (before * after <= 0)
		{
			return new double[]{0, 0};
		}
		double sum = lengthBefore + lengthAfter;
		double leaving = (a * lengthAfter * before + b * lengthBefore * after) / sum;
		double arriving = (b * lengthAfter * before + a * lengthBefore * after) / sum;
		// no more than three times the step's own speed (monotone cubic limit)
		return new double[]{clamp(leaving, after), clamp(arriving, before)};
	}

	/**
	 * Fritsch-Carlson: a cubic between two keys stays between them when neither tangent points against the step and
	 * (leaving / step)^2 + (arriving / step)^2 is at most 9.
	 */
	private static void monotone(double[] leaving, double[] arriving, int axis, double secant)
	{
		if (secant == 0)
		{
			leaving[axis] = 0;
			arriving[axis] = 0;
			return;
		}
		double a = Math.max(0, leaving[axis] / secant), b = Math.max(0, arriving[axis] / secant);
		double sum = a * a + b * b;
		if (sum > 9)
		{
			double t = 3 / Math.sqrt(sum);
			a *= t;
			b *= t;
		}
		leaving[axis] = a * secant;
		arriving[axis] = b * secant;
	}

	private static double clamp(double tangent, double secant)
	{
		double limit = 3 * Math.abs(secant);
		return Math.max(-limit, Math.min(limit, tangent));
	}

	/**
	 * Writes the pose at keyframe k, s of the way (0..1) to keyframe k + 1, into out (x, y, z, ...) for the vertices of
	 * moving parts; the others are left as they are.
	 */
	void pose(float[] out, int k, double s)
	{
		pose(out, k, s, true);
	}

	/** A push turns a part at most this much (radians), see room. */
	static final double EXTRA_TURN = Math.toRadians(45);
	/** Past a keyframe nothing may sink further below the ground than at that keyframe, give or take this (model units). */
	static final double ROOM_SLACK = 2;

	/**
	 * How far a pose at one of step k's keyframes can be pushed on (see push) the way the step goes (onward: from its
	 * end, on past it; else from its start, back), up to most (a part of the step): no part turning more than
	 * EXTRA_TURN, and nothing sinking lower than the ground or than it is at that keyframe, whichever is lower (a slam
	 * into the floor doesn't go on through it).
	 */
	double room(int k, boolean onward, double most)
	{
		if (k < 0 || k >= keyframes - 1 || most <= 0)
		{
			return 0;
		}
		double fastest = 0;
		for (int p = 0; p < parts; p++)
		{
			if (driven[p])
			{
				double[] t = turn[k][p];
				fastest = Math.max(fastest, Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]));
			}
		}
		most = Math.min(most, fastest > 0 ? EXTRA_TURN / fastest : most);
		float[] key = keys[onward ? k + 1 : k];
		double floor = 0;
		for (int v = 1; v < key.length; v += 3)
		{
			// model heights grow downwards; the ground is 0
			floor = Math.max(floor, key[v]);
		}
		floor += ROOM_SLACK;
		float[] out = new float[key.length];
		if (above(out, key, k, onward ? most : -most, floor))
		{
			return most;
		}
		double low = 0, high = most;
		for (int i = 0; i < 6; i++)
		{
			double mid = (low + high) / 2;
			if (above(out, key, k, onward ? mid : -mid, floor))
			{
				low = mid;
			}
			else
			{
				high = mid;
			}
		}
		return low;
	}

	/** Keyframe k's pose. */
	float[] key(int k)
	{
		return keys[k].clone();
	}

	/** The pose drawn at step k, s of the way: the spline's, or where the client blends the step, the client's. */
	float[] drawn(int k, double s)
	{
		float[] out = keys[k].clone();
		if (!clientPose(out, k, s) && !smooths(k))
		{
			return null;
		}
		pose(out, k, s);
		return out;
	}

	/**
	 * How much two moves of the model go the same way: the cosine between them over every vertex (below 0: back the
	 * other way); 0 when one doesn't move. Also how big the first is against the second (root of the sums of squares).
	 */
	static double[] agree(float[] from, float[] to, float[] otherFrom, float[] otherTo)
	{
		double dot = 0, one = 0, other = 0;
		for (int i = 0; i < from.length; i++)
		{
			double x = to[i] - from[i], y = otherTo[i] - otherFrom[i];
			dot += x * y;
			one += x * x;
			other += y * y;
		}
		return new double[]{one > 0 && other > 0 ? dot / Math.sqrt(one * other) : 0, other > 0 ? Math.sqrt(one / other) : 0};
	}

	/** Whether the keyframe pose pushed along step k by e keeps everything above the floor. */
	private boolean above(float[] out, float[] key, int k, double e, double floor)
	{
		System.arraycopy(key, 0, out, 0, key.length);
		push(out, k, e, true);
		for (int v = 1; v < out.length; v += 3)
		{
			if (out[v] > floor)
			{
				return false;
			}
		}
		// a rope or chain pushed on may not stretch much past what it is at the keyframe
		for (int i = 0; ropeGuard && i + 1 < edges.length; i += 2)
		{
			if (length(out, edges[i], edges[i + 1]) > STRETCH_PUSH * length(key, edges[i], edges[i + 1]) + STRETCH_UNITS)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Pushes a pose (the spline's or the client's) on along step k, e of the step (below 0: back), for an exaggerated
	 * blow (see ActorDrawer.moment): each moving part turns further as it turns in the step, about the joint it hangs on
	 * (that its parent's push carries along; a root part moves on as the step moves it), keeping its shape; what a part
	 * carries goes with it. Standing false leaves what the client moves with the walk.
	 */
	void push(float[] out, int k, double e, boolean standing)
	{
		if (e == 0 || k < 0 || k >= keyframes - 1)
		{
			return;
		}
		// per part: the point it turns about now (anchor), where that goes (moved) and the turn (spin, where spun)
		Scratch work = scratch.get();
		work.parts(parts);
		double[][] anchor = work.anchor, moved = work.moved, spin = work.pushSpin, now = work.now;
		boolean[] spun = work.spun;
		byte[] seen = work.seen;
		java.util.Arrays.fill(spun, false);
		java.util.Arrays.fill(seen, (byte) 0);
		double[] r = work.pushR, to = work.to;
		for (int p : order)
		{
			if (!driven[p])
			{
				continue;
			}
			double[] t = turn[k][p];
			Quaternions.exp(e * t[0], e * t[1], e * t[2], spin[p]);
			spun[p] = true;
			int up = parent[p];
			if (up < 0)
			{
				double[] c = anchor[p];
				centroid(out, p, c);
				double[] a = fit.centre[k][p], b = fit.centre[k + 1][p];
				moved[p][0] = c[0] + e * (b[0] - a[0]);
				moved[p][1] = c[1] + e * (b[1] - a[1]);
				moved[p][2] = c[2] + e * (b[2] - a[2]);
				continue;
			}
			// the joint: carried by the parent as it is now
			if (seen[up] == 0)
			{
				seen[up] = measure(out, up, now[up]) ? (byte) 1 : (byte) 2;
			}
			if (seen[up] == 2)
			{
				spun[p] = false;
				continue;
			}
			double[] found = now[up];
			double[] c0 = fit.centre[0][up];
			Quaternions.rotate(found, pivot[p][0] - c0[0], pivot[p][1] - c0[1], pivot[p][2] - c0[2], r);
			double[] joint = anchor[p];
			joint[0] = r[0] + found[4];
			joint[1] = r[1] + found[5];
			joint[2] = r[2] + found[6];
			if (spun[up])
			{
				carry(spin[up], anchor[up], moved[up], joint[0], joint[1], joint[2], moved[p]);
			}
			else
			{
				System.arraycopy(joint, 0, moved[p], 0, 3);
			}
		}
		for (int v = 0; v < vertices; v++)
		{
			int p = partOf[v] >= 0 ? partOf[v] : follows[v];
			if (p < 0 || !spun[p] || partOf[v] < 0 && quiet[v] && !standing)
			{
				continue;
			}
			carry(spin[p], anchor[p], moved[p], out[3 * v], out[3 * v + 1], out[3 * v + 2], to);
			out[3 * v] = (float) to[0];
			out[3 * v + 1] = (float) to[1];
			out[3 * v + 2] = (float) to[2];
		}
	}

	/**
	 * A pose pushed along a step can part what is held in two hands: each arm goes on as it turns, and a tool hangs on one.
	 * This turns the other arm at its shoulder, the way that puts its hand back where the pushed tool has taken what it
	 * held (see grip). Before: the pose before the push; k: the keyframe of the hold.
	 */
	void keepGrip(float[] out, float[] before, int k, boolean standing)
	{
		int[] grip = grip(k);
		if (grip == NO_GRIP)
		{
			return;
		}
		int tool = grip[0], hand = grip[1], arm = grip[2];
		Scratch work = scratch.get();
		double[] from = work.from, to = work.toward, body = work.body;
		if (!measure(before, tool, from) || !measure(out, tool, to) || !measure(out, parent[arm], body))
		{
			return;
		}
		// where the hand's place on the tool has gone, and the turn about the shoulder that gets the hand there
		double[] held = work.grasp, now = work.hand;
		centroid(before, hand, held);
		centroid(out, hand, now);
		double[] undo = work.undo, move = work.move;
		undo[0] = from[0];
		undo[1] = -from[1];
		undo[2] = -from[2];
		undo[3] = -from[3];
		Quaternions.multiply(to, undo, move);
		double[] r = work.gripR;
		Quaternions.rotate(move, held[0] - from[4], held[1] - from[5], held[2] - from[6], r);
		double[] c0 = fit.centre[0][parent[arm]];
		double[] s = work.gripS;
		Quaternions.rotate(body, pivot[arm][0] - c0[0], pivot[arm][1] - c0[1], pivot[arm][2] - c0[2], s);
		double[] shoulder = work.shoulder;
		shoulder[0] = s[0] + body[4];
		shoulder[1] = s[1] + body[5];
		shoulder[2] = s[2] + body[6];
		double[] spin = work.gripSpin;
		between(now[0] - shoulder[0], now[1] - shoulder[1], now[2] - shoulder[2],
			r[0] + to[4] - shoulder[0], r[1] + to[5] - shoulder[1], r[2] + to[6] - shoulder[2], spin);
		double[] turned = work.gripTo;
		for (int v = 0; v < vertices; v++)
		{
			int p = partOf[v] >= 0 ? partOf[v] : follows[v];
			if (p < 0 || !under(p, arm) || partOf[v] < 0 && quiet[v] && !standing)
			{
				continue;
			}
			carry(spin, shoulder, shoulder, out[3 * v], out[3 * v + 1], out[3 * v + 2], turned);
			out[3 * v] = (float) turned[0];
			out[3 * v + 1] = (float) turned[1];
			out[3 * v + 2] = (float) turned[2];
		}
	}

	/** The shortest turn from one direction to another, into out. */
	private static void between(double ax, double ay, double az, double bx, double by, double bz, double[] out)
	{
		double w = Math.sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz)) + ax * bx + ay * by + az * bz,
			x = ay * bz - az * by, y = az * bx - ax * bz, z = ax * by - ay * bx;
		double size = Math.sqrt(w * w + x * x + y * y + z * z);
		if (size > 0)
		{
			out[0] = w / size;
			out[1] = x / size;
			out[2] = y / size;
			out[3] = z / size;
		}
		else
		{
			out[0] = 1;
			out[1] = 0;
			out[2] = 0;
			out[3] = 0;
		}
	}

	/**
	 * What is held in two hands at keyframe k: {the tool (the part that reaches furthest from its joint of those the step
	 * into k turns), the part of the other arm nearest it, within GRIP_TOUCH, that arm's first part}; NO_GRIP: nothing is.
	 */
	private int[] grip(int k)
	{
		if (grips == null)
		{
			grips = new int[keyframes][];
		}
		if (grips[k] == null)
		{
			grips[k] = findGrip(k);
		}
		return grips[k];
	}

	private int[] findGrip(int k)
	{
		float[] key = keys[k], rest = keys[0];
		int tool = -1;
		double furthest = 0;
		for (int p = 0; p < parts; p++)
		{
			double[] t = k > 0 && driven[p] && parent[p] >= 0 ? turn[k - 1][p] : null;
			if (t == null || t[0] * t[0] + t[1] * t[1] + t[2] * t[2] == 0)
			{
				continue;
			}
			for (int v : members[p])
			{
				double reach = Math.sqrt(sq(rest[3 * v] - pivot[p][0]) + sq(rest[3 * v + 1] - pivot[p][1]) + sq(rest[3 * v + 2] - pivot[p][2]));
				if (reach > furthest)
				{
					furthest = reach;
					tool = p;
				}
			}
		}
		int hand = -1;
		double nearest = GRIP_TOUCH;
		for (int q = 0; tool >= 0 && q < parts; q++)
		{
			if (under(q, tool) || under(tool, q) || members[q].length < 3 || !driven[q])
			{
				continue;
			}
			for (int v : members[q])
			{
				for (int w : members[tool])
				{
					double gap = Math.sqrt(sq(key[3 * v] - key[3 * w]) + sq(key[3 * v + 1] - key[3 * w + 1]) + sq(key[3 * v + 2] - key[3 * w + 2]));
					if (gap < nearest)
					{
						nearest = gap;
						hand = q;
					}
				}
			}
		}
		if (hand < 0)
		{
			return NO_GRIP;
		}
		// the arm: the hand's way up to where it joins the tool's
		int arm = hand;
		while (parent[arm] >= 0 && !under(tool, parent[arm]))
		{
			arm = parent[arm];
		}
		return parent[arm] < 0 ? NO_GRIP : new int[]{tool, hand, arm};
	}

	/** A point (x, y, z) turned by spin about anchor, then moved with the anchor to moved; into out (not anchor or moved). */
	private static void carry(double[] spin, double[] anchor, double[] moved, double x, double y, double z, double[] out)
	{
		Quaternions.rotate(spin, x - anchor[0], y - anchor[1], z - anchor[2], out);
		out[0] = out[0] + moved[0];
		out[1] = out[1] + moved[1];
		out[2] = out[2] + moved[2];
	}

	/** Where part p's vertices are in a pose, on average; into out. */
	private void centroid(float[] pose, int p, double[] out)
	{
		double cx = 0, cy = 0, cz = 0;
		int[] own = members[p];
		for (int v : own)
		{
			cx += pose[3 * v];
			cy += pose[3 * v + 1];
			cz += pose[3 * v + 2];
		}
		out[0] = cx / own.length;
		out[1] = cy / own.length;
		out[2] = cz / own.length;
	}

	/**
	 * As {@link #pose(float[], int, double)}; standing false (the character walks) leaves parts that hardly move in the
	 * action to the client, which moves them with the walk.
	 */
	void pose(float[] out, int k, double s, boolean standing)
	{
		poseSpline(out, k, s, standing, null);
	}

	/**
	 * As {@link #pose(float[], int, double, boolean)}, a rope's head put in as another pose has it (see headFrom; null:
	 * as this one has it), its stub of rope where this pose has it in the hand.
	 */
	void pose(float[] out, int k, double s, boolean standing, float[] head)
	{
		poseSpline(out, k, s, standing, head);
	}

	private void poseSpline(float[] out, int k, double s, boolean standing, float[] head)
	{
		if (k >= keyframes - 1 || fallback[k])
		{
			// the last keyframe the client shows as it is, and steps the spline breaks a rule in are the client's
			if (head != null)
			{
				headFrom(out, head);
			}
			return;
		}
		int next = Math.min(k + 1, keyframes - 1);
		double s2 = s * s, s3 = s2 * s;
		double h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s, h01 = -2 * s3 + 3 * s2, h11 = s3 - s2;
		// per part (where posed) its turn, stretch and centre
		Scratch work = scratch.get();
		work.parts(parts);
		double[][] q = work.turn;
		double[][] m = work.stretch;
		double[][] c = work.centre;
		boolean[] posed = work.posed;
		java.util.Arrays.fill(posed, false);
		double[] spin = work.spin, centre = work.middle, off = work.off, step = work.step, r = work.r;
		int length = lengths[k];
		for (int p : order)
		{
			if (!driven[p])
			{
				// a part the action doesn't move, that a moving part hangs on or that carries vertices: where the client
				// has it now
				double[] found = work.held;
				if (!measuredParts[p] || !measure(out, p, found))
				{
					continue;
				}
				System.arraycopy(found, 0, q[p], 0, 4);
				java.util.Arrays.fill(m[p], 0);
				m[p][0] = 1;
				m[p][4] = 1;
				m[p][8] = 1;
				System.arraycopy(found, 4, c[p], 0, 3);
				posed[p] = true;
				continue;
			}
			for (int axis = 0; axis < 3; axis++)
			{
				spin[axis] = s == 0 ? 0 : h10 * length * spinOut[k][p][axis] + h01 * turn[k][p][axis] + h11 * length * spinIn[next][p][axis];
				centre[axis] = h00 * fit.centre[k][p][axis] + h10 * length * moveOut[k][p][axis] + h01 * fit.centre[next][p][axis]
					+ h11 * length * moveIn[next][p][axis];
				off[axis] = h00 * offset[k][p][axis] + h10 * length * offsetOut[k][p][axis] + h01 * offset[next][p][axis]
					+ h11 * length * offsetIn[next][p][axis];
			}
			// stretch eases from one keyframe's to the next's, never past either
			for (int e = 0; e < 9; e++)
			{
				m[p][e] = h00 * fit.stretch[k][p][e] + h01 * fit.stretch[next][p][e];
			}
			Quaternions.exp(spin[0], spin[1], spin[2], step);
			Quaternions.multiply(step, fit.rotation[k][p], q[p]);
			posed[p] = true;
			int up = parent[p];
			if (up >= 0 && posed[up])
			{
				// hangs on its parent: the joint point goes where the parent carries it
				double[] c0 = fit.centre[0][up];
				double x = pivot[p][0] - c0[0], y = pivot[p][1] - c0[1], z = pivot[p][2] - c0[2];
				double[] mu = m[up];
				Quaternions.rotate(q[up], mu[0] * x + mu[1] * y + mu[2] * z, mu[3] * x + mu[4] * y + mu[5] * z,
					mu[6] * x + mu[7] * y + mu[8] * z, r);
				double[] where = work.where, carried = work.carried, turned = work.turned;
				where[0] = r[0] + c[up][0];
				where[1] = r[1] + c[up][1];
				where[2] = r[2] + c[up][2];
				carried(p, q[p], m[p], where, carried);
				Quaternions.rotate(q[up], off[0], off[1], off[2], turned);
				c[p][0] = carried[0] + turned[0];
				c[p][1] = carried[1] + turned[1];
				c[p][2] = carried[2] + turned[2];
			}
			else
			{
				System.arraycopy(centre, 0, c[p], 0, 3);
			}
		}
		// where the parts go now, with what the fit misses of the keyframes blended back in
		double w0 = h00, w1 = h01;
		float[][] holder = placing.get();
		if (holder[0].length < 3 * vertices)
		{
			holder[0] = new float[3 * vertices];
		}
		float[] placed = holder[0];
		for (int v = 0; v < vertices; v++)
		{
			int p = partOf[v];
			if (p < 0 || !posed[p])
			{
				continue;
			}
			double rx = fit.rest[3 * v] + w0 * residual[k][3 * v] + w1 * residual[next][3 * v];
			double ry = fit.rest[3 * v + 1] + w0 * residual[k][3 * v + 1] + w1 * residual[next][3 * v + 1];
			double rz = fit.rest[3 * v + 2] + w0 * residual[k][3 * v + 2] + w1 * residual[next][3 * v + 2];
			double[] s9 = m[p];
			Quaternions.rotate(q[p], s9[0] * rx + s9[1] * ry + s9[2] * rz, s9[3] * rx + s9[4] * ry + s9[5] * rz,
				s9[6] * rx + s9[7] * ry + s9[8] * rz, r);
			placed[3 * v] = (float) (r[0] + c[p][0]);
			placed[3 * v + 1] = (float) (r[1] + c[p][1]);
			placed[3 * v + 2] = (float) (r[2] + c[p][2]);
		}
		// carried vertices: blended in their carrier's frame, then placed with it
		for (int v = 0; v < vertices; v++)
		{
			int p = follows[v];
			if (p < 0 || !posed[p] || quiet[v] && !standing)
			{
				continue;
			}
			int pc = pieceOf[v];
			for (int axis = 0; axis < 3; axis++)
			{
				spin[axis] = s == 0 ? 0 : h10 * length * pieceSpinOut[k][pc][axis] + h01 * pieceTurn[k][pc][axis]
					+ h11 * length * pieceSpinIn[next][pc][axis];
				centre[axis] = h00 * pieceCentre[k][pc][axis] + h10 * length * pieceMoveOut[k][pc][axis]
					+ h01 * pieceCentre[next][pc][axis] + h11 * length * pieceMoveIn[next][pc][axis];
			}
			double[] piece = work.piece, turned = work.turned, local = work.local;
			Quaternions.exp(spin[0], spin[1], spin[2], step);
			Quaternions.multiply(step, pieceRotation[k][pc], piece);
			Quaternions.rotate(piece, pieceRest[3 * v], pieceRest[3 * v + 1], pieceRest[3 * v + 2], turned);
			for (int axis = 0; axis < 3; axis++)
			{
				int i = 3 * v + axis;
				local[axis] = turned[axis] + centre[axis] + h00 * pieceBend[k][i] + h01 * pieceBend[next][i];
			}
			Quaternions.rotate(q[p], local[0], local[1], local[2], r);
			out[3 * v] = (float) (r[0] + c[p][0]);
			out[3 * v + 1] = (float) (r[1] + c[p][1]);
			out[3 * v + 2] = (float) (r[2] + c[p][2]);
		}
		for (int v = 0; v < vertices; v++)
		{
			if (partOf[v] >= 0)
			{
				out[3 * v] = placed[3 * v];
				out[3 * v + 1] = placed[3 * v + 1];
				out[3 * v + 2] = placed[3 * v + 2];
			}
		}
		double[] held = work.held;
		if (tied.length > 0 && measure(out, tieOn, held) && tieStub.length > 0)
		{
			// the head's stub of rope stays where it lies in the hand that holds it: the head, whole, moved over by what
			// its own way round misses of that
			double[] stub = work.stub;
			java.util.Arrays.fill(stub, 0);
			for (int v : tieStub)
			{
				for (int axis = 0; axis < 3; axis++)
				{
					stub[axis] += (double) out[3 * v + axis] / tieStub.length;
				}
			}
			double[] lies = r;
			Quaternions.rotate(held, h00 * stubAt[k][0] + h01 * stubAt[next][0], h00 * stubAt[k][1] + h01 * stubAt[next][1],
				h00 * stubAt[k][2] + h01 * stubAt[next][2], lies);
			int part = partOf[tieStub[0]];
			for (int v : members[part])
			{
				for (int axis = 0; axis < 3; axis++)
				{
					out[3 * v + axis] += (float) (held[4 + axis] + lies[axis] - stub[axis]);
				}
			}
		}
		if (head != null)
		{
			headFrom(out, head);
		}
		rope(out);
	}

	/**
	 * A rope's links (see rope) shorter than this part of its length ride on the link before them, and the last one is
	 * this part of it at least: its end pieces are too short to point anywhere, and one of the game's labels spans them.
	 */
	private static final double ROPE_LINK = 0.06, ROPE_TAIL = 0.2;
	/** The rope's end lies on this many of its head's vertices at most (see rope). */
	private static final int ROPE_HOLD = 4;
	/** The rope's links are put back to their lengths at most this many times over, or until its end is this close. */
	private static final int ROPE_ROUNDS = 16;
	private static final double ROPE_CLOSE = 0.05;
	/** A rope this little off the straight line between its ends (model units) hangs down rather than that way. */
	private static final double ROPE_HANGS = 2;
	/** Ends of a rope closer together than this part of its length are taken this far apart for its shape. */
	private static final double ROPE_NARROW = 0.05;
	/**
	 * The tied rope as a chain (see rope): its joints in keyframe 0 (the first link's, then each next link's, last the
	 * rope's tip), the first link's part, per tied vertex the link it is of, and the head's vertices its tip lies on.
	 */
	private double[][] ropeNode = new double[0][];
	/** Off while the steps are checked against the rules (see rope): those judge the body and the head, not the rope. */
	private boolean laying = true;

	/** These vertex pairs without those of a rope that rope() lays: its gaps and bends are not the spline's. */
	private int[][] unlaid(int[][] pairs)
	{
		if (ropeNode.length < 2)
		{
			return pairs;
		}
		boolean[] laid = new boolean[vertices];
		for (int i = 0; i < tied.length; i++)
		{
			laid[tied[i]] = ropeLinkOf[i] >= 1;
		}
		return java.util.Arrays.stream(pairs).filter(pair -> !laid[pair[0]] && !laid[pair[1]]).toArray(int[][]::new);
	}
	private int ropeFirst = -1;
	private int[] ropeLinkOf = new int[0], ropeHold = new int[0];

	/**
	 * Lays a tied rope (see tie) in a pose as a real rope: a chain of its links, each whole and as long as in keyframe 0,
	 * from the far end of its first link (the handle, which stays as the pose has it in the hand) to where its tip lies
	 * on the head (the head's stub of rope, wherever the pose has the head). No memory: the same pose gives the same
	 * rope, at any moment. What it is longer than the way between the two hangs as a chain hangs (a catenary), in the
	 * plane and to the side the pose had the rope (the keyframes' shape, blended), or down where that is none; the fold
	 * the keyframes hide spare rope in and the gaps their blends open are gone. Each link is turned the short way from
	 * the one before it, so the rope doesn't twist.
	 */
	void rope(float[] out)
	{
		int n = ropeNode.length - 1;
		if (n < 1 || ropeHold.length == 0 || !laying)
		{
			return;
		}
		int fixed = 1, base = ropeFirst;
		Scratch work = scratch.get();
		double[] held = work.ropeHeld;
		boolean found = measure(out, base, held);
		if (!found)
		{
			// a first link too small to tell how it is turned: the rope starts where what it hangs on has its joint
			fixed = 0;
			base = parent[ropeFirst];
			found = base >= 0 && measure(out, base, held);
		}
		int links = n - fixed;
		if (!found || links < 1)
		{
			return;
		}
		double[] c0 = fit.centre[0][base];
		double[] r = work.ropeR;
		Quaternions.rotate(held, ropeNode[fixed][0] - c0[0], ropeNode[fixed][1] - c0[1], ropeNode[fixed][2] - c0[2], r);
		double[] from = {held[4] + r[0], held[5] + r[1], held[6] + r[2]}, to = new double[3], bulk = new double[3];
		for (int v : ropeHold)
		{
			for (int axis = 0; axis < 3; axis++)
			{
				to[axis] += (double) out[3 * v + axis] / ropeHold.length;
			}
		}
		int counted = 0;
		for (int i = 0; i < tied.length; i++)
		{
			if (ropeLinkOf[i] >= fixed)
			{
				counted++;
				for (int axis = 0; axis < 3; axis++)
				{
					bulk[axis] += out[3 * tied[i] + axis];
				}
			}
		}
		double[] reach = new double[links], along = new double[links + 1];
		for (int i = 0; i < links; i++)
		{
			double[] a = ropeNode[fixed + i], b = ropeNode[fixed + i + 1];
			reach[i] = Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
			along[i + 1] = along[i] + reach[i];
		}
		double length = along[links];
		double[] way = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
		double apart = Math.sqrt(way[0] * way[0] + way[1] * way[1] + way[2] * way[2]);
		if (apart < 1e-6)
		{
			way = new double[]{1, 0, 0};
			apart = 1;
		}
		else
		{
			way = new double[]{way[0] / apart, way[1] / apart, way[2] / apart};
		}
		// to the side the pose has the rope's bulk off the straight way; down (y) where it is on it
		double[] side = new double[3], down = {0, 1, 0};
		double on = 0, fall = down[0] * way[0] + down[1] * way[1] + down[2] * way[2];
		for (int axis = 0; axis < 3; axis++)
		{
			side[axis] = bulk[axis] / Math.max(1, counted) - (from[axis] + to[axis]) / 2;
			on += side[axis] * way[axis];
		}
		double off = 0;
		for (int axis = 0; axis < 3; axis++)
		{
			side[axis] -= on * way[axis];
			off += side[axis] * side[axis];
		}
		off = Math.sqrt(off);
		double hangs = Math.max(0, 1 - off / ROPE_HANGS), norm = 0;
		for (int axis = 0; axis < 3; axis++)
		{
			side[axis] = (off > 1e-9 ? (1 - hangs) * side[axis] / off : 0) + hangs * (down[axis] - fall * way[axis]);
			norm += side[axis] * side[axis];
		}
		norm = Math.sqrt(norm);
		if (norm < 1e-6)
		{
			// straight down already: any side
			side = Math.abs(way[0]) < 0.9 ? new double[]{1 - way[0] * way[0], -way[0] * way[1], -way[0] * way[2]}
				: new double[]{-way[2] * way[0], -way[2] * way[1], 1 - way[2] * way[2]};
			norm = Math.sqrt(side[0] * side[0] + side[1] * side[1] + side[2] * side[2]);
		}
		side = new double[]{side[0] / norm, side[1] / norm, side[2] / norm};
		// the chain's shape: a catenary this long between ends this far apart (sinh(z) / z = length / apart, by halving)
		double span = Math.min(length, Math.max(apart, ROPE_NARROW * length)), ratio = length / span;
		// the chain's joints, each its own array (from and to are copied in, never shared)
		work.links(n);
		double[][] now = work.joints;
		if (ratio < 1 + 1e-7)
		{
			for (int i = 0; i <= links; i++)
			{
				double far = along[i] / length * Math.min(apart, length);
				now[i][0] = from[0] + far * way[0];
				now[i][1] = from[1] + far * way[1];
				now[i][2] = from[2] + far * way[2];
			}
		}
		else
		{
			double low = 0, high = 1;
			while (Math.sinh(high) / high < ratio)
			{
				high *= 2;
			}
			for (int round = 0; round < 60; round++)
			{
				double z = (low + high) / 2;
				if (Math.sinh(z) / z < ratio)
				{
					low = z;
				}
				else
				{
					high = z;
				}
			}
			double a = span / (low + high), top = Math.sqrt(a * a + length * length / 4);
			for (int i = 0; i <= links; i++)
			{
				double s = along[i] - length / 2, x = a * Math.log(s / a + Math.sqrt(s * s / (a * a) + 1)) * apart / span;
				double sag = top - Math.sqrt(a * a + s * s);
				now[i][0] = (from[0] + to[0]) / 2 + x * way[0] + sag * side[0];
				now[i][1] = (from[1] + to[1]) / 2 + x * way[1] + sag * side[1];
				now[i][2] = (from[2] + to[2]) / 2 + x * way[2] + sag * side[2];
			}
		}
		// the links back to their lengths: from the tip and from the start in turn until both ends hold
		for (int round = 0; ; round++)
		{
			System.arraycopy(from, 0, now[0], 0, 3);
			for (int i = 0; i < links; i++)
			{
				towards(now[i], now[i + 1], reach[i], now[i + 1]);
			}
			if (round == ROPE_ROUNDS || sq(now[links][0] - to[0]) + sq(now[links][1] - to[1]) + sq(now[links][2] - to[2]) < ROPE_CLOSE * ROPE_CLOSE)
			{
				break;
			}
			System.arraycopy(to, 0, now[links], 0, 3);
			for (int i = links - 1; i > 0; i--)
			{
				towards(now[i + 1], now[i], reach[i], now[i]);
			}
		}
		// each link whole on its two joints, turned the short way from the link before it
		double[] turn = held;
		double[][] turns = work.turns;
		double[] was = work.was, dir = work.dir, bend = work.bend;
		for (int i = 0; i < links; i++)
		{
			double[] a = ropeNode[fixed + i], b = ropeNode[fixed + i + 1];
			Quaternions.rotate(turn, b[0] - a[0], b[1] - a[1], b[2] - a[2], was);
			dir[0] = now[i + 1][0] - now[i][0];
			dir[1] = now[i + 1][1] - now[i][1];
			dir[2] = now[i + 1][2] - now[i][2];
			between(was, dir, bend);
			Quaternions.multiply(bend, turn, turns[fixed + i]);
			turn = turns[fixed + i];
		}
		double[] moved = work.ropeMoved;
		for (int i = 0; i < tied.length; i++)
		{
			int link = ropeLinkOf[i], v = tied[i];
			if (link < fixed)
			{
				continue;
			}
			double[] a = ropeNode[link], at = now[link - fixed];
			Quaternions.rotate(turns[link], keys[0][3 * v] - a[0], keys[0][3 * v + 1] - a[1], keys[0][3 * v + 2] - a[2], moved);
			out[3 * v] = (float) (at[0] + moved[0]);
			out[3 * v + 1] = (float) (at[1] + moved[1]);
			out[3 * v + 2] = (float) (at[2] + moved[2]);
		}
	}

	/**
	 * The shortest turn (w, x, y, z) that points one direction the way of another, into out; none for a direction of no
	 * length.
	 */
	private static void between(double[] d, double[] e, double[] out)
	{
		double dl = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), el = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
		double w = 0, x = 0, y = 0, z = 0, norm = 0;
		if (dl >= 1e-6 && el >= 1e-6)
		{
			w = 1 + (d[0] * e[0] + d[1] * e[1] + d[2] * e[2]) / (dl * el);
			x = (d[1] * e[2] - d[2] * e[1]) / (dl * el);
			y = (d[2] * e[0] - d[0] * e[2]) / (dl * el);
			z = (d[0] * e[1] - d[1] * e[0]) / (dl * el);
			norm = Math.sqrt(w * w + x * x + y * y + z * z);
		}
		if (dl < 1e-6 || el < 1e-6 || w < 1e-6)
		{
			out[0] = 1;
			out[1] = 0;
			out[2] = 0;
			out[3] = 0;
			return;
		}
		out[0] = w / norm;
		out[1] = x / norm;
		out[2] = y / norm;
		out[3] = z / norm;
	}

	/** The point this far from a point, towards another; into out (which may be to, not from). */
	private static void towards(double[] from, double[] to, double far, double[] out)
	{
		double x = to[0] - from[0], y = to[1] - from[1], z = to[2] - from[2], length = Math.sqrt(x * x + y * y + z * z);
		double scale = length > 1e-9 ? far / length : 0;
		out[0] = from[0] + scale * x;
		out[1] = from[1] + scale * y;
		out[2] = from[2] + scale * z;
	}

	/** A tie (see tie) is between parts at least this many joints apart along the tree. */
	private static final int TIE_JOINTS = 4;
	/** A rope's tip (see rope) is its vertices within this of the one furthest along it (model units), this many at least. */
	private static final double ROPE_TIP = 2.5;
	private static final int ROPE_TIPS = 3;
	/** Vertices of two of a rope's parts this close in keyframe 0 touch: the way along the rope goes on there. */
	private static final double ROPE_TOUCH = 2;
	/** The end of a head's stub of rope: its outmost vertex and those within this of it (model units). */
	private static final double ROPE_END = 6;
	/** A rope's tip lies on its head's outmost end when it is usually within this part of the rope's length of it. */
	private static final double ROPE_HOLD_NEAR = 0.2;
	/** Tied vertices are next to each other in at least this part of the keyframes (the rest draw the rope stretched). */
	private static final double TIE_NEAR = 0.6;
	/**
	 * Tied vertex pairs: the rope's end (tieEnd) and what it is tied to (tieHold, most of them of part tieOn); where the
	 * rope's end lies on that part per keyframe, as the part is turned there (see measure).
	 */
	private int[] tieEnd = new int[0], tieHold = new int[0];
	private int tieOn;
	/**
	 * The vertices of the rope's head (see solid) the rope is tied to, when a hand holds the rope there (tieOn): the
	 * head's stub of rope, and where it lies on that hand per keyframe, as that hand is turned there (see measure).
	 */
	private int[] tieStub = new int[0];
	private double[][] stubAt;
	/** The tied rope's vertices (its parts' and what they carry). */
	private int[] tied = new int[0];
	/** Per vertex: of the tied rope (laid whole by rope(), so not checked for stretching, see stretched). */
	private boolean[] tiedEnd;
	/** The tied rope's parts, each after the one it hangs on. */
	private int[] tieParts;

	/**
	 * A rope held at both ends (a flail's rope from one hand to the other, the head hung past that): its links hang on
	 * each other from one hand only, each turned its own way between keyframes, so its end doesn't arrive where the
	 * keyframes have it, on the other hand: a gap in the rope. Found as vertices of parts far apart along the tree that
	 * are next to each other in most keyframes; the longer way up to where the two meet is the rope. What its end misses
	 * of the other hand is given along the rope (see poseSpline), none where it starts (its first part, the hand that
	 * holds it, stays), all at its end: each link, whole, turned about its joint to point at where its far end goes.
	 */
	private void tie(float[][] keys)
	{
		if (!rope)
		{
			return;
		}
		int[] depth = new int[parts];
		for (int p : order)
		{
			depth[p] = parent[p] >= 0 ? depth[parent[p]] + 1 : 0;
		}
		// per pair: the rope's vertex, the other, the rope's second part (its first part down from where the two meet stays)
		java.util.List<int[]> found = new java.util.ArrayList<>();
		java.util.Map<Integer, Integer> votes = new java.util.HashMap<>();
		for (int[] pair : nearPairs)
		{
			int pa = partOf[pair[0]], pb = partOf[pair[1]];
			if (pa < 0 || pb < 0 || pa == pb)
			{
				continue;
			}
			int near = 0;
			for (float[] key : keys)
			{
				near += Rules.pair(key, pair[0], pair[1]) <= JOINT_NEAR ? 1 : 0;
			}
			if (near < TIE_NEAR * keyframes)
			{
				continue;
			}
			int a = pa, b = pb, second = -1, other = -1;
			while (a != b && a >= 0 && b >= 0)
			{
				if (depth[a] >= depth[b])
				{
					second = a;
					a = parent[a];
				}
				else
				{
					other = b;
					b = parent[b];
				}
			}
			if (a < 0 || b < 0 || second < 0 || other < 0)
			{
				// different trees, or one hangs on the other
				continue;
			}
			int up = depth[pa] - depth[a], down = depth[pb] - depth[a];
			if (up + down < TIE_JOINTS || up == down)
			{
				continue;
			}
			boolean first = up > down;
			// the rope's second part: one down from the child of where the two meet
			int link = first ? pa : pb;
			while (depth[link] > depth[a] + 2)
			{
				link = parent[link];
			}
			if (depth[link] != depth[a] + 2)
			{
				continue;
			}
			found.add(new int[]{first ? pair[0] : pair[1], first ? pair[1] : pair[0], link});
			votes.merge(link, 1, Integer::sum);
		}
		if (found.isEmpty())
		{
			return;
		}
		int start = java.util.Collections.max(votes.entrySet(), java.util.Map.Entry.comparingByValue()).getKey();
		found.removeIf(pair -> pair[2] != start);
		// how far along the rope each of its parts' joints is
		double[] along = new double[parts];
		Arrays.fill(along, -1);
		along[start] = 0;
		for (int p : order)
		{
			int up = parent[p];
			if (p != start && up >= 0 && along[up] >= 0)
			{
				along[p] = along[up] + Math.sqrt(sq(pivot[p][0] - pivot[up][0]) + sq(pivot[p][1] - pivot[up][1]) + sq(pivot[p][2] - pivot[up][2]));
			}
		}
		double[] at = new double[vertices];
		Arrays.fill(at, -1);
		for (int v = 0; v < vertices; v++)
		{
			int p = partOf[v] >= 0 ? partOf[v] : follows[v];
			if (p >= 0 && along[p] >= 0)
			{
				at[v] = along[p] + Math.sqrt(sq(keys[0][3 * v] - pivot[p][0]) + sq(keys[0][3 * v + 1] - pivot[p][1]) + sq(keys[0][3 * v + 2] - pivot[p][2]));
			}
		}
		double length = 0;
		for (int[] pair : found)
		{
			length += at[pair[0]] / found.size();
		}
		if (length <= 0)
		{
			return;
		}
		tieEnd = found.stream().mapToInt(pair -> pair[0]).toArray();
		tieHold = found.stream().mapToInt(pair -> pair[1]).toArray();
		java.util.Map<Integer, Integer> holds = new java.util.HashMap<>();
		int headPart = head.length > 0 ? partOf[head[0]] : -1;
		for (int v : tieHold)
		{
			// the hand that holds it rather than the head: the head's stub of rope slides out through that hand
			holds.merge(partOf[v], partOf[v] == headPart ? 1 : vertices, Integer::sum);
		}
		tieOn = java.util.Collections.max(holds.entrySet(), java.util.Map.Entry.comparingByValue()).getKey();
		int[] stub = tieOn == headPart ? new int[0] : java.util.Arrays.stream(tieHold).filter(v -> partOf[v] == headPart).distinct().toArray();
		stubAt = new double[keyframes][];
		for (int k = 0; k < keyframes; k++)
		{
			double[] held = measure(keys[k], tieOn);
			if (held == null)
			{
				tieEnd = new int[0];
				tieHold = new int[0];
				return;
			}
			double[] base = new double[3];
			for (int axis = 0; axis < 3; axis++)
			{
				for (int v : stub)
				{
					base[axis] += (double) keys[k][3 * v + axis] / stub.length;
				}
			}
			double[] back = Quaternions.conjugate(held);
			stubAt[k] = Quaternions.rotate(back, base[0] - held[4], base[1] - held[5], base[2] - held[6]);
		}
		tieStub = stub;
		// the rope's parts, each after the one it hangs on (its first is first)
		tieParts = java.util.Arrays.stream(order).filter(p -> along[p] >= 0).toArray();
		tied = java.util.stream.IntStream.range(0, vertices).filter(v -> at[v] >= 0).toArray();
		tiedEnd = new boolean[vertices];
		for (int v : tied)
		{
			// the whole rope is laid by rope(): it can't run off anywhere (see stretched)
			tiedEnd[v] = true;
		}
		// its tip: its vertices furthest along it from where it starts, the way measured over the rope itself (within a
		// part straight; from part to part where they touch, else where the one is nearest the one it hangs on)
		int[] headVertices = headPart >= 0 ? members[headPart] : new int[0];
		int m = tied.length;
		if (headVertices.length == 0 || m < ROPE_TIPS)
		{
			return;
		}
		int[] of = new int[m];
		double[][] step = new double[m][m];
		int source = 0;
		for (int i = 0; i < m; i++)
		{
			of[i] = partOf[tied[i]] >= 0 ? partOf[tied[i]] : follows[tied[i]];
			Arrays.fill(step[i], Double.MAX_VALUE);
			source = sq(keys[0][3 * tied[i]] - pivot[start][0]) + sq(keys[0][3 * tied[i] + 1] - pivot[start][1]) + sq(keys[0][3 * tied[i] + 2] - pivot[start][2])
				< sq(keys[0][3 * tied[source]] - pivot[start][0]) + sq(keys[0][3 * tied[source] + 1] - pivot[start][1]) + sq(keys[0][3 * tied[source] + 2] - pivot[start][2]) ? i : source;
		}
		int[] slot = new int[vertices];
		Arrays.fill(slot, -1);
		for (int i = 0; i < m; i++)
		{
			slot[tied[i]] = i;
		}
		for (int e = 0; e + 1 < edges.length; e += 2)
		{
			int a = slot[edges[e]], b = slot[edges[e + 1]];
			if (a >= 0 && b >= 0 && a != b)
			{
				step[a][b] = step[b][a] = Rules.pair(keys[0], tied[a], tied[b]);
			}
		}
		for (int i = 0; i < m; i++)
		{
			for (int j = i + 1; j < m; j++)
			{
				double d = Rules.pair(keys[0], tied[i], tied[j]);
				if (d <= ROPE_TOUCH)
				{
					step[i][j] = step[j][i] = d;
				}
			}
		}
		double[] gone = new double[m];
		Arrays.fill(gone, Double.MAX_VALUE);
		gone[source] = 0;
		double furthest = 0;
		for (int pieces = 0; pieces < m; pieces++)
		{
			boolean[] done = new boolean[m];
			for (int round = 0; round < m; round++)
			{
				int u = -1;
				for (int i = 0; i < m; i++)
				{
					if (!done[i] && gone[i] < Double.MAX_VALUE && (u < 0 || gone[i] < gone[u]))
					{
						u = i;
					}
				}
				if (u < 0)
				{
					break;
				}
				done[u] = true;
				furthest = Math.max(furthest, gone[u]);
				for (int i = 0; i < m; i++)
				{
					if (step[u][i] < Double.MAX_VALUE && gone[u] + step[u][i] < gone[i])
					{
						gone[i] = gone[u] + step[u][i];
					}
				}
			}
			// a piece of the rope's mesh not joined to the rest: on from where it is nearest what is reached
			int from = -1, to = -1;
			double least = Double.MAX_VALUE;
			for (int i = 0; i < m; i++)
			{
				for (int j = 0; j < m && gone[i] < Double.MAX_VALUE; j++)
				{
					double d = gone[j] < Double.MAX_VALUE ? Double.MAX_VALUE : Rules.pair(keys[0], tied[i], tied[j]);
					if (d < least)
					{
						least = d;
						from = i;
						to = j;
					}
				}
			}
			if (from < 0)
			{
				break;
			}
			step[from][to] = step[to][from] = least;
		}
		double reached = furthest;
		Integer[] byGone = java.util.stream.IntStream.range(0, m).filter(i -> gone[i] <= reached).boxed().toArray(Integer[]::new);
		Arrays.sort(byGone, (a, b) -> Double.compare(gone[b], gone[a]));
		if (byGone.length < ROPE_TIPS)
		{
			return;
		}
		int tips = 0;
		while (tips < byGone.length && (tips < ROPE_TIPS || gone[byGone[tips]] >= furthest - ROPE_TIP))
		{
			tips++;
		}
		java.util.Map<Integer, Integer> tipParts = new java.util.HashMap<>();
		double[][] tipAt = new double[keyframes][3];
		for (int i = 0; i < tips; i++)
		{
			int v = tied[byGone[i]];
			tipParts.merge(of[byGone[i]], 1, Integer::sum);
			for (int k = 0; k < keyframes; k++)
			{
				for (int axis = 0; axis < 3; axis++)
				{
					tipAt[k][axis] += (double) keys[k][3 * v + axis] / tips;
				}
			}
		}
		double[] tip = tipAt[0], apart = new double[keyframes];
		// the chain: the parts on the way from the first to the tip's; a part whose joint is too close to the last
		// link's or to the tip rides on that link, as do the parts off the way
		java.util.List<Integer> way = new java.util.ArrayList<>();
		for (int p = java.util.Collections.max(tipParts.entrySet(), java.util.Map.Entry.comparingByValue()).getKey(); p != start; p = parent[p])
		{
			if (p < 0 || along[p] < 0)
			{
				return;
			}
			way.add(0, p);
		}
		int[] linkOf = new int[parts];
		Arrays.fill(linkOf, -1);
		java.util.List<double[]> nodes = new java.util.ArrayList<>();
		nodes.add(pivot[start]);
		linkOf[start] = 0;
		for (int next : way)
		{
			double[] node = nodes.get(nodes.size() - 1);
			double from = Math.sqrt(sq(pivot[next][0] - node[0]) + sq(pivot[next][1] - node[1]) + sq(pivot[next][2] - node[2]));
			double left = Math.sqrt(sq(pivot[next][0] - tip[0]) + sq(pivot[next][1] - tip[1]) + sq(pivot[next][2] - tip[2]));
			if (from >= ROPE_LINK * length && left >= ROPE_TAIL * length)
			{
				nodes.add(pivot[next]);
			}
			linkOf[next] = nodes.size() - 1;
		}
		nodes.add(tip);
		for (int p : tieParts)
		{
			if (linkOf[p] < 0)
			{
				linkOf[p] = linkOf[parent[p]];
			}
		}
		ropeLinkOf = new int[tied.length];
		for (int i = 0; i < tied.length; i++)
		{
			ropeLinkOf[i] = linkOf[partOf[tied[i]] >= 0 ? partOf[tied[i]] : follows[tied[i]]];
		}
		// where its tip lies on the head: the end of the head's stub of rope, its vertices furthest from its middle
		// (the stub reaches further than the ball's spikes), when the tip is usually near that; else the head's
		// vertices that are nearest the tip in most keyframes (the median: the keyframes that fold the rope's end back
		// along the stub, or draw it loose, don't count)
		double[] usual = new double[headVertices.length], out = new double[headVertices.length], middle = new double[3];
		Integer[] nearest = new Integer[headVertices.length], outmost = new Integer[headVertices.length];
		for (int v : headVertices)
		{
			for (int axis = 0; axis < 3; axis++)
			{
				middle[axis] += (double) keys[0][3 * v + axis] / headVertices.length;
			}
		}
		for (int i = 0; i < headVertices.length; i++)
		{
			int v = headVertices[i];
			for (int k = 0; k < keyframes; k++)
			{
				apart[k] = Math.sqrt(sq(keys[k][3 * v] - tipAt[k][0]) + sq(keys[k][3 * v + 1] - tipAt[k][1]) + sq(keys[k][3 * v + 2] - tipAt[k][2]));
			}
			double[] sorted = apart.clone();
			Arrays.sort(sorted);
			usual[i] = sorted[keyframes / 2];
			out[i] = Math.sqrt(sq(keys[0][3 * v] - middle[0]) + sq(keys[0][3 * v + 1] - middle[1]) + sq(keys[0][3 * v + 2] - middle[2]));
			nearest[i] = i;
			outmost[i] = i;
		}
		Arrays.sort(nearest, (a, b) -> Double.compare(usual[a], usual[b]));
		Arrays.sort(outmost, (a, b) -> Double.compare(out[b], out[a]));
		java.util.List<Integer> hold = new java.util.ArrayList<>();
		for (int i = 0; i < outmost.length && hold.size() < ROPE_HOLD; i++)
		{
			if (Rules.pair(keys[0], headVertices[outmost[i]], headVertices[outmost[0]]) <= ROPE_END)
			{
				hold.add(outmost[i]);
			}
		}
		boolean stubEnd = usual[outmost[0]] <= ROPE_HOLD_NEAR * length;
		for (int i = 0; !stubEnd && i < ROPE_HOLD && i < nearest.length; i++)
		{
			if (i == 0)
			{
				hold.clear();
			}
			hold.add(nearest[i]);
		}
		ropeHold = hold.stream().mapToInt(i -> headVertices[i]).toArray();
		ropeNode = nodes.toArray(new double[0][]);
		ropeFirst = start;
		double chain = 0;
		StringBuilder lengths = new StringBuilder();
		for (int i = 0; i + 1 < ropeNode.length; i++)
		{
			double link = Math.sqrt(sq(ropeNode[i + 1][0] - ropeNode[i][0]) + sq(ropeNode[i + 1][1] - ropeNode[i][1]) + sq(ropeNode[i + 1][2] - ropeNode[i][2]));
			chain += link;
			lengths.append(String.format(" %.0f", link));
		}
		splits.append(String.format("  tie: %d pairs, rope from part %d, %.0f long, %d vertices, held on part %d, stub %d; chain of %d links%s (%.0f), tip of %d vertices on %d of the head (usually %.1f off), %.0f along the rope%n",
			found.size(), start, length, tied.length, tieOn, tieStub.length, ropeNode.length - 1, lengths, chain, tips, ropeHold.length,
			usual[hold.get(0)], furthest));
	}

	/**
	 * Puts a rope's head as another pose has it (a moment of its own, see ActorDrawer.moment) into this one: turned and
	 * stretched as there, its end of the rope kept where this pose has it (on the hand that holds it).
	 */
	void headFrom(float[] out, float[] other)
	{
		if (head.length == 0 || partOf[head[0]] < 0)
		{
			return;
		}
		int part = partOf[head[0]];
		double[] shift = new double[3];
		int held = 0;
		for (int pass = 0; pass < 2 && held == 0; pass++)
		{
			// by its vertices the rope is tied to; without those, by all of it
			for (int v : pass == 0 ? tieHold : head)
			{
				if (partOf[v] == part)
				{
					for (int axis = 0; axis < 3; axis++)
					{
						shift[axis] += out[3 * v + axis] - other[3 * v + axis];
					}
					held++;
				}
			}
		}
		for (int v = 0; v < vertices; v++)
		{
			if (partOf[v] == part)
			{
				for (int axis = 0; axis < 3; axis++)
				{
					out[3 * v + axis] = (float) (other[3 * v + axis] + shift[axis] / held);
				}
			}
		}
	}

	/**
	 * Flings a rope's head on past keyframe k + 1 the way step k flings it out (e of the step): every vertex of it as
	 * much further from its end of the rope (which stays in the hand) as the step takes it, turned as the head is now.
	 */
	void fling(float[] out, int k, double e)
	{
		if (e == 0 || k < 0 || k >= keyframes - 1 || head.length == 0 || partOf[head[0]] < 0)
		{
			return;
		}
		int part = partOf[head[0]];
		Scratch work = scratch.get();
		double[] now = work.flingNow, then = work.flingThen;
		if (!measure(out, part, now) || !measure(keys[k + 1], part, then))
		{
			return;
		}
		double[] back = work.flingBack, turned = work.flingTurn;
		back[0] = then[0];
		back[1] = -then[1];
		back[2] = -then[2];
		back[3] = -then[3];
		Quaternions.multiply(now, back, turned);
		int[] held = headHeld();
		double[] shift = work.flingShift, on = work.flingOn;
		java.util.Arrays.fill(shift, 0);
		for (int v : held)
		{
			for (int axis = 0; axis < 3; axis++)
			{
				shift[axis] += (double) (keys[k + 1][3 * v + axis] - keys[k][3 * v + axis]) / held.length;
			}
		}
		for (int v : members[part])
		{
			Quaternions.rotate(turned, e * (keys[k + 1][3 * v] - keys[k][3 * v] - shift[0]),
				e * (keys[k + 1][3 * v + 1] - keys[k][3 * v + 1] - shift[1]), e * (keys[k + 1][3 * v + 2] - keys[k][3 * v + 2] - shift[2]), on);
			for (int axis = 0; axis < 3; axis++)
			{
				out[3 * v + axis] += (float) on[axis];
			}
		}
	}

	/** The head's vertices its rope is tied to (see headFrom); without those, all of it. */
	int[] headHeld()
	{
		if (head.length == 0 || partOf[head[0]] < 0)
		{
			return head;
		}
		int[] held = java.util.Arrays.stream(tieHold).filter(v -> partOf[v] == partOf[head[0]]).distinct().toArray();
		return held.length > 0 ? held : head;
	}

	/** Whether this is a rope's animation (see Rules.ropes). */
	boolean roped()
	{
		return rope;
	}

	/** A rope's head (see solid) is at least this many vertices. */
	private static final int HEAD_LEAST = 40;
	/** A rope's head is solid in at least this part of the keyframes (the rest stretch it: a smear as it is flung out). */
	private static final double HEAD_SOLID = 0.8;
	/** The vertices of the rope's head (see solid), all in one part; empty: none. */
	private final int[] head;

	int[] head()
	{
		return head;
	}

	/**
	 * A rope's head: the vertices that keep their distance to the one that travels furthest (and to two more of them, far
	 * apart) in most keyframes: one solid thing (a flail's ball with the stub of rope it is rigged with), though its mesh
	 * is many loose pieces (spikes, plates) and a keyframe or two draw it stretched. Empty: none.
	 */
	private static int[] solid(float[][] keys)
	{
		int vertices = keys[0].length / 3;
		int far = -1;
		double most = 0;
		for (int v = 0; v < vertices; v++)
		{
			double travel = 0;
			for (int k = 1; k < keys.length; k++)
			{
				travel += Math.sqrt(sq(keys[k][3 * v] - keys[k - 1][3 * v]) + sq(keys[k][3 * v + 1] - keys[k - 1][3 * v + 1]) + sq(keys[k][3 * v + 2] - keys[k - 1][3 * v + 2]));
			}
			if (travel > most)
			{
				most = travel;
				far = v;
			}
		}
		java.util.List<Integer> in = new java.util.ArrayList<>();
		for (int v = 0; v < vertices && far >= 0; v++)
		{
			in.add(v);
		}
		int ref = far;
		double[] apart = new double[keys.length];
		for (int round = 0; round < 3 && ref >= 0; round++)
		{
			int from = ref, next = -1;
			double furthest = 0;
			java.util.List<Integer> kept = new java.util.ArrayList<>();
			for (int v : in)
			{
				for (int k = 0; k < keys.length; k++)
				{
					apart[k] = Rules.pair(keys[k], v, from);
				}
				double[] sorted = apart.clone();
				Arrays.sort(sorted);
				double usual = sorted[sorted.length / 2];
				int same = 0;
				for (double d : apart)
				{
					same += Math.abs(d - usual) <= Math.max(0.5, 0.01 * usual) ? 1 : 0;
				}
				if (same >= HEAD_SOLID * keys.length)
				{
					kept.add(v);
					if (usual > furthest)
					{
						furthest = usual;
						next = v;
					}
				}
			}
			in = kept;
			ref = next;
		}
		return in.size() >= HEAD_LEAST ? in.stream().mapToInt(Integer::intValue).toArray() : new int[0];
	}

	/**
	 * For each vertex in no part that can be fitted: the moving part that carries it best (whose motion, applied to where
	 * it was in keyframe 0, misses it least over the keyframes), taken per piece (fitted group, else the vertex alone) so
	 * a piece stays whole; else -1.
	 */
	private int[] carriers(float[][] keys, boolean[] usable, int[] pieceOf)
	{
		int[] carrier = new int[vertices];
		Arrays.fill(carrier, -1);
		Arrays.fill(pieceOf, -1);
		int pieceCount = 0;
		java.util.Map<Integer, java.util.List<Integer>> pieces = new java.util.HashMap<>();
		for (int v = 0; v < vertices; v++)
		{
			int g = group[v];
			if (g >= 0 && driven[g])
			{
				continue;
			}
			pieces.computeIfAbsent(g >= 0 ? g : -2 - v, key -> new java.util.ArrayList<>()).add(v);
		}
		for (java.util.List<Integer> piece : pieces.values())
		{
			// the piece's centre in every keyframe
			double[][] centre = new double[keyframes][3];
			for (int k = 0; k < keyframes; k++)
			{
				for (int v : piece)
				{
					centre[k][0] += keys[k][3 * v] / piece.size();
					centre[k][1] += keys[k][3 * v + 1] / piece.size();
					centre[k][2] += keys[k][3 * v + 2] / piece.size();
				}
			}
			int best = -1;
			double bestMiss = Double.MAX_VALUE;
			for (int p = 0; p < parts; p++)
			{
				if (!driven[p])
				{
					continue;
				}
				double[] local = fit.local(p, 0, centre[0][0], centre[0][1], centre[0][2]);
				double miss = 0;
				for (int k = 1; k < keyframes && miss < bestMiss; k++)
				{
					double[] at = fit.place(p, k, local[0], local[1], local[2]);
					miss = Math.max(miss, Math.sqrt(sq(at[0] - centre[k][0]) + sq(at[1] - centre[k][1]) + sq(at[2] - centre[k][2])));
				}
				if (miss < bestMiss)
				{
					bestMiss = miss;
					best = p;
				}
			}
			int g = group[piece.get(0)];
			if (g >= 0 && usable[g])
			{
				// a part that fits but hardly moves: only carried where a moving part takes it along exactly
				if (best < 0 || !carriesAll(best, piece))
				{
					continue;
				}
				for (int v : piece)
				{
					quiet[v] = true;
				}
			}
			if (best < 0)
			{
				continue;
			}
			for (int v : piece)
			{
				carrier[v] = best;
				pieceOf[v] = pieceCount;
			}
			pieceCount++;
		}
		return carrier;
	}

	/**
	 * The rigid bodies a piece is made of: groups of its vertices that each keep their distance from every other vertex
	 * of the group (as an edge does, see components) over every keyframe, grown from the vertex with the most such
	 * partners; 0 for the largest body, then 1, 2, ... (the vertices in no body of three or more go together, last);
	 * -1 for vertices of other pieces.
	 */
	private static int[] rigidBodies(float[][] keys, int[] piece, int pc)
	{
		int vertices = keys[0].length / 3;
		int[] members = java.util.stream.IntStream.range(0, vertices).filter(v -> piece[v] == pc).toArray();
		int n = members.length;
		boolean[][] rigid = new boolean[n][n];
		int[] partners = new int[n];
		for (int i = 0; i < n; i++)
		{
			for (int j = i + 1; j < n; j++)
			{
				int a = members[i], b = members[j];
				double min = Double.MAX_VALUE, max = 0;
				for (float[] key : keys)
				{
					double d = Math.sqrt(sq(key[3 * a] - key[3 * b]) + sq(key[3 * a + 1] - key[3 * b + 1]) + sq(key[3 * a + 2] - key[3 * b + 2]));
					min = Math.min(min, d);
					max = Math.max(max, d);
				}
				if (max - min <= Math.max(EDGE_UNITS, EDGE_STRICT * max))
				{
					rigid[i][j] = rigid[j][i] = true;
					partners[i]++;
					partners[j]++;
				}
			}
		}
		int[] bodyOf = new int[n];
		Arrays.fill(bodyOf, -1);
		java.util.List<int[]> bodies = new java.util.ArrayList<>();
		while (true)
		{
			int seed = -1;
			for (int i = 0; i < n; i++)
			{
				if (bodyOf[i] < 0 && (seed < 0 || partners[i] > partners[seed]))
				{
					seed = i;
				}
			}
			if (seed < 0 || partners[seed] < 2)
			{
				break;
			}
			// grown from the seed: every candidate must keep its distance to all taken so far
			int from = seed;
			java.util.List<Integer> taken = new java.util.ArrayList<>();
			taken.add(from);
			Integer[] candidates = java.util.stream.IntStream.range(0, n).filter(i -> bodyOf[i] < 0 && rigid[from][i]).boxed()
				.toArray(Integer[]::new);
			Arrays.sort(candidates, (x, y) -> partners[y] - partners[x]);
			for (int c : candidates)
			{
				boolean fits = true;
				for (int t : taken)
				{
					fits &= rigid[c][t];
				}
				if (fits)
				{
					taken.add(c);
				}
			}
			for (int t : taken)
			{
				bodyOf[t] = bodies.size();
				for (int i = 0; i < n; i++)
				{
					partners[i] -= rigid[t][i] ? 1 : 0;
				}
			}
			bodies.add(taken.stream().mapToInt(Integer::intValue).toArray());
		}
		// largest first; the vertices in no body go together, last
		Integer[] order = java.util.stream.IntStream.range(0, bodies.size()).boxed().toArray(Integer[]::new);
		Arrays.sort(order, (x, y) -> bodies.get(y).length - bodies.get(x).length);
		int[] rank = new int[bodies.size()];
		for (int r = 0; r < order.length; r++)
		{
			rank[order[r]] = r;
		}
		int[] body = new int[vertices];
		Arrays.fill(body, -1);
		for (int i = 0; i < n; i++)
		{
			body[members[i]] = bodyOf[i] >= 0 ? rank[bodyOf[i]] : bodies.size();
		}
		return body;
	}

	/** Groups of vertices joined by edges whose length changes by at most part of itself over all keyframes. */
	private static int[] components(float[][] keys, int[] faces, double part)
	{
		int vertices = keys[0].length / 3;
		int[] root = new int[vertices];
		for (int v = 0; v < vertices; v++)
		{
			root[v] = v;
		}
		for (int f = 0; f + 2 < faces.length; f += 3)
		{
			for (int e = 0; e < 3; e++)
			{
				int a = faces[f + e], b = faces[f + (e + 1) % 3];
				if (a >= vertices || b >= vertices || a == b)
				{
					continue;
				}
				double min = Double.MAX_VALUE, max = 0;
				for (float[] key : keys)
				{
					double d = Math.sqrt(sq(key[3 * a] - key[3 * b]) + sq(key[3 * a + 1] - key[3 * b + 1]) + sq(key[3 * a + 2] - key[3 * b + 2]));
					min = Math.min(min, d);
					max = Math.max(max, d);
				}
				if (max - min <= Math.max(EDGE_UNITS, part * max))
				{
					union(root, a, b);
				}
			}
		}
		int[] group = new int[vertices];
		for (int v = 0; v < vertices; v++)
		{
			group[v] = find(root, v);
		}
		return renumber(group);
	}

	/** Numbers groups 0, 1, 2, ... in order of appearance; -1 stays -1. */
	private static int[] renumber(int[] group)
	{
		int[] index = new int[Math.max(group.length, count(group)) + 1];
		Arrays.fill(index, -1);
		int[] out = new int[group.length];
		int next = 0;
		for (int v = 0; v < group.length; v++)
		{
			if (group[v] < 0)
			{
				out[v] = -1;
				continue;
			}
			if (index[group[v]] < 0)
			{
				index[group[v]] = next++;
			}
			out[v] = index[group[v]];
		}
		return out;
	}

	private static int count(int[] group)
	{
		int max = -1;
		for (int g : group)
		{
			max = Math.max(max, g);
		}
		return max + 1;
	}

	private static double sq(double x)
	{
		return x * x;
	}

	private static int find(int[] root, int v)
	{
		while (root[v] != v)
		{
			root[v] = root[root[v]];
			v = root[v];
		}
		return v;
	}

	private static void union(int[] root, int a, int b)
	{
		int ra = find(root, a), rb = find(root, b);
		if (ra != rb)
		{
			root[ra] = rb;
		}
	}

	/** Turn, stretch and centre of groups of vertices per keyframe, fitted to the keyframe poses (keyframe 0 is rest). */
	static final class Fit
	{
		final float[][] keys;
		final int[] group;
		final int[] members;
		/** Each vertex relative to its group's centre in keyframe 0. */
		final double[] rest;
		final double[][][] rotation;
		/** Symmetric stretch in the rest frame, row-major 3x3, applied before the turn. */
		final double[][][] stretch;
		final double[][][] centre;
		/** Largest distance of a vertex from its group's centre. */
		final double[] reach;

		Fit(float[][] keys, int[] group, int groups)
		{
			this.keys = keys;
			this.group = group;
			int frames = keys.length, vertices = keys[0].length / 3;
			members = new int[groups];
			centre = new double[frames][groups][3];
			rotation = new double[frames][groups][];
			stretch = new double[frames][groups][];
			rest = new double[3 * vertices];
			reach = new double[groups];
			for (int v = 0; v < vertices; v++)
			{
				if (group[v] >= 0)
				{
					members[group[v]]++;
				}
			}
			for (int k = 0; k < frames; k++)
			{
				for (int v = 0; v < vertices; v++)
				{
					int g = group[v];
					if (g >= 0)
					{
						centre[k][g][0] += keys[k][3 * v] / members[g];
						centre[k][g][1] += keys[k][3 * v + 1] / members[g];
						centre[k][g][2] += keys[k][3 * v + 2] / members[g];
					}
				}
			}
			double[][] spread = new double[groups][9];
			for (int v = 0; v < vertices; v++)
			{
				int g = group[v];
				if (g < 0)
				{
					continue;
				}
				double x = keys[0][3 * v] - centre[0][g][0], y = keys[0][3 * v + 1] - centre[0][g][1], z = keys[0][3 * v + 2] - centre[0][g][2];
				rest[3 * v] = x;
				rest[3 * v + 1] = y;
				rest[3 * v + 2] = z;
				reach[g] = Math.max(reach[g], Math.sqrt(x * x + y * y + z * z));
				outer(spread[g], x, y, z, x, y, z);
			}
			for (int k = 0; k < frames; k++)
			{
				double[][] cross = new double[groups][9];
				for (int v = 0; v < vertices; v++)
				{
					int g = group[v];
					if (g < 0)
					{
						continue;
					}
					double[] c = centre[k][g];
					outer(cross[g], rest[3 * v], rest[3 * v + 1], rest[3 * v + 2],
						keys[k][3 * v] - c[0], keys[k][3 * v + 1] - c[1], keys[k][3 * v + 2] - c[2]);
				}
				for (int g = 0; g < groups; g++)
				{
					double[] q = members[g] >= 3 ? Quaternions.fit(cross[g]) : new double[]{1, 0, 0, 0};
					// the short way from the last keyframe
					if (k > 0 && Quaternions.dot(q, rotation[k - 1][g]) < 0)
					{
						q = Quaternions.negate(q);
					}
					rotation[k][g] = q;
				}
				// stretch: least squares of (turned back) target against rest, pulled towards none where the part is flat
				double[][] back = new double[groups][9];
				for (int v = 0; v < vertices; v++)
				{
					int g = group[v];
					if (g < 0)
					{
						continue;
					}
					double[] c = centre[k][g];
					double[] t = Quaternions.rotate(Quaternions.conjugate(rotation[k][g]),
						keys[k][3 * v] - c[0], keys[k][3 * v + 1] - c[1], keys[k][3 * v + 2] - c[2]);
					outer(back[g], t[0], t[1], t[2], rest[3 * v], rest[3 * v + 1], rest[3 * v + 2]);
				}
				for (int g = 0; g < groups; g++)
				{
					if (k == 0 || members[g] < 4)
					{
						stretch[k][g] = new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
						continue;
					}
					double[] m = spread[g];
					double mu = 1e-3 * (m[0] + m[4] + m[8]) + 1e-6;
					double[] lhs = back[g].clone();
					double[] rhs = m.clone();
					for (int d = 0; d < 9; d += 4)
					{
						lhs[d] += mu;
						rhs[d] += mu;
					}
					double[] s = multiply(lhs, inverse(rhs));
					// keep it symmetric: a stretch, not a turn
					stretch[k][g] = new double[]{s[0], (s[1] + s[3]) / 2, (s[2] + s[6]) / 2, (s[1] + s[3]) / 2, s[4],
						(s[5] + s[7]) / 2, (s[2] + s[6]) / 2, (s[5] + s[7]) / 2, s[8]};
				}
			}
		}

		/** sum += (a)(b)^T */
		static void outer(double[] sum, double ax, double ay, double az, double bx, double by, double bz)
		{
			sum[0] += ax * bx;
			sum[1] += ax * by;
			sum[2] += ax * bz;
			sum[3] += ay * bx;
			sum[4] += ay * by;
			sum[5] += ay * bz;
			sum[6] += az * bx;
			sum[7] += az * by;
			sum[8] += az * bz;
		}

		/** Where group g puts a point given relative to its rest centre, at keyframe k. */
		double[] place(int g, int k, double x, double y, double z)
		{
			double[] s = stretch[k][g];
			double[] r = Quaternions.rotate(rotation[k][g], s[0] * x + s[1] * y + s[2] * z, s[3] * x + s[4] * y + s[5] * z,
				s[6] * x + s[7] * y + s[8] * z);
			double[] c = centre[k][g];
			return new double[]{r[0] + c[0], r[1] + c[1], r[2] + c[2]};
		}

		/** The point (in group g's rest frame, relative to its rest centre) that group g puts at x, y, z in keyframe k. */
		double[] local(int g, int k, double x, double y, double z)
		{
			double[] c = centre[k][g];
			double[] r = Quaternions.rotate(Quaternions.conjugate(rotation[k][g]), x - c[0], y - c[1], z - c[2]);
			double[] inv = inverse(stretch[k][g]);
			return new double[]{
				inv[0] * r[0] + inv[1] * r[1] + inv[2] * r[2],
				inv[3] * r[0] + inv[4] * r[1] + inv[5] * r[2],
				inv[6] * r[0] + inv[7] * r[1] + inv[8] * r[2],
			};
		}

		/** How far group g's motion misses vertex v (rest taken from keyframe 0), at worst over the keyframes. */
		double miss(int g, int v)
		{
			double[] c0 = centre[0][g];
			double x = keys[0][3 * v] - c0[0], y = keys[0][3 * v + 1] - c0[1], z = keys[0][3 * v + 2] - c0[2];
			double worst = 0;
			for (int k = 1; k < keys.length; k++)
			{
				double[] p = place(g, k, x, y, z);
				worst = Math.max(worst, Math.sqrt(sq(p[0] - keys[k][3 * v]) + sq(p[1] - keys[k][3 * v + 1]) + sq(p[2] - keys[k][3 * v + 2])));
			}
			return worst;
		}

		private double tolerance(double distance)
		{
			return FIT_UNITS + FIT_PART * distance;
		}

		/** Whether group g's motion moves vertex v onto its keyframe poses (within the tolerance for its reach). */
		boolean inlier(int g, int v)
		{
			double d = Math.sqrt(sq(rest[3 * v]) + sq(rest[3 * v + 1]) + sq(rest[3 * v + 2]));
			return miss(g, v) <= tolerance(d);
		}

		/** The half of group g its motion fits best, as a group of its own (0; the rest -1): the body it mostly is. */
		int[] core(int g)
		{
			int n = group.length;
			double[] misses = new double[n];
			int count = 0;
			for (int v = 0; v < n; v++)
			{
				misses[v] = group[v] == g ? miss(g, v) : Double.MAX_VALUE;
				count += group[v] == g ? 1 : 0;
			}
			double[] sorted = misses.clone();
			Arrays.sort(sorted);
			double cut = sorted[Math.max(0, count / 2 - 1)];
			int[] core = new int[n];
			for (int v = 0; v < n; v++)
			{
				core[v] = group[v] == g && misses[v] <= cut ? 0 : -1;
			}
			return core;
		}



		/** Per group: whether one turn and stretch per keyframe moves all its vertices onto the keyframe poses. */
		boolean[] fits()
		{
			boolean[] ok = new boolean[members.length];
			Arrays.fill(ok, true);
			for (int v = 0; v < group.length; v++)
			{
				int g = group[v];
				if (g >= 0 && ok[g] && members[g] >= 3)
				{
					double d = Math.sqrt(sq(rest[3 * v]) + sq(rest[3 * v + 1]) + sq(rest[3 * v + 2]));
					ok[g] = miss(g, v) <= tolerance(d);
				}
			}
			return ok;
		}

		/** Whether group a's motion also moves group b's vertices onto their keyframe poses (a few of them checked). */
		boolean carries(int a, int b)
		{
			int checked = 0, step = Math.max(1, members[b] / 8), seen = 0;
			for (int v = 0; v < group.length && checked < 8; v++)
			{
				if (group[v] != b || seen++ % step != 0)
				{
					continue;
				}
				double[] c0 = centre[0][a];
				double d = Math.sqrt(sq(keys[0][3 * v] - c0[0]) + sq(keys[0][3 * v + 1] - c0[1]) + sq(keys[0][3 * v + 2] - c0[2]));
				if (miss(a, v) > tolerance(d))
				{
					return false;
				}
				checked++;
			}
			return checked > 0;
		}

		/** The group whose motion moves vertex v best, if within tolerance; else -1. */
		int holding(int v)
		{
			int best = -1;
			double bestMiss = Double.MAX_VALUE;
			for (int g = 0; g < members.length; g++)
			{
				if (members[g] < 3)
				{
					continue;
				}
				double m = miss(g, v);
				double[] c0 = centre[0][g];
				double d = Math.sqrt(sq(keys[0][3 * v] - c0[0]) + sq(keys[0][3 * v + 1] - c0[1]) + sq(keys[0][3 * v + 2] - c0[2]));
				if (m <= tolerance(d) && m < bestMiss)
				{
					bestMiss = m;
					best = g;
				}
			}
			return best;
		}
	}

	private static double[] multiply(double[] a, double[] b)
	{
		double[] out = new double[9];
		for (int r = 0; r < 3; r++)
		{
			for (int c = 0; c < 3; c++)
			{
				out[3 * r + c] = a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c];
			}
		}
		return out;
	}

	private static double[] inverse(double[] m)
	{
		double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
		double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
		if (Math.abs(det) < 1e-12)
		{
			return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
		}
		return new double[]{
			(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
			(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
			(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
		};
	}
}
