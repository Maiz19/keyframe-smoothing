package com.keyframesmoothing;

/**
 * Finds the weapon's faces in a player's model. The client builds a player's model by joining the models of what they
 * wear one after another in slot order (head, cape, amulet, weapon, body, ...), each keeping its faces together and in
 * order, so the weapon is one run of faces right after those of what is worn on the head, back and neck; no API says
 * where. The models are named by the items' definitions in the cache. The run is checked by its shape: the corners its
 * faces share, which the client's joining keeps. That also tells when an animation has the client draw another item in
 * the weapon's hand.
 */
final class WeaponFinder
{
	private WeaponFinder()
	{
	}

	/** The worn models of an item: for men and for women, up to three models each and a height offset. */
	static final class WornModels
	{
		final int[] male = {-1, -1, -1};
		final int[] female = {-1, -1, -1};
		int maleOffset;
		int femaleOffset;

		int[] models(boolean isFemale)
		{
			return isFemale ? female : male;
		}
	}

	/**
	 * Reads the worn models from an item's definition as stored in the cache (config archive 10). It has to step over
	 * every other field, as fields come in any order.
	 *
	 * @return the models, or null for data it does not understand
	 */
	static WornModels wornModels(byte[] data)
	{
		try
		{
			return read(new Reader(data));
		}
		catch (ArrayIndexOutOfBoundsException e)
		{
			return null;
		}
	}

	private static WornModels read(Reader in)
	{
		WornModels out = new WornModels();
		while (true)
		{
			int opcode = in.u8();
			switch (opcode)
			{
				case 0:
					return out;
				case 11:
				case 15:
				case 16:
				case 65:
				case 160:
				case 251:
					break;
				case 13:
				case 14:
				case 27:
				case 42:
				case 113:
				case 114:
				case 115:
					in.skip(1);
					break;
				case 1:
				case 4:
				case 5:
				case 6:
				case 7:
				case 8:
				case 75:
				case 90:
				case 91:
				case 92:
				case 93:
				case 94:
				case 95:
				case 97:
				case 98:
				case 99:
				case 110:
				case 111:
				case 112:
				case 139:
				case 140:
				case 148:
				case 149:
					in.skip(2);
					break;
				case 12:
				case 44:
				case 51:
				case 52:
				case 53:
				case 54:
					in.skip(4);
					break;
				case 2:
				case 3:
				case 9:
					in.string();
					break;
				case 23:
					out.male[0] = in.u16();
					out.maleOffset = in.u8();
					break;
				case 24:
					out.male[1] = in.u16();
					break;
				case 25:
					out.female[0] = in.u16();
					out.femaleOffset = in.u8();
					break;
				case 26:
					out.female[1] = in.u16();
					break;
				case 40:
				case 41:
					in.skip(4 * in.u8());
					break;
				case 43:
					in.skip(1);
					while (in.u8() != 0)
					{
						in.string();
					}
					break;
				case 45:
					out.male[0] = in.i32();
					out.maleOffset = in.u8();
					break;
				case 46:
					out.male[1] = in.i32();
					break;
				case 47:
					out.male[2] = in.i32();
					break;
				case 48:
					out.female[0] = in.i32();
					out.femaleOffset = in.u8();
					break;
				case 49:
					out.female[1] = in.i32();
					break;
				case 50:
					out.female[2] = in.i32();
					break;
				case 78:
					out.male[2] = in.u16();
					break;
				case 79:
					out.female[2] = in.u16();
					break;
				case 161:
					in.skip(2 * in.u16());
					break;
				case 200:
					in.skip(2);
					in.string();
					break;
				case 201:
					in.skip(13);
					in.string();
					break;
				case 202:
					in.skip(15);
					in.string();
					break;
				case 249:
					for (int count = in.u8(); count > 0; count--)
					{
						int type = in.u8();
						in.skip(3);
						if (type == 1)
						{
							in.string();
						}
						else
						{
							in.skip(type == 2 ? 8 : 4);
						}
					}
					break;
				default:
					if (opcode >= 30 && opcode < 40)
					{
						in.string();
						break;
					}
					if (opcode >= 100 && opcode < 110)
					{
						in.skip(4);
						break;
					}
					return null;
			}
		}
	}

	/** Faces of a model: per face its three corners (vertex numbers). */
	static final class Faces
	{
		final int[] a;
		final int[] b;
		final int[] c;

		Faces(int[] a, int[] b, int[] c)
		{
			this.a = a;
			this.b = b;
			this.c = c;
		}

		int count()
		{
			return a.length;
		}
	}

	/**
	 * Joins models as the client does: faces one model after another, in order, and vertices at the same spot made one,
	 * also across models.
	 */
	static final class Joiner
	{
		private final java.util.Map<Long, Integer> spots = new java.util.HashMap<>();
		private int[] corners = new int[3 * 256];
		private int faces;

