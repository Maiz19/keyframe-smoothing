package com.keyframesmoothing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Actor;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;

/**
 * Works out an animation on a character's model before it plays, from the game's own data: the model built again as the
 * client builds it (what an NPC is made of, what a player wears, the kits of their body), then posed by the client
 * itself (Client.applyTransformations, as AnimationController does) at every keyframe and at points between keyframes as
 * the client blends them. Nothing has to be seen played. It is checked once against the model the client draws: the
 * same vertices in the same order, and the same pose at the keyframe it shows.
 *
 * <p>Must be called on the client thread, between frames: the client poses into models it shares.
 */
final class Poses
{
	/** Config archives: item and kit definitions. */
	private static final int ITEM_CONFIG = 10;
	private static final int KIT_CONFIG = 3;
	/**
	 * The player's equipment slots holding the shield and the weapon, which an animation can swap: the client puts its
	 * left hand's item in the shield's, its right hand's in the weapon's.
	 */
	private static final int SHIELD_SLOT = 5, WEAPON_SLOT = 3;
	/** What Animation.getLeftHandItem/getRightHandItem give for a hand the animation empties (its 0, less 512). */
	private static final int EMPTIED = -512;
	/** A pose this far (model units, any coordinate) from what the client draws is another. */
	static final double SAME = 1.0;
	/** Built models kept, the least recently used going first. */
	private static final int MODELS = 64;

