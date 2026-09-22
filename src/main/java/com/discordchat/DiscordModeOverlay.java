package com.discordchat;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Shown above the chatbox while Discord mode is on, so it's obvious your typing won't go to game chat.
 */
class DiscordModeOverlay extends OverlayPanel
{
	private static final Color BLURPLE = new Color(0x58, 0x65, 0xf2);

	private final DiscordChatPlugin plugin;

	@Inject
	DiscordModeOverlay(DiscordChatPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.isDiscordMode())
		{
			return null;
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Discord")
			.leftColor(BLURPLE)
			.right(plugin.getActiveChannelLabel())
			.build());
		return super.render(graphics);
	}
}
