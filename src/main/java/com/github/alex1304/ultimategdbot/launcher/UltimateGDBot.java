package com.github.alex1304.ultimategdbot.launcher;

import java.nio.file.Path;
import java.util.Locale;

import reactor.util.Logger;
import reactor.util.Loggers;

class UltimateGDBot {
	
	private static final Logger LOGGER = Loggers.getLogger(UltimateGDBot.class);

	public static void main(String[] args) {
		if (args.length < 1) {
			LOGGER.error("Expects the path to config directory in program argument");
			System.exit(1);
			return;
		}
		Locale.setDefault(Locale.ENGLISH);
		try {
			BotSupport.create(Path.of(args[0])).flatMap(BotSupport::start).block();
		} catch (Throwable e) {
			LOGGER.error("The bot could not be started. Make sure that all configuration files are present and have a valid content", e);
			System.exit(1);
		}
	}
}
