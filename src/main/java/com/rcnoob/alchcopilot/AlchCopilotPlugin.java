package com.rcnoob.alchcopilot;

import com.google.inject.Provides;
import com.rcnoob.alchcopilot.model.AlchItem;
import com.rcnoob.alchcopilot.service.VolumeChecker;
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

    private List<AlchItem> recommendations = new ArrayList<>();
    private Set<Integer> recommendedItemIds = new HashSet<>();
    private VolumeChecker volumeChecker;
    private NavigationButton navButton;
    private AlchCopilotPanel panel;

    @Override
    protected void startUp() throws Exception {
        volumeChecker = new VolumeChecker();

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

    public void refreshRecommendations() {
        clearRecommendations();
        findingNewItem = false;
        readyForOptimalUpdate = true;
        searchInProgress = false;
    }

    public void findNewOptimalItem() {
        findingNewItem = true;
        readyForOptimalUpdate = true;
        searchInProgress = false;
    }

    private void executeOptimalAlchItemSearch(boolean isNewItemSearch) {
        if (searchInProgress) {
            log.debug("Search already in progress, skipping duplicate request");
            return;
        }

        searchInProgress = true;
        log.info("Starting optimal alch item search... (isNewItemSearch: {}, excluded items: {})",
                isNewItemSearch, recommendedItemIds.size());

        List<ItemPrice> itemPrices = this.itemManager.search("");
        int natureRunePrice = this.itemManager.getItemPrice(net.runelite.api.ItemID.NATURE_RUNE);

        List<AlchItem> candidates = new ArrayList<>();
        int skippedDuplicates = 0;

        for (ItemPrice price : itemPrices) {
            int itemId = price.getId();

            if (isNewItemSearch && recommendedItemIds.contains(itemId)) {
                skippedDuplicates++;
                continue;
            }

            ItemComposition itemComposition = this.itemManager.getItemComposition(itemId);
            ItemStats itemStats = itemManager.getItemStats(itemId);
            String name = price.getName();

            if (itemStats == null || itemComposition == null || name.length() == 0) {
                continue;
            }

            int currentPrice = itemManager.getWikiPrice(price);
            int highAlchPrice = itemComposition.getHaPrice();
            int profit = highAlchPrice - currentPrice - natureRunePrice;
            int geLimit = itemStats.getGeLimit();

            int recommendedQuantity = calculateOptimalQuantity(currentPrice, geLimit);
            long totalCost = (long) recommendedQuantity * currentPrice;

            if (profit < config.minimumProfit() ||
                    geLimit < config.minimumGeLimit() ||
                    (config.maxPrice() > 0 && currentPrice > config.maxPrice()) ||
                    (config.maxTotalPrice() > 0 && totalCost > config.maxTotalPrice()) ||
                    isLowVolumeItem(name, geLimit)) {
                continue;
            }

            BufferedImage image = itemManager.getImage(itemId, geLimit, false);
            candidates.add(new AlchItem(name, itemId, currentPrice, highAlchPrice, profit, geLimit, image));
        }

        log.info("Found {} candidates after filtering (skipped {} duplicates)", candidates.size(), skippedDuplicates);

        if (candidates.isEmpty()) {
            log.warn("No suitable alch items found meeting criteria");
            SwingUtilities.invokeLater(() -> panel.updateItemList());
            readyForOptimalUpdate = false;
            searchInProgress = false;
            return;
        }

        searchWithProgressiveFallback(candidates, isNewItemSearch);
    }

    private void searchWithProgressiveFallback(List<AlchItem> candidates, boolean isNewItemSearch) {
        candidates.sort(Comparator.comparing(AlchItem::getHighAlchProfit).reversed());

        int[][] searchTiers = {
                {8, 1},
                {15, 1},
                {25, 0},
                {50, 0}
        };

        executeSearchTier(candidates, isNewItemSearch, searchTiers, 0);
    }

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

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    ScoredItem bestItem = null;
                    double bestScore = -1;

                    for (CompletableFuture<ScoredItem> future : futures) {
                        try {
                            ScoredItem scoredItem = future.get();

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
                        log.info("No items found in tier {} - trying next tier", tierIndex + 1);
                        executeSearchTier(candidates, isNewItemSearch, searchTiers, tierIndex + 1);
                    }
                });
    }

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

    private void addRecommendation(AlchItem item, VolumeChecker.VolumeData volumeData) {
        if (recommendedItemIds.contains(item.getItemId())) {
            log.debug("Item {} already recommended, skipping duplicate", item.getName());
            return;
        }

        if (volumeData != null) {
            item.setVolumeData(volumeData);
        }

        recommendations.add(0, item);
        recommendedItemIds.add(item.getItemId());

        while (recommendations.size() > config.maxRecommendations()) {
            AlchItem removed = recommendations.remove(recommendations.size() - 1);
            recommendedItemIds.remove(removed.getItemId());
        }

        log.info("Added recommendation: {} (Total recommendations: {})", item.getName(), recommendations.size());
    }

    public void removeRecommendation(AlchItem item) {
        recommendations.remove(item);
        recommendedItemIds.remove(item.getItemId());
    }

    public void clearRecommendations() {
        recommendations.clear();
        recommendedItemIds.clear();
    }

    private boolean isLowVolumeItem(String name, int geLimit) {
        String lowerName = name.toLowerCase();

        if (lowerName.contains("ornament") || lowerName.contains("(or)") ||
                lowerName.contains("3rd age") || lowerName.contains("gilded") ||
                lowerName.contains("rare") || lowerName.contains("holiday") ||
                lowerName.contains("golden") || lowerName.contains("blessed") ||
                lowerName.contains("(t)") || lowerName.contains("(g)") ||
                (lowerName.contains("crystal") && !lowerName.contains("seed")) ||
                lowerName.contains("trimmed") || lowerName.contains("decorative") ||
                lowerName.contains("void") || lowerName.contains("elite void")) {
            return true;
        }

        if (geLimit < 150) {
            return true;
        }

        if (lowerName.contains("rune") || lowerName.contains("dragon") ||
                lowerName.contains("barrows") || lowerName.contains("whip")) {
            return false;
        }

        return false;
    }

    private int calculateOptimalQuantity(int itemPrice, int geLimit) {
        int quantity = geLimit;

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
        if (volumeChecker != null) {
            volumeChecker.shutdown();
        }
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

    private void onLoginOrActivated() {
        if (config.refreshOnLogin()) {
            readyForOptimalUpdate = true;
        }
    }
}