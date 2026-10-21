# Keyframe Smoothing

Smooth animations that still hit hard. Plain smoothing blends every keyframe into the next one at an even speed, so attacks and other actions turn floaty: the wind-up before a special attack or the moment a whip cracks drifts away instead of landing.

Keyframe Smoothing keeps the motion smooth but gives it timing. In actions, a pose the animation holds stays put for a moment, and each move speeds up into the next pose, so hits land hard. Walking and standing stay even.

It also leaves out smoothing on the keyframes where smoothing goes wrong: parts spinning the wrong way round, windmills turning back, and effects sliding across the screen. Only those keyframes are left unsmoothed, the rest of the animation stays smooth.

## Settings

- **Impact**: 0% is plain smoothing. Higher holds poses longer and makes moves speed up harder into the next pose.
- **Fix smoothing glitches**: leave out smoothing where it would spin or slide things.
- **Players, NPCs, Objects and effects**: choose what gets smoothed.

You don't need RuneLite's Animation Smoothing turned on. If it is, both work together.
