package com.keyframesmoothing;

/** How a character's animation is drawn between its keyframes. */
enum Smoothing
{
	/** Keyframe to keyframe, as the game without smoothing. */
	OFF,
	/** The client's even blend: where the plugin's own smoothing can't be used (no GPU renderer). */
	PLAIN,
	/** The plugin's own: one fluid motion through every keyframe. */
	KEYFRAMES
}
