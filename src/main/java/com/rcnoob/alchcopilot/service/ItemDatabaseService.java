package com.rcnoob.alchcopilot.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Singleton
public class ItemDatabaseService {

    private static final String ITEM_DB_URL = "https://raw.githubusercontent.com/0xNeffarion/osrsreboxed-db/master/docs/items-complete.json";
    private static final Duration CACHE_DURATION = Duration.ofHours(24); // Cache for 24 hours

    private final OkHttpClient httpClient;
    private final Gson gson;

    // cache mapping item id to whether it's members-only
    private final Map<Integer, Boolean> membershipCache = new ConcurrentHashMap<>();
    private volatile long lastFetchTime = 0;
    private volatile boolean fetchInProgress = false;
    private CompletableFuture<Void> currentFetch = null;

    @Inject
    public ItemDatabaseService(OkHttpClient httpClient, Gson gson) {
        this.httpClient = httpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = gson;
    }

    // check if item is members-only, fetch data if needed
    public CompletableFuture<Boolean> isItemMembersOnly(int itemId) {
        if (membershipCache.containsKey(itemId)) {
            return CompletableFuture.completedFuture(membershipCache.get(itemId));
        }

        if (isCacheRecent()) {
            return CompletableFuture.completedFuture(null);
        }

        return ensureDatabaseLoaded().thenApply(v -> membershipCache.get(itemId));
    }

    // get membership status without triggering fetch
    public Boolean getMembershipStatusSync(int itemId) {
        return membershipCache.get(itemId);
    }

    // force refresh of the entire database
    public CompletableFuture<Void> refreshDatabase() {
        log.info("Forcing refresh of item database");
        lastFetchTime = 0; // Invalidate cache
        return ensureDatabaseLoaded();
    }

    public int getCacheSize() {
        return membershipCache.size();
    }

    // check if cached data is still fresh
    public boolean isCacheRecent() {
        return System.currentTimeMillis() - lastFetchTime < CACHE_DURATION.toMillis();
    }

    // ensure database is loaded, avoid duplicate fetches
    private CompletableFuture<Void> ensureDatabaseLoaded() {
        if (isCacheRecent()) {
            return CompletableFuture.completedFuture(null);
        }

        synchronized (this) {
            if (fetchInProgress && currentFetch != null) {
                return currentFetch;
            }

            fetchInProgress = true;
            currentFetch = fetchItemDatabase();
            return currentFetch;
        }
    }

    // download and parse item database from github
    private CompletableFuture<Void> fetchItemDatabase() {
        log.info("Fetching item database from: {}", ITEM_DB_URL);

        Request request = new Request.Builder()
                .url(ITEM_DB_URL)
                .header("User-Agent", "AlchCopilot-RuneLite-Plugin")
                .build();

        return CompletableFuture.supplyAsync(() -> {
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            throw new RuntimeException("Failed to fetch item database: HTTP " + response.code());
                        }
                        return response.body().string();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to fetch item database", e);
                    }
                }).thenAccept(this::parseAndCacheItems)
                .whenComplete((result, throwable) -> {
                    synchronized (this) {
                        fetchInProgress = false;
                        if (throwable == null) {
                            lastFetchTime = System.currentTimeMillis();
                            log.info("Successfully loaded {} items into membership cache", membershipCache.size());
                        } else {
                            log.error("Failed to fetch item database", throwable);
                        }
                    }
                });
    }

    // parse JSON and extract membership info for each item
    private void parseAndCacheItems(String jsonData) {
        try {
            log.debug("Parsing item database JSON...");
            JsonObject rootObject = gson.fromJson(jsonData, JsonObject.class);

            int parsed = 0;
            int membersItems = 0;
            int f2pItems = 0;

            // iterate through all items in the database
            for (Map.Entry<String, JsonElement> entry : rootObject.entrySet()) {
                try {
                    int itemId = Integer.parseInt(entry.getKey());
                    JsonObject itemData = entry.getValue().getAsJsonObject();

                    // extract membership requirement
                    if (itemData.has("members")) {
                        boolean isMembers = itemData.get("members").getAsBoolean();
                        membershipCache.put(itemId, isMembers);

                        if (isMembers) {
                            membersItems++;
                        } else {
                            f2pItems++;
                        }
                        parsed++;
                    }
                } catch (NumberFormatException e) {
                    // Skip invalid item IDs
                    log.debug("Skipping invalid item ID: {}", entry.getKey());
                } catch (Exception e) {
                    log.debug("Error parsing item {}: {}", entry.getKey(), e.getMessage());
                }
            }

            log.info("Parsed {} items: {} F2P, {} Members", parsed, f2pItems, membersItems);

        } catch (Exception e) {
            log.error("Error parsing item database JSON", e);
            throw new RuntimeException("Failed to parse item database", e);
        }
    }

    // cleanup resources on shutdown
    public void shutdown() {
        try {
            if (currentFetch != null && !currentFetch.isDone()) {
                currentFetch.cancel(true);
            }
        } catch (Exception e) {
            log.debug("Error during shutdown", e);
        }
        membershipCache.clear();
    }
}