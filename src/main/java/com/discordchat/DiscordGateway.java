package com.discordchat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Minimal Discord gateway (websocket) client for a bot account. Receives messages, tracks channels and
 * member presence, and posts messages through the REST API. All mutable connection state is only touched
 * on {@link #executor}; the caches are concurrent so other threads can read them.
 */
@Slf4j
class DiscordGateway extends WebSocketListener
{
	interface Listener
	{
		void onMessage(DiscordMessage message);

		void onStatus(String status);

		/**
		 * Channels, servers or online members changed.
		 */
		void onStateChanged();
	}

	@Value
	static class Server
	{
		String id;
		String name;
	}

	@Value
	static class Channel
	{
		String id;
		String guildId;
		String guildName;
		String name;

		@Override
		public String toString()
		{
			return guildName + " #" + name;
		}
	}

	@Value
	static class OnlineMember
	{
		String name;
		String status; // online, idle, dnd
	}

	@Value
	static class OnlineGuild
	{
		String id;
		String name;
		List<OnlineMember> members;
	}

	private static class Guild
	{
		private final String name;
		private final Map<String, String> memberNames = new ConcurrentHashMap<>();
		private final Map<String, String> statuses = new ConcurrentHashMap<>();

		private Guild(String name)
		{
			this.name = name;
		}
	}

	private static class ChannelInfo
	{
		private final String guildId;
		private final String name;
		private final int type;
		private final int position;

		private ChannelInfo(String guildId, String name, int type, int position)
		{
			this.guildId = guildId;
			this.name = name;
			this.type = type;
			this.position = position;
		}
	}

	private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
	private static final String API_URL = "https://discord.com/api/v10";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private static final int INTENT_GUILDS = 1;
	private static final int INTENT_GUILD_MEMBERS = 1 << 1;
	private static final int INTENT_GUILD_PRESENCES = 1 << 8;
	private static final int INTENT_GUILD_MESSAGES = 1 << 9;
	private static final int INTENT_DIRECT_MESSAGES = 1 << 12;
	private static final int INTENT_MESSAGE_CONTENT = 1 << 15;

	private static final int MESSAGE_TYPE_DEFAULT = 0;
	private static final int MESSAGE_TYPE_REPLY = 19;

	private static final int CHANNEL_TYPE_TEXT = 0;
	private static final int CHANNEL_TYPE_ANNOUNCEMENT = 5;

	private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");
	private static final Pattern ROLE_MENTION = Pattern.compile("<@&(\\d+)>");
	private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d+)>");
	private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?(:\\w+:)\\d+>");

	private static final Comparator<OnlineMember> MEMBER_ORDER = Comparator
		.comparingInt((OnlineMember m) -> statusRank(m.getStatus()))
		.thenComparing(m -> m.getName().toLowerCase());

	private final OkHttpClient http;
	private final Gson gson;
	private final String token;
	private final boolean trackPresence;
	private final Listener listener;
	private final ScheduledExecutorService executor;

	private final Map<String, Guild> guilds = new ConcurrentHashMap<>();
	private final Map<String, ChannelInfo> channels = new ConcurrentHashMap<>();
	private final Map<String, String> dmNames = new ConcurrentHashMap<>();
	private final Map<String, String> roleNames = new ConcurrentHashMap<>();

	private volatile boolean running;
	private volatile boolean connected;
	private WebSocket socket;
	private Integer sequence;
	private boolean heartbeatAcked;
	private ScheduledFuture<?> heartbeatTask;
	private volatile String selfId;
	private boolean announced;
	private int reconnectAttempts;
	private final Map<String, Set<String>> pendingLookups = new HashMap<>();
	private ScheduledFuture<?> lookupTask;

	DiscordGateway(OkHttpClient http, Gson gson, String token, boolean trackPresence, Listener listener)
	{
		// Gateway heartbeats are ~41s apart, so the default read timeout would kill the socket
		this.http = http.newBuilder()
			.readTimeout(0, TimeUnit.MILLISECONDS)
			.build();
		this.gson = gson;
		this.token = token;
		this.trackPresence = trackPresence;
		this.listener = listener;
		this.executor = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "discord-chat-gateway");
			t.setDaemon(true);
			return t;
		});
	}

	void start()
	{
		running = true;
		submit(this::connect);
	}

	void stop()
	{
		running = false;
		connected = false;
		submit(() ->
		{
			stopHeartbeat();
			if (socket != null)
			{
				socket.close(1000, "Plugin stopped");
				socket = null;
			}
		});
		executor.shutdown();
	}

	boolean isConnected()
	{
		return connected;
	}

	/**
	 * Returns a chatbox label for a channel: "#name" (optionally prefixed with the server name) for server
	 * channels, or "DM name" for direct messages.
	 */
	String channelLabel(String channelId, boolean withServer)
	{
		String dm = dmNames.get(channelId);
		if (dm != null)
		{
			return "DM " + dm;
		}
		ChannelInfo info = channels.get(channelId);
		if (info == null)
		{
			return "#" + channelId;
		}
		Guild guild = guilds.get(info.guildId);
		return withServer && guild != null ? guild.name + " #" + info.name : "#" + info.name;
	}

	/**
	 * Text channels the bot can see, grouped by server and in Discord's sidebar order.
	 */
	List<Channel> textChannels()
	{
		Map<Channel, Integer> positions = new HashMap<>();
		for (Map.Entry<String, ChannelInfo> e : channels.entrySet())
		{
			ChannelInfo info = e.getValue();
			Guild guild = guilds.get(info.guildId);
			if (guild != null && (info.type == CHANNEL_TYPE_TEXT || info.type == CHANNEL_TYPE_ANNOUNCEMENT))
			{
				positions.put(new Channel(e.getKey(), info.guildId, guild.name, info.name), info.position);
			}
		}

		List<Channel> result = new ArrayList<>(positions.keySet());
		result.sort(Comparator
			.comparing((Channel c) -> c.getGuildName().toLowerCase())
			.thenComparingInt(positions::get));
		return result;
	}

	/**
	 * Online members per server, sorted online > idle > dnd, then by name.
	 */
	List<OnlineGuild> onlineMembers()
	{
		List<OnlineGuild> result = new ArrayList<>();
		for (Map.Entry<String, Guild> entry : guilds.entrySet())
		{
			Guild guild = entry.getValue();
			List<OnlineMember> members = new ArrayList<>();
			for (Map.Entry<String, String> e : guild.statuses.entrySet())
			{
				String name = guild.memberNames.get(e.getKey());
				if (name != null && !e.getKey().equals(selfId))
				{
					members.add(new OnlineMember(name, e.getValue()));
				}
			}
			members.sort(MEMBER_ORDER);
			result.add(new OnlineGuild(entry.getKey(), guild.name, members));
		}
		result.sort(Comparator.comparing(g -> g.getName().toLowerCase()));
		return result;
	}

	/**
	 * The server a channel belongs to, or null for DMs and unknown channels.
	 */
	String guildOf(String channelId)
	{
		ChannelInfo info = channels.get(channelId);
		return info == null ? null : info.guildId;
	}

	/**
	 * Every server the bot has been added to, sorted by name.
	 */
	List<Server> servers()
	{
		List<Server> result = new ArrayList<>();
		for (Map.Entry<String, Guild> e : guilds.entrySet())
		{
			result.add(new Server(e.getKey(), e.getValue().name));
		}
		result.sort(Comparator.comparing(g -> g.getName().toLowerCase()));
		return result;
	}

	void sendMessage(String channelId, String content, Consumer<String> onError)
	{
		if (!channelId.matches("\\d+"))
		{
			onError.accept("invalid channel ID " + channelId);
			return;
		}

		JsonObject allowedMentions = new JsonObject();
		allowedMentions.add("parse", new JsonArray()); // never ping @everyone/roles/users from game
		JsonObject body = new JsonObject();
		body.addProperty("content", content);
		body.add("allowed_mentions", allowedMentions);

		Request request = new Request.Builder()
			.url(API_URL + "/channels/" + channelId + "/messages")
			.header("Authorization", "Bot " + token)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept(e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == 403)
					{
						onError.accept("the bot doesn't have permission to post in that channel");
					}
					else if (!r.isSuccessful())
					{
						onError.accept("Discord returned HTTP " + r.code());
					}
				}
			}
		});
	}

	// ---- connection lifecycle ----

	private void connect()
	{
		if (!running)
		{
			return;
		}
		sequence = null;
		Request request = new Request.Builder().url(GATEWAY_URL).build();
		socket = http.newWebSocket(request, this);
	}

	private void scheduleReconnect()
	{
		if (!running)
		{
			return;
		}
		long delay = Math.min(60, 1L << Math.min(reconnectAttempts, 6));
		reconnectAttempts++;
		log.debug("Reconnecting to Discord in {}s", delay);
		executor.schedule(this::connect, delay, TimeUnit.SECONDS);
	}

	private void reconnectNow()
	{
		WebSocket ws = socket;
		socket = null;
		connected = false;
		stopHeartbeat();
		if (ws != null)
		{
			ws.close(4000, "Reconnecting");
		}
		listener.onStateChanged();
		scheduleReconnect();
	}

	private void handleDisconnect(int code, String reason)
	{
		socket = null;
		connected = false;
		stopHeartbeat();
		listener.onStateChanged();
		log.debug("Discord gateway disconnected: {} {}", code, reason);

		switch (code)
		{
			case 4004:
				running = false;
				listener.onStatus("Invalid bot token. Check the plugin settings.");
				return;
			case 4013:
			case 4014:
				running = false;
				listener.onStatus(trackPresence
					? "Discord refused the connection. In the Discord Developer Portal, turn on 'Presence Intent', 'Server Members Intent' and 'Message Content Intent' for your bot, then toggle the plugin. (Or turn off 'Show online members'.)"
					: "Discord refused the connection. Turn on 'Message Content Intent' for your bot in the Discord Developer Portal, then toggle the plugin.");
				return;
		}
		scheduleReconnect();
	}

	@Override
	public void onMessage(WebSocket ws, String text)
	{
		submit(() ->
		{
			if (ws != socket)
			{
				return;
			}
			try
			{
				handlePayload(text);
			}
			catch (RuntimeException e)
			{
				log.warn("Error handling Discord payload", e);
			}
		});
	}

	@Override
	public void onClosing(WebSocket ws, int code, String reason)
	{
		ws.close(1000, null);
		submit(() ->
		{
			if (ws == socket)
			{
				handleDisconnect(code, reason);
			}
		});
	}

	@Override
	public void onFailure(WebSocket ws, Throwable t, Response response)
	{
		submit(() ->
		{
			if (ws == socket)
			{
				handleDisconnect(-1, t.getMessage());
			}
		});
	}

	private void submit(Runnable r)
	{
		try
		{
			executor.execute(r);
		}
		catch (RejectedExecutionException e)
		{
			// gateway was stopped
		}
	}

	// ---- gateway protocol ----

	private void handlePayload(String text)
	{
		JsonObject payload = gson.fromJson(text, JsonObject.class);
		JsonElement s = payload.get("s");
		if (s != null && !s.isJsonNull())
		{
			sequence = s.getAsInt();
		}
		JsonObject d = obj(payload, "d");

		switch (payload.get("op").getAsInt())
		{
			case 0: // dispatch
				if (d != null)
				{
					dispatch(payload.get("t").getAsString(), d);
				}
				break;
			case 1: // heartbeat request
				sendHeartbeat();
				break;
			case 7: // reconnect
			case 9: // invalid session
				reconnectNow();
				break;
			case 10: // hello
				startHeartbeat(d.get("heartbeat_interval").getAsLong());
				identify();
				break;
			case 11: // heartbeat ack
				heartbeatAcked = true;
				break;
		}
	}

	private void identify()
	{
		int intents = INTENT_GUILDS | INTENT_GUILD_MESSAGES | INTENT_DIRECT_MESSAGES | INTENT_MESSAGE_CONTENT;
		if (trackPresence)
		{
			intents |= INTENT_GUILD_MEMBERS | INTENT_GUILD_PRESENCES;
		}

		JsonObject properties = new JsonObject();
		properties.addProperty("os", System.getProperty("os.name", "unknown"));
		properties.addProperty("browser", "runelite-discord-chat");
		properties.addProperty("device", "runelite-discord-chat");

		JsonObject d = new JsonObject();
		d.addProperty("token", token);
		d.addProperty("intents", intents);
		// Send the full member list for servers up to 250 members; bigger servers only send online members
		d.addProperty("large_threshold", 250);
		d.add("properties", properties);

		send(2, d);
	}

	private void startHeartbeat(long interval)
	{
		stopHeartbeat();
		heartbeatAcked = true;
		long firstBeat = (long) (interval * Math.random());
		heartbeatTask = executor.scheduleAtFixedRate(() ->
		{
			if (!heartbeatAcked)
			{
				log.debug("Discord heartbeat not acknowledged, reconnecting");
				reconnectNow();
				return;
			}
			heartbeatAcked = false;
			sendHeartbeat();
		}, firstBeat, interval, TimeUnit.MILLISECONDS);
	}

	private void stopHeartbeat()
	{
		if (heartbeatTask != null)
		{
			heartbeatTask.cancel(false);
			heartbeatTask = null;
		}
	}

	private void sendHeartbeat()
	{
		send(1, sequence == null ? JsonNull.INSTANCE : new JsonPrimitive(sequence));
	}

	private void send(int op, JsonElement d)
	{
		if (socket == null)
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("op", op);
		payload.add("d", d);
		socket.send(gson.toJson(payload));
	}

	private void dispatch(String type, JsonObject d)
	{
		switch (type)
		{
			case "READY":
			{
				reconnectAttempts = 0;
				connected = true;
				guilds.clear(); // every server is re-sent as GUILD_CREATE
				pendingLookups.clear();
				JsonObject user = obj(d, "user");
				selfId = str(user, "id");
				if (!announced)
				{
					announced = true;
					listener.onStatus("Connected as " + str(user, "username") + ".");
				}
				listener.onStateChanged();
				break;
			}
			case "GUILD_CREATE":
				handleGuildCreate(d);
				listener.onStateChanged();
				break;
			case "GUILD_UPDATE":
			{
				Guild old = guilds.get(str(d, "id"));
				String name = str(d, "name");
				if (old != null && name != null && !name.equals(old.name))
				{
					Guild renamed = new Guild(name);
					renamed.memberNames.putAll(old.memberNames);
					renamed.statuses.putAll(old.statuses);
					guilds.put(str(d, "id"), renamed);
					listener.onStateChanged();
				}
				break;
			}
			case "GUILD_DELETE":
				guilds.remove(str(d, "id"));
				listener.onStateChanged();
				break;
			case "CHANNEL_CREATE":
			case "CHANNEL_UPDATE":
			case "THREAD_CREATE":
			case "THREAD_UPDATE":
				cacheChannel(d, str(d, "guild_id"));
				listener.onStateChanged();
				break;
			case "CHANNEL_DELETE":
				channels.remove(str(d, "id"));
				listener.onStateChanged();
				break;
			case "GUILD_MEMBER_ADD":
			case "GUILD_MEMBER_UPDATE":
			{
				Guild guild = guilds.get(str(d, "guild_id"));
				JsonObject user = obj(d, "user");
				if (guild != null && user != null)
				{
					guild.memberNames.put(str(user, "id"), displayName(user, d));
					listener.onStateChanged();
				}
				break;
			}
			case "GUILD_MEMBER_REMOVE":
			{
				Guild guild = guilds.get(str(d, "guild_id"));
				JsonObject user = obj(d, "user");
				if (guild != null && user != null)
				{
					guild.memberNames.remove(str(user, "id"));
					guild.statuses.remove(str(user, "id"));
					listener.onStateChanged();
				}
				break;
			}
			case "GUILD_MEMBERS_CHUNK":
			{
				Guild guild = guilds.get(str(d, "guild_id"));
				if (guild != null)
				{
					cacheMembers(guild, arr(d, "members"));
					listener.onStateChanged();
				}
				break;
			}
			case "PRESENCE_UPDATE":
				handlePresence(str(d, "guild_id"), d);
				listener.onStateChanged();
				break;
			case "MESSAGE_CREATE":
				handleMessage(d);
				break;
		}
	}

	private void handleGuildCreate(JsonObject d)
	{
		String guildId = str(d, "id");
		String name = str(d, "name");
		if (guildId == null || name == null)
		{
			return; // unavailable guild
		}

		Guild guild = new Guild(name);
		guilds.put(guildId, guild);

		for (String key : new String[]{"channels", "threads"})
		{
			JsonArray items = arr(d, key);
			if (items != null)
			{
				for (JsonElement e : items)
				{
					cacheChannel(e.getAsJsonObject(), guildId);
				}
			}
		}

		JsonArray roles = arr(d, "roles");
		if (roles != null)
		{
			for (JsonElement e : roles)
			{
				JsonObject role = e.getAsJsonObject();
				roleNames.put(str(role, "id"), str(role, "name"));
			}
		}

		cacheMembers(guild, arr(d, "members"));

		JsonArray presences = arr(d, "presences");
		if (presences != null)
		{
			for (JsonElement e : presences)
			{
				handlePresence(guildId, e.getAsJsonObject());
			}
		}
	}

	private void handlePresence(String guildId, JsonObject presence)
	{
		Guild guild = guildId == null ? null : guilds.get(guildId);
		JsonObject user = obj(presence, "user");
		if (guild == null || user == null)
		{
			return;
		}

		String userId = str(user, "id");
		String status = str(presence, "status");
		if (status == null || "offline".equals(status) || "invisible".equals(status))
		{
			guild.statuses.remove(userId);
			return;
		}

		guild.statuses.put(userId, status);
		if (!guild.memberNames.containsKey(userId))
		{
			if (str(user, "username") != null)
			{
				guild.memberNames.put(userId, displayName(user, null));
			}
			else
			{
				queueLookup(guildId, userId);
			}
		}
	}

	private void cacheChannel(JsonObject c, String guildId)
	{
		String id = str(c, "id");
		String name = str(c, "name");
		if (id == null || name == null || guildId == null)
		{
			return;
		}
		JsonElement type = c.get("type");
		JsonElement position = c.get("position");
		channels.put(id, new ChannelInfo(guildId, name,
			type == null ? -1 : type.getAsInt(),
			position == null || position.isJsonNull() ? 0 : position.getAsInt()));
	}

	private void cacheMembers(Guild guild, JsonArray members)
	{
		if (members == null)
		{
			return;
		}
		for (JsonElement e : members)
		{
			JsonObject member = e.getAsJsonObject();
			JsonObject user = obj(member, "user");
			if (user != null)
			{
				guild.memberNames.put(str(user, "id"), displayName(user, member));
			}
		}
	}

	/**
	 * Presence updates for big servers can arrive for members we don't have names for yet. Batch those up
	 * and ask the gateway for them (op 8) a couple of seconds later.
	 */
	private void queueLookup(String guildId, String userId)
	{
		pendingLookups.computeIfAbsent(guildId, k -> new HashSet<>()).add(userId);
		if (lookupTask == null || lookupTask.isDone())
		{
			lookupTask = executor.schedule(this::flushLookups, 2, TimeUnit.SECONDS);
		}
	}

	private void flushLookups()
	{
		for (Map.Entry<String, Set<String>> e : pendingLookups.entrySet())
		{
			Iterator<String> ids = e.getValue().iterator();
			while (ids.hasNext())
			{
				JsonArray batch = new JsonArray();
				while (ids.hasNext() && batch.size() < 100)
				{
					batch.add(ids.next());
				}
				JsonObject d = new JsonObject();
				d.addProperty("guild_id", e.getKey());
				d.add("user_ids", batch);
				send(8, d);
			}
		}
		pendingLookups.clear();
	}

	private void handleMessage(JsonObject d)
	{
		JsonObject author = obj(d, "author");
		if (author == null)
		{
			return;
		}
		// Messages from our own bot are still relayed: when friends share one bot token, their in-game
		// messages arrive as the bot. The plugin drops the ones this client sent itself.
		boolean fromBot = str(author, "id").equals(selfId);

		JsonElement type = d.get("type");
		int messageType = type == null ? MESSAGE_TYPE_DEFAULT : type.getAsInt();
		if (messageType != MESSAGE_TYPE_DEFAULT && messageType != MESSAGE_TYPE_REPLY)
		{
			return; // joins, pins, boosts, etc.
		}

		String content = formatContent(d);
		if (content.isEmpty())
		{
			return;
		}

		String channelId = str(d, "channel_id");
		String guildId = str(d, "guild_id");
		boolean direct = guildId == null;
		String name = displayName(author, obj(d, "member"));
		if (direct && !fromBot)
		{
			dmNames.put(channelId, name);
		}

		listener.onMessage(new DiscordMessage(channelId, guildId, direct, fromBot, name, content));
	}

	private String formatContent(JsonObject d)
	{
		String content = str(d, "content");
		if (content == null)
		{
			content = "";
		}

		Map<String, String> mentioned = new HashMap<>();
		JsonArray mentions = arr(d, "mentions");
		if (mentions != null)
		{
			for (JsonElement e : mentions)
			{
				JsonObject user = e.getAsJsonObject();
				mentioned.put(str(user, "id"), displayName(user, obj(user, "member")));
			}
		}

		content = replace(USER_MENTION, content, id -> "@" + mentioned.getOrDefault(id, "user"));
		content = replace(ROLE_MENTION, content, id -> "@" + roleNames.getOrDefault(id, "role"));
		content = replace(CHANNEL_MENTION, content, id ->
		{
			ChannelInfo info = channels.get(id);
			return "#" + (info == null ? "channel" : info.name);
		});
		content = CUSTOM_EMOJI.matcher(content).replaceAll("$1");
		content = content.replaceAll("\\s*\\R\\s*", " ").trim();

		StringBuilder sb = new StringBuilder(content);
		appendCount(sb, arr(d, "attachments"), "attachment");
		appendCount(sb, arr(d, "sticker_items"), "sticker");
		if (sb.length() == 0)
		{
			appendCount(sb, arr(d, "embeds"), "embed");
		}
		return sb.toString();
	}

	// ---- helpers ----

	private static int statusRank(String status)
	{
		switch (status)
		{
			case "online":
				return 0;
			case "idle":
				return 1;
			default:
				return 2;
		}
	}

	private static String displayName(JsonObject user, JsonObject member)
	{
		String nick = member == null ? null : str(member, "nick");
		if (nick != null)
		{
			return nick;
		}
		String global = str(user, "global_name");
		return global != null ? global : str(user, "username");
	}

	private static void appendCount(StringBuilder sb, JsonArray items, String noun)
	{
		if (items == null || items.size() == 0)
		{
			return;
		}
		if (sb.length() > 0)
		{
			sb.append(' ');
		}
		sb.append('[');
		if (items.size() > 1)
		{
			sb.append(items.size()).append(' ').append(noun).append('s');
		}
		else
		{
			sb.append(noun);
		}
		sb.append(']');
	}

	private static String replace(Pattern pattern, String input, Function<String, String> replacer)
	{
		Matcher m = pattern.matcher(input);
		StringBuffer sb = new StringBuffer();
		while (m.find())
		{
			m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m.group(1))));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static String str(JsonObject o, String key)
	{
		JsonElement e = o == null ? null : o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static JsonObject obj(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static JsonArray arr(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}
}
