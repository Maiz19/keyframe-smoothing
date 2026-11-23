package com.keyframesmoothing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The names in a setting ("Dragon claws, Abyssal *"): separated by commas or line breaks, matched whole whatever the
 * case, with * for anything.
 */
final class NameList
{
	static final NameList EMPTY = new NameList(new String[0]);

	private final String[] names;
	private final Pattern[] patterns;

	private NameList(String[] names)
	{
		this.names = names;
		patterns = new Pattern[names.length];
		for (int i = 0; i < names.length; i++)
		{
			StringBuilder regex = new StringBuilder();
			for (String part : names[i].split("\\*", -1))
			{
				regex.append(regex.length() > 0 ? ".*" : "").append(Pattern.quote(part));
			}
			patterns[i] = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
		}
	}

	static NameList parse(String text)
	{
		List<String> names = new ArrayList<>();
		for (String name : text == null ? new String[0] : text.split("[,\n]"))
		{
			if (!name.trim().isEmpty())
			{
				names.add(name.trim());
			}
		}
		return names.isEmpty() ? EMPTY : new NameList(names.toArray(new String[0]));
	}

	boolean has(String name)
	{
		for (Pattern pattern : patterns)
		{
			if (pattern.matcher(name).matches())
			{
				return true;
			}
		}
		return false;
	}

	/** A setting's text with a name added (once) or taken out, as it is written back: comma-separated. */
	static String with(String text, String name, boolean add)
	{
		List<String> names = new ArrayList<>(Arrays.asList(parse(text).names));
		names.removeIf(n -> n.equalsIgnoreCase(name));
		if (add)
		{
			names.add(name);
		}
		return String.join(", ", names);
	}
}
