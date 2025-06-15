package com.rcnoob.alchcopilot.model;

import java.awt.image.BufferedImage;

public class AlchItem {
    private final String name;
    private final int itemId;
    private final int gePrice;
    private final int highAlchPrice;
    private final int highAlchProfit;
    private final int geLimit;
    private final BufferedImage image;

    public AlchItem(String name, int itemId, int gePrice, int highAlchPrice, int highAlchProfit, int geLimit, BufferedImage image) {
        this.name = name;
        this.itemId = itemId;
        this.gePrice = gePrice;
        this.highAlchPrice = highAlchPrice;
        this.highAlchProfit = highAlchProfit;
        this.geLimit = geLimit;
        this.image = image;
    }

    // Legacy constructor for backward compatibility
    public AlchItem(String name, int gePrice, int highAlchPrice, int highAlchProfit, int geLimit, BufferedImage image) {
        this(name, -1, gePrice, highAlchPrice, highAlchProfit, geLimit, image);
    }

    public String getName() {
        return name;
    }

    public int getItemId() {
        return itemId;
    }

    public int getGePrice() {
        return gePrice;
    }

    public int getHighAlchPrice() {
        return highAlchPrice;
    }

    public int getHighAlchProfit() {
        return highAlchProfit;
    }

    public int getGeLimit() {
        return geLimit;
    }

    public BufferedImage getImage() {
        return image;
    }
}