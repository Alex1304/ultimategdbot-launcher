package com.github.alex1304.ultimategdbot.launcher;

import static com.github.alex1304.rdi.config.FactoryMethod.constructor;
import static com.github.alex1304.rdi.config.FactoryMethod.externalStaticFactory;
import static com.github.alex1304.rdi.config.FactoryMethod.staticFactory;
import static com.github.alex1304.rdi.config.Injectable.ref;
import static com.github.alex1304.rdi.config.Injectable.value;
import static com.github.alex1304.ultimategdbot.api.service.CommonServices.DATABASE_SERVICE;
import static com.github.alex1304.ultimategdbot.api.service.CommonServices.GATEWAY_DISCORD_CLIENT;
import static com.github.alex1304.ultimategdbot.api.service.CommonServices.PLUGIN_METADATA_SERVICE;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toUnmodifiableList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import org.jdbi.v3.core.Jdbi;

import com.github.alex1304.rdi.RdiServiceContainer;
import com.github.alex1304.rdi.ServiceReference;
import com.github.alex1304.rdi.config.RdiConfig;
import com.github.alex1304.rdi.config.ServiceDescriptor;
import com.github.alex1304.ultimategdbot.api.BotConfig;
import com.github.alex1304.ultimategdbot.api.Plugin;
import com.github.alex1304.ultimategdbot.api.PluginMetadata;
import com.github.alex1304.ultimategdbot.api.database.DatabaseService;
import com.github.alex1304.ultimategdbot.api.service.ServiceDeclarator;
import com.github.alex1304.ultimategdbot.api.util.PropertyReader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import discord4j.common.store.Store;
import discord4j.common.store.impl.LocalStoreLayout;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.EventDispatcher;
import discord4j.core.object.presence.Activity;
import discord4j.core.object.presence.Presence;
import discord4j.core.retriever.EntityRetrievalStrategy;
import discord4j.core.shard.MemberRequestFilter;
import discord4j.discordjson.json.gateway.StatusUpdate;
import discord4j.gateway.intent.IntentSet;
import discord4j.rest.request.RouteMatcher;
import discord4j.rest.response.ResponseFunction;
import discord4j.rest.route.Routes;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.Queues;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

public final class BotSupport {
	
	private static final Logger LOGGER = Loggers.getLogger(BotSupport.class);
	private static final ServiceReference<DiscordClient> DISCORD_CLIENT = ServiceReference.ofType(DiscordClient.class);
	
	private final BotConfig botConfig;
	private final Set<PluginMetadata> plugins = ConcurrentHashMap.newKeySet();

	private BotSupport(BotConfig botConfig) {
		this.botConfig = botConfig;
	}
	
	public static Mono<BotSupport> create(Path configDir) {
		return Mono.fromCallable(() -> {
					try (var stream = Files.list(configDir)) {
						return stream.filter(file -> file.toString().endsWith(".properties"))
								.collect(toUnmodifiableList());
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
				.collect(toMap(Tuple2::getT1, Tuple2::getT2))
				.map(BotConfig::fromResourceMap)
				.map(BotSupport::new);
	}

	public Mono<Void> start() {
		return Mono.defer(() -> {
			RdiServiceContainer serviceContainer = initServiceContainer();
			return Flux.fromIterable(ServiceLoader.load(Plugin.class))
					.flatMap(plugin -> serviceContainer.getService(plugin.rootService())
							.onErrorMap(e -> new RuntimeException("Failed to init root service for plugin " + plugin, e))
							.then(Mono.defer(() -> plugin.metadata().doOnNext(plugins::add))))
					.then(serviceContainer.getService(GATEWAY_DISCORD_CLIENT))
					.flatMap(GatewayDiscordClient::onDisconnect);
		});
	}
	
	private RdiServiceContainer initServiceContainer() {
		// Register database and discord client
		RdiConfig.Builder rdiConfigBuilder = RdiConfig.builder()
				.registerService(initDatabaseDescriptor())
				.registerService(ServiceDescriptor.builder(DISCORD_CLIENT)
						.setFactoryMethod(externalStaticFactory(BotSupport.class, "createDiscordClient", DiscordClient.class,
								value(botConfig, BotConfig.class)))
						.build())
				.registerService(ServiceDescriptor.builder(GATEWAY_DISCORD_CLIENT)
						.setFactoryMethod(externalStaticFactory(BotSupport.class, "connectToGateway", Mono.class,
								value(botConfig, BotConfig.class),
								ref(DISCORD_CLIENT)))
						.build())
				.registerService(ServiceDescriptor.builder(PLUGIN_METADATA_SERVICE)
						.setFactoryMethod(constructor(value(plugins, Set.class)))
						.build());
		// Register custom services declared in external modules
		for (ServiceDeclarator sdp : ServiceLoader.load(ServiceDeclarator.class)) {
			for (ServiceDescriptor sd : sdp.declareServices(botConfig)) {
				rdiConfigBuilder.registerService(sd);
			}
		}
		return RdiServiceContainer.create(rdiConfigBuilder.build());
	}
	
	private ServiceDescriptor initDatabaseDescriptor() {
		HikariConfig hikariCfg = new HikariConfig(botConfig.resource("hikari").toJdkProperties());
		var jdbi = Jdbi.create(new HikariDataSource(hikariCfg));
		return ServiceDescriptor.builder(DATABASE_SERVICE)
				.setFactoryMethod(staticFactory("create", DatabaseService.class,
						value(jdbi, Jdbi.class)))
				.build();
	}
	
	public static DiscordClient createDiscordClient(BotConfig botConfig) {
		var config = botConfig.resource("bot");
		if (config == null) {
			throw new RuntimeException("The configuration file bot.properties is missing");
		}
		var restTimeout = config.readOptional("rest.timeout_seconds")
				.map(Integer::parseInt)
				.map(Duration::ofSeconds)
				.orElse(Duration.ofMinutes(2));
		return DiscordClient.builder(config.read("token"))
				.onClientResponse(ResponseFunction.emptyIfNotFound())
				.onClientResponse(ResponseFunction.emptyOnErrorStatus(RouteMatcher.route(Routes.REACTION_CREATE), 400))
				.onClientResponse(request -> response -> response.timeout(restTimeout)
						.onErrorResume(TimeoutException.class, e -> Mono.fromRunnable(
								() -> LOGGER.warn("REST request timed out: {}", request))))
				.build();
	}
	
	public static Mono<GatewayDiscordClient> connectToGateway(BotConfig botConfig, DiscordClient discordClient) {
		var config = botConfig.resource("bot");
		return discordClient.gateway()
				.setInitialStatus(shard -> parseStatus(config))
				.setStore(Store.fromLayout(LocalStoreLayout.create()))
				.setEventDispatcher(EventDispatcher.withLatestEvents(Queues.SMALL_BUFFER_SIZE))
				.setEntityRetrievalStrategy(EntityRetrievalStrategy.STORE_FALLBACK_REST)
				.setAwaitConnections(true)
				.setEnabledIntents(IntentSet.of(Long.parseLong(config.read("enabled_intents"))))
				.setMemberRequestFilter(MemberRequestFilter.none())
				.login()
				.single();
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
