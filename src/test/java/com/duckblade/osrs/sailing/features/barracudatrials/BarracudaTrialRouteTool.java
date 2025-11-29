package com.duckblade.osrs.sailing.features.barracudatrials;

import com.duckblade.osrs.sailing.module.PluginLifecycleComponent;
import com.google.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldEntity;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

public class BarracudaTrialRouteTool extends Overlay implements PluginLifecycleComponent
{
	private final Client client;

	private List<Object> points = new ArrayList<>();

	@RequiredArgsConstructor
	private static class Pt
	{
		final float x;
		final float y;
		final int angle;
	}

	private static final Object TELEPORT = new Object();

	@Inject
	BarracudaTrialRouteTool(Client client)
	{
		this.client = client;

		setPosition(OverlayPosition.DYNAMIC);
	}

	private void reset()
	{
		points.clear();
	}

	private BarracudaTrialRouteTool pt(float x, float y, int angle)
	{
		this.points.add(new Pt(x, y, angle));
		return this;
	}

	private BarracudaTrialRouteTool crate(int obj)
	{
		this.points.add(obj);
		return this;
	}

	@SneakyThrows
	private void print()
	{
		var out = "new Trial.Builder()\n";
		for (var obj : points)
		{
			if (obj instanceof Pt)
			{
				var pt = (Pt) obj;
				out += "\t.pt(" + pt.x + "f, " + pt.y + "f, " + pt.angle + ")\n";
			}
			else if (obj instanceof Integer)
			{
				int id = (Integer) obj;
				String name = "" + id;
				for (var f : ObjectID.class.getFields())
				{
					if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class)
					{
						f.setAccessible(true);
						if ((Integer) f.get(null) == id)
						{
							name = "ObjectID." + f.getName();
						}
					}
				}

				out += "\t.crate(" + name + ")\n";
			}
			else if (obj == TELEPORT)
			{
				out += "\t.teleport()\n";
			}
		}
		out += "\t.finish()\n";
		System.out.println("\n" + out);
	}

	boolean recording;

	@Subscribe
	private void onVarbitChanged(VarbitChanged e)
	{
		if (!recording)
		{
			return;
		}

		int baseCrateVarbit = VarbitID.SAILING_BT_OBJECTIVE0;
		int maxCrateVarbit = VarbitID.SAILING_BT_OBJECTIVE95;
		int baseCrateId = ObjectID.SAILING_BT_JUBBLY_JIVE_COLLECTABLE_1;

		if (e.getVarbitId() >= baseCrateVarbit && e.getVarbitId() <= maxCrateVarbit)
		{
			int wvid = client.getLocalPlayer().getWorldView().getId();
			var we = client.getTopLevelWorldView().worldEntities().byIndex(wvid);
			points.add(ptForWE(we));

			points.add(e.getVarbitId() - baseCrateVarbit + baseCrateId);
		}
	}

	Pt lastRealPos;

	@Subscribe
	private void onGameTick(GameTick t)
	{
		if (!recording)
		{
			return;
		}

		int wvid = client.getLocalPlayer().getWorldView().getId();
		if (wvid == -1)
		{
			return;
		}

		Pt lastPt = lastPt();

		var we = client.getTopLevelWorldView().worldEntities().byIndex(wvid);
		var currentPt = ptForWE(we);

		if (lastPt == null || lastPt.angle != currentPt.angle ||
			((lastPt.x != currentPt.x || lastPt.y != currentPt.y)
				&& ((Math.round(Math.atan2(lastPt.x - currentPt.x, lastPt.y - currentPt.y) * (8. / Math.PI)) & 15)) != lastPt.angle
			))
		{
			if (lastPt != null && Math.hypot(lastRealPos.y - currentPt.y, lastRealPos.x - currentPt.x) > 6)
			{
				points.add(new Pt(lastRealPos.x, lastRealPos.y, (lastRealPos.angle + 4) & 15));
				points.add(TELEPORT);
			}

			points.add(currentPt);
		}

		lastRealPos = currentPt;
	}

	private Pt ptForWE(WorldEntity we)
	{
		var tlwv = client.getTopLevelWorldView();
		var lp = we.getTargetLocation();
		return new Pt((lp.getX() / 128f) + tlwv.getBaseX(), (lp.getY() / 128.f) + tlwv.getBaseY(), we.getTargetOrientation() / 128);
	}

	private Pt lastPt()
	{
		for (int i = points.size() - 1; i >= 0; i--)
		{
			var obj = points.get(i);
			if (obj instanceof Pt)
			{
				return (Pt) obj;
			}
		}

		return null;
	}

	@Subscribe
	private void onCommandExecuted(CommandExecuted ev)
	{
		switch (ev.getCommand())
		{
			case "btpush":
				var wv = client.getTopLevelWorldView();
				var we = wv.worldEntities().byIndex(client.getLocalPlayer().getWorldView().getId());
				points.add(ptForWE(we));
			case "btstart":
				recording = true;
				break;
			case "btstop":
				recording = false;
				print();
				break;
			case "btreset":
			case "btr":
				reset();
				break;
			case "btcrate":
				points.add(Integer.parseInt(ev.getArguments()[0]));
				break;
			case "btpop":
				points.remove(points.size() - 1);
				print();
				break;
		}
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		g.setColor(Color.GREEN.darker());
		g.setStroke(new BasicStroke(2));

		var tb = new Trial.Builder();
		for (var obj : points)
		{
			if (obj instanceof Pt)
			{
				var pt = (Pt) obj;
				tb.pt(pt.x, pt.y, pt.angle);
			}
			else if (obj instanceof Integer)
			{
				// ignore for debug
			}
			else if (obj == TELEPORT)
			{
				tb.teleport();
			}
		}

		tb.finish();

		var t = new Trial(-1, -1, tb);

		if (t.getCheckpoints().size() > 0)
		{
			t.getBoatPath().render(client, g, 0, t.getCheckpoints().get(t.getCheckpoints().size() - 1).end);
		}

		return null;
	}
}
