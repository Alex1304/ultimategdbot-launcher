package com.github.alex1304.ultimategdbot.launcher;

import static java.util.Collections.synchronizedSet;
import static java.util.Collections.unmodifiableSet;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import com.github.alex1304.ultimategdbot.api.Bot;
import com.github.alex1304.ultimategdbot.api.Plugin;
import com.github.alex1304.ultimategdbot.api.PluginMetadata;
import com.github.alex1304.ultimategdbot.api.service.Service;
import com.github.alex1304.ultimategdbot.api.service.ServiceContainer;
import com.github.alex1304.ultimategdbot.api.util.PropertyReader;

import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.EventDispatcher;
import discord4j.core.object.entity.User;
import discord4j.core.object.presence.Activity;
import discord4j.core.object.presence.Presence;
import discord4j.core.retriever.EntityRetrievalStrategy;
import discord4j.discordjson.json.ApplicationInfoData;
import discord4j.discordjson.json.ImmutableMessageCreateRequest;
import discord4j.discordjson.json.MessageData;
import discord4j.discordjson.json.UserData;
import discord4j.discordjson.json.gateway.StatusUpdate;
import discord4j.discordjson.possible.Possible;
import discord4j.rest.request.RequestQueueFactory;
import discord4j.rest.request.RouteMatcher;
import discord4j.rest.response.ResponseFunction;
import discord4j.rest.route.Routes;
import discord4j.rest.util.Snowflake;
import discord4j.store.api.mapping.MappingStoreService;
import discord4j.store.caffeine.CaffeineStoreService;
import discord4j.store.jdk.JdkStoreService;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.Queues;

/**
 * Basic implementation of a Discord bot, configured with default Discord4J
 * settings.
 */
public class SimpleBot implements Bot {
	
	private static final Logger LOGGER = Loggers.getLogger(SimpleBot.class);
	
	private final Path configDir;
	private final PropertyReader mainConfig;
	private final DiscordClient discordClient;
	private final Set<PluginMetadata> plugins = synchronizedSet(new HashSet<>());
	private final Mono<Snowflake> ownerId;
	private final ConcurrentHashMap<String, Mono<PropertyReader>> properties = new ConcurrentHashMap<>();
	private final ServiceContainer serviceContainer = new ServiceContainer(this);
	private final Snowflake debugLogChannelId;

	private volatile GatewayDiscordClient gateway;

	private SimpleBot(Path configDir, PropertyReader mainConfig, DiscordClient discordClient) {
		this.configDir = configDir;
		this.mainConfig = mainConfig;
		this.discordClient = discordClient;
		this.ownerId = discordClient.getApplicationInfo()
				.map(ApplicationInfoData::owner)
				.map(UserData::id)
				.map(Snowflake::of)
				.cache();
		this.debugLogChannelId = mainConfig.readOptional("debug_log_channel_id").map(Snowflake::of).orElse(null);
		properties.put("config", Mono.just(mainConfig).cache());
	}
	
	@Override
	public PropertyReader config() {
		return mainConfig;
	}
	
	@Override
	public Mono<PropertyReader> extraConfig(String name) {
		return properties.computeIfAbsent(name, k -> PropertyReader
				.fromPropertiesFile(configDir.resolve(Path.of(name + ".properties")))
				.cache());
	}
	
	@Override
	public <S extends Service> S service(Class<S> serviceType) {
		return serviceContainer.get(serviceType);
	}
	
	@Override
	public DiscordClient rest() {
		return discordClient;
	}

	@Override
	public GatewayDiscordClient gateway() {
		return gateway;
	}

	@Override
	public Set<PluginMetadata> plugins() {
		return unmodifiableSet(plugins);
	}

	@Override
	public Mono<User> owner() {
		return ownerId.flatMap(gateway::getUserById);
	} 
	
	@Override
	public Mono<Void> log(String message) {
		return Mono.defer(() -> {
			if (debugLogChannelId == null) {
				return Mono.empty();
			}
			return Mono.just(debugLogChannelId)
					.map(discordClient::getChannelById)
					.flatMap(c -> c.createMessage(ImmutableMessageCreateRequest.builder().content(Possible.of(message)).build()))
					.onErrorResume(e -> Mono.fromRunnable(() -> LOGGER.warn("Failed to send a message to log channel: " + message, e)))
					.then();
		});
	}

