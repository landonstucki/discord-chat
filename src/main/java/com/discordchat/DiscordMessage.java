package com.discordchat;

import lombok.Value;

@Value
class DiscordMessage
{
	String channelId;
	/** Server the message was posted in, or null for direct messages. */
	String guildId;
	boolean direct;
	/** Posted by our own bot, e.g. by a friend's plugin sharing the same bot token. */
	boolean fromBot;
	String author;
	String content;
}
