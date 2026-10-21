package com.keyframesmoothing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Keyframes of the game's classic animations that smoothing turns into a glitch, worked out from the game cache ahead of
 * time: one letter per keyframe, for the blend from that keyframe to the next. Used where the plugin can't learn an
 * animation itself: walking, standing and animated objects. Animations added later are simply smoothed.
 */
@Slf4j
final class KeyframeTable
{
	/** Smoothing would turn a part the wrong way round. */
	static final byte TURN = 'T';
	/** Smoothing would slide a part over a jump (a loop starting over, an effect popping back). */
	static final byte JUMP = 'C';

	private final Map<Integer, byte[]> keyframes;
	/** Animations with a keyframe that is a glitch when smoothed. Read from the render threads too: never changed. */
	private final Set<Integer> glitches;

	private KeyframeTable(Map<Integer, byte[]> keyframes)
	{
		this.keyframes = keyframes;
		Set<Integer> glitches = new HashSet<>();
		keyframes.forEach((id, letters) ->
		{
			for (byte letter : letters)
			{
				if (letter == TURN || letter == JUMP)
				{
					glitches.add(id);
					break;
				}
			}
		});
		this.glitches = Collections.unmodifiableSet(glitches);
	}

	boolean hasGlitches(int animationId)
	{
		return glitches.contains(animationId);
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
