package com.keyframesmoothing;

import net.runelite.api.DynamicObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TileObject;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Draws objects and effects (from {@link DrawHook}):
 * <ul>
 * <li>each with its own blend: the client is told one answer per animation, so one showing a keyframe that should be
 * blended otherwise than the others showing that animation is built again with its own (on the client thread only; the
 * GPU plugin's render threads build objects too, and there it can't be).</li>
 * </ul>
 */
final class ObjectDrawer
{
	private final KeyframeSmoothingPlugin plugin;
	/** Off once the plugin stops: a hook that another one keeps in its chain then only passes calls on. */
	volatile boolean enabled = true;

	ObjectDrawer(KeyframeSmoothingPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** An effect drawn on the client thread (a teleport's graphic, a projectile): the model to draw, with its own blend. */
	Model effect(Renderable renderable, Model model)
	{
		if (!enabled || model == null)
		{
			return model;
		}
		try
		{
			return plugin.own(renderable, model);
		}
		catch (RuntimeException e)
		{
			plugin.failed("effects", e);
			return model;
		}
	}

	/** An object drawn on the client thread (thread below 0) or a render thread: on the client thread, with its own blend. */
	void drawDynamic(DrawCallbacks next, int thread, Projection projection, Scene scene, TileObject object,
		Renderable renderable, Model model, int orientation, int x, int y, int z)
	{
		try
		{
			if (enabled && model != null && renderable instanceof DynamicObject && thread < 0)
			{
				model = plugin.own(renderable, model);
			}
		}
		catch (RuntimeException e)
		{
			plugin.failed("objects", e);
		}
		next.drawDynamic(thread, projection, scene, object, renderable, model, orientation, x, y, z);
	}
}
