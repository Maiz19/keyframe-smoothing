package com.keyframesmoothing;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Deque;
import java.util.Iterator;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws the trails behind fast swings: a line through the tip's last positions that fades and thins with age. */
class TrailOverlay extends Overlay
{
	private final Client client;
	private final KeyframeSmoothingPlugin plugin;

	@Inject
	TrailOverlay(Client client, KeyframeSmoothingPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (plugin.trails.isEmpty())
		{
			return null;
		}
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		long now = plugin.clock.getAsLong();
		float[] vx = new float[1], vy = new float[1], vz = new float[1];
		int[] sx = new int[1], sy = new int[1];
		for (Deque<KeyframeSmoothingPlugin.TrailPoint> points : plugin.trails.values())
		{
			int lastX = 0, lastY = 0;
			boolean first = true;
			for (Iterator<KeyframeSmoothingPlugin.TrailPoint> it = points.iterator(); it.hasNext(); )
			{
				KeyframeSmoothingPlugin.TrailPoint p = it.next();
				vx[0] = p.vx;
				vy[0] = p.vy;
				vz[0] = p.vz;
				Perspective.modelToCanvas(client, 1, p.x, p.y, p.z, p.orientation, vx, vy, vz, sx, sy);
				if (sx[0] == Integer.MIN_VALUE)
				{
					first = true;
					continue;
				}
				if (!first)
				{
					double age = (now - p.nanos) / (double) KeyframeSmoothingPlugin.TRAIL_NANOS;
					float strength = (float) Math.max(0, 1 - age);
					graphics.setStroke(new BasicStroke(1 + 5 * strength, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					graphics.setColor(new Color(1f, 1f, 1f, 0.55f * strength));
					graphics.drawLine(lastX, lastY, sx[0], sy[0]);
				}
				lastX = sx[0];
				lastY = sy[0];
				first = false;
			}
		}
		return null;
	}
}
