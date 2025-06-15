package com.rcnoob.alchcopilot.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MarketDataFetcher {
    private static final String OSRS_EXCHANGE_API = "https://prices.runescape.wiki/api/v1/osrs/";
    private static final String LATEST_PRICES_ENDPOINT = "latest";
    private static final String VOLUME_ENDPOINT = "5m"; // 5-minute volume data

    private final OkHttpClient httpClient;
    private final Gson gson;

    public MarketDataFetcher() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    public static class MarketData {
        public final int itemId;
        public final int buyPrice;
        public final int sellPrice;
        public final long buyVolume;
        public final long sellVolume;
        public final Instant timestamp;

        public MarketData(int itemId, int buyPrice, int sellPrice, long buyVolume, long sellVolume, Instant timestamp) {
            this.itemId = itemId;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
            this.buyVolume = buyVolume;
            this.sellVolume = sellVolume;
            this.timestamp = timestamp;
        }

        public long getDailyVolume() {
            // Estimate daily volume from 5-minute data
            // This is approximate - 5min data * 288 periods = 24 hours
            return (buyVolume + sellVolume) * 288;
        }

        public int getEffectivePrice() {
            // Use buy price (what we'd pay) if available, otherwise sell price
            return buyPrice > 0 ? buyPrice : sellPrice;
        }
    }

    public CompletableFuture<MarketData> fetchMarketData(int itemId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Fetch latest prices
                String latestUrl = OSRS_EXCHANGE_API + LATEST_PRICES_ENDPOINT;
                Request request = new Request.Builder()
                        .url(latestUrl)
                        .header("User-Agent", "AlchHelper RuneLite Plugin")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.warn("Failed to fetch latest prices: {}", response.code());
                        return null;
                    }

                    String responseBody = response.body().string();
                    JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                    JsonObject data = jsonResponse.getAsJsonObject("data");

                    if (!data.has(String.valueOf(itemId))) {
                        log.debug("No price data available for item {}", itemId);
                        return null;
                    }

                    JsonObject itemData = data.getAsJsonObject(String.valueOf(itemId));
                    int buyPrice = itemData.has("high") ? itemData.get("high").getAsInt() : 0;
                    int sellPrice = itemData.has("low") ? itemData.get("low").getAsInt() : 0;
                    long timestamp = itemData.has("highTime") ?
                            itemData.get("highTime").getAsLong() :
                            System.currentTimeMillis() / 1000;

                    // Fetch volume data
                    String volumeUrl = OSRS_EXCHANGE_API + VOLUME_ENDPOINT;
                    Request volumeRequest = new Request.Builder()
                            .url(volumeUrl)
                            .header("User-Agent", "AlchHelper RuneLite Plugin")
                            .build();

                    long buyVolume = 0;
                    long sellVolume = 0;

                    try (Response volumeResponse = httpClient.newCall(volumeRequest).execute()) {
                        if (volumeResponse.isSuccessful()) {
                            String volumeResponseBody = volumeResponse.body().string();
                            JsonObject volumeJson = gson.fromJson(volumeResponseBody, JsonObject.class);
                            JsonObject volumeData = volumeJson.getAsJsonObject("data");

                            if (volumeData.has(String.valueOf(itemId))) {
                                JsonObject itemVolumeData = volumeData.getAsJsonObject(String.valueOf(itemId));
                                buyVolume = itemVolumeData.has("highPriceVolume") ?
                                        itemVolumeData.get("highPriceVolume").getAsLong() : 0;
                                sellVolume = itemVolumeData.has("lowPriceVolume") ?
                                        itemVolumeData.get("lowPriceVolume").getAsLong() : 0;
                            }
                        }
                    }

                    return new MarketData(itemId, buyPrice, sellPrice, buyVolume, sellVolume,
                            Instant.ofEpochSecond(timestamp));
                }
            } catch (IOException e) {
                log.error("Error fetching market data for item {}: {}", itemId, e.getMessage());
                return null;
            }
        });
    }

    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}