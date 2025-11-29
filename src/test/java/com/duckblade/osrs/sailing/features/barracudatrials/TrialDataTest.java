package com.duckblade.osrs.sailing.features.barracudatrials;

import org.junit.Test;

public class TrialDataTest
{
	@Test
	public void testLoad()
	{
		for (var t : TrialData.values())
		{
			t.buildTrial();
		}
	}
}
