import com.github.alex1304.ultimategdbot.api.Plugin;

module ultimategdbot.launcher {
	requires com.github.benmanes.caffeine;
	requires com.zaxxer.hikari;
	requires jdk.unsupported;
	requires discord4j.store.caffeine;
	requires discord4j.store.jdk;
	requires ultimategdbot.api;
	
	requires com.fasterxml.jackson.core;
	requires com.fasterxml.jackson.databind;
	requires slf4j.api;
	requires logback.core;
	requires logback.classic;
	
	exports com.github.alex1304.ultimategdbot.launcher.logback to logback.core;
	opens com.github.alex1304.ultimategdbot.launcher.logback to logback.core;
	
	uses Plugin;
}