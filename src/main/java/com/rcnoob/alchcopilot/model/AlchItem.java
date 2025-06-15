package com.rcnoob.alchcopilot.model;

import com.rcnoob.alchcopilot.service.VolumeChecker;
import lombok.Getter;
import lombok.Setter;

import java.awt.image.BufferedImage;

@Getter
@Setter
public class AlchItem {
    private final String name;
    private final int itemId;
    private final int gePrice;
    private final int highAlchPrice;
    private final int highAlchProfit;
    private final int geLimit;
    private final BufferedImage image;
    private VolumeChecker.VolumeData volumeData;

    public AlchItem(String name, int itemId, int gePrice, int highAlchPrice, int highAlchProfit, int geLimit, BufferedImage image) {
        this.name = name;
        this.itemId = itemId;
        this.gePrice = gePrice;
        this.highAlchPrice = highAlchPrice;
        this.highAlchProfit = highAlchProfit;
        this.geLimit = geLimit;
        this.image = image;
        this.volumeData = null;
    }
}