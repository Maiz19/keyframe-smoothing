package com.keyframesmoothing;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(KeyframeSmoothingConfig.GROUP)
public interface KeyframeSmoothingConfig extends Config
{
	String GROUP = "keyframesmoothing";

	@ConfigSection(
		name = "Smooth",
		description = "What gets smoothed",
		position = 10
	)
	String smoothSection = "smooth";

	@ConfigSection(
		name = "Experimental",
		description = "Effects still being tried out",
		position = 20,
		closedByDefault = true
	)
	String experimentalSection = "experimental";

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "exaggerate",
		name = "Exaggerate timing",
		description = "Makes the fast moves of attacks and other actions faster and the slow ones slower,<br>"
			+ "as an animator would, so swings snap and wind-ups read. An action still takes as long as in the game.<br>"
			+ "Each action is learnt the first time it is seen; until then it is plainly smoothed",
		position = 0
	)
	default int exaggerate()
	{
		return 50;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "pacing",
		name = "Fluid pacing",
		description = "Paces actions like one fluid motion through their keyframes:<br>"
			+ "swings carry on, turning points ease, hits arrive at full speed. 0: an even blend per keyframe",
		position = 1
	)
	default int pacing()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "fixGlitches",
		name = "Fix smoothing glitches",
		description = "Shows a keyframe without smoothing when smoothing would spin a part the wrong way<br>"
			+ "or slide it across the screen (windmills, spell effects, some NPCs)",
		position = 2
	)
	default boolean fixGlitches()
	{
		return true;
	}

	@ConfigItem(
		keyName = "players",
		name = "Players",
		description = "Smooth player animations",
		position = 3,
		section = smoothSection
	)
	default boolean players()
	{
		return true;
	}

	@ConfigItem(
		keyName = "npcs",
		name = "NPCs",
		description = "Smooth NPC and pet animations",
		position = 4,
		section = smoothSection
	)
	default boolean npcs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "objects",
		name = "Objects and effects",
		description = "Smooth animated objects and spell, hit and other effects",
		position = 5,
		section = smoothSection
	)
	default boolean objects()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hitStop",
		name = "Hit stop",
		description = "Holds the pose a moment where an attack hits, as fighting games do. The time comes out of<br>"
			+ "the slower parts of the action, so it still ends on time",
		position = 0,
		section = experimentalSection
	)
	default boolean hitStop()
	{
		return false;
	}

	@Range(min = 20, max = 160)
	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "hitStopTime",
		name = "Hit stop length",
		description = "How long the hit pose is held",
		position = 1,
		section = experimentalSection
	)
	default int hitStopTime()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "weaponTrails",
		name = "Weapon trails",
		description = "A short trail behind the fastest moving point (the weapon's tip) during fast swings",
		position = 2,
		section = experimentalSection
	)
	default boolean weaponTrails()
	{
		return false;
	}
}
