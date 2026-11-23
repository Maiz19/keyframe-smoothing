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
		double xx = s[0], xy = s[1], xz = s[2], yx = s[3], yy = s[4], yz = s[5], zx = s[6], zy = s[7], zz = s[8];
		double[][] n = {
			{xx + yy + zz, yz - zy, zx - xz, xy - yx},
			{yz - zy, xx - yy - zz, xy + yx, zx + xz},
			{zx - xz, xy + yx, -xx + yy - zz, yz + zy},
			{xy - yx, zx + xz, yz + zy, -xx - yy + zz},
		};
		double[] q = largestEigenvector(n);
		double length = Math.sqrt(dot(q, q));
		if (length < 1e-12)
		{
			return new double[]{1, 0, 0, 0};
		}
		return new double[]{q[0] / length, q[1] / length, q[2] / length, q[3] / length};
	}

	/** Jacobi eigenvalue method for a symmetric 4x4 matrix. */
	private static double[] largestEigenvector(double[][] m)
	{
		double[][] a = new double[4][];
		for (int i = 0; i < 4; i++)
		{
			a[i] = m[i].clone();
		}
		double[][] v = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
		for (int sweep = 0; sweep < 50; sweep++)
		{
			double off = 0;
			for (int i = 0; i < 4; i++)
			{
				for (int j = i + 1; j < 4; j++)
				{
					off += a[i][j] * a[i][j];
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
					if (Math.abs(a[p][q]) < 1e-15)
					{
						continue;
					}
					double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
					double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
					if (theta == 0)
					{
						t = 1;
					}
					double c = 1 / Math.sqrt(t * t + 1), s = t * c;
					for (int k = 0; k < 4; k++)
					{
						double akp = a[k][p], akq = a[k][q];
						a[k][p] = c * akp - s * akq;
						a[k][q] = s * akp + c * akq;
					}
					for (int k = 0; k < 4; k++)
					{
						double apk = a[p][k], aqk = a[q][k];
						a[p][k] = c * apk - s * aqk;
						a[q][k] = s * apk + c * aqk;
					}
					for (int k = 0; k < 4; k++)
					{
						double vkp = v[k][p], vkq = v[k][q];
						v[k][p] = c * vkp - s * vkq;
						v[k][q] = s * vkp + c * vkq;
					}
				}
			}
		}
		int best = 0;
		for (int i = 1; i < 4; i++)
		{
			if (a[i][i] > a[best][best])
			{
				best = i;
			}
		}
		return new double[]{v[0][best], v[1][best], v[2][best], v[3][best]};
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
		return new double[]{
			a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
			a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
			a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
			a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
		};
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
		double angle = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		if (angle < 1e-12)
		{
			return new double[]{1, v[0] / 2, v[1] / 2, v[2] / 2};
		}
		double s = Math.sin(angle / 2) / angle;
		return new double[]{Math.cos(angle / 2), v[0] * s, v[1] * s, v[2] * s};
	}

	static double[] rotate(double[] q, double x, double y, double z)
	{
		double w = q[0], qx = q[1], qy = q[2], qz = q[3];
		// t = 2 * cross(q.xyz, v); v' = v + w * t + cross(q.xyz, t)
		double tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
		return new double[]{
			x + w * tx + (qy * tz - qz * ty),
			y + w * ty + (qz * tx - qx * tz),
			z + w * tz + (qx * ty - qy * tx),
		};
	}
}
