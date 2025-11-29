package com.duckblade.osrs.sailing.features.barracudatrials;

import java.awt.Graphics2D;
import java.awt.geom.CubicCurve2D;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.Perspective;

class BoatPath
{
	private static final float LINE_SEG_LEN = 3 * 128;

	private static final int OP_LINE = 0;
	private static final int OP_CORNER = 1;

	private static final int IDX_SHIFT = 2;

	private final int[] ops;
	private final float[] xs;
	private final float[] ys;
	private final float[] zs;

	private final int[] x2ds;
	private final int[] y2ds;

	public BoatPath(List<List<Point>> lines)
	{
		class Builder
		{
			int[] ops = new int[64];
			float[] xs = new float[64];
			float[] ys = new float[64];

			int coordi = 0;
			int opi = 0;

			void pushLine(Point a, Point b)
			{
				float x1 = b.x - a.x;
				float y1 = b.y - a.y;
				float len = (float) Math.sqrt(x1 * x1 + y1 * y1);

				float combinedOutset = a.outset + b.outset;
				if (combinedOutset > len)
				{
					return;
				}

				// we split the line up into segments about 3 tiles in length
				// so long lines don't get culled when too close to the camera

				float segments = Math.max(1, Math.round((len - combinedOutset) / LINE_SEG_LEN));

				float step = (len - combinedOutset) / (segments * len);
				float rad = a.outset / len;
				for (int i = (int) segments; i >= 0; i--)
				{
					if (i > 0)
					{
						pushOp(OP_LINE, coordi);
					}
					pushPoint(a.x + x1 * rad, a.y + y1 * rad);
					rad += step;
				}
			}

			void pushCorner(Point a, Point b, Point c)
			{
				pushOp(OP_CORNER, coordi);
				pushHalfCorner(a, b);
				pushHalfCorner(c, b);
			}

			void pushHalfCorner(Point b, Point a)
			{
				float x1 = b.x - a.x;
				float y1 = b.y - a.y;
				float m = a.outset / (float) Math.sqrt(x1 * x1 + y1 * y1);

				pushPoint(a.x + x1 * m, a.y + y1 * m);
				m *= .25f;
				pushPoint(a.x + x1 * m, a.y + y1 * m);
			}

			void pushPoint(float x, float y)
			{
				if (coordi >= xs.length)
				{
					xs = Arrays.copyOf(xs, xs.length + 64);
					ys = Arrays.copyOf(ys, xs.length);
				}

				xs[coordi] = x;
				ys[coordi++] = y;
			}

			void pushOp(int op, int index)
			{
				if (opi >= ops.length)
				{
					ops = Arrays.copyOf(ops, ops.length + 64);
				}

				ops[opi++] = op | index << IDX_SHIFT;
			}

			void build()
			{
				for (var points : lines)
				{
					for (int i = 1; i < points.size() - 1; i++)
					{
						var p0 = points.get(i - 1);
						var p1 = points.get(i);
						var p2 = points.get(i + 1);

						double dx1 = p0.x - p1.x;
						double dy1 = p0.y - p1.y;
						double dx2 = p2.x - p1.x;
						double dy2 = p2.y - p1.y;

						double dot = dx1 * dx2 + dy1 * dy2;

						double mag1 = Math.sqrt(dx1 * dx1 + dy1 * dy1);
						double mag2 = Math.sqrt(dx2 * dx2 + dy2 * dy2);

						if (mag1 > 0 && mag2 > 0)
						{
							double angle = Math.abs(Math.acos(Math.min(1.0, Math.max(-1.0, dot / (mag1 * mag2)))) - Math.PI);

							// 2 tiles per 16th of a turn
							p1.outset = Math.min(
								(float) (angle * ((Perspective.LOCAL_TILE_SIZE * 1.75 * 8) / Math.PI)),
								(float) Math.min(mag1, mag2) / 2f);
						}
					}

					points.get(0).end = opi;
					points.get(0).start = opi;
					for (int i = 1; i < points.size(); i++)
					{
						pushLine(points.get(i - 1), points.get(i));
						points.get(i).start = opi;
						if (i + 1 < points.size() && points.get(i).outset > 0)
						{
							pushCorner(points.get(i - 1), points.get(i), points.get(i + 1));
							points.get(i).end = opi - 1;
						}
						else
						{
							points.get(i).end = opi;
						}
					}
				}
			}
		}

		var b = new Builder();
		b.build();

		this.ops = b.ops;
		this.xs = b.xs;
		this.ys = b.ys;

		zs = new float[xs.length];

		x2ds = new int[xs.length];
		y2ds = new int[xs.length];
	}

	@RequiredArgsConstructor
	public static class Point
	{
		final float x, y;
		float outset;
		int start;
		int end;
	}

	void render(Client client, Graphics2D g, int start, int end)
	{
		var wv = client.getTopLevelWorldView();

		Perspective.modelToCanvas(client, wv,
			xs.length,
			wv.getBaseX() * -Perspective.LOCAL_TILE_SIZE, wv.getBaseY() * -Perspective.LOCAL_TILE_SIZE, 0,
			0,
			xs, ys, zs,
			x2ds, y2ds);

		CubicCurve2D.Float curve = new CubicCurve2D.Float();

		for (int opi = start; opi <= end; opi++)
		{
			int op = ops[opi] & ((1 << IDX_SHIFT) - 1);
			int i = ops[opi] >>> IDX_SHIFT;

			if (op == OP_LINE)
			{
				if (x2ds[i] != Integer.MIN_VALUE && x2ds[i + 1] != Integer.MIN_VALUE)
				{
					g.drawLine(x2ds[i], y2ds[i], x2ds[i + 1], y2ds[i + 1]);
				}
			}
			else if (op == OP_CORNER)
			{
				curve.x1 = x2ds[i];
				curve.y1 = y2ds[i];
				curve.ctrlx1 = x2ds[++i];
				curve.ctrly1 = y2ds[i];
				curve.x2 = x2ds[++i];
				curve.y2 = y2ds[i];
				curve.ctrlx2 = x2ds[++i];
				curve.ctrly2 = y2ds[i];
				if (curve.x1 != Integer.MIN_VALUE
					&& curve.ctrlx1 != Integer.MIN_VALUE
					&& curve.ctrlx2 != Integer.MIN_VALUE
					&& curve.x2 != Integer.MIN_VALUE)
				{
					g.draw(curve);
				}
			}
		}
	}
}
