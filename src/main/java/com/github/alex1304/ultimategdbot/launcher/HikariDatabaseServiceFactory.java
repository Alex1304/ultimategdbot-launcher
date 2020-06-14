package com.github.alex1304.ultimategdbot.launcher;

import org.jdbi.v3.core.Jdbi;

import com.github.alex1304.ultimategdbot.api.Bot;
import com.github.alex1304.ultimategdbot.api.database.DatabaseService;
import com.github.alex1304.ultimategdbot.api.service.ServiceFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class HikariDatabaseServiceFactory implements ServiceFactory<DatabaseService> {

	@Override
	public DatabaseService create(Bot bot) {
		HikariConfig hikariCfg = new HikariConfig(bot.config("hikari").toJdkProperties());
		return DatabaseService.create(bot, Jdbi.create(new HikariDataSource(hikariCfg)));
	}

	@Override
	public Class<DatabaseService> serviceClass() {
		return DatabaseService.class;
	}

}
