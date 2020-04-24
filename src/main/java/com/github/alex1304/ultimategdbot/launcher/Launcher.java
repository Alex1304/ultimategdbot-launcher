package com.github.alex1304.ultimategdbot.launcher;

import static java.util.stream.Collectors.toCollection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;

public class Launcher {
	
	private static final Path LAUNCHER_PROPS_FILE = Path.of(".", "config", "launcher.properties");
	private static final Path DEFAULT_PLUGINS_DIRECTORY = Path.of(".", "plugins");

	public static void main(String[] args) throws IOException {
		var launcherProps = new Properties();
		try (var reader = Files.newBufferedReader(LAUNCHER_PROPS_FILE)) {
			launcherProps.load(reader);
		} catch (IOException e) {
			System.err.println("[WARNING] Failed to load file " + LAUNCHER_PROPS_FILE.toString()
					+ ", using default launcher settings instead");
		}
		var jvmOptions = launcherProps.getProperty("jvmOptions", "");
		var pluginsDirectory = Path.of(launcherProps.getProperty("pluginsDirectory", DEFAULT_PLUGINS_DIRECTORY.toString()));
		var resolvedJavaHome = Path.of(System.getProperty("java.home")).resolve(Path.of("bin", "java")).toString();
		var modulePath = buildModulePath(pluginsDirectory);
		
		var processBuilder = new ProcessBuilder();
		var command = processBuilder.command();
		command.add(resolvedJavaHome);
		if (!jvmOptions.isEmpty()) {
			command.add(jvmOptions);
		}
		if (!modulePath.isEmpty()) {
			command.add("--module-path");
			command.add(modulePath);
		}
		command.add("-cp");
		command.add("config");
		command.add("-m");
		command.add("ultimategdbot.launcher/" + UltimateGDBot.class.getName());
		System.out.println(String.join(" ", command));
		var process = processBuilder.start();
		System.out.println("Bot started (PID: " + process.pid() + ")");
	}
	
	private static String buildModulePath(Path pluginsDirectory) throws IOException {
		try (var stream = Files.list(pluginsDirectory)) {
			var paths = stream.filter(Files::isDirectory)
					.map(Path::toString)
					.collect(toCollection(ArrayList::new));
			paths.add("lib");
			return String.join(":", paths);
		}
	}
}
