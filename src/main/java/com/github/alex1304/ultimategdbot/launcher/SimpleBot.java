package com.github.alex1304.ultimategdbot.launcher;

import static java.util.Collections.synchronizedSet;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toUnmodifiableSet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.github.alex1304.ultimategdbot.api.Bot;
import com.github.alex1304.ultimategdbot.api.Plugin;
import com.github.alex1304.ultimategdbot.api.PluginMetadata;
import com.github.alex1304.ultimategdbot.api.service.Service;
import com.github.alex1304.ultimategdbot.api.service.ServiceContainer;
import com.github.alex1304.ultimategdbot.api.service.ServiceDependant;
import com.github.alex1304.ultimategdbot.api.service.ServiceFactory;
import com.github.alex1304.ultimategdbot.api.util.PropertyReader;

import discord4j.common.util.Snowflake;
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
import discord4j.store.api.mapping.MappingStoreService;
import discord4j.store.caffeine.CaffeineStoreService;
import discord4j.store.jdk.JdkStoreService;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.Queues;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

/**
 * Basic implementation of a Discord bot, configured with default Discord4J
 * settings.
 */
public class SimpleBot implements Bot {
	
	private static final Logger LOGGER = Loggers.getLogger(SimpleBot.class);
	
	private final Map<String, PropertyReader> configMap;
	private final DiscordClient discordClient;
	private final Set<PluginMetadata> plugins = synchronizedSet(new HashSet<>());
	private final Mono<Snowflake> ownerId;
	private final ServiceContainer serviceContainer = new ServiceContainer(this);
	private final Snowflake debugLogChannelId;
	private final Locale defaultLocale;

	private volatile GatewayDiscordClient gateway;

	private SimpleBot(Map<String, PropertyReader> configMap, DiscordClient discordClient, Locale defaultLocale) {
		this.configMap = configMap;
		this.discordClient = discordClient;
		this.ownerId = discordClient.getApplicationInfo()
				.map(ApplicationInfoData::owner)
				.map(UserData::id)
				.map(Snowflake::of)
				.cache();
		this.debugLogChannelId = config("bot")
				.readOptional("debug_log_channel_id")
				.map(Snowflake::of)
				.orElse(null);
		this.defaultLocale = defaultLocale;
	}
	
	@Override
	public PropertyReader config(String name) {
		var config = configMap.get(name);
		if (config == null) {
			throw new IllegalArgumentException("The configuration file " + name + ".properties is missing");
		}
		return config;
	}
	
	@Override
	public <S extends Service> S service(Class<S> serviceType) {
		return serviceContainer.get(serviceType);
	}

	@Override
	public boolean hasService(Class<? extends Service> serviceType) {
		return serviceContainer.has(serviceType);
	}
	
	@Override
	public DiscordClient rest() {
		return discordClient;
	}

	@Override
	public GatewayDiscordClient gateway() {
		if (gateway == null) {
			throw new IllegalStateException("bot has not been started");
		}
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
	public Locale getLocale() {
		return defaultLocale;
	}

	@Override
	public Mono<Void> start() {
		var mainConfig = config("bot");
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
				.collectList()
				.flatMap(plugins -> initServices(plugins).thenReturn(plugins))
				.flatMapMany(Flux::fromIterable)
				.flatMap(plugin -> Mono.defer(() -> plugin.setup(this))
						.doOnError(e -> LOGGER.error("Failed to setup plugin " + plugin, e))
						.then(Mono.defer(() -> plugin.metadata().doOnNext(plugins::add))))
				.then(gateway.onDisconnect());
	}
	
	private Mono<Void> initServices(List<? extends ServiceDependant> serviceDependants) {
		var serviceFactories = getAllDeclaredServiceFactories();
		var initialDeps = serviceDependants.stream()
				.flatMap(sd -> sd.requiredServices().stream())
				.collect(toUnmodifiableSet());
		var queue = EmitterProcessor.<Class<? extends Service>>create(false);
		var sink = queue.sink(FluxSink.OverflowStrategy.BUFFER);
		var remaining = new AtomicInteger(initialDeps.size());
		initialDeps.forEach(sink::next);
		return queue.flatMap(serviceClass -> Mono.justOrEmpty(serviceFactories.get(serviceClass))
						.switchIfEmpty(Mono.error(() -> new RuntimeException(
								"No factory declared for required service " + serviceClass))))
				.flatMap(factory -> serviceContainer.add(factory)
						.doOnNext(service -> {
							LOGGER.info("Loaded service {} via {}", service, factory);
							var deps = service.requiredServices();
							remaining.addAndGet(deps.size());
							deps.forEach(sink::next);
						})
						.thenReturn(0))
				.doOnNext(__ -> {
					if (remaining.decrementAndGet() == 0) {
						sink.complete();
					}
				})
				.then();
	}
	
	private Map<Class<? extends Service>, ServiceFactory<?>> getAllDeclaredServiceFactories() {
		return configMap.get("services").toJdkProperties().entrySet().stream()
				.map(SimpleBot::resolveServiceClass)
				.collect(toMap(Entry::getKey, Entry::getValue));
	}
	
	private static Entry<Class<? extends Service>, ServiceFactory<?>> resolveServiceClass(Entry<Object, Object> serviceEntry) {
		var serviceClassName = serviceEntry.getKey().toString();
		var serviceFactoryClassName = serviceEntry.getValue().toString();
		try {
			var serviceClass = Class.forName(serviceClassName).asSubclass(Service.class);
			var serviceFactoryClass = Class.forName(serviceFactoryClassName).asSubclass(ServiceFactory.class);
			var serviceFactory = serviceFactoryClass.getConstructor().newInstance();
			if (serviceFactory.serviceClass() != serviceClass) {
				throw new RuntimeException(serviceFactoryClassName
						+ " is incompatible for service type " + serviceClassName);
			}
			return Map.entry(serviceClass, serviceFactory);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		} catch (InstantiationException | IllegalAccessException | NoSuchMethodException e) {
			throw new AssertionError(e);
		} catch (InvocationTargetException e) {
			var cause = e.getCause();
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			if (cause instanceof Error) {
				throw (Error) cause;
			}
			throw new UndeclaredThrowableException(cause);
		}
	}

	public static Mono<SimpleBot> create(Path configDir) {
		return Mono.fromCallable(() -> {
					try (var stream = Files.list(configDir)) {
						return stream.filter(file -> file.toString().endsWith(".properties"))
								.collect(Collectors.toUnmodifiableList());
					}
				})
				.subscribeOn(Schedulers.boundedElastic())
				.flatMapMany(Flux::fromIterable)
				.flatMap(file -> PropertyReader.fromPropertiesFile(file)
						.map(props -> {
							var filename = file.getFileName().toString();
							var extensionLength = ".properties".length();
							if (filename.length() > extensionLength) {
								filename = filename.substring(0, filename.length() - extensionLength);
							}
							return Tuples.of(filename, props);
						}))
				.collect(Collectors.toMap(Tuple2::getT1, Tuple2::getT2))
				.map(configMap -> {
					var mainConfig = configMap.get("bot");
					if (mainConfig == null) {
						throw new RuntimeException("The configuration file bot.properties is missing");
					}
					var defaultLocale = mainConfig.readOptional("default_locale")
							.map(Locale::forLanguageTag)
							.orElse(Locale.getDefault());
					LOGGER.debug("Default locale: {}", defaultLocale);
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
					return new SimpleBot(configMap, discordClient, defaultLocale);
				});
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