	private final Client client;
	/** Set while building a model when one of its parts isn't loaded yet: not kept, tried again. */
	private boolean missing;
	private final Map<String, Model> models = new LinkedHashMap<String, Model>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Model> eldest)
		{
			return size() > MODELS;
		}
	};

	/** What came of working one out: the lesson, or why there is none. */
	static final class Outcome
	{
		final Learning.Lesson lesson;
		final String why;
		/** Worth trying again at another keyframe (the action may not play yet: its start delay). */
		final boolean again;

		private Outcome(Learning.Lesson lesson, String why, boolean again)
		{
			this.lesson = lesson;
			this.why = why;
			this.again = again;
		}
	}

	Poses(Client client)
	{
		this.client = client;
	}

	void clear()
	{
		models.clear();
	}

	/**
	 * The animation worked out on the character's model, keyed by shape. The model drawn is the client's at keyframe frame
	 * without blending: the check. Worked out as the action plays alone, also while the character walks (the client mixes
	 * the walk's legs in then, and so does the check): one lesson, whichever way it first shows.
	 */
	Outcome lesson(Actor actor, int animationId, int[] lengths, Model drawn, long shape, int frame)
	{
		Outcome failed = check(actor, animationId, drawn, frame);
		if (failed != null)
		{
			return failed;
		}
		return new Outcome(lesson(checked, animationId, lengths, drawn, shape), null, false);
	}

	/**
	 * The animation worked out before the character plays it (the local player's, known from before: see
	 * KeyframeSmoothingPlugin.workAhead), on the model built for it: nothing drawn to check it against yet (see check).
	 * Null when the model can't be made (now: its parts may not be loaded yet, see missing()).
	 */
	Learning.Lesson ahead(Actor actor, int animationId, int[] lengths)
	{
		Animation animation = client.loadAnimation(animationId);
		missing = false;
		if (animation == null || animation.isMayaAnim())
		{
			return null;
		}
		Model model = model(actor, animation, false);
		if (model == null)
		{
			return null;
		}
		return lesson(posing(actor, model, animation), animationId, lengths, model, Learning.shape(actor, model));
	}

	/** Which model (Learning.shape) ahead would work the animation out on, 0 when it can't be made (now: see missing()). */
	long shapeAhead(Actor actor, int animationId)
	{
		Animation animation = client.loadAnimation(animationId);
		missing = false;
		Model model = animation == null || animation.isMayaAnim() ? null : model(actor, animation, false);
		return model == null ? 0 : Learning.shape(actor, model);
	}

	/** Whether the last model asked for couldn't be made because a part of it isn't loaded yet: worth asking again. */
	boolean missing()
	{
		return missing;
	}

	/**
	 * Whether the character's model as built here, posed at keyframe frame, is what the client draws there unblended:
	 * null when it is (its posing left in checked), else why not.
	 */
	Outcome check(Actor actor, int animationId, Model drawn, int frame)
	{
		checked = null;
		Animation animation = client.loadAnimation(animationId);
		if (animation == null || animation.isMayaAnim())
		{
			return new Outcome(null, "no keyframes", false);
		}
		missing = false;
		Model model = model(actor, animation, false);
		if (model != null && !sameFaces(model, drawn) && changesHands(animation))
		{
			// should the hands be the other way round in some game, the model the client draws tells
			Model swapped = model(actor, animation, true);
			model = swapped != null && sameFaces(swapped, drawn) ? swapped : model;
		}
		if (model == null)
		{
			return new Outcome(null, missing ? "models not loaded yet" : "model not made", missing);
		}
		if (!sameFaces(model, drawn))
		{
			return new Outcome(null, "other model: v=" + model.getVerticesCount() + " f=" + model.getFaceCount()
				+ " drawn v=" + drawn.getVerticesCount() + " f=" + drawn.getFaceCount(), true);
		}
		Posing alone = posing(actor, model, animation);
		float[] seen = Learning.vertices(drawn);
		double off = off(alone.pose(frame, 0), seen);
		if (off > SAME && actor.getPoseAnimation() >= 0)
		{
			// an action that leaves part of the body (the legs) to the pose animation while the character walks: the
			// client draws it mixed, so it is checked mixed; worked out alone, as it plays standing (the walk's legs
			// stay the client's when it is drawn: see PartSpline.pose)
			Animation pose = client.loadAnimation(actor.getPoseAnimation());
			Posing mixed = new Posing(model, animation, pose, actor.getPoseAnimationFrame(), alone.scaleX, alone.scaleY);
			off = pose == null ? off : Math.min(off, off(mixed.pose(frame, 0), seen));
		}
		if (off > SAME)
		{
			return new Outcome(null, String.format("keyframe %d off by %.1f", frame, off), true);
		}
		checked = alone;
		return null;
	}

	/** The posing check found to be the client's, null when it didn't. */
	private Posing checked;

	/** The animation posing the model alone, sized as the client sizes the character. */
	private Posing posing(Actor actor, Model model, Animation animation)
	{
		float scaleX = 1, scaleY = 1;
		NPCComposition npc = actor instanceof NPC ? ((NPC) actor).getTransformedComposition() : null;
		if (npc != null)
		{
			// the client sizes an NPC after posing it
			scaleX = npc.getWidthScale() / 128f;
			scaleY = npc.getHeightScale() / 128f;
		}
		return new Posing(model, animation, null, 0, scaleX, scaleY);
	}

	/** Every keyframe and every blend there is of a posing: nothing more to wait for. */
	private static Learning.Lesson lesson(Posing posing, int animationId, int[] lengths, Model model, long shape)
	{
		Learning.Lesson lesson = new Learning.Lesson(animationId, shape, lengths, model);
		for (int k = 0; k < lengths.length; k++)
		{
			lesson.key(k, posing.pose(k, 0));
			for (int t = 1; k < lengths.length - 1 && t < lengths[k]; t++)
			{
				double s = t / (double) lengths[k];
				int slot = lesson.sampleSlot(k, s);
				if (slot >= 0)
				{
					lesson.sample(k, slot, s, posing.pose(k, t));
				}
			}
		}
		lesson.passes = Learning.PASSES;
		return lesson;
	}

	/** An animation posing one model (with the pose animation mixed in, or not), sized afterwards. */
	private final class Posing
	{
		final Model model;
		final Animation animation;
		final Animation pose;
		final int poseFrame;
		final float scaleX, scaleY;

		Posing(Model model, Animation animation, Animation pose, int poseFrame, float scaleX, float scaleY)
		{
			this.model = model;
			this.animation = animation;
			this.pose = pose;
			this.poseFrame = poseFrame;
			this.scaleX = scaleX;
			this.scaleY = scaleY;
		}

		/** The pose at keyframe k, ticks into it as the client blends it (0: the keyframe itself). */
		float[] pose(int k, int ticks)
		{
			// as AnimationController packs it: blended, how far into the keyframe
			int packed = ticks == 0 ? k : Integer.MIN_VALUE | ticks << 16 | k;
			Model posed = client.applyTransformations(model, animation, packed, pose, poseFrame);
			int count = model.getVerticesCount();
			float[] x = posed.getVerticesX(), y = posed.getVerticesY(), z = posed.getVerticesZ();
			float[] out = new float[3 * count];
			for (int v = 0; v < count; v++)
			{
				out[3 * v] = x[v] * scaleX;
				out[3 * v + 1] = y[v] * scaleY;
				out[3 * v + 2] = z[v] * scaleX;
			}
			return out;
		}
	}

	/** The furthest any coordinate of one pose is from the other's; infinite when they differ in size. */
	static double off(float[] pose, float[] seen)
	{
		if (pose.length != seen.length)
		{
			return Double.POSITIVE_INFINITY;
		}
		double worst = 0;
		for (int i = 0; i < pose.length; i++)
		{
			worst = Math.max(worst, Math.abs(pose[i] - seen[i]));
		}
		return worst;
	}

	private static boolean sameFaces(Model a, Model b)
	{
		int faces = a.getFaceCount();
		return a.getVerticesCount() == b.getVerticesCount() && faces == b.getFaceCount()
			&& same(a.getFaceIndices1(), b.getFaceIndices1(), faces) && same(a.getFaceIndices2(), b.getFaceIndices2(), faces)
			&& same(a.getFaceIndices3(), b.getFaceIndices3(), faces);
	}

	private static boolean same(int[] a, int[] b, int count)
	{
		return a != null && b != null && a.length >= count && b.length >= count
			&& Arrays.equals(a, 0, count, b, 0, count);
	}

	// ---- the character's model as the client builds it ----

	/**
	 * The character's model unposed, as the client builds it for this animation (with the hands' items the other way
	 * round: swapped); null when it can't be made.
	 */
	private Model model(Actor actor, Animation animation, boolean swapped)
	{
		if (actor instanceof NPC)
		{
			NPCComposition npc = ((NPC) actor).getTransformedComposition();
			return npc == null ? null : npcModel(npc);
		}
		if (!(actor instanceof Player))
		{
			return null;
		}
		PlayerComposition look = ((Player) actor).getPlayerComposition();
		if (look == null)
		{
			return null;
		}
		if (look.getTransformedNpcId() >= 0)
		{
			NPCComposition npc = client.getNpcDefinition(look.getTransformedNpcId());
			return npc == null ? null : npcModel(npc);
		}
		boolean female = look.getGender() == 1;
		int[] slots = look.getEquipmentIds().clone();
		// an animation can put something else in a hand, or nothing (eating, chopping with an axe from the inventory)
		int left = animation.getLeftHandItem(), right = animation.getRightHandItem();
		if (left != -1)
		{
			slots[swapped ? WEAPON_SLOT : SHIELD_SLOT] = held(left);
		}
		if (right != -1)
		{
			slots[swapped ? SHIELD_SLOT : WEAPON_SLOT] = held(right);
		}
		String key = "p" + (female ? 1 : 0) + Arrays.toString(slots);
		if (models.containsKey(key))
		{
			return models.get(key);
		}
		// the worn items and the kits of the body, slot after slot, joined into one
		missing = false;
		List<ModelData> parts = new ArrayList<>();
		for (int id : slots)
		{
			ModelData part = id >= PlayerComposition.ITEM_OFFSET ? itemModel(id - PlayerComposition.ITEM_OFFSET, female)
				: id >= PlayerComposition.KIT_OFFSET ? kitModel(id - PlayerComposition.KIT_OFFSET) : null;
			if (part != null)
			{
				parts.add(part);
			}
		}
		if (missing)
		{
			return null;
		}
		Model model = parts.isEmpty() ? null : client.mergeModels(parts.toArray(new ModelData[0]), parts.size()).light();
		models.put(key, model);
		return model;
	}

	/** What an animation puts in a hand (an item id, or EMPTIED), as an equipment id: 0 for nothing. */
	private static int held(int item)
	{
		return item == EMPTIED ? 0 : item + PlayerComposition.ITEM_OFFSET;
	}

	/** Whether the animation puts anything in or out of the hands. */
	private static boolean changesHands(Animation animation)
	{
		return animation.getLeftHandItem() != -1 || animation.getRightHandItem() != -1;
	}

	private Model npcModel(NPCComposition npc)
	{
		String key = "n" + npc.getId();
		if (models.containsKey(key))
		{
			return models.get(key);
		}
		int[] ids = npc.getModels();
		missing = false;
		ModelData joined = ids == null ? null : join(ids);
		if (missing)
		{
			return null;
		}
		Model model = joined == null ? null : joined.light();
		models.put(key, model);
		return model;
	}

	/** An item as worn: its worn models joined, moved by its offset; null for none. */
	private ModelData itemModel(int item, boolean female)
	{
		byte[] data = client.getIndexConfig().loadData(ITEM_CONFIG, item);
		WeaponFinder.WornModels worn = data == null ? null : WeaponFinder.wornModels(data);
		if (worn == null)
		{
			return null;
		}
		int[] ids = Arrays.stream(worn.models(female)).filter(id -> id >= 0).toArray();
		ModelData joined = join(ids);
		int offset = female ? worn.femaleOffset : worn.maleOffset;
		if (joined != null && offset != 0)
		{
			joined = joined.shallowCopy().cloneVertices().translate(0, offset, 0);
		}
		return joined;
	}

	/** A kit's body models joined; null for none. */
	private ModelData kitModel(int kit)
	{
		byte[] data = client.getIndexConfig().loadData(KIT_CONFIG, kit);
		int[] ids = data == null ? null : kitModels(data);
		return ids == null ? null : join(ids);
	}

	/** Models joined as the client joins them; null when there are none, or one isn't loaded (then missing is set). */
	private ModelData join(int[] ids)
	{
		if (ids.length == 0)
		{
			return null;
		}
		ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				missing = true;
				return null;
			}
		}
		return parts.length == 1 ? parts[0] : client.mergeModels(parts, parts.length);
	}

	/** The body models in a kit's definition as stored in the cache (config archive 3); null for data it can't read. */
	static int[] kitModels(byte[] data)
	{
		WeaponFinder.Reader in = new WeaponFinder.Reader(data);
		int[] models = new int[0];
		try
		{
			while (true)
			{
				int opcode = in.u8();
				if (opcode == 0)
				{
					return models;
				}
				else if (opcode == 1)
				{
					in.skip(1);
				}
				else if (opcode == 2 || opcode == 5)
				{
					models = new int[in.u8()];
					for (int i = 0; i < models.length; i++)
					{
						models[i] = opcode == 2 ? in.u16() : in.i32();
					}
				}
				else if (opcode == 3)
				{
					// not selectable: nothing follows
				}
				else if (opcode == 40 || opcode == 41)
				{
					in.skip(4 * in.u8());
				}
				else if (opcode >= 60 && opcode < 70)
				{
					in.skip(2);
				}
				else if (opcode >= 70 && opcode < 80)
				{
					in.skip(4);
				}
				else
				{
					return null;
				}
			}
		}
		catch (ArrayIndexOutOfBoundsException e)
		{
			return null;
		}
	}
}
