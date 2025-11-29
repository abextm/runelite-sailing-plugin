package com.duckblade.osrs.sailing.features.barracudatrials;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Perspective;
import net.runelite.api.geometry.Geometry;

@Getter
class Trial
{
	private final int dbrow;
	private final int tier;
	private final BoatPath boatPath;
	private final List<Checkpoint> checkpoints;

	public Trial(int dbrow, int tier, Builder builder)
	{
		this.dbrow = dbrow;
		this.tier = tier;
		this.boatPath = new BoatPath(builder.points);
		this.checkpoints = builder.checkpoints;

		for (int i = 0; i < builder.checkpoints.size(); i++)
		{
			var checkpoint = this.checkpoints.get(i);
			checkpoint.start = builder.checkpointPoints.get(i).start;
			checkpoint.end = builder.checkpointPoints.get(Math.min(i + 10, this.checkpoints.size() - 1)).end;

			List<BoatPath.Point> seg = null;
			for (var iseg : builder.points)
			{
				if (iseg.get(0).start <= checkpoint.start && iseg.get(iseg.size() - 1).end >= checkpoint.start)
				{
					seg = iseg;
					break;
				}
			}

			if (seg != null)
			{
				int start = 0;
				for (; start < seg.size(); start++)
				{
					if (seg.get(start).start >= checkpoint.start)
					{
						break;
					}
				}

				int end = start;
				for (; end < seg.size(); end++)
				{
					if (seg.get(start).end > checkpoint.end)
					{
						break;
					}
				}

				outer:
				for (int j = Math.max(start - 2, 0) + 1; j < end; j++)
				{
					var a = seg.get(j - 1);
					var b = seg.get(j);
					for (int k = j + 2; k < end; k++)
					{
						var c = seg.get(k - 1);
						var d = seg.get(k);
						if (Geometry.lineIntersectionPoint(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y) != null)
						{
							//checkpoint.end = c.start;
							break outer;
						}
					}
				}
			}
		}
	}

	@RequiredArgsConstructor
	@Getter
	public static class Checkpoint
	{
		final int objectID;
		int start;
		int end;
	}

	public static class Builder
	{
		private final List<List<BoatPath.Point>> points = new ArrayList<>();
		private final List<Checkpoint> checkpoints = new ArrayList<>();
		private final List<BoatPath.Point> checkpointPoints = new ArrayList<>();

		private BoatPath.Point last = null;
		private double lastTheta;

		{
			this.points.add(new ArrayList<>());
		}

		Builder pt(float x, float y, int angle)
		{
			var pts = this.points.get(points.size() - 1);
			if (pts.size() == 1)
			{
				// we have to have a checkpoint associated with the first point
				crate(-1);
			}

			double theta = (angle + 4) * -(Math.PI / 8.);

			var pt = new BoatPath.Point(x * Perspective.LOCAL_TILE_SIZE, y * Perspective.LOCAL_TILE_SIZE);

			if (last == null || theta == this.lastTheta)
			{
				pts.add(pt);
			}
			else
			{
				double c1 = Math.cos(theta);
				double s1 = Math.sin(theta);
				double c2 = Math.cos(lastTheta);
				double s2 = Math.sin(lastTheta);

				double dx = last.x - pt.x;
				double dy = last.y - pt.y;

				double den = c1 * s2 - s1 * c2;

				double t = (dx * s2 - dy * c2) / den;

				pts.add(new BoatPath.Point((float) (pt.x + t * c1), (float) (pt.y + t * s1)));
			}

			last = pt;
			this.lastTheta = theta;

			return this;
		}

		Builder crate(int object)
		{
			if (this.points.size() > 0)
			{
				var pts = this.points.get(this.points.size() - 1);
				if (pts.size() > 0)
				{
					this.checkpointPoints.add(pts.get(pts.size() - 1));
					this.checkpoints.add(new Checkpoint(object));
				}
			}
			return this;
		}

		Builder teleport()
		{
			finish();
			this.points.add(new ArrayList<>());
			last = null;
			return this;
		}

		Builder finish()
		{
			// we have to have a checkpoint associated with the last point
			return crate(-1);
		}

		Builder j(Function<Builder, Builder> fn)
		{
			return fn.apply(this);
		}
	}
}
