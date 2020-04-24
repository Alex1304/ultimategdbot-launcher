import com.github.alex1304.ultimategdbot.api.PluginBootstrap;

module ultimategdbot.launcher {
	requires com.github.benmanes.caffeine;
	requires com.zaxxer.hikari;
	requires jdk.unsupported;
	requires stores.caffeine;
	requires stores.jdk;
	requires ultimategdbot.api;
	
	requires transitive com.fasterxml.jackson.core;
	requires transitive com.fasterxml.jackson.databind;
	
	uses PluginBootstrap;
}