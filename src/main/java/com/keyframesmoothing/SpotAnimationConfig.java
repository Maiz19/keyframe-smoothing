package com.keyframesmoothing;

/**
 * Reads which animation a spot animation (a graphic on a character: a teleport's, a spell's) plays, from its config in
 * the game cache: the client draws it on its own and asks the filter about that animation.
 */
final class SpotAnimationConfig
{
	private SpotAnimationConfig()
	{
	}

	/** @return the animation, or -1 for none or for data it does not understand */
	static int animation(byte[] data)
	{
		try
		{
			WeaponFinder.Reader in = new WeaponFinder.Reader(data);
			while (true)
			{
				int opcode = in.u8();
				switch (opcode)
				{
					case 0:
						return -1;
					case 2:
						int animation = in.u16();
						return animation == 0xFFFF ? -1 : animation;
					case 1:
					case 4:
					case 5:
					case 6:
					case 42:
						in.skip(2);
						break;
					case 3:
						in.skip(4);
						break;
					case 7:
					case 8:
						in.skip(1);
						break;
					case 9:
						in.string();
						break;
					case 10:
						break;
					case 40:
					case 41:
						in.skip(4 * in.u8());
						break;
					default:
						return -1;
				}
			}
		}
		catch (ArrayIndexOutOfBoundsException e)
		{
			return -1;
		}
	}
}
