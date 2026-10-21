# Keyframe Smoothing

Smooth animations that still hit hard. Smoothing blends every keyframe into the next one, which turns the poses an animation holds (the wind-up before a special attack, the moment a whip cracks or a maul lands) into a slow drift. Keyframe Smoothing smooths everything else, but holds those poses and only moves on at the end of the hold.

It also leaves out smoothing on the keyframes where it would go wrong: parts spinning the wrong way round, windmills turning back, and effects sliding across the screen. Only those keyframes are left unsmoothed, the rest of the animation stays smooth.

## Settings

- **Style**: Smooth (everything smoothed, glitches fixed), Balanced (held poses stay put) or Punchy (shorter holds stay put too).
- **Move into next pose**: how long a held pose takes to move into the next one. 0 snaps straight to it, as the game does.
- **Fix smoothing glitches**: leave out smoothing where it would spin or slide things.
- **Players, NPCs, Objects and effects**: choose what gets smoothed.

You don't need RuneLite's Animation Smoothing turned on. If it is, both work together.
