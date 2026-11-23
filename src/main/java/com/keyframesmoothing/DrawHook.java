package com.keyframesmoothing;

import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Stands in front of the renderer (the GPU plugin or another GPU renderer) and passes every call on unchanged, except
 * that players and NPCs go through {@link ActorDrawer} first, and animated objects and effects through
 * {@link ObjectDrawer}. The client hands the renderer each player and NPC, and each effect, as a model posed for this
 * frame (drawTemp, on the client thread), and each animated object likewise (drawDynamic, on the client thread or a
 * render thread); the drawer changes that pose, lets the renderer draw it, and puts the model back as it was.
 */
final class DrawHook implements DrawCallbacks
{
	private final DrawCallbacks delegate;
	private final ActorDrawer drawer;
	private final ObjectDrawer objects;
	/** Calls since the plugin last looked: a hook that gets none in the world is no longer in front of the renderer. */
	volatile int calls;

	DrawHook(DrawCallbacks delegate, ActorDrawer drawer, ObjectDrawer objects)
	{
		this.delegate = delegate;
		this.drawer = drawer;
		this.objects = objects;
	}

	DrawCallbacks delegate()
	{
		return delegate;
	}

	@Override
	public void drawTemp(Projection projection, Scene scene, GameObject gameObject, Model model, int orientation, int x, int y, int z)
	{
		calls++;
		Renderable renderable = gameObject.getRenderable();
		if (renderable instanceof Actor && model != null)
		{
			drawer.draw(delegate, (Actor) renderable, projection, scene, gameObject, model, orientation, x, y, z);
		}
		else
		{
			Model own = objects == null ? model : objects.effect(renderable, model);
			delegate.drawTemp(projection, scene, gameObject, own, orientation, x, y, z);
		}
	}

	// ---- everything else goes straight through ----

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		delegate.draw(projection, scene, renderable, orientation, x, y, z, hash);
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileY)
	{
		delegate.drawScenePaint(scene, paint, plane, tileX, tileY);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileY)
	{
		delegate.drawSceneTileModel(scene, model, tileX, tileY);
	}

	@Override
	public void draw(int overlayColor)
	{
		calls++;
		delegate.draw(overlayColor);
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane)
	{
		calls++;
		delegate.drawScene(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane);
	}

	@Override
	public void postDrawScene()
	{
		delegate.postDrawScene();
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		delegate.animate(texture, diff);
	}

	@Override
	public void loadScene(Scene scene)
	{
		delegate.loadScene(scene);
	}

	@Override
	public void swapScene(Scene scene)
	{
		delegate.swapScene(scene);
	}

	@Override
	public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos, int cameraX,
		int cameraY, int cameraZ, int plane, int msx, int msy)
	{
		return delegate.tileInFrustum(scene, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, plane, msx, msy);
	}

	@Override
	public boolean zoneInFrustum(int a, int b, int c, int d)
	{
		return delegate.zoneInFrustum(a, b, c, d);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		delegate.loadScene(worldView, scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		delegate.despawnWorldView(worldView);
	}

	@Override
	public void preSceneDraw(Scene scene, Projection projection, float cameraX, float cameraY, float cameraZ, float cameraPitch,
		float cameraYaw, int plane, int minLevel, int maxLevel, Set<Integer> zones)
	{
		calls++;
		delegate.preSceneDraw(scene, projection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane, minLevel, maxLevel, zones);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void preSceneDraw(Scene scene, float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int plane, int minLevel, int maxLevel, Set<Integer> zones)
	{
		calls++;
		delegate.preSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane, minLevel, maxLevel, zones);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		calls++;
		delegate.postSceneDraw(scene);
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		calls++;
		delegate.drawPass(projection, scene, pass);
	}

	@Override
	public void drawZoneOpaque(Projection projection, Scene scene, int zx, int zy)
	{
		delegate.drawZoneOpaque(projection, scene, zx, zy);
	}

	@Override
	public void drawZoneAlpha(Projection projection, Scene scene, int zx, int zy, int level)
	{
		delegate.drawZoneAlpha(projection, scene, zx, zy, level);
	}

	@Override
	public void drawDynamic(Projection projection, Scene scene, TileObject tileObject, Renderable renderable, Model model,
		int orientation, int x, int y, int z)
	{
		delegate.drawDynamic(projection, scene, tileObject, renderable, model, orientation, x, y, z);
	}

	@Override
	public void drawDynamic(int thread, Projection projection, Scene scene, TileObject tileObject, Renderable renderable,
		Model model, int orientation, int x, int y, int z)
	{
		if (objects == null)
		{
			delegate.drawDynamic(thread, projection, scene, tileObject, renderable, model, orientation, x, y, z);
			return;
		}
		objects.drawDynamic(delegate, thread, projection, scene, tileObject, renderable, model, orientation, x, y, z);
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zy)
	{
		delegate.invalidateZone(scene, zx, zy);
	}
}
