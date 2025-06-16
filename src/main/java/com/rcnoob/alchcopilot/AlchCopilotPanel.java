package com.rcnoob.alchcopilot;

import com.rcnoob.alchcopilot.model.AlchItem;
import com.rcnoob.alchcopilot.service.VolumeChecker;
import com.rcnoob.alchcopilot.util.IntegerUtil;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.util.List;

public class AlchCopilotPanel extends PluginPanel {

    private static final double SECONDS_PER_ALCH = 3.0;
    private static final double ALCHS_PER_HOUR = 3600.0 / SECONDS_PER_ALCH;

    private final Client client;
    private final ItemManager itemManager;
    AlchCopilotPlugin plugin;
    JPanel recommendationsPanel;
    JScrollPane scrollPane;
    JButton refreshButton;
    JButton newItemButton;
    JButton clearButton;
    JLabel statusLabel;

    public AlchCopilotPanel(AlchCopilotPlugin plugin, Client client, ItemManager itemManager) {
        super();
        this.plugin = plugin;
        this.client = client;
        this.itemManager = itemManager;
        this.refreshButton = new JButton("Find Optimal Item");
        this.newItemButton = new JButton("New Item");

        setBorder(new EmptyBorder(6, 6, 6, 6));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setLayout(new BorderLayout());

        final JPanel layoutPanel = new JPanel();
        BoxLayout boxLayout = new BoxLayout(layoutPanel, BoxLayout.Y_AXIS);
        layoutPanel.setLayout(boxLayout);
        add(layoutPanel, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new GridLayout(1, 3, 3, 0));

        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                plugin.refreshRecommendations();
                statusLabel.setText("Refreshing recommendations...");
                refreshButton.setEnabled(false);
                newItemButton.setEnabled(false);
                clearButton.setEnabled(false);
            }
        });
        buttonPanel.add(refreshButton);

        newItemButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                plugin.findNewOptimalItem();
                statusLabel.setText("Searching for additional item...");
                refreshButton.setEnabled(false);
                newItemButton.setEnabled(false);
                clearButton.setEnabled(false);
            }
        });
        newItemButton.setEnabled(false);
        buttonPanel.add(newItemButton);

        clearButton = new JButton("Clear");
        clearButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                plugin.clearRecommendations();
                updateItemList();
            }
        });
        clearButton.setEnabled(false);
        buttonPanel.add(clearButton);

        layoutPanel.add(buttonPanel);

        statusLabel = new JLabel("Ready to search for optimal alch item");
        statusLabel.setForeground(Color.LIGHT_GRAY);
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        layoutPanel.add(statusLabel);

        recommendationsPanel = new JPanel();
        BoxLayout recommendationsBoxLayout = new BoxLayout(recommendationsPanel, BoxLayout.Y_AXIS);
        recommendationsPanel.setLayout(recommendationsBoxLayout);
        recommendationsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        scrollPane = new JScrollPane(recommendationsPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);

        add(scrollPane, BorderLayout.CENTER);

        updateItemList();
    }

    public void updateItemList() {
        refreshButton.setText("Refresh");
        refreshButton.setEnabled(true);
        newItemButton.setEnabled(true);
        clearButton.setEnabled(plugin.hasRecommendations());

        List<AlchItem> recommendations = plugin.getAlchItems();
        recommendationsPanel.removeAll();

        if (recommendations.isEmpty()) {
            if (plugin.readyForOptimalUpdate) {
                statusLabel.setText("Searching for optimal item...");
                JLabel waitingLabel = new JLabel("Please wait while we find the best alch item...");
                waitingLabel.setBorder(new EmptyBorder(20, 10, 20, 10));
                recommendationsPanel.add(waitingLabel);
                newItemButton.setEnabled(false);
                clearButton.setEnabled(false);
            } else {
                statusLabel.setText("No suitable items found");
                JLabel noItemsLabel = new JLabel("No items meet the current criteria.");
                noItemsLabel.setBorder(new EmptyBorder(10, 10, 5, 10));
                recommendationsPanel.add(noItemsLabel);

                JLabel suggestionLabel = new JLabel("Try lowering the minimum profit in config.");
                suggestionLabel.setBorder(new EmptyBorder(5, 10, 20, 10));
                suggestionLabel.setForeground(Color.LIGHT_GRAY);
                recommendationsPanel.add(suggestionLabel);
                newItemButton.setEnabled(false);
                clearButton.setEnabled(false);
            }
        } else {
            statusLabel.setText("Found " + recommendations.size() + " recommendation" + (recommendations.size() == 1 ? "" : "s"));

            for (int i = 0; i < recommendations.size(); i++) {
                AlchItem item = recommendations.get(i);
                JPanel itemPanel = generateOptimalItemPanel(item, i + 1);
                recommendationsPanel.add(itemPanel);

                if (i < recommendations.size() - 1) {
                    recommendationsPanel.add(Box.createVerticalStrut(10));
                }
            }
        }

        recommendationsPanel.revalidate();
        recommendationsPanel.repaint();

        SwingUtilities.invokeLater(() -> {
            scrollPane.getVerticalScrollBar().setValue(0);
        });
    }

    private JPanel generateOptimalItemPanel(AlchItem item, int rank) {
        JPanel container = new JPanel();

        Color borderColor;
        if (rank == 1) {
            borderColor = Color.GREEN;
        } else if (rank <= 3) {
            borderColor = Color.ORANGE;
        } else {
            borderColor = Color.GRAY;
        }

        container.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 2),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JLabel rankLabel = new JLabel("#" + rank);
        rankLabel.setForeground(borderColor);
        rankLabel.setFont(FontManager.getRunescapeBoldFont());
        rankLabel.setBorder(new EmptyBorder(0, 0, 0, 10));
        headerPanel.add(rankLabel, BorderLayout.WEST);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setOpaque(false);

        BufferedImage itemImage = item.getImage();
        JLabel iconLabel = new JLabel(new ImageIcon(itemImage));
        iconLabel.setToolTipText(item.getName() + " - GE Limit: " + formatNumber(item.getGeLimit()) + " every 4 hours");
        iconLabel.setBorder(new EmptyBorder(0, 0, 0, 10));
        centerPanel.add(iconLabel, BorderLayout.WEST);

        JLabel nameLabel = new JLabel(item.getName());
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(FontManager.getRunescapeBoldFont());
        centerPanel.add(nameLabel, BorderLayout.CENTER);

        headerPanel.add(centerPanel, BorderLayout.CENTER);

        container.add(headerPanel);
        container.add(Box.createVerticalStrut(10));

        JPanel profitPanel = createInfoRow("Profit per Alch:",
                formatNumber(item.getHighAlchProfit()) + " gp",
                Color.GREEN);
        container.add(profitPanel);

        long profitPerHour = Math.round(item.getHighAlchProfit() * ALCHS_PER_HOUR);
        Color profitHourColor = getProfitPerHourColor(profitPerHour);
        JPanel profitHourPanel = createInfoRow("Profit per Hour:",
                formatNumber((int)profitPerHour) + " gp/hr",
                profitHourColor);
        container.add(profitHourPanel);

        JPanel pricePanel = createInfoRow("Current GE Price:",
                formatNumber(item.getGePrice()) + " gp",
                Color.ORANGE);
        container.add(pricePanel);

        if (plugin.config.showDetailedInfo()) {
            JPanel alchPanel = createInfoRow("High Alch Value:",
                    formatNumber(item.getHighAlchPrice()) + " gp",
                    Color.YELLOW);
            container.add(alchPanel);

            JPanel limitPanel = createInfoRow("GE Limit:",
                    formatNumber(item.getGeLimit()) + "/4h",
                    Color.LIGHT_GRAY);
            container.add(limitPanel);
        }

        if (plugin.config.showVolumeInfo() && item.getVolumeData() != null) {
            VolumeChecker.VolumeData volumeData = item.getVolumeData();

            JPanel dailyVolumePanel = createInfoRow("Daily Volume:",
                    formatNumber((int) volumeData.getEstimatedDailyVolume()) + " units",
                    getVolumeColor((int) volumeData.getEstimatedDailyVolume()));
            container.add(dailyVolumePanel);

            int hourlyVolume = Math.max(1, (int) volumeData.getEstimatedDailyVolume() / 24);
            JPanel hourlyVolumePanel = createInfoRow("Hourly Volume:",
                    formatNumber(hourlyVolume) + " units/hr",
                    getVolumeColor(hourlyVolume * 24));
            container.add(hourlyVolumePanel);
        }

        container.add(Box.createVerticalStrut(10));

        int recommendedQuantity = plugin.calculateRecommendedQuantity(item.getGePrice(), item.getGeLimit());
        long totalCost = (long) recommendedQuantity * item.getGePrice();

        JPanel recommendationPanel = new JPanel(new BorderLayout());
        recommendationPanel.setOpaque(false);
        recommendationPanel.setBorder(new EmptyBorder(5, 0, 0, 0));

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setOpaque(false);

        JLabel recommendationTitle = new JLabel("Recommendation:");
        recommendationTitle.setForeground(Color.WHITE);
        recommendationTitle.setFont(FontManager.getRunescapeBoldFont());
        recommendationTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        String quantityText = formatNumber(recommendedQuantity);
        String costText = formatNumber((int) Math.min(totalCost, Integer.MAX_VALUE));

        JLabel recommendationDetails = new JLabel(String.format("<html>Buy %s units<br>for %s gp total</html>",
                quantityText, costText));
        recommendationDetails.setForeground(Color.LIGHT_GRAY);
        recommendationDetails.setFont(FontManager.getRunescapeSmallFont());
        recommendationDetails.setAlignmentX(Component.LEFT_ALIGNMENT);

        contentPanel.add(recommendationTitle);
        contentPanel.add(recommendationDetails);

        recommendationPanel.add(contentPanel, BorderLayout.WEST);
        container.add(recommendationPanel);

        if (rank > 1) {
            container.add(Box.createVerticalStrut(8));

            JButton removeButton = new JButton("Remove");
            removeButton.setFont(FontManager.getRunescapeSmallFont());
            removeButton.setPreferredSize(new Dimension(80, 25));
            removeButton.addActionListener(e -> {
                plugin.removeRecommendation(item);
                updateItemList();
            });

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            buttonPanel.setOpaque(false);
            buttonPanel.add(removeButton);
            container.add(buttonPanel);
        }

        return container;
    }

    private Color getProfitPerHourColor(long profitPerHour) {
        if (profitPerHour >= 500000) {
            return new Color(0, 255, 0);
        } else if (profitPerHour >= 300000) {
            return new Color(144, 238, 144);
        } else if (profitPerHour >= 150000) {
            return Color.GREEN;
        } else if (profitPerHour >= 75000) {
            return Color.YELLOW;
        } else if (profitPerHour >= 25000) {
            return Color.ORANGE;
        } else {
            return Color.RED;
        }
    }

    private Color getVolumeColor(int volume) {
        if (volume >= 10000) {
            return new Color(0, 255, 0);
        } else if (volume >= 5000) {
            return new Color(144, 238, 144);
        } else if (volume >= 2000) {
            return Color.GREEN;
        } else if (volume >= 1000) {
            return Color.YELLOW;
        } else if (volume >= 500) {
            return Color.ORANGE;
        } else {
            return Color.RED;
        }
    }

    private JPanel createInfoRow(String label, String value, Color valueColor) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);

        JLabel labelComponent = new JLabel(label);
        labelComponent.setForeground(Color.LIGHT_GRAY);
        labelComponent.setFont(FontManager.getRunescapeSmallFont());

        JLabel valueComponent = new JLabel(value);
        valueComponent.setForeground(valueColor);
        valueComponent.setFont(FontManager.getRunescapeSmallFont());
        valueComponent.setHorizontalAlignment(SwingConstants.LEFT);

        panel.add(labelComponent, BorderLayout.WEST);
        panel.add(valueComponent, BorderLayout.CENTER);

        return panel;
    }

    private String formatNumber(int number) {
        try {
            String shorthand = IntegerUtil.toShorthand(number);
            if (shorthand != null && !shorthand.isEmpty()) {
                return shorthand;
            }
        } catch (Exception ignored) {

        }

        return String.format("%,d", number);
    }
}