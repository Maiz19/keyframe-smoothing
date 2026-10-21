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

	enum Style
	{
		SMOOTH("Smooth"),
		BALANCED("Balanced"),
		PUNCHY("Punchy");

		private final String name;

		Style(String name)
		{
			this.name = name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	@ConfigSection(
		name = "Smooth",
		description = "What gets smoothed",
		position = 10
	)
	String smoothSection = "smooth";

	@ConfigItem(
		keyName = "style",
		name = "Style",
		description = "Smooth: everything is smoothed (glitches still fixed).<br>"
			+ "Balanced: poses the animation holds stay put, then move on.<br>"
			+ "Punchy: like Balanced, and shorter holds stay put too",
		position = 0
	)
	default Style style()
	{
		return Style.BALANCED;
	}

	@Range(max = 200)
	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "blendTime",
		name = "Move into next pose",
		description = "How long a held pose takes to move into the next one, at the end of the hold.<br>"
			+ "0 snaps straight to the next pose, as the game does without smoothing",
		position = 1
	)
	default int blendTime()
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
}
