# Keyframe Smoothing

Smooth animations with an animator's timing. Plain smoothing blends every keyframe into the next one at an even speed, so attacks and specials look mechanical and lose their impact.

Keyframe Smoothing learns each attack, special and other action the first time it sees it, from the movement of the model itself, so new weapons and monsters work without updates. From then on it:

- makes the fast moves faster and the slow ones slower, so swings snap and wind-ups read, while the action still takes exactly as long as in the game;
- paces every swing as one fluid motion through its keyframes: it carries on through a swing, eases at turning points and arrives at full speed on a hit;
- leaves out smoothing where it goes wrong: parts spinning the wrong way round, windmills turning back, effects sliding across the screen.

Walking and standing are smoothed as usual.

## Settings

- **Exaggerate timing**: how much faster the fast moves and slower the slow ones get. 0% keeps the game's timing.
- **Fluid pacing**: how strongly swings are paced as one motion. 0% is an even blend per keyframe.
- **Fix smoothing glitches**: leave out smoothing where it would spin or slide things.
- **Players, NPCs, Objects and effects**: choose what gets smoothed.
- **Experimental**: Hit stop (holds the pose a moment where an attack hits) and Weapon trails (a short trail behind fast swings).

You don't need RuneLite's Animation Smoothing turned on. If it is, both work together.
