package com.discordchat;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ChatboxInput;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Discord Chat",
	description = "Brings Discord channels into your chatbox. @message or ::d to send, ::dc or right-click the Clan tab to talk in Discord",
	tags = {"discord", "chat", "pm", "social", "bridge", "clan"}
)
public class DiscordChatPlugin extends Plugin
{
	private static final String SEND_COMMAND = "d";
	private static final String REPLY_COMMAND = "dr";
	private static final String MODE_COMMAND = "dc";
	private static final String ONLINE_COMMAND = "donline";
	private static final String BLURPLE = "5865f2";
	private static final Color BLURPLE_COLOR = new Color(0x58, 0x65, 0xf2);
	private static final Pattern RSN_PREFIX = Pattern.compile("^[*][*](.+?)[*][*]: (.*)$");
	private static final int RECENTLY_SENT_LIMIT = 20;
	private static final int MAX_MENU_CHANNELS = 25;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DiscordChatConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private DiscordModeOverlay overlay;

	private DiscordGateway gateway;
	private DiscordChatPanel panel;
	private NavigationButton navButton;
	private Timer panelRefresh;

	/** Index of our Discord icon in the client's chat icons, usable as &lt;img=N&gt;. -1 until loaded. */
	private int iconIndex = -1;

	@Getter
	private volatile boolean discordMode;
	private volatile String activeChannelId;
	private volatile String lastChannelId;
	// Messages this client posted, so they aren't shown twice when Discord echoes them back
	private final Deque<String> recentlySent = new ConcurrentLinkedDeque<>();

