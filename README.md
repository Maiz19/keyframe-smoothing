# Keyframe Smoothing

Smooth animations that follow their keyframes. Plain smoothing blends every keyframe into the next one at an even speed, and changes speed abruptly at every keyframe, so attacks and other actions look mechanical and lose their impact.

Keyframe Smoothing paces actions like one fluid motion through their keyframes, the way an animator's spline would: a swing carries on through its keyframes without slowing down, turning points ease in and out, and hits arrive at full speed. Every keyframe is still reached exactly. Walking and standing stay even.

It also leaves out smoothing on the keyframes where smoothing goes wrong: parts spinning the wrong way round, windmills turning back, and effects sliding across the screen.

## Settings

- **Impact**: 0% is plain smoothing. Higher follows the pace of the keyframes more strongly.
- **Fix smoothing glitches**: leave out smoothing where it would spin or slide things.
- **Players, NPCs, Objects and effects**: choose what gets smoothed.

You don't need RuneLite's Animation Smoothing turned on. If it is, both work together.
