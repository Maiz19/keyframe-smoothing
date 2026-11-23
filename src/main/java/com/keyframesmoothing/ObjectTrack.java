package com.keyframesmoothing;

/**
 * What the plugin follows of one animated object (the Great Olm's head and hands): the animation and keyframe it shows
 * and since when, whether the client blends it. Updated before each frame on the client thread; read while the object is
 * drawn, also on the GPU plugin's render threads (the client draws objects there).
 */
final class ObjectTrack
{
	volatile int animationId = -1;
	volatile int frame = -1;
	/** Game cycle the keyframe started on: the frame it moved on in (an object's keyframe moves on as it is drawn). */
	volatile int startCycle;
	/** The animation's keyframe lengths in ticks, or null. */
	volatile int[] lengths;
	/** Whether the client should blend the keyframe it shows. */
	volatile boolean blend;

	// client thread only
	/** The frame (KeyframeSmoothingPlugin.renders) it was last seen in the scene. */
	int seen;
}
