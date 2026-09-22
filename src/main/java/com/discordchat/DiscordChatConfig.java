package com.discordchat;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup(DiscordChatConfig.GROUP)
public interface DiscordChatConfig extends Config
{
	String GROUP = "discordchat";

	@ConfigSection(
		name = "Connection",
		description = "Discord bot connection settings",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "Display",
		description = "How Discord messages appear in the chatbox",
		position = 1
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "botToken",
		name = "Bot token",
		description = "Token of your Discord bot (Developer Portal > your app > Bot > Reset Token)",
		secret = true,
		position = 0,
		section = connectionSection
	)
	default String botToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "allowedServers",
		name = "Server IDs",
		description = "Comma-separated Discord server IDs the plugin may use. Leave blank to allow every server the bot is in. You can also tick servers in the Discord Chat side panel.",
		position = 1,
		section = connectionSection
	)
	default String allowedServers()
	{
		return "";
	}

	@ConfigItem(
		keyName = "channelIds",
		name = "Channel IDs",
		description = "Comma-separated Discord channel IDs to relay. Leave blank to relay every channel the bot can see.",
		position = 2,
		section = connectionSection
	)
	default String channelIds()
	{
		return "";
	}

	@ConfigItem(
		keyName = "sendChannelId",
		name = "Send channel ID",
		description = "Channel that ::d posts to. Leave blank to post to the channel you last received a message from.",
		position = 3,
		section = connectionSection
	)
	default String sendChannelId()
	{
		return "";
	}

	@ConfigItem(
		keyName = "relayDms",
		name = "Relay DMs to bot",
		description = "Show direct messages sent to your bot",
		position = 4,
		section = connectionSection
	)
	default boolean relayDms()
	{
		return true;
	}

	@ConfigItem(
		keyName = "includeRsn",
		name = "Include RSN when sending",
		description = "Prefix messages you send from game with your character name",
		position = 5,
		section = connectionSection
	)
	default boolean includeRsn()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOnline",
		name = "Show online members",
		description = "Track who's online in your servers (side panel and ::donline). Needs 'Presence Intent' and 'Server Members Intent' turned on for your bot.",
		position = 6,
		section = connectionSection
	)
	default boolean showOnline()
	{
		return true;
	}

	@ConfigItem(
		keyName = "toggleKeybind",
		name = "Discord mode hotkey",
		description = "Toggles Discord mode, where everything you type in the chatbox goes to your Discord channel instead of game chat",
		position = 0,
		section = displaySection
	)
	default Keybind toggleKeybind()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "sendPrefix",
		name = "Quick-send prefix",
		description = "Start a chat line with this to send just that message to Discord, like // for clan chat. Leave blank to turn off.",
		position = 1,
		section = displaySection
	)
	default String sendPrefix()
	{
		return "@";
	}

	@ConfigItem(
		keyName = "messageStyle",
		name = "Message style",
		description = "How Discord messages appear. 'Chatbox' shows them like normal public chat with a Discord icon (needs your Public chat filter set to Show all).",
		position = 2,
		section = displaySection
	)
	default ChatStyle chatStyle()
	{
		return ChatStyle.CHATBOX;
	}

	@ConfigItem(
		keyName = "showChannel",
		name = "Show channel name",
		description = "Show which Discord channel a message came from",
		position = 3,
		section = displaySection
	)
	default boolean showChannel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showServer",
		name = "Show server name",
		description = "Also show which Discord server a message came from",
		position = 4,
		section = displaySection
	)
	default boolean showServer()
	{
		return false;
	}
}
