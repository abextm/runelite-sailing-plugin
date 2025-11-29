package com.duckblade.osrs.sailing.features.barracudatrials;

import com.duckblade.osrs.sailing.SailingConfig;
import com.duckblade.osrs.sailing.features.util.SailingUtil;
import com.duckblade.osrs.sailing.module.PluginLifecycleComponent;
import com.google.common.collect.ImmutableSet;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

@Slf4j
@Singleton
public class BarracudaTrialHelper
	extends Overlay
	implements PluginLifecycleComponent
{
	private static final Set<Integer> TRACKED_OBJECTS;

	static
	{
		var trackedObjects = ImmutableSet.<Integer>builder();

		trackedObjects.addAll(TrialData.CARGO_OBJECTS);

		trackedObjects.add(
			ObjectID.SAILING_BT_JUBBLY_JIVE_TOAD_SUPPLIES_PARENT,
			ObjectID.SAILING_BT_TEMPOR_TANTRUM_NORTH_LOC_PARENT,
			ObjectID.SAILING_BT_TEMPOR_TANTRUM_SOUTH_LOC_PARENT
		);

		TRACKED_OBJECTS = trackedObjects.build();
	}

	private final Client client;
	private final SailingConfig config;

	private final Map<Integer, GameObject> objects = new HashMap<>();

	private int trialDBRow = -1;
	private int trialRank;
	private Trial activeTrial;

	@Inject
	public BarracudaTrialHelper(Client client, SailingConfig config)
	{
		this.client = client;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public boolean isEnabled(SailingConfig config)
	{
		return config.barracudaHighlightCrates() || config.barracudaShowPath();
	}

	@Override
	public void shutDown()
	{
		trialDBRow = -1;
		objects.clear();
	}

	@Subscribe
	public void onWorldViewUnloaded(WorldViewUnloaded e)
	{
		objects.values().removeIf(go -> go.getWorldView() == e.getWorldView());
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		GameObject o = e.getGameObject();
		if (TRACKED_OBJECTS.contains(o.getId()))
		{
			objects.put(o.getId(), o);
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e)
	{
		objects.remove(e.getGameObject().getId());
	}

	@Subscribe
	private void onScriptPreFired(ScriptPreFired ev)
	{
		if (ev.getScriptId() == 8605)
		{
			try
			{
				var args = ev.getScriptEvent().getArguments();

				trialDBRow = (Integer) args[1];
				trialRank = (Integer) args[6];
			}
			catch (Exception e)
			{
				log.warn("failed to get trial args", e);
				trialDBRow = -1;
			}
		}
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (trialDBRow == -1 || client.getVarbitValue(VarbitID.SAILING_BT_IN_TRIAL) == 0)
		{
			return null;
		}

		{
			var obj = objects.get(ObjectID.SAILING_BT_JUBBLY_JIVE_TOAD_SUPPLIES_PARENT);
			if (obj != null)
			{
				var objwe = client.getTopLevelWorldView().worldEntities().byIndex(obj.getWorldView().getId());
				//var objPos = objwe.transformToMainWorld(obj.getLocalLocation());
				var objPos = objwe.getLocalLocation().dx(128);

				g.setColor(Color.RED);
				g.drawString(objwe.getWorldView().getScene().getTiles()[0].length + " " + objwe.getWorldView().getScene().getTiles()[0][0].length, 200, 200);

				int wvid = client.getLocalPlayer().getWorldView().getId();
				var boatPos = client.getTopLevelWorldView().worldEntities().byIndex(wvid)
					.getTargetLocation();

				int dx = objPos.getX() - boatPos.getX();
				int dy = objPos.getY() - boatPos.getY();

				int sz = 14 * 128 + 64;
				boolean inside = dx > -sz && dx <= sz && dy > -sz && dy <= sz;

				var poly = Perspective.getCanvasTileAreaPoly(client, objPos, (sz * 2) / 128);
				if (poly != null)
				{
					OverlayUtil.renderPolygon(g, poly, inside ? Color.GREEN : Color.RED);
				}
			}
		}


		Trial trial = null;
		if (config.barracudaShowPath())
		{
			if (activeTrial == null
				|| activeTrial.getDbrow() != trialDBRow
				|| activeTrial.getTier() != trialRank)
			{
				var td = TrialData.findTrial(trialDBRow, trialRank);
				if (td != null)
				{
					trial = td.buildTrial();
				}

				//TODO: activeTrial = trial;
			}
		}
		else
		{
			activeTrial = null;
		}

		if (trial == null && config.barracudaHighlightCrates())
		{
			var crateColor = config.barracudaCrateColor();

			for (GameObject o : objects.values())
			{
				if (TrialData.CARGO_OBJECTS.contains(o.getId()))
				{
					renderCrate(g, o, crateColor);
				}
			}
		}

		if (trial != null)
		{
			renderTrial(trial, g);
		}

		return null;
	}

	private void renderTrial(Trial t, Graphics2D g)
	{
		var checkpoints = t.getCheckpoints();

		int i = 0;
		for (; i < checkpoints.size(); i++)
		{
			int obj = checkpoints.get(i).objectID;

			if (obj > -1 && client.getObjectDefinition(obj).getImpostor() != null)
			{
				break;
			}
		}

		var range = checkpoints.get(Math.max(0, i - 3));

		if (config.barracudaHighlightCrates())
		{
			var crateColor = config.barracudaCrateColor();

			for (; i < checkpoints.size(); i++)
			{
				var ckpt = checkpoints.get(i);
				if (ckpt.start > range.end)
				{
					break;
				}

				var obj = objects.get(ckpt.objectID);
				if (obj != null)
				{
					renderCrate(g, obj, crateColor);
				}
			}
		}

		g.setStroke(new BasicStroke(2));
		g.setColor(config.barracudaPathColor());

		t.getBoatPath().render(client, g, range.start, range.end);
	}

	private void renderCrate(Graphics2D g, GameObject obj, Color color)
	{
		ObjectComposition def = SailingUtil.getTransformedObject(client, obj);
		if (def != null)
		{
			var poly = Perspective.getCanvasTileAreaPoly(client, obj.getLocalLocation(), 5);
			if (poly != null)
			{
				OverlayUtil.renderPolygon(g, poly, color);
			}
		}
	}
}
