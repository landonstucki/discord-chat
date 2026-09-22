package com.discordchat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

class DiscordChatPanel extends PluginPanel
{
	private static final int MAX_MEMBERS_SHOWN = 200;
	private static final Color CONNECTED = new Color(0x3b, 0xa5, 0x5d);
	private static final Color DISCONNECTED = new Color(0xed, 0x42, 0x45);

	private final DiscordChatPlugin plugin;
	private final JLabel statusLabel = new JLabel();
	private final JCheckBox modeToggle = new JCheckBox("Talk in Discord instead of game");
	private final JComboBox<DiscordGateway.Channel> channelBox = new JComboBox<>();
	private final JPanel serverPanel = new JPanel();
	private final JPanel onlinePanel = new JPanel();

	private List<DiscordGateway.Channel> shownChannels = Collections.emptyList();
	private boolean updating;

	DiscordChatPanel(DiscordChatPlugin plugin)
	{
		this.plugin = plugin;

		setLayout(new BorderLayout(0, 10));
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel controls = new JPanel(new GridLayout(0, 1, 0, 5));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Discord Chat");
		title.setFont(FontManager.getRunescapeBoldFont());
		controls.add(title);
		controls.add(statusLabel);

		JLabel channelLabel = new JLabel("Channel");
		channelLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		controls.add(channelLabel);
		controls.add(channelBox);
		controls.add(modeToggle);

		channelBox.addActionListener(e ->
		{
			DiscordGateway.Channel selected = (DiscordGateway.Channel) channelBox.getSelectedItem();
			if (!updating && selected != null)
			{
				plugin.setActiveChannel(selected.getId());
			}
		});
		modeToggle.setBackground(ColorScheme.DARK_GRAY_COLOR);
		modeToggle.addActionListener(e ->
		{
			if (!updating)
			{
				plugin.setDiscordMode(modeToggle.isSelected());
			}
		});

		serverPanel.setLayout(new BoxLayout(serverPanel, BoxLayout.Y_AXIS));
		serverPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		onlinePanel.setLayout(new BoxLayout(onlinePanel, BoxLayout.Y_AXIS));
		onlinePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel lists = new JPanel(new BorderLayout(0, 10));
		lists.setBackground(ColorScheme.DARK_GRAY_COLOR);
		lists.add(serverPanel, BorderLayout.NORTH);
		lists.add(onlinePanel, BorderLayout.CENTER);

		add(controls, BorderLayout.NORTH);
		add(lists, BorderLayout.CENTER);

		update(false, Collections.emptyList(), id -> true, Collections.emptyList(), null, false, null);
	}

	/**
	 * Must be called on the Swing thread.
	 *
	 * @param servers   every server the bot is in
	 * @param allowed   which of those servers the plugin may use
	 * @param channels  channels in allowed servers
	 * @param online    online members of allowed servers, or null if presence tracking is turned off
	 */
	void update(boolean connected, List<DiscordGateway.Server> servers, Predicate<String> allowed,
		List<DiscordGateway.Channel> channels, String activeChannelId, boolean discordMode,
		List<DiscordGateway.OnlineGuild> online)
	{
		updating = true;
		try
		{
			statusLabel.setText(connected ? "Connected" : "Not connected");
			statusLabel.setForeground(connected ? CONNECTED : DISCONNECTED);
			modeToggle.setSelected(discordMode);

			if (!channels.equals(shownChannels))
			{
				shownChannels = channels;
				channelBox.removeAllItems();
				channels.forEach(channelBox::addItem);
			}
			channelBox.setSelectedItem(null);
			for (DiscordGateway.Channel c : channels)
			{
				if (Objects.equals(c.getId(), activeChannelId))
				{
					channelBox.setSelectedItem(c);
				}
			}

			rebuildServers(servers, allowed);
			rebuildOnline(online);
		}
		finally
		{
			updating = false;
		}
	}

	private void rebuildServers(List<DiscordGateway.Server> servers, Predicate<String> allowed)
	{
		serverPanel.removeAll();
		if (!servers.isEmpty())
		{
			JLabel header = new JLabel("Servers");
			header.setFont(FontManager.getRunescapeBoldFont());
			serverPanel.add(header);

			for (DiscordGateway.Server server : servers)
			{
				JCheckBox box = new JCheckBox(server.getName(), allowed.test(server.getId()));
				box.setBackground(ColorScheme.DARK_GRAY_COLOR);
				box.setToolTipText("Untick to ignore this server: no messages, channels or online list from it");
				box.addActionListener(e -> plugin.setServerAllowed(server.getId(), box.isSelected()));
				serverPanel.add(box);
			}
		}
		serverPanel.revalidate();
		serverPanel.repaint();
	}

	private void rebuildOnline(List<DiscordGateway.OnlineGuild> online)
	{
		onlinePanel.removeAll();

		if (online == null)
		{
			onlinePanel.add(new JLabel("<html>Turn on 'Show online members' in the plugin settings to see who's online.</html>"));
		}
		else
		{
			for (DiscordGateway.OnlineGuild guild : online)
			{
				JLabel header = new JLabel("<html>" + escape(guild.getName()) + " - " + guild.getMembers().size() + " online</html>");
				header.setFont(FontManager.getRunescapeBoldFont());
				header.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
				onlinePanel.add(header);

				List<DiscordGateway.OnlineMember> members = guild.getMembers();
				for (int i = 0; i < members.size() && i < MAX_MEMBERS_SHOWN; i++)
				{
					DiscordGateway.OnlineMember member = members.get(i);
					onlinePanel.add(new JLabel("<html><font color='" + statusColor(member.getStatus()) + "'>●</font> "
						+ escape(member.getName()) + "</html>"));
				}
				if (members.size() > MAX_MEMBERS_SHOWN)
				{
					onlinePanel.add(new JLabel("...and " + (members.size() - MAX_MEMBERS_SHOWN) + " more"));
				}
			}
		}

		onlinePanel.revalidate();
		onlinePanel.repaint();
	}

	private static String statusColor(String status)
	{
		switch (status)
		{
			case "online":
				return "#3ba55d";
			case "idle":
				return "#faa81a";
			default:
				return "#ed4245";
		}
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
