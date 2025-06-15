package com.rcnoob;

import com.rcnoob.alchcopilot.AlchCopilotPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AlchCopilotTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AlchCopilotPlugin.class);
		RuneLite.main(args);
	}
}