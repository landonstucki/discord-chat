package com.discordchat;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChatStyle
{
	CHATBOX("Chatbox (Discord icon)"),
	PRIVATE("Private message"),
	FRIENDS_CHAT("Friends chat"),
	CLAN_CHAT("Clan chat"),
	GAME("Game message");

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
