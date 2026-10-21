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

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "impact",
		name = "Impact",
		description = "0: plain smoothing, every keyframe blends at an even speed.<br>"
			+ "Higher: attacks and other actions are paced like one fluid motion through their keyframes:<br>"
			+ "swings carry on, turning points ease, hits land at full speed. Walking and standing stay even",
		position = 0
	)
	default int impact()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "fixGlitches",
		name = "Fix smoothing glitches",
		description = "Shows a keyframe without smoothing when smoothing would spin a part the wrong way<br>"
			+ "or slide it across the screen (windmills, spell effects, some NPCs)",
		position = 1
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
}