	@Override
	public Mono<Void> start() {
		gateway = discordClient.gateway()
				.setInitialStatus(shard -> parseStatus(mainConfig))
				.setStoreService(MappingStoreService.create()
						.setMapping(new CaffeineStoreService(builder -> {
							var maxSize = mainConfig.readOptional("message_cache_max_size")
									.map(Integer::parseInt)
									.orElse(2048);
							if (maxSize >= 1) {
								builder.maximumSize(maxSize);
							}
							return builder;
						}), MessageData.class)
						.setFallback(new JdkStoreService()))
				.setEventDispatcher(EventDispatcher.withLatestEvents(Queues.SMALL_BUFFER_SIZE))
				.setEntityRetrievalStrategy(EntityRetrievalStrategy.STORE)
				.setAwaitConnections(true)
				.login()
				.single()
				.block();
		
		return Flux.fromIterable(ServiceLoader.load(Plugin.class))
				.flatMap(plugin -> Mono.defer(() -> plugin.setup(this))
						.doOnError(e -> LOGGER.error("Failed to setup plugin " + plugin.getClass().getName(), e))
						.thenReturn(plugin))
				.doOnNext(plugin -> {
					var serviceQueue = new ArrayDeque<>(plugin.dependedServices());
					while (!serviceQueue.isEmpty()) {
						var head = serviceQueue.remove();
						if (serviceContainer.add(head)) {
							serviceQueue.addAll(head.dependedServices());
						}
					}
				})
				.then(gateway.onDisconnect());
	}

	public static SimpleBot create(Path configDir, PropertyReader mainConfig) {
		var restTimeout = mainConfig.readOptional("rest.timeout_seconds")
				.map(Integer::parseInt)
				.map(Duration::ofSeconds)
				.orElse(Duration.ofMinutes(2));
		var restBufferSize = mainConfig.readOptional("rest.buffer_size")
				.map(Integer::parseInt)
				.orElse(Queues.SMALL_BUFFER_SIZE);
		var discordClient = DiscordClient.builder(mainConfig.read("token"))
				.onClientResponse(ResponseFunction.emptyIfNotFound())
				.onClientResponse(ResponseFunction.emptyOnErrorStatus(RouteMatcher.route(Routes.REACTION_CREATE), 400))
				.onClientResponse(request -> response -> response.timeout(restTimeout)
						.onErrorResume(TimeoutException.class, e -> Mono.fromRunnable(
								() -> LOGGER.warn("REST request timed out: {}", request))))
				.setRequestQueueFactory(RequestQueueFactory.backedByProcessor(
						() -> EmitterProcessor.create(restBufferSize, false), FluxSink.OverflowStrategy.LATEST))
				.build();
		return new SimpleBot(configDir, mainConfig, discordClient);
	}
	
	private static StatusUpdate parseStatus(PropertyReader config) {
		var activity = config.readOptional("activity")
				.map(value -> {
					if (value.isEmpty() || value.equalsIgnoreCase("none") || value.equalsIgnoreCase("null")) {
						return null;
					} else if (value.matches("playing:.+")) {
						return Activity.playing(value.split(":")[1]);
					} else if (value.matches("watching:.+")) {
						return Activity.watching(value.split(":")[1]);
					} else if (value.matches("listening:.+")) {
						return Activity.listening(value.split(":")[1]);
					} else if (value.matches("streaming:[^:]+:[^:]+")) {
						var split = value.split(":");
						return Activity.streaming(split[1], split[2]);
					}
					LOGGER.warn("activity: Expected one of: ''|'none'|'null', 'playing:<text>', 'watching:<text>', 'listening:<text>'"
							+ " or 'streaming:<url>' in lower case. Defaulting to no activity");
					return null;
				})
				.orElse(null);
		return config.readOptional("status").map(value -> {
			switch (value) {
				case "online": return activity != null ? Presence.online(activity) : Presence.online();
				case "idle": return activity != null ? Presence.idle(activity) : Presence.idle();
				case "dnd": return activity != null ? Presence.doNotDisturb(activity) : Presence.doNotDisturb();
				case "invisible": return Presence.invisible();
				default:
					LOGGER.warn("status: Expected one of 'online', 'idle', 'dnd', 'invisible'. Defaulting to 'online'.");
					return activity != null ? Presence.online(activity) : Presence.online();
			}
		}).orElse(activity != null ? Presence.online(activity) : Presence.online());
	}
}