		void add(int vertexCount, float[] x, float[] y, float[] z, int faceCount, int[] a, int[] b, int[] c)
		{
			int[] spot = new int[vertexCount];
			for (int v = 0; v < vertexCount; v++)
			{
				long key = ((long) (int) x[v] & 0x1FFFFF) << 42 | ((long) (int) y[v] & 0x1FFFFF) << 21 | (long) (int) z[v] & 0x1FFFFF;
				spot[v] = spots.computeIfAbsent(key, k -> spots.size());
			}
			if (3 * (faces + faceCount) > corners.length)
			{
				corners = java.util.Arrays.copyOf(corners, Math.max(2 * corners.length, 3 * (faces + faceCount)));
			}
			for (int f = 0; f < faceCount; f++, faces++)
			{
				corners[3 * faces] = spot[a[f]];
				corners[3 * faces + 1] = spot[b[f]];
				corners[3 * faces + 2] = spot[c[f]];
			}
		}

		Faces faces()
		{
			int[] a = new int[faces], b = new int[faces], c = new int[faces];
			for (int f = 0; f < faces; f++)
			{
				a[f] = corners[3 * f];
				b[f] = corners[3 * f + 1];
				c[f] = corners[3 * f + 2];
			}
			return new Faces(a, b, c);
		}
	}

	/**
	 * Whether the weapon's faces run from face start in the model: corners shared between the weapon's faces are shared
	 * the same way in the run (numbered in order of first use, the numbers agree).
	 */
	static boolean matches(Faces model, int start, Faces weapon)
	{
		if (start < 0 || weapon.count() == 0 || start + weapon.count() > model.count())
		{
			return false;
		}
		int[] numbered = new int[1 + Math.max(top(weapon, 0, weapon.count()), top(model, start, weapon.count()))];
		java.util.Arrays.fill(numbered, -1);
		int[] own = number(weapon, 0, weapon.count(), numbered);
		java.util.Arrays.fill(numbered, -1);
		return java.util.Arrays.equals(own, number(model, start, weapon.count(), numbered));
	}

	/**
	 * Where the weapon's faces run in the model: from start, where the worn items before it end, or else the nearest place
	 * they do (in game the run need not begin exactly there); -1 for nowhere.
	 */
	static int find(Faces model, int start, Faces weapon)
	{
		int count = weapon.count(), last = model.count() - count;
		if (start < 0 || count == 0 || last < 0)
		{
			return -1;
		}
		int[] numbered = new int[1 + Math.max(top(weapon, 0, count), top(model, 0, model.count()))];
		java.util.Arrays.fill(numbered, -1);
		int[] own = number(weapon, 0, count, numbered);
		java.util.Arrays.fill(numbered, -1);
		int[] touched = new int[3 * count];
		start = Math.min(start, last);
		for (int d = 0; start - d >= 0 || start + d <= last; d++)
		{
			if (start - d >= 0 && agrees(model, start - d, own, numbered, touched))
			{
				return start - d;
			}
			if (d > 0 && start + d <= last && agrees(model, start + d, own, numbered, touched))
			{
				return start + d;
			}
		}
		return -1;
	}

	/**
	 * Whether the faces from start number their corners as own does (see number), stopping at the first that doesn't;
	 * numbered (all -1) is left as it was.
	 */
	private static boolean agrees(Faces faces, int start, int[] own, int[] numbered, int[] touched)
	{
		int numbers = 0;
		boolean same = true;
		for (int i = 0; i < own.length && same; i++)
		{
			int f = start + i / 3;
			int vertex = i % 3 == 0 ? faces.a[f] : i % 3 == 1 ? faces.b[f] : faces.c[f];
			if (numbered[vertex] < 0)
			{
				numbered[vertex] = numbers;
				touched[numbers++] = vertex;
			}
			same = numbered[vertex] == own[i];
		}
		for (int t = 0; t < numbers; t++)
		{
			numbered[touched[t]] = -1;
		}
		return same;
	}

	/** The corners of count faces from start, numbered in order of first use. */
	private static int[] number(Faces faces, int start, int count, int[] numbered)
	{
		int[] out = new int[3 * count];
		int numbers = 0;
		for (int i = 0; i < out.length; i++)
		{
			int f = start + i / 3;
			int vertex = i % 3 == 0 ? faces.a[f] : i % 3 == 1 ? faces.b[f] : faces.c[f];
			if (numbered[vertex] < 0)
			{
				numbered[vertex] = numbers++;
			}
			out[i] = numbered[vertex];
		}
		return out;
	}

	private static int top(Faces faces, int start, int count)
	{
		int top = 0;
		for (int f = start; f < start + count; f++)
		{
			top = Math.max(top, Math.max(faces.a[f], Math.max(faces.b[f], faces.c[f])));
		}
		return top;
	}

	/** Reads a config from the game cache (also SpotAnimationConfig's). */
	static final class Reader
	{
		private final byte[] data;
		private int at;

		Reader(byte[] data)
		{
			this.data = data;
		}

		int u8()
		{
			return data[at++] & 0xFF;
		}

		int u16()
		{
			int value = (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
			at += 2;
			return value;
		}

		int i32()
		{
			int value = (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8 | data[at + 3] & 0xFF;
			at += 4;
			return value;
		}

		void skip(int bytes)
		{
			at += bytes;
		}

		void string()
		{
			while (u8() != 0)
			{
			}
		}
	}
}
