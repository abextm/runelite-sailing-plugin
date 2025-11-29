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
	private boolean hasSupplyBoatItem;

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
		return config.barracudaHighlightLostCrates() || config.barracudaShowPath();
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
				hasSupplyBoatItem = 1 == (Integer) args[5];
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

		if (trial == null && config.barracudaHighlightLostCrates())
		{
			var crateColor = config.barracudaHighlightLostCratesColor();

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
		for (; i < checkpoints.size() - 1; i++)
		{
			int obj = checkpoints.get(i).objectID;

			if (obj > -1 && client.getObjectDefinition(obj).getImpostor() != null)
			{
				break;
			}
		}

		var range = checkpoints.get(i);

		if (config.barracudaHighlightLostCrates())
		{
			var crateColor = config.barracudaHighlightLostCratesColor();

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
