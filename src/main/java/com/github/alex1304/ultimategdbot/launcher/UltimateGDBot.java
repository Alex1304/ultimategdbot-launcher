package com.github.alex1304.ultimategdbot.launcher;

import java.nio.file.Paths;

import com.github.alex1304.ultimategdbot.api.Bot;

import reactor.util.Logger;
import reactor.util.Loggers;

class UltimateGDBot {
	
	private static final Logger LOGGER = Loggers.getLogger(UltimateGDBot.class);

	public static void main(String[] args) {
		try {
			SimpleBot.create(Paths.get("config")).flatMap(Bot::start).block();
		} catch (Throwable e) {
			LOGGER.error("The bot could not be started. Make sure that all configuration files are present and have a valid content", e);
			System.exit(1);
		}
	}
}
