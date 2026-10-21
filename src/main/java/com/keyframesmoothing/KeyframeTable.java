package com.keyframesmoothing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * How to treat each keyframe of the game's classic animations, worked out from the game cache ahead of time:
 * one letter per keyframe, for the blend from that keyframe to the next.
 */
@Slf4j
final class KeyframeTable
{
	/** Smoothing would turn a part the wrong way round. */
	static final byte TURN = 'T';
	/** Smoothing would slide a part over a jump (a loop starting over, an effect popping back). */
	static final byte JUMP = 'C';
	/** A pose the animation holds before a fast move. */
	static final byte HOLD = 'H';
	/** A shorter held pose, kept in the Punchy style only. */
	static final byte PUNCH = 'P';

	private final Map<Integer, byte[]> keyframes;

	private KeyframeTable(Map<Integer, byte[]> keyframes)
	{
		this.keyframes = keyframes;
	}

	/** The letters for an animation, or null when all its keyframes are smoothed. */
	byte[] get(int animationId)
	{
		return keyframes.get(animationId);
	}

	static KeyframeTable load()
	{
		Map<Integer, byte[]> keyframes = new HashMap<>();
		try (InputStream in = KeyframeTable.class.getResourceAsStream("keyframes.txt"))
		{
			if (in == null)
			{
				log.warn("keyframes.txt is missing; every keyframe is smoothed");
				return new KeyframeTable(keyframes);
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line; (line = reader.readLine()) != null; )
			{
				if (line.isEmpty() || line.charAt(0) == '#')
				{
					continue;
				}
				String[] parts = line.split(" ");
				byte[] letters = parts[2].getBytes(StandardCharsets.US_ASCII);
				if (letters.length == Integer.parseInt(parts[1]))
				{
					keyframes.put(Integer.parseInt(parts[0]), letters);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Couldn't read keyframes.txt; every keyframe is smoothed", e);
		}
		return new KeyframeTable(keyframes);
	}
}
