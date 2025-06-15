package com.rcnoob.alchcopilot;

import com.rcnoob.alchcopilot.model.AlchItem;
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

public class AlchCopilotPanel extends PluginPanel {

    private static final String[] PRICE_SOURCE_OPTIONS = {"Wiki Prices", "Standard Prices"};
    private static final int WIKI_PRICES_INDEX = 0;
    private final Client client;
    private final ItemManager itemManager;
    AlchCopilotPlugin plugin;
    JPanel contentPanel;
    JButton refreshButton;
    JLabel statusLabel;

    public AlchCopilotPanel(AlchCopilotPlugin plugin, Client client, ItemManager itemManager) {
        super();
        this.plugin = plugin;
        this.client = client;
        this.itemManager = itemManager;
        this.refreshButton = new JButton("Find Optimal Item");

        setBorder(new EmptyBorder(6, 6, 6, 6));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setLayout(new BorderLayout());

        final JPanel layoutPanel = new JPanel();
        BoxLayout boxLayout = new BoxLayout(layoutPanel, BoxLayout.Y_AXIS);
        layoutPanel.setLayout(boxLayout);
        add(layoutPanel, BorderLayout.NORTH);

        // Price source selection
        JComboBox<String> priceSourceBox = new JComboBox<>(PRICE_SOURCE_OPTIONS);
        priceSourceBox.setSelectedIndex(0);
        priceSourceBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                plugin.useWikiPrices = priceSourceBox.getSelectedIndex() == WIKI_PRICES_INDEX;
                plugin.readyForOptimalUpdate = true;
            }
        });
        layoutPanel.add(createLabeledRow("Price Source:", priceSourceBox));

        // Refresh button
        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                plugin.readyForOptimalUpdate = true;
                statusLabel.setText("Searching for optimal item...");
                refreshButton.setEnabled(false);
            }
        });
        layoutPanel.add(refreshButton);

        // Status label
        statusLabel = new JLabel("Ready to search for optimal alch item");
        statusLabel.setForeground(Color.LIGHT_GRAY);
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        layoutPanel.add(statusLabel);

        // Content panel for the optimal item display
        contentPanel = new JPanel();
        BoxLayout contentBoxLayout = new BoxLayout(contentPanel, BoxLayout.Y_AXIS);
        contentPanel.setLayout(contentBoxLayout);
        layoutPanel.add(contentPanel);

        updateItemList();
        add(contentPanel);
    }

    private static JPanel createLabeledRow(String label, Component item) {
        JPanel rowPanel = new JPanel();
        rowPanel.setLayout(new GridLayout(1, 2));
        rowPanel.add(new JLabel(label));
        rowPanel.add(item);
        return rowPanel;
    }

    /**
     * Updates the display with the current optimal item.
     */
    public void updateItemList() {
        refreshButton.setText("Refresh");
        refreshButton.setEnabled(true);

        AlchItem optimalItem = plugin.getOptimalItem();
        contentPanel.removeAll();

        if (optimalItem == null) {
            if (plugin.readyForOptimalUpdate) {
                statusLabel.setText("Searching for optimal item...");
                contentPanel.add(new JLabel("Please wait while we find the best alch item..."));
            } else {
                statusLabel.setText("No suitable items found");
                contentPanel.add(new JLabel("No items meet the current criteria."));
                contentPanel.add(Box.createVerticalStrut(10));
                contentPanel.add(new JLabel("Try lowering the minimum profit in config."));
            }
        } else {
            statusLabel.setText("Optimal item found!");
            JPanel optimalItemPanel = generateOptimalItemPanel(optimalItem);
            contentPanel.add(optimalItemPanel);
        }

        contentPanel.revalidate();
        contentPanel.repaint();
    }

    private JPanel generateOptimalItemPanel(AlchItem item) {
        // Create the main container
        JPanel container = new JPanel();
        container.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GREEN, 2),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Header with item image and name
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        BufferedImage itemImage = item.getImage();
        JLabel iconLabel = new JLabel(new ImageIcon(itemImage));
        iconLabel.setToolTipText(item.getName() + " - GE Limit: " + IntegerUtil.toShorthand(item.getGeLimit()) + " every 4 hours");
        headerPanel.add(iconLabel, BorderLayout.WEST);

        JLabel nameLabel = new JLabel(item.getName());
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(FontManager.getRunescapeBoldFont());
        headerPanel.add(nameLabel, BorderLayout.CENTER);

        container.add(headerPanel);
        container.add(Box.createVerticalStrut(10));

        // Profit information
        JPanel profitPanel = createInfoRow("Profit per Alch:",
                IntegerUtil.toShorthand(item.getHighAlchProfit()) + " gp",
                Color.GREEN);
        container.add(profitPanel);

        // Price information
        JPanel pricePanel = createInfoRow("Current GE Price:",
                IntegerUtil.toShorthand(item.getGePrice()) + " gp",
                Color.ORANGE);
        container.add(pricePanel);

        // Show detailed info if enabled
        if (plugin.config.showDetailedInfo()) {
            // Alch price
            JPanel alchPanel = createInfoRow("High Alch Value:",
                    IntegerUtil.toShorthand(item.getHighAlchPrice()) + " gp",
                    Color.YELLOW);
            container.add(alchPanel);

            // GE Limit
            JPanel limitPanel = createInfoRow("GE Limit:",
                    IntegerUtil.toShorthand(item.getGeLimit()) + "/4h",
                    Color.LIGHT_GRAY);
            container.add(limitPanel);
        }

        container.add(Box.createVerticalStrut(10));

        // Purchase recommendation
        int recommendedQuantity = Math.min(item.getGeLimit(), plugin.config.recommendedQuantity());

        JLabel recommendationLabel = new JLabel(
                "<html><b>Recommendation:</b> Buy " +
                        IntegerUtil.toShorthand(recommendedQuantity) +
                        " units for " +
                        IntegerUtil.toShorthand(recommendedQuantity * item.getGePrice()) +
                        " gp total</html>"
        );
        recommendationLabel.setForeground(Color.WHITE);
        recommendationLabel.setFont(FontManager.getRunescapeFont());
        container.add(recommendationLabel);

        return container;
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

        panel.add(labelComponent, BorderLayout.WEST);
        panel.add(valueComponent, BorderLayout.EAST);

        return panel;
    }
}