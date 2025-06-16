package com.rcnoob.alchcopilot;

import com.google.inject.Provides;
import com.rcnoob.alchcopilot.model.AlchItem;
import com.rcnoob.alchcopilot.service.VolumeChecker;
import com.rcnoob.alchcopilot.service.ItemDatabaseService;
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
import net.runelite.client.game.ItemStats;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@PluginDescriptor(
        name = "Alch Copilot"
)
public class AlchCopilotPlugin extends Plugin {

    // flags to control search behavior
    public boolean readyForOptimalUpdate = true;
    public boolean findingNewItem = false;
    private boolean searchInProgress = false;

    @Inject
    private Client client;
    @Inject
    public AlchCopilotConfig config;
    @Inject
    private ItemManager itemManager;
    @Inject
    private ClientToolbar clientToolbar;
    @Inject
    private VolumeChecker volumeChecker;
    @Inject
    private ItemDatabaseService itemDatabaseService;

    // store recommendations and track which items we've already recommended
    private final List<AlchItem> recommendations = new ArrayList<>();
    private final Set<Integer> recommendedItemIds = new HashSet<>();
    private NavigationButton navButton;
    private AlchCopilotPanel panel;

    @Override
    protected void startUp() throws Exception {
        // load item database for membership filtering
        itemDatabaseService.refreshDatabase()
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        log.warn("Failed to load item database on startup: {}", throwable.getMessage());
                    } else {
                        log.info("Item database loaded successfully with {} items", itemDatabaseService.getCacheSize());
                    }
                });

        if (client.getGameState() == GameState.LOGGED_IN) {
            onLoginOrActivated();
        }

        // create UI panel and navigation button
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

    // trigger search for new items to add to existing list
    public void findNewOptimalItem() {
        findingNewItem = true;
        readyForOptimalUpdate = true;
        searchInProgress = false;
    }

    // main search logic - finds optimal alch items based on config criteria
    private void executeOptimalAlchItemSearch(boolean isNewItemSearch) {
        if (searchInProgress) {
            log.debug("Search already in progress, skipping duplicate request");
            return;
        }

        searchInProgress = true;
        log.info("Starting optimal alch item search... (isNewItemSearch: {}, excluded items: {}, membership filter: {})",
                isNewItemSearch, recommendedItemIds.size(), config.membershipFilter());

        // get all item prices and nature rune cost
        List<ItemPrice> itemPrices = this.itemManager.search("");
        int natureRunePrice = this.itemManager.getItemPrice(net.runelite.api.gameval.ItemID.NATURERUNE);

        List<AlchItem> candidates = new ArrayList<>();
        int skippedDuplicates = 0;
        int skippedMembership = 0;

        // filter items based on config criteria
        for (ItemPrice price : itemPrices) {
            int itemId = price.getId();

            // skip items we already recommended if looking for new items
            if (isNewItemSearch && recommendedItemIds.contains(itemId)) {
                skippedDuplicates++;
                continue;
            }

            // apply membership filter
            if (!passesMembershipFilter(itemId)) {
                skippedMembership++;
                continue;
            }

            ItemComposition itemComposition = this.itemManager.getItemComposition(itemId);
            ItemStats itemStats = itemManager.getItemStats(itemId);
            String name = price.getName();

            if (itemStats == null || itemComposition == null || name.length() == 0) {
                continue;
            }

            // calculate profit and apply filters
            int currentPrice = itemManager.getWikiPrice(price);
            int highAlchPrice = itemComposition.getHaPrice();
            int profit = highAlchPrice - currentPrice - natureRunePrice;
            int geLimit = itemStats.getGeLimit();

            int recommendedQuantity = calculateOptimalQuantity(currentPrice, geLimit);
            long totalCost = (long) recommendedQuantity * currentPrice;

            // apply config-based filters
            if (profit < config.minimumProfit() ||
                    geLimit < config.minimumGeLimit() ||
                    (config.maxPrice() > 0 && currentPrice > config.maxPrice()) ||
                    (config.maxTotalPrice() > 0 && totalCost > config.maxTotalPrice())) {
                continue;
            }

            BufferedImage image = itemManager.getImage(itemId, geLimit, false);
            candidates.add(new AlchItem(name, itemId, currentPrice, highAlchPrice, profit, geLimit, image));
        }

        log.info("Found {} candidates after filtering (skipped {} duplicates, {} membership filtered)",
                candidates.size(), skippedDuplicates, skippedMembership);

        if (candidates.isEmpty()) {
            log.warn("No suitable alch items found meeting criteria");
            SwingUtilities.invokeLater(() -> panel.updateItemList());
            readyForOptimalUpdate = false;
            searchInProgress = false;
            return;
        }

        searchWithProgressiveFallback(candidates, isNewItemSearch);
    }

    // check if item passes membership requirement filter
    private boolean passesMembershipFilter(int itemId) {
        AlchCopilotConfig.MembershipFilter filter = config.membershipFilter();

        if (filter == AlchCopilotConfig.MembershipFilter.BOTH) {
            return true;
        }

        Boolean isMembers = itemDatabaseService.getMembershipStatusSync(itemId);

        if (isMembers == null) {
            return true;
        }

        switch (filter) {
            case F2P:
                return !isMembers;
            case P2P:
                return isMembers;
            case BOTH:
            default:
                return true;
        }
    }

    // try multiple search strategies with fallback if no items found
    private void searchWithProgressiveFallback(List<AlchItem> candidates, boolean isNewItemSearch) {
        // gp/hour = profit per alch * alchs per hour (1200 alchs/hour at 3 seconds per alch)
        final double ALCHS_PER_HOUR = 3600.0 / 3.0;

        candidates.sort((item1, item2) -> {
            double gpPerHour1 = item1.getHighAlchProfit() * ALCHS_PER_HOUR;
            double gpPerHour2 = item2.getHighAlchProfit() * ALCHS_PER_HOUR;
            return Double.compare(gpPerHour2, gpPerHour1); // descending order
        });

        // search tiers: [candidates to check, check volume (1=yes, 0=no)]
        int[][] searchTiers = {
                {8, 1},   // top 8 with volume check
                {15, 1},  // top 15 with volume check
                {25, 0},  // top 25 without volume check
                {50, 0}   // top 50 without volume check
        };

        executeSearchTier(candidates, isNewItemSearch, searchTiers, 0);
    }

    // execute search for a specific tier with optional volume checking
    private void executeSearchTier(List<AlchItem> candidates, boolean isNewItemSearch, int[][] searchTiers, int tierIndex) {
        if (tierIndex >= searchTiers.length) {
            log.warn("All search tiers exhausted - no suitable items found");
            readyForOptimalUpdate = false;
            searchInProgress = false;
            SwingUtilities.invokeLater(() -> panel.updateItemList());
            return;
        }

        int candidatesToCheck = Math.min(searchTiers[tierIndex][0], candidates.size());
        boolean checkVolume = searchTiers[tierIndex][1] == 1;

        log.info("Executing search tier {} - checking {} candidates (check volume: {})",
                tierIndex + 1, candidatesToCheck, checkVolume);

        List<AlchItem> tierCandidates = candidates.subList(0, candidatesToCheck);

        // if no volume check needed, just pick the best profit item
        if (!checkVolume) {
            if (!tierCandidates.isEmpty()) {
                AlchItem bestItem = tierCandidates.get(0);
                addRecommendation(bestItem, null);

                log.info("Selected item from tier {} (no volume check): {} (Profit: {} gp/alch)",
                        tierIndex + 1, bestItem.getName(), bestItem.getHighAlchProfit());

                readyForOptimalUpdate = false;
                searchInProgress = false;
                SwingUtilities.invokeLater(() -> panel.updateItemList());
            } else {
                executeSearchTier(candidates, isNewItemSearch, searchTiers, tierIndex + 1);
            }
            return;
        }

        // perform volume checks on all candidates in this tier
        AtomicInteger completed = new AtomicInteger(0);
        List<CompletableFuture<ScoredItem>> futures = new ArrayList<>();

        for (AlchItem candidate : tierCandidates) {
            CompletableFuture<ScoredItem> future = volumeChecker
                    .checkVolume(candidate.getItemId())
                    .thenApply(volumeData -> {
                        int progress = completed.incrementAndGet();
                        log.debug("Volume check {}/{} (tier {}): {} - {} daily volume",
                                progress, tierCandidates.size(), tierIndex + 1,
                                candidate.getName(), volumeData.getEstimatedDailyVolume());

                        // score items based on profit and volume
                        double profitScore = candidate.getHighAlchProfit() / 1000.0;
                        double volumeScore = Math.min(Math.log10(volumeData.getEstimatedDailyVolume() + 1) / 6.0, 1.0);
                        double totalScore = (0.6 * profitScore) + (0.4 * volumeScore);

                        return new ScoredItem(candidate, volumeData, totalScore);
                    })
                    .exceptionally(throwable -> {
                        log.warn("Error checking volume for {}: {}", candidate.getName(), throwable.getMessage());
                        return new ScoredItem(candidate, null, candidate.getHighAlchProfit() / 1000.0);
                    });

            futures.add(future);
        }

        // wait for all volume checks to complete and pick best item
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    ScoredItem bestItem = null;
                    double bestScore = -1;

                    for (CompletableFuture<ScoredItem> future : futures) {
                        try {
                            ScoredItem scoredItem = future.get();

                            // apply volume filter
                            if (!passesVolumeFilter(scoredItem.volumeData)) {
                                log.debug("Item {} filtered out by volume requirement",
                                        scoredItem.item.getName());
                                continue;
                            }

                            if (scoredItem.score > bestScore) {
                                bestItem = scoredItem;
                                bestScore = scoredItem.score;
                            }
                        } catch (Exception e) {
                            log.warn("Error processing volume data: {}", e.getMessage());
                        }
                    }

                    if (bestItem != null) {
                        addRecommendation(bestItem.item, bestItem.volumeData);

                        String volumeInfo = bestItem.volumeData != null ?
                                String.format("Daily volume: %d", bestItem.volumeData.getEstimatedDailyVolume()) :
                                "No volume data";
                        log.info("Selected item from tier {}: {} (Profit: {} gp/alch, {})",
                                tierIndex + 1, bestItem.item.getName(), bestItem.item.getHighAlchProfit(), volumeInfo);

                        readyForOptimalUpdate = false;
                        searchInProgress = false;
                        SwingUtilities.invokeLater(() -> panel.updateItemList());
                    } else {
                        log.info("No items found in tier {} that meet volume requirements - trying next tier", tierIndex + 1);
                        executeSearchTier(candidates, isNewItemSearch, searchTiers, tierIndex + 1);
                    }
                });
    }

    // filter items based on volume requirements after volume checks are complete
    private boolean passesVolumeFilter(VolumeChecker.VolumeData volumeData) {
        if (config.minimumVolumePerHour() <= 0) {
            return true; // filter disabled
        }

        if (volumeData == null) {
            return true; // no volume data available, allow through
        }

        double hourlyVolume = volumeData.getEstimatedDailyVolume() / 24.0;
        return hourlyVolume >= config.minimumVolumePerHour();
    }

    // wrapper class for items with volume data and calculated scores
    private static class ScoredItem {
        final AlchItem item;
        final VolumeChecker.VolumeData volumeData;
        final double score;

        ScoredItem(AlchItem item, VolumeChecker.VolumeData volumeData, double score) {
            this.item = item;
            this.volumeData = volumeData;
            this.score = score;
        }
    }

    // add item to recommendations list, avoiding duplicates
    private void addRecommendation(AlchItem item, VolumeChecker.VolumeData volumeData) {
        if (recommendedItemIds.contains(item.getItemId())) {
            log.debug("Item {} already recommended, skipping duplicate", item.getName());
            return;
        }

        if (volumeData != null) {
            item.setVolumeData(volumeData);
        }

        // add item and re-sort by gp/hour
        recommendations.add(item);
        recommendedItemIds.add(item.getItemId());

        // sort recommendations by gp/hour (descending)
        final double ALCHS_PER_HOUR = 3600.0 / 3.0; // 1200 alchs per hour
        recommendations.sort((item1, item2) -> {
            double gpPerHour1 = item1.getHighAlchProfit() * ALCHS_PER_HOUR;
            double gpPerHour2 = item2.getHighAlchProfit() * ALCHS_PER_HOUR;
            return Double.compare(gpPerHour2, gpPerHour1); // Descending order
        });

        // trim list to max size (remove from end since list is sorted)
        while (recommendations.size() > config.maxRecommendations()) {
            AlchItem removed = recommendations.remove(recommendations.size() - 1);
            recommendedItemIds.remove(removed.getItemId());
        }

        log.info("Added recommendation: {} (Total recommendations: {})", item.getName(), recommendations.size());
    }

    // remove specific item from recommendations
    public void removeRecommendation(AlchItem item) {
        recommendations.remove(item);
        recommendedItemIds.remove(item.getItemId());
    }

    // clear all recommendations
    public void clearRecommendations() {
        recommendations.clear();
        recommendedItemIds.clear();
    }

    // calculate how many items to buy based on GE limit and total cost constraints
    private int calculateOptimalQuantity(int itemPrice, int geLimit) {
        int quantity = geLimit;

        // limit by total investment if configured
        if (config.maxTotalPrice() > 0) {
            int maxByTotalPrice = config.maxTotalPrice() / itemPrice;
            quantity = Math.min(quantity, maxByTotalPrice);
        }

        return Math.max(1, quantity);
    }

    public int calculateRecommendedQuantity(int itemPrice, int geLimit) {
        return calculateOptimalQuantity(itemPrice, geLimit);
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

    // trigger search when conditions are met
    @Subscribe
    public void onGameTick(GameTick event) {
        if (readyForOptimalUpdate && client.getGameState() == GameState.LOGGED_IN && !searchInProgress) {
            executeOptimalAlchItemSearch(findingNewItem);
        }
    }

    public List<AlchItem> getAlchItems() {
        return new ArrayList<>(recommendations);
    }

    public boolean hasRecommendations() {
        return !recommendations.isEmpty();
    }

    // auto-search on login if configured
    private void onLoginOrActivated() {
        if (config.refreshOnLogin()) {
            readyForOptimalUpdate = true;
        }
    }
}