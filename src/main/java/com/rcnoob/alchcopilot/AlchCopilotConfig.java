package com.rcnoob.alchcopilot;

import net.runelite.client.config.*;

@ConfigGroup("AlchCopilotPlugin")
public interface AlchCopilotConfig extends Config {

    @ConfigSection(
            name = "Selection Criteria",
            description = "Criteria for selecting the optimal alch item",
            position = 0)
    String selectionSection = "Selection Criteria";

    @ConfigItem(
            keyName = "minimumProfit",
            name = "Minimum Profit",
            description = "Minimum profit per alch (gp). Items below this threshold will be ignored.",
            section = selectionSection,
            position = 0
    )
    default int minimumProfit() {
        return 100;
    }

    @ConfigItem(
            keyName = "minimumGeLimit",
            name = "Minimum GE Limit",
            description = "Minimum Grand Exchange buy limit. Items with lower limits may not be worth buying.",
            section = selectionSection,
            position = 1
    )
    default int minimumGeLimit() {
        return 100;
    }

    @ConfigItem(
            keyName = "maxPrice",
            name = "Maximum Item Price",
            description = "Maximum price per item (gp). Set to 0 to disable this filter.",
            section = selectionSection,
            position = 2
    )
    default int maxPrice() {
        return 0;
    }

    @ConfigSection(
            name = "Display Settings",
            description = "How the plugin displays information",
            position = 1)
    String displaySection = "Display Settings";

    @ConfigItem(
            keyName = "showDetailedInfo",
            name = "Show Detailed Info",
            description = "Show additional details like GE limits and calculations in the panel.",
            section = displaySection,
            position = 0
    )
    default boolean showDetailedInfo() {
        return true;
    }

    @ConfigItem(
            keyName = "recommendedQuantity",
            name = "Recommended Purchase Quantity",
            description = "How many items to recommend buying (will not exceed GE limit).",
            section = displaySection,
            position = 1
    )
    @Range(min = 1, max = 10000)
    default int recommendedQuantity() {
        return 1000;
    }

    @ConfigItem(
            keyName = "refreshOnLogin",
            name = "Auto-refresh on Login",
            description = "Automatically search for optimal items when logging in.",
            section = displaySection,
            position = 2
    )
    default boolean refreshOnLogin() {
        return true;
    }
}