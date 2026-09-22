# Discord Chat (RuneLite plugin)

Brings Discord into your OSRS chatbox. Messages from your Discord channels show up like normal chat with a Discord icon. You can reply from in game, switch into Discord mode (right-click the Clan tab) so your chat goes to a Discord channel instead of game chat, and see who's online in each server.

## 1. Make a Discord bot (one time)

1. Go to https://discord.com/developers/applications, click **New Application** and give it a name.
2. On the **Bot** tab, click **Reset Token** and copy the token. Keep it private, because anyone with it can control the bot.
3. On the same **Bot** tab, under *Privileged Gateway Intents*, turn on:
   - **Message Content Intent** (required)
   - **Server Members Intent** and **Presence Intent** (only needed for the online list)
4. On **OAuth2 > URL Generator**, tick the `bot` scope, then tick these permissions: *View Channels*, *Send Messages*, *Read Message History*. Open the generated URL and add the bot to your server(s).
5. To get a channel ID, turn on Discord **Settings > Advanced > Developer Mode**, then right-click a channel and choose **Copy Channel ID**.

## 2. Configure the plugin

| Setting | What it does |
|---|---|
| Bot token | The token from step 1 |
| Server IDs | Servers the plugin may use. Blank means all servers the bot is in. You can also tick or untick servers in the side panel |
| Channel IDs | Comma-separated channels to relay. Blank means every channel the bot can see |
| Send channel ID | The default channel for `::d` and Discord mode |
| Relay DMs to bot | Shows DMs that people send to your bot |
| Include RSN when sending | Posts as `**YourRSN**: message` |
| Show online members | Online list in the side panel and `::donline` |
| Discord mode hotkey | A key that toggles Discord mode |
| Quick-send prefix | Start a line with this (default `@`) to send just that line to Discord |
| Message style | Chatbox with Discord icon (default) / Private message / Friends chat / Clan chat / Game message |

## 3. Use it in game

| Command | What it does |
|---|---|
| `@hello` | Sends just this line to your current Discord channel |
| Right-click the **Clan** or **Channel** tab | **Talk in Discord** / **Stop talking in Discord**, and a **Discord channel** submenu to switch channels |
| `::d hello` | Sends to your current channel |
| `::dr hello` | Replies to the channel of the last message you received |
| `::dc` | Toggles **Discord mode**: everything you type goes to Discord and never reaches game chat |
| `::dc general` | Switches to the `#general` channel and turns Discord mode on |
| `::donline` | Lists who's online in each server |

While Discord mode is on, a "Discord #channel" box sits above the chatbox. The **Discord Chat** side panel (the blue "D" icon) shows your connection status, a channel picker, the Discord mode toggle, and the online members of each server.

## Limitations

- **Chatbox style needs Public chat on "Show all".** Discord lines use the public chat slot, so they're hidden if your Public filter is set to Friends, Hide or Off.
- **The bot only sees servers you add it to.** It can't join servers by itself. To remove it from a server completely, kick it from that server in Discord.
- **Personal DMs and friends lists aren't supported.** A bot can't read your own Discord account's DMs or friends list, and automating a user account ("selfbot") breaks Discord's Terms of Service and can get that account banned. For private chats, have friends DM your bot (with *Relay DMs* on) or use a small private server with the bot in it.
- Messages you send appear in Discord as the bot, with your RSN in front.
- For servers with more than 250 members, Discord only sends online members, which is all the online list needs anyway.

## Build and run

You need JDK 11 or newer. IntelliJ can download one for you. Open the folder in IntelliJ and run `DiscordChatPluginTest.main` (under `src/test`) with the VM option `-ea`. That launches RuneLite with the plugin loaded. To publish it, follow the [Plugin Hub guide](https://github.com/runelite/plugin-hub).
