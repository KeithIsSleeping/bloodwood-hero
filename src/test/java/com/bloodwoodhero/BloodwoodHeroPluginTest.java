package com.bloodwoodhero;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BloodwoodHeroPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BloodwoodHeroPlugin.class);
		RuneLite.main(args);
	}
}
