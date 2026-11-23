package com.keyframesmoothing;

/** Unit quaternions as double[]{w, x, y, z}; rotation vectors as double[]{x, y, z} (axis times angle in radians). */
final class Quaternions
{
	private Quaternions()
	{
	}

	/**
	 * The rotation that best turns a set of points onto another (Horn, 1987), from their cross-covariance
	 * {Sxx, Sxy, Sxz, Syx, Syy, Syz, Szx, Szy, Szz} (sum of a_i * b_j, both sets centred).
	 */
	static double[] fit(double[] s)
	{
		double[] out = new double[4];
		fit(s, new double[32], out);
		return out;
	}

	/** As {@link #fit(double[])}, into out (w, x, y, z), with work (32 long) to work in. */
	static void fit(double[] s, double[] work, double[] out)
	{
		double xx = s[0], xy = s[1], xz = s[2], yx = s[3], yy = s[4], yz = s[5], zx = s[6], zy = s[7], zz = s[8];
		// the symmetric 4x4 matrix, row-major in work[0..15]
		work[0] = xx + yy + zz;
		work[1] = yz - zy;
		work[2] = zx - xz;
		work[3] = xy - yx;
		work[4] = yz - zy;
		work[5] = xx - yy - zz;
		work[6] = xy + yx;
		work[7] = zx + xz;
		work[8] = zx - xz;
		work[9] = xy + yx;
		work[10] = -xx + yy - zz;
		work[11] = yz + zy;
		work[12] = xy - yx;
		work[13] = zx + xz;
		work[14] = yz + zy;
		work[15] = -xx - yy + zz;
		int best = largestEigenvector(work);
		double q0 = work[16 + best], q1 = work[20 + best], q2 = work[24 + best], q3 = work[28 + best];
		double length = Math.sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
		if (length < 1e-12)
		{
			out[0] = 1;
			out[1] = 0;
			out[2] = 0;
			out[3] = 0;
			return;
		}
		out[0] = q0 / length;
		out[1] = q1 / length;
		out[2] = q2 / length;
		out[3] = q3 / length;
	}

	/**
	 * Jacobi eigenvalue method for the symmetric 4x4 matrix in work[0..15] (row-major, worked on in place): the
	 * eigenvectors go in work[16..31] as columns, and this returns the column of the largest eigenvalue.
	 */
	private static int largestEigenvector(double[] work)
	{
		final int v = 16;
		for (int i = 0; i < 16; i++)
		{
			work[v + i] = i % 5 == 0 ? 1 : 0;
		}
		for (int sweep = 0; sweep < 50; sweep++)
		{
			double off = 0;
			for (int i = 0; i < 4; i++)
			{
				for (int j = i + 1; j < 4; j++)
				{
					off += work[4 * i + j] * work[4 * i + j];
				}
			}
			if (off < 1e-18)
			{
				break;
			}
			for (int p = 0; p < 4; p++)
			{
				for (int q = p + 1; q < 4; q++)
				{
					if (Math.abs(work[4 * p + q]) < 1e-15)
					{
						continue;
					}
					double theta = (work[4 * q + q] - work[4 * p + p]) / (2 * work[4 * p + q]);
					double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
					if (theta == 0)
					{
						t = 1;
					}
					double c = 1 / Math.sqrt(t * t + 1), s = t * c;
					for (int k = 0; k < 4; k++)
					{
						double akp = work[4 * k + p], akq = work[4 * k + q];
						work[4 * k + p] = c * akp - s * akq;
						work[4 * k + q] = s * akp + c * akq;
					}
					for (int k = 0; k < 4; k++)
					{
						double apk = work[4 * p + k], aqk = work[4 * q + k];
						work[4 * p + k] = c * apk - s * aqk;
						work[4 * q + k] = s * apk + c * aqk;
					}
					for (int k = 0; k < 4; k++)
					{
						double vkp = work[v + 4 * k + p], vkq = work[v + 4 * k + q];
						work[v + 4 * k + p] = c * vkp - s * vkq;
						work[v + 4 * k + q] = s * vkp + c * vkq;
					}
				}
			}
		}
		int best = 0;
		for (int i = 1; i < 4; i++)
		{
			if (work[4 * i + i] > work[4 * best + best])
			{
				best = i;
			}
		}
		return best;
	}

	static double dot(double[] a, double[] b)
	{
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
	}

	static double[] negate(double[] q)
	{
		return new double[]{-q[0], -q[1], -q[2], -q[3]};
	}

	static double[] conjugate(double[] q)
	{
		return new double[]{q[0], -q[1], -q[2], -q[3]};
	}

	static double[] multiply(double[] a, double[] b)
	{
		double[] out = new double[4];
		multiply(a, b, out);
		return out;
	}

	/** As {@link #multiply(double[], double[])}, into out (which may be a or b). */
	static void multiply(double[] a, double[] b, double[] out)
	{
		double w = a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3];
		double x = a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2];
		double y = a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1];
		double z = a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0];
		out[0] = w;
		out[1] = x;
		out[2] = y;
		out[3] = z;
	}

	/** The angle (radians) between two rotations. */
	static double angle(double[] a, double[] b)
	{
		return 2 * Math.acos(Math.min(1, Math.abs(dot(a, b))));
	}

	/** Rotation vector of q, the short way round (angle up to pi). */
	static double[] log(double[] q)
	{
		double w = q[0], x = q[1], y = q[2], z = q[3];
		if (w < 0)
		{
			w = -w;
			x = -x;
			y = -y;
			z = -z;
		}
		double sin = Math.sqrt(x * x + y * y + z * z);
		if (sin < 1e-12)
		{
			return new double[]{2 * x, 2 * y, 2 * z};
		}
		double angle = 2 * Math.atan2(sin, w);
		return new double[]{x / sin * angle, y / sin * angle, z / sin * angle};
	}

	static double[] exp(double[] v)
	{
		double[] out = new double[4];
		exp(v[0], v[1], v[2], out);
		return out;
	}

	/** As {@link #exp(double[])} of {x, y, z}, into out. */
	static void exp(double x, double y, double z, double[] out)
	{
		double angle = Math.sqrt(x * x + y * y + z * z);
		if (angle < 1e-12)
		{
			out[0] = 1;
			out[1] = x / 2;
			out[2] = y / 2;
			out[3] = z / 2;
			return;
		}
		double s = Math.sin(angle / 2) / angle;
		out[0] = Math.cos(angle / 2);
		out[1] = x * s;
		out[2] = y * s;
		out[3] = z * s;
	}

	static double[] rotate(double[] q, double x, double y, double z)
	{
		double[] out = new double[3];
		rotate(q, x, y, z, out);
		return out;
	}

	/** As {@link #rotate(double[], double, double, double)}, into out (which may be q). */
	static void rotate(double[] q, double x, double y, double z, double[] out)
	{
		double w = q[0], qx = q[1], qy = q[2], qz = q[3];
		// t = 2 * cross(q.xyz, v); v' = v + w * t + cross(q.xyz, t)
		double tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
		out[0] = x + w * tx + (qy * tz - qz * ty);
		out[1] = y + w * ty + (qz * tx - qx * tz);
		out[2] = z + w * tz + (qx * ty - qy * tx);
	}
}