	private final HotkeyListener toggleHotkey = new HotkeyListener(() -> config.toggleKeybind())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invokeLater(() -> setDiscordMode(!discordMode));
		}
	};

	@Provides
	DiscordChatConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DiscordChatConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new DiscordChatPanel(this);
		navButton = NavigationButton.builder()
			.tooltip("Discord Chat")
			.icon(createIcon(16))
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Presence updates can be very chatty, so refresh the panel at most twice a second
		panelRefresh = new Timer(500, e -> refreshPanelNow());
		panelRefresh.setRepeats(false);

		overlayManager.add(overlay);
		keyManager.registerKeyListener(toggleHotkey);
		clientThread.invoke(this::loadChatIcon);

		String sendChannel = config.sendChannelId().trim();
		activeChannelId = sendChannel.isEmpty() ? null : sendChannel;

		connect();
	}

	@Override
	protected void shutDown()
	{
		disconnect();
		keyManager.unregisterKeyListener(toggleHotkey);
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		panelRefresh.stop();
		panel = null;
		discordMode = false;
		activeChannelId = null;
		lastChannelId = null;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!DiscordChatConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		switch (event.getKey())
		{
			case "botToken":
			case "showOnline":
				disconnect();
				connect();
				break;
			case "sendChannelId":
				String sendChannel = config.sendChannelId().trim();
				if (!sendChannel.isEmpty())
				{
					activeChannelId = sendChannel;
				}
				refreshPanel();
				break;
			default:
				refreshPanel();
				break;
		}
	}

	private void connect()
	{
		String token = config.botToken().trim();
		if (token.isEmpty())
		{
			refreshPanel();
			return;
		}

		gateway = new DiscordGateway(okHttpClient, gson, token, config.showOnline(), new DiscordGateway.Listener()
		{
			@Override
			public void onMessage(DiscordMessage message)
			{
				onDiscordMessage(message);
			}

			@Override
			public void onStatus(String status)
			{
				status(status);
			}

			@Override
			public void onStateChanged()
			{
				refreshPanel();
			}
		});
		gateway.start();
	}

	private void disconnect()
	{
		if (gateway != null)
		{
			gateway.stop();
			gateway = null;
		}
		refreshPanel();
	}

	// ---- server / channel filtering ----

	private boolean isServerAllowed(String guildId)
	{
		List<String> allowed = Text.fromCSV(config.allowedServers());
		return allowed.isEmpty() || allowed.contains(guildId);
	}

	private boolean isChannelAllowed(String channelId)
	{
		DiscordGateway gw = gateway;
		String guildId = gw == null ? null : gw.guildOf(channelId);
		if (guildId != null && !isServerAllowed(guildId))
		{
			return false;
		}
		List<String> allowed = Text.fromCSV(config.channelIds());
		return allowed.isEmpty() || allowed.contains(channelId);
	}

	/**
	 * Allows or blocks a server. Called from the side panel.
	 */
	void setServerAllowed(String guildId, boolean allowed)
	{
		DiscordGateway gw = gateway;
		if (gw == null)
		{
			return;
		}

		List<String> allIds = gw.servers().stream().map(DiscordGateway.Server::getId).collect(Collectors.toList());
		List<String> current = Text.fromCSV(config.allowedServers());
		Set<String> ids = new LinkedHashSet<>(current.isEmpty() ? allIds : current);
		if (allowed)
		{
			ids.add(guildId);
		}
		else
		{
			ids.remove(guildId);
		}

		// Blank means "every server", which also covers servers the bot joins later
		String value = ids.containsAll(allIds) ? "" : String.join(",", ids);
		configManager.setConfiguration(DiscordChatConfig.GROUP, "allowedServers", value);

		if (!allowed && activeChannelId != null && guildId.equals(gw.guildOf(activeChannelId)))
		{
			activeChannelId = null;
			if (discordMode)
			{
				setDiscordMode(false);
			}
		}
	}

	private List<DiscordGateway.Channel> allowedChannels()
	{
		DiscordGateway gw = gateway;
		if (gw == null)
		{
			return Collections.emptyList();
		}
		return gw.textChannels().stream()
			.filter(c -> isChannelAllowed(c.getId()))
			.collect(Collectors.toList());
	}

	// ---- incoming ----

	// Called on the gateway thread
	private void onDiscordMessage(DiscordMessage received)
	{
		DiscordMessage message = received;
		if (received.isFromBot())
		{
			if (recentlySent.remove(received.getContent()))
			{
				return; // our own message echoed back
			}
			// Sent by someone else sharing this bot token: show it under their RSN instead of the bot's name
			Matcher m = RSN_PREFIX.matcher(received.getContent());
			if (m.matches())
			{
				message = new DiscordMessage(received.getChannelId(), received.getGuildId(), received.isDirect(),
					true, m.group(1), m.group(2));
			}
		}

		if (message.isDirect())
		{
			if (!config.relayDms())
			{
				return;
			}
		}
		else if (!isServerAllowed(message.getGuildId()) || !isChannelAllowed(message.getChannelId()))
		{
			return;
		}

		lastChannelId = message.getChannelId();
		DiscordMessage shown = message;
		clientThread.invokeLater(() -> showIncoming(shown));
	}

	private void showIncoming(DiscordMessage message)
	{
		DiscordGateway gw = gateway;
		String label = message.isDirect() || gw == null ? "DM" : gw.channelLabel(message.getChannelId(), config.showServer());
		String author = escape(message.getAuthor());
		String text = escape(message.getContent());

		// DMs always look like private messages
		if (message.isDirect() || config.chatStyle() == ChatStyle.PRIVATE)
		{
			String from = config.showChannel() && !message.isDirect() ? author + " (" + escape(label) + ")" : author;
			client.addChatMessage(ChatMessageType.PRIVATECHAT, icon() + from, text, null);
		}
		else
		{
			addChannelMessage(author, escape(label), text);
		}
	}

	private void showOutgoing(String channelId, String text)
	{
		String label = escape(gateway.channelLabel(channelId, config.showServer()));
		boolean direct = gateway.guildOf(channelId) == null;
		if (direct || config.chatStyle() == ChatStyle.PRIVATE)
		{
			client.addChatMessage(ChatMessageType.PRIVATECHATOUT, icon() + label, escape(text), null);
		}
		else
		{
			String rsn = localName();
			addChannelMessage(escape(rsn == null ? "You" : rsn), label, escape(text));
		}
	}

	/**
	 * Shows a line in the chatbox, friends chat, clan chat or game message style. All arguments must already be escaped.
	 */
	private void addChannelMessage(String author, String label, String text)
	{
		String sender = config.showChannel() ? label : "Discord";
		switch (config.chatStyle())
		{
			case CHATBOX:
				// Looks like normal public chat: "<discord icon>Bob (#general): hi"
				String name = config.showChannel() ? author + " (" + label + ")" : author;
				client.addChatMessage(ChatMessageType.PUBLICCHAT, icon() + name, text, null);
				break;
			case FRIENDS_CHAT:
				client.addChatMessage(ChatMessageType.FRIENDSCHAT, icon() + author, text, sender);
				break;
			case CLAN_CHAT:
				client.addChatMessage(ChatMessageType.CLAN_CHAT, icon() + author, text, sender);
				break;
			default:
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					icon() + "<col=" + BLURPLE + ">[" + sender + "]</col> " + author + ": " + text, null);
				break;
		}
	}

	// ---- outgoing ----

	/**
	 * Sends a chat line to Discord instead of the game when Discord mode is on, or when it starts with the
	 * quick-send prefix.
	 */
	@Subscribe
	public void onChatboxInput(ChatboxInput input)
	{
		String text = input.getValue();
		if (text.startsWith("::"))
		{
			return;
		}

		String prefix = config.sendPrefix().trim();
		boolean quickSend = !prefix.isEmpty() && text.startsWith(prefix);
		if (!discordMode && !quickSend)
		{
			return;
		}

		input.consume(); // never sent to the game server
		String message = (quickSend ? text.substring(prefix.length()) : text).trim();
		if (!message.isEmpty())
		{
			send(activeChannelId != null ? activeChannelId : lastChannelId, message);
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		String command = event.getCommand().toLowerCase();
		String args = String.join(" ", event.getArguments()).trim();

		switch (command)
		{
			case SEND_COMMAND:
			case REPLY_COMMAND:
				if (args.isEmpty())
				{
					status("Usage: ::d <message> posts to your current channel, ::dr <message> replies to the last message.");
					return;
				}
				String target = command.equals(REPLY_COMMAND) || activeChannelId == null ? lastChannelId : activeChannelId;
				send(target, args);
				break;
			case MODE_COMMAND:
				if (args.isEmpty())
				{
					setDiscordMode(!discordMode);
				}
				else
				{
					switchChannel(args);
				}
				break;
			case ONLINE_COMMAND:
				printOnline();
				break;
		}
	}

	private void send(String channelId, String text)
	{
		if (gateway == null)
		{
			status("Not connected. Add your bot token in the Discord Chat plugin settings.");
			return;
		}
		if (channelId == null)
		{
			status("No channel picked. Right-click the Clan tab, use ::dc <channel name>, or pick one in the side panel.");
			return;
		}
		if (gateway.guildOf(channelId) != null && !isChannelAllowed(channelId))
		{
			status("That channel's server is turned off in the plugin. Pick another channel.");
			return;
		}

		String rsn = localName();
		String outgoing = config.includeRsn() && rsn != null ? "**" + rsn + "**: " + text : text;
		recentlySent.addLast(outgoing);
		while (recentlySent.size() > RECENTLY_SENT_LIMIT)
		{
			recentlySent.pollFirst();
		}
		gateway.sendMessage(channelId, outgoing, error -> status("Couldn't send message: " + error));
		showOutgoing(channelId, text);
	}

	// ---- right-click menu on the Clan / Channel chat tabs ----

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (gateway == null || !isChatTabMenu(event.getMenuEntries()))
		{
			return;
		}

		Menu menu = client.getMenu();
		List<DiscordGateway.Channel> channels = allowedChannels();
		boolean oneServer = channels.stream().map(DiscordGateway.Channel::getGuildId).distinct().count() <= 1;

		if (!channels.isEmpty())
		{
			MenuEntry parent = menu.createMenuEntry(-1)
				.setOption("<col=" + BLURPLE + ">Discord channel</col>")
				.setTarget("")
				.setType(MenuAction.RUNELITE);
			Menu channelMenu = parent.createSubMenu();
			for (DiscordGateway.Channel channel : channels.subList(0, Math.min(channels.size(), MAX_MENU_CHANNELS)))
			{
				String name = oneServer ? "#" + channel.getName() : channel.getGuildName() + " #" + channel.getName();
				channelMenu.createMenuEntry(-1)
					.setOption((channel.getId().equals(activeChannelId) ? "✓ " : "") + name)
					.setTarget("")
					.setType(MenuAction.RUNELITE)
					.onClick(e ->
					{
						activeChannelId = channel.getId();
						setDiscordMode(true);
					});
			}
		}

		menu.createMenuEntry(-1)
			.setOption(discordMode ? "Stop talking in Discord" : "Talk in Discord")
			.setTarget("<col=" + BLURPLE + ">" + getActiveChannelLabel() + "</col>")
			.setType(MenuAction.RUNELITE)
			.onClick(e -> setDiscordMode(!discordMode));
	}

	private static boolean isChatTabMenu(MenuEntry[] entries)
	{
		for (MenuEntry entry : entries)
		{
			int widgetId = entry.getParam1();
			if (widgetId == InterfaceID.Chatbox.CHAT_CLAN || widgetId == InterfaceID.Chatbox.CHAT_FRIENDSCHAT)
			{
				return true;
			}
		}
		return false;
	}

	// ---- Discord mode / channel switching ----

	/**
	 * Turns Discord mode on or off. Safe to call from any thread.
	 */
	void setDiscordMode(boolean enabled)
	{
		if (enabled && (activeChannelId == null || !isChannelAllowed(activeChannelId)))
		{
			activeChannelId = lastChannelId != null && isChannelAllowed(lastChannelId) ? lastChannelId : firstChannelId();
			if (activeChannelId == null)
			{
				status("No channel to talk in yet. Right-click the Clan tab, use ::dc <channel name>, or pick one in the side panel.");
				refreshPanel();
				return;
			}
		}

		discordMode = enabled;
		status(enabled
			? "Now talking in " + getActiveChannelLabel() + ". Type ::dc or right-click the Clan tab to switch back."
			: "Stopped talking in Discord. Your chat goes to the game again.");
		refreshPanel();
	}

	/**
	 * Selects the channel Discord mode and ::d post to. Safe to call from any thread.
	 */
	void setActiveChannel(String channelId)
	{
		activeChannelId = channelId;
		if (discordMode)
		{
			status("Now talking in " + getActiveChannelLabel() + ".");
		}
		refreshPanel();
	}

	private void switchChannel(String query)
	{
		if (gateway == null)
		{
			status("Not connected. Add your bot token in the Discord Chat plugin settings.");
			return;
		}

		String q = query.toLowerCase().replaceFirst("^#", "");
		DiscordGateway.Channel match = null;
		for (DiscordGateway.Channel c : allowedChannels())
		{
			String name = c.getName().toLowerCase();
			if (name.equals(q) || c.getId().equals(q))
			{
				match = c;
				break;
			}
			if (match == null && name.contains(q))
			{
				match = c;
			}
		}

		if (match == null)
		{
			status("No channel called '" + query + "'.");
			return;
		}

		activeChannelId = match.getId();
		setDiscordMode(true);
	}

	String getActiveChannelLabel()
	{
		DiscordGateway gw = gateway;
		String id = activeChannelId;
		if (id == null)
		{
			return "no channel";
		}
		return gw == null ? id : gw.channelLabel(id, config.showServer());
	}

	private String firstChannelId()
	{
		List<DiscordGateway.Channel> channels = allowedChannels();
		return channels.isEmpty() ? null : channels.get(0).getId();
	}

	// ---- who's online ----

	private void printOnline()
	{
		DiscordGateway gw = gateway;
		if (gw == null || !gw.isConnected())
		{
			status("Not connected to Discord.");
			return;
		}
		if (!config.showOnline())
		{
			status("Turn on 'Show online members' in the plugin settings first.");
			return;
		}

		for (DiscordGateway.OnlineGuild guild : gw.onlineMembers())
		{
			if (!isServerAllowed(guild.getId()))
			{
				continue;
			}
			String names = guild.getMembers().stream()
				.limit(40)
				.map(DiscordGateway.OnlineMember::getName)
				.collect(Collectors.joining(", "));
			if (guild.getMembers().size() > 40)
			{
				names += ", ...";
			}
			status(guild.getName() + " (" + guild.getMembers().size() + " online): " + (names.isEmpty() ? "nobody" : names));
		}
	}

	// ---- panel ----

	private void refreshPanel()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (panelRefresh != null && !panelRefresh.isRunning())
			{
				panelRefresh.start();
			}
		});
	}

	private void refreshPanelNow()
	{
		DiscordGateway gw = gateway;
		if (panel == null)
		{
			return;
		}

		List<DiscordGateway.OnlineGuild> online = null;
		if (gw != null && config.showOnline())
		{
			online = gw.onlineMembers().stream()
				.filter(g -> isServerAllowed(g.getId()))
				.collect(Collectors.toList());
		}

		panel.update(
			gw != null && gw.isConnected(),
			gw == null ? Collections.emptyList() : gw.servers(),
			this::isServerAllowed,
			allowedChannels(),
			activeChannelId,
			discordMode,
			online);
	}

	// ---- icons ----

	/**
	 * Adds the Discord icon to the client's chat icons (the same list ironman and mod crowns use), so chat
	 * lines can show it with &lt;img=N&gt;. Retried by the client thread until the icons have loaded.
	 */
	private boolean loadChatIcon()
	{
		if (iconIndex >= 0)
		{
			return true;
		}
		IndexedSprite[] modIcons = client.getModIcons();
		if (modIcons == null)
		{
			return false;
		}

		IndexedSprite[] icons = Arrays.copyOf(modIcons, modIcons.length + 1);
		icons[modIcons.length] = ImageUtil.getImageIndexedSprite(createChatIcon(), client);
		client.setModIcons(icons);
		iconIndex = modIcons.length;
		return true;
	}

	private String icon()
	{
		return iconIndex >= 0 ? "<img=" + iconIndex + ">" : "";
	}

	/**
	 * A small Discord-style face: blurple rounded body with two eyes. Drawn without anti-aliasing because chat
	 * icons only support fully opaque or fully transparent pixels.
	 */
	private static BufferedImage createChatIcon()
	{
		BufferedImage image = new BufferedImage(13, 11, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(BLURPLE_COLOR);
		g.fillRoundRect(0, 0, 13, 11, 6, 6);
		g.setColor(Color.WHITE);
		g.fillRect(3, 4, 2, 3);
		g.fillRect(8, 4, 2, 3);
		g.dispose();
		return image;
	}

	private static BufferedImage createIcon(int size)
	{
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(BLURPLE_COLOR);
		g.fillRoundRect(0, 1, size, size - 2, 6, 6);
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
		FontMetrics fm = g.getFontMetrics();
		g.drawString("D", (size - fm.stringWidth("D")) / 2, 12);
		g.dispose();
		return image;
	}

	// ---- helpers ----

	private String localName()
	{
		Player local = client.getLocalPlayer();
		return local == null || local.getName() == null ? null : Text.sanitize(local.getName());
	}

	private void status(String message)
	{
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			icon() + "<col=" + BLURPLE + ">[Discord]</col> " + escape(message), null));
	}

	/**
	 * Stops Discord text from being interpreted as chatbox formatting tags like &lt;col&gt; or &lt;img&gt;.
	 */
	private static String escape(String text)
	{
		return text.replace("<", "<lt>").replace(">", "<gt>");
	}
}
