package com.keyframesmoothing;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(KeyframeSmoothingConfig.GROUP)
public interface KeyframeSmoothingConfig extends Config
{
	String GROUP = "keyframesmoothing";

	@ConfigSection(
		name = "Smoothing",
		description = "One fluid motion through every keyframe, for characters it is on for (needs the GPU plugin or another<br>"
			+ "GPU renderer; each action is worked out from the game's own data as it starts). Off: keyframe to keyframe, as without smoothing",
		position = 0
	)
	String smoothingSection = "smoothing";

	@ConfigSection(
		name = "Weapon ghosts",
		description = "A short, fading trail of your weapon on the strike of an attack",
		position = 1
	)
	String ghostsSection = "ghosts";

	@ConfigSection(
		name = "Exceptions",
		description = "Weapons, NPCs and animated objects that get the opposite of the settings above, by name; and the<br>"
			+ "shift-right-click menu entries that put them here",
		position = 2
	)
	String exceptionsSection = "exceptions";

	// ---- smoothing ----

	@ConfigItem(
		keyName = "smoothMe",
		name = "Your character",
		description = "Your own movement, attacks and other actions",
		position = 0,
		section = smoothingSection
	)
	default boolean smoothMe()
	{
		return true;
	}

	@ConfigItem(
		keyName = "smoothPlayers",
		name = "Other players",
		description = "Other players' movement, attacks and other actions",
		position = 1,
		section = smoothingSection
	)
	default boolean smoothPlayers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "smoothNpcs",
		name = "NPCs",
		description = "NPCs and pets: movement, attacks and everything else they do",
		position = 2,
		section = smoothingSection
	)
	default boolean smoothNpcs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "objects",
		name = "Objects and effects",
		description = "An even blend between keyframes for animated objects and spell, hit and other effects",
		position = 3,
		section = smoothingSection
	)
	default boolean objects()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exaggerate",
		name = "Exaggerated blows",
		description = "Attacks and skilling swings (chopping, mining, smithing) wind up further than their keyframes,<br>"
			+ "strike faster and faster and carry on past the hit before they spring back.<br>"
			+ "Off: blows follow their keyframes exactly, only faster towards the hit",
		position = 4,
		section = smoothingSection
	)
	default boolean exaggerate()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncHits",
		name = "Hits on the hitsplat",
		description = "Your own attacks land when their hitsplat and its sound come (melee: a game tick after the attack starts),<br>"
			+ "not later as some keyframes have it: the attack is shown faster up to the hit and slower after it.<br>"
			+ "Learnt from your first hits with each attack",
		position = 5,
		section = smoothingSection
	)
	default boolean syncHits()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skilling",
		name = "Skilling",
		description = "Skilling swings (mining, chopping, harpoons, hammers) strike as attacks do: a wind-up, then a fast blow<br>"
			+ "that lands on its keyframe. Off: as the game times them",
		position = 6,
		section = smoothingSection
	)
	default boolean skilling()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hitOnSound",
		name = "Hit on the chop sound",
		description = "Chopping in the Gauntlet: your axe hits the tree each time you hear a chop.<br>"
			+ "Off: some chop sounds play without a hit. Needs Skilling on",
		position = 7,
		section = smoothingSection
	)
	default boolean hitOnSound()
	{
		return false;
	}

	// ---- weapon ghosts ----

	@ConfigItem(
		keyName = "ghosts",
		name = "Weapon ghosts",
		description = "A short trail of your weapon, in its own colours, on the strike of an attack (not the wind-up),<br>"
			+ "fading out after the hit. Your own character, with smoothing on; needs a GPU renderer",
		position = 0,
		section = ghostsSection
	)
	default boolean ghosts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "ghostsSpecOnly",
		name = "Special attacks only",
		description = "The trail only on your special attacks",
		position = 1,
		section = ghostsSection
	)
	default boolean ghostsSpecOnly()
	{
		return false;
	}

	@ConfigItem(
		keyName = "ghostsMelee",
		name = "Melee",
		description = "Trails on your melee attacks",
		position = 2,
		section = ghostsSection
	)
	default boolean ghostsMelee()
	{
		return true;
	}

	@ConfigItem(
		keyName = "ghostsRanged",
		name = "Ranged",
		description = "Trails on your ranged attacks (bows, crossbows, thrown weapons)",
		position = 3,
		section = ghostsSection
	)
	default boolean ghostsRanged()
	{
		return true;
	}

	@ConfigItem(
		keyName = "ghostsMagic",
		name = "Magic",
		description = "Trails on your spells: cast onto a monster or player, or autocast",
		position = 4,
		section = ghostsSection
	)
	default boolean ghostsMagic()
	{
		return true;
	}

	@ConfigItem(
		keyName = "ghostsSkilling",
		name = "Skilling",
		description = "Trails on the strike of your skilling tool: mining, woodcutting, harpoon fishing, smithing, building.<br>"
			+ "Needs Smoothing > Skilling on; off with Weapon ghosts off or Special attacks only on",
		position = 5,
		section = ghostsSection
	)
	default boolean ghostsSkilling()
	{
		return false;
	}

	// ---- exceptions ----

	@ConfigItem(
		keyName = "smoothExceptions",
		name = "Smoothing",
		description = "Smoothed when their kind isn't, shown keyframe to keyframe when it is: weapons (your attacks with them),<br>"
			+ "NPCs and animated objects by name, comma-separated; * stands for anything (Dragon *)",
		position = 0,
		section = exceptionsSection
	)
	default String smoothExceptions()
	{
		return "";
	}

	@ConfigItem(
		keyName = "ghostExceptions",
		name = "Ghosts",
		description = "Weapons that leave a trail when weapon ghosts are off, and none when they are on; comma-separated,<br>"
			+ "* stands for anything",
		position = 1,
		section = exceptionsSection
	)
	default String ghostExceptions()
	{
		return "";
	}

	@ConfigItem(
		keyName = "smoothMenu",
		name = "Shift-right-click: smooth",
		description = "Smooth / Don't smooth on the shift-right-click menu of monsters, animated objects and weapons in your<br>"
			+ "inventory, bank and equipment: puts them on the Smoothing list above, or takes them off",
		position = 2,
		section = exceptionsSection
	)
	default boolean smoothMenu()
	{
		return false;
	}

	@ConfigItem(
		keyName = "ghostMenu",
		name = "Shift-right-click: ghosts",
		description = "Ghosts / No ghosts on the menu of weapons: the Ghosts list above",
		position = 3,
		section = exceptionsSection
	)
	default boolean ghostMenu()
	{
		return false;
	}
}
