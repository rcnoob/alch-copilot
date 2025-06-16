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

    @ConfigItem(
            keyName = "maxTotalPrice",
            name = "Maximum Total Investment",
            description = "Maximum total cost for recommended quantity (gp). Set to 0 to disable this filter.",
            section = selectionSection,
            position = 3
    )
    default int maxTotalPrice() {
        return 0;
    }

    @ConfigItem(
            keyName = "membershipFilter",
            name = "Item Type Filter",
            description = "Filter items by membership requirement",
            section = selectionSection,
            position = 4
    )
    default MembershipFilter membershipFilter() {
        return MembershipFilter.BOTH;
    }

    enum MembershipFilter {
        F2P("Free-to-Play Only"),
        P2P("Members Only"),
        BOTH("Both F2P and P2P");

        private final String displayName;

        MembershipFilter(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
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
            keyName = "showVolumeInfo",
            name = "Show Volume Info",
            description = "Show trading volume information when available.",
            section = displaySection,
            position = 1
    )
    default boolean showVolumeInfo() {
        return true;
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

    @ConfigItem(
            keyName = "maxRecommendations",
            name = "Maximum Stored Recommendations",
            description = "Maximum number of item recommendations to keep in the list.",
            section = displaySection,
            position = 3
    )
    @Range(min = 1, max = 100)
    default int maxRecommendations() {
        return 10;
    }
}