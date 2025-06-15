package com.rcnoob.alchcopilot;

import com.google.inject.Provides;
import com.rcnoob.alchcopilot.model.AlchItem;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.http.api.item.ItemStats;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@PluginDescriptor(
        name = "Alch Copilot"
)
public class AlchCopilotPlugin extends Plugin {

    /**
     * Whether to update the selection at the next available time.
     */
    public boolean readyForOptimalUpdate = true;
    /**
     * Whether to use wiki prices for pricing.
     */
    public boolean useWikiPrices = true;

    @Inject
    private Client client;
    @Inject
    public AlchCopilotConfig config;
    @Inject
    private ItemManager itemManager;
    @Inject
    private ClientToolbar clientToolbar;

    /**
     * The current optimal item selection.
     */
    private AlchItem optimalItem;
    /**
     * The RuneLite sidebar navigation button.
     */
    private NavigationButton navButton;
    /**
     * Plugin sidebar panel.
     */
    private AlchCopilotPanel panel;

    @Override
    protected void startUp() throws Exception {
        if (client.getGameState() == GameState.LOGGED_IN) {
            onLoginOrActivated();
        }

        panel = new AlchCopilotPanel(this, client, itemManager);
        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");

        navButton = NavigationButton.builder()
                .tooltip("Alch Copilot")
                .icon(icon)
                .priority(2)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);
    }

    public void findOptimalAlchItem() {
        log.info("Starting optimal alch item search...");

        List<ItemPrice> itemPrices = this.itemManager.search("");
        int natureRunePrice = this.itemManager.getItemPrice(ItemID.NATURE_RUNE);

        List<AlchItem> candidates = new ArrayList<>();

        // Process all items and calculate profits using RuneLite's data
        for (ItemPrice price : itemPrices) {
            int itemId = price.getId();
            ItemComposition itemComposition = this.itemManager.getItemComposition(itemId);
            ItemStats itemStats = itemManager.getItemStats(itemId, false);
            String name = price.getName();

            if (itemStats == null || itemComposition == null || name.length() == 0) {
                continue;
            }

            // Use wiki prices for most accurate current pricing
            int currentPrice = this.useWikiPrices ? itemManager.getWikiPrice(price) : price.getPrice();
            int highAlchPrice = itemComposition.getHaPrice();
            int profit = highAlchPrice - currentPrice - natureRunePrice;
            int geLimit = itemStats.getGeLimit();

            // Pre-filter: must meet minimum profit, GE limit, and max price criteria
            if (profit < config.minimumProfit() ||
                    geLimit < config.minimumGeLimit() ||
                    (config.maxPrice() > 0 && currentPrice > config.maxPrice())) {
                continue;
            }

            BufferedImage image = itemManager.getImage(itemId, geLimit, false);
            candidates.add(new AlchItem(name, itemId, currentPrice, highAlchPrice, profit, geLimit, image));
        }

        log.info("Found {} candidates meeting profit criteria", candidates.size());

        if (candidates.isEmpty()) {
            optimalItem = null;
            log.warn("No suitable alch items found meeting criteria");
            SwingUtilities.invokeLater(() -> panel.updateItemList());
            readyForOptimalUpdate = false;
            return;
        }

        // Sort by profit and select the best one
        candidates.sort(Comparator.comparing(AlchItem::getHighAlchProfit).reversed());
        optimalItem = candidates.get(0);

        readyForOptimalUpdate = false;
        log.info("Selected optimal item: {} (Profit: {} gp)", optimalItem.getName(), optimalItem.getHighAlchProfit());
        SwingUtilities.invokeLater(() -> panel.updateItemList());
    }

    @Override
    protected void shutDown() throws Exception {
        clientToolbar.removeNavigation(navButton);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        if (gameStateChanged.getGameState() == GameState.LOGGED_IN) {
            onLoginOrActivated();
        }
    }

    @Provides
    AlchCopilotConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AlchCopilotConfig.class);
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (readyForOptimalUpdate && client.getGameState() == GameState.LOGGED_IN) {
            findOptimalAlchItem();
        }
    }

    public AlchItem getOptimalItem() {
        return optimalItem;
    }

    public List<AlchItem> getAlchItems() {
        // Return single item as list for panel compatibility
        if (optimalItem == null) {
            return new ArrayList<>();
        }
        return List.of(optimalItem);
    }

    /**
     * Run when the user logs in, or when they enable the plugin while logged in.
     */
    private void onLoginOrActivated() {
        if (config.refreshOnLogin()) {
            readyForOptimalUpdate = true;
        }
    }
}