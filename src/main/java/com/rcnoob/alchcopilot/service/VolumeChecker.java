package com.rcnoob.alchcopilot.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class VolumeChecker {
    // OSRS wiki API for 5-minute trading data
    private static final String OSRS_EXCHANGE_API = "https://prices.runescape.wiki/api/v1/osrs/5m";

    private final OkHttpClient httpClient;
    private final Gson gson;

    public VolumeChecker() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    // wrapper for volume data with calculations
    public static class VolumeData {
        public final int itemId;
        public final long buyVolume;
        public final long sellVolume;
        public final boolean hasRecentActivity;

        public VolumeData(int itemId, long buyVolume, long sellVolume) {
            this.itemId = itemId;
            this.buyVolume = buyVolume;
            this.sellVolume = sellVolume;
            this.hasRecentActivity = (buyVolume + sellVolume) > 0;
        }

        public long getTotalVolume() {
            return buyVolume + sellVolume;
        }

        // extrapolate 5-minute data to daily estimate (288 five-minute periods per day)
        public long getEstimatedDailyVolume() {
            return getTotalVolume() * 288;
        }
    }

    // fetch trading volume data for specific item
    public CompletableFuture<VolumeData> checkVolume(int itemId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Request request = new Request.Builder()
                        .url(OSRS_EXCHANGE_API)
                        .header("User-Agent", "AlchCopilot-RuneLite-Plugin")
                        .build();

                // make API call and parse response
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.warn("Failed to fetch volume data: {}", response.code());
                        return new VolumeData(itemId, 0, 0);
                    }

                    String responseBody = response.body().string();
                    JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                    JsonObject data = jsonResponse.getAsJsonObject("data");

                    // check if item exists in response
                    if (!data.has(String.valueOf(itemId))) {
                        log.debug("No volume data available for item {}", itemId);
                        return new VolumeData(itemId, 0, 0);
                    }

                    // extract buy and sell volumes
                    JsonObject itemData = data.getAsJsonObject(String.valueOf(itemId));
                    long buyVolume = itemData.has("highPriceVolume") ?
                            itemData.get("highPriceVolume").getAsLong() : 0;
                    long sellVolume = itemData.has("lowPriceVolume") ?
                            itemData.get("lowPriceVolume").getAsLong() : 0;

                    return new VolumeData(itemId, buyVolume, sellVolume);
                }
            } catch (IOException e) {
                log.error("Error fetching volume data for item {}: {}", itemId, e.getMessage());
                return new VolumeData(itemId, 0, 0);
            }
        });
    }

    // cleanup HTTP client resources
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}