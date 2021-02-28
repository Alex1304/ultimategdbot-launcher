package com.github.alex1304.ultimategdbot.launcher;

import static java.util.stream.Collectors.toCollection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;

public final class Launcher {
	
	private static final String LAUNCHER_PROPS_FILE = "launcher.properties";
	private static final Path DEFAULT_CONFIG_DIRECTORY = Path.of(".", "config");
	private static final Path DEFAULT_PLUGINS_DIRECTORY = Path.of(".", "plugins");

	public static void main(String[] args) throws IOException, InterruptedException {
		var detached = args.length > 0 && args[0].equals("--detached");
		var launcherProps = new Properties();

		var configDirectory = Path.of(launcherProps.getProperty("configDirectory", DEFAULT_CONFIG_DIRECTORY.toString()));
		try (var reader = Files.newBufferedReader(configDirectory.resolve(LAUNCHER_PROPS_FILE))) {
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
		command.add(configDirectory.toString());
		command.add("-m");
		command.add("ultimategdbot.launcher/" + UltimateGDBot.class.getName());
		command.add(configDirectory.toString());
		System.out.println(String.join(" ", command));
		if (detached) {
			var process = processBuilder.start();
			System.out.println("Bot started (PID: " + process.pid() + ")");
		} else {
			var process = processBuilder.inheritIO().start();
			Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
			process.waitFor();
		}
	}
	
	private static String buildModulePath(Path pluginsDirectory) throws IOException {
		try (var stream = Files.list(pluginsDirectory)) {
			var paths = stream.filter(Files::isDirectory)
					.map(Path::toString)
					.collect(toCollection(ArrayList::new));
			paths.add("lib");
			return String.join(System.getProperty("path.separator"), paths);
		}
	}
}
