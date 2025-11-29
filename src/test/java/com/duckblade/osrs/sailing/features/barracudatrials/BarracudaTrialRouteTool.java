package com.duckblade.osrs.sailing.features.barracudatrials;

import com.duckblade.osrs.sailing.debugplugin.module.DebugLifecycleComponent;
import com.google.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import net.runelite.api.Client;
import net.runelite.api.WorldEntity;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.HotkeyListener;

public class BarracudaTrialRouteTool extends Overlay implements DebugLifecycleComponent
{
	private static final Object TELEPORT = new Object();

	private final Client client;
	private final KeyManager keyManager;
	private final ClientThread clientThread;

	private final HotkeyListener markListener = new HotkeyListener(() -> new Keybind(KeyEvent.VK_SPACE, 0))
	{
		@Override
		public void hotkeyPressed()
		{
			if (recordingCrate)
			{
				clientThread.invoke(() ->
				{
					int wvid = client.getLocalPlayer().getWorldView().getId();
					var we = client.getTopLevelWorldView().worldEntities().byIndex(wvid);
					points.add(ptForWE(we));
				});
			}
		}
	};

	private List<Object> points = new ArrayList<>();

	boolean recording;
	boolean recordingCrate;

	@RequiredArgsConstructor
	private static class Pt
	{
		final float x;
		final float y;
		final int angle;
	}

	@Inject
	BarracudaTrialRouteTool(Client client, KeyManager keyManager, ClientThread clientThread)
	{
		this.client = client;
		this.keyManager = keyManager;
		this.clientThread = clientThread;

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
		final int SPLIT_AT = 200;

		var out = points.size() > SPLIT_AT
			? "t ->\n{\nt\n"
			:  "t -> t\n";
		for (int i = 0; i < points.size(); i++)
		{
			var obj = points.get(i);
			if (obj instanceof Pt)
			{
				var pt = (Pt) obj;
				out += "\t.pt(" + pt.x + "f, " + pt.y + "f, " + pt.angle + ")";
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

				out += "\t.crate(" + name + ")";
			}
			else if (obj == TELEPORT)
			{
				out += "\t.teleport()";
			}


			if (i % SPLIT_AT == 0 && i != 0)
			{
				out += ";\nt";
			}

			out += "\n";
		}
		out += "\t.finish();\n";
		if (points.size() > SPLIT_AT)
		{
			out += "}";
		}
		System.out.println("\n" + out);
	}

	@Subscribe
	private void onVarbitChanged(VarbitChanged e)
	{
		if (!recordingCrate)
		{
			return;
		}

		int baseCrateVarbit = VarbitID.SAILING_BT_OBJECTIVE0;
		int maxCrateVarbit = VarbitID.SAILING_BT_OBJECTIVE95;
		int baseCrateId = ObjectID.SAILING_BT_GWENITH_GLIDE_COLLECTABLE_1;

		if (e.getVarbitId() >= baseCrateVarbit && e.getVarbitId() <= maxCrateVarbit)
		{
			if (recording)
			{
				int wvid = client.getLocalPlayer().getWorldView().getId();
				var we = client.getTopLevelWorldView().worldEntities().byIndex(wvid);
				points.add(ptForWE(we));
			}

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
				recordingCrate = true;
				break;
			case "btstartcrate":
				recordingCrate = true;
				keyManager.registerKeyListener(markListener);
				break;
			case "btstop":
				recording = false;
				recordingCrate = false;
				keyManager.unregisterKeyListener(markListener);
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
		if (points.size() < 2)
		{
			return null;
		}

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
