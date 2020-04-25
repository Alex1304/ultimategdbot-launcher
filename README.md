# UltimateGDBot Launcher

![GitHub release (latest SemVer including pre-releases)](https://img.shields.io/github/v/release/ultimategdbot/ultimategdbot-launcher?include_prereleases&sort=semver)
![License](https://img.shields.io/github/license/ultimategdbot/ultimategdbot-launcher)
[![Official Server](https://img.shields.io/discord/357655103768887297?color=%237289DA&label=Official%20Server&logo=discord)](https://discord.gg/VpVdKvg)

Provides a basic runtime environment for Discord bots based on [UltimateGDBot API](https://github.com/ultimategdbot/ultimategdbot).

## Installation

Download the ZIP archive containing everything needed to run a bot at the [Releases page](https://github.com/ultimategdbot/ultimategdbot-launcher/releases) of this repository (latest version).

If you are compiling the bot yourself, here is how you can get the ZIP file:
- Make sure you have [JDK 11](https://adoptopenjdk.net/?variant=openjdk11&jvmVariant=hotspot) and [Maven](https://maven.apache.org) installed
- Run `mvn package` in a terminal
- The ZIP file is generated in the `target/` directory

## Structure

```
-+
 |
 +- config/ -+
 |           |
 |           +- bot.properties
 |           |
 |           +- hikari.properties
 |           |
 |           +- launcher.properties
 |           |
 |           +- logback.xml
 |
 +- lib/
 |
 +- plugins/
 |
 +- start.sh
 |
 +- start.bat
 |
 +- start-detached.sh
 |
 +- start-detached.bat
```

- `bot.properties` - Config file for the bot (bot token, command prefix, etc)
- `hikari.properties` - Config file for the MySQL database (host, username, password), and configuration of the HikariCP connection pool (optional).
- `launcher.properties` - Specifies the JVM options (default nothing) and the plugins folder (default `plugins`) to launch the bot
- `logback.xml` - Configure logging (log files and console output)

## Configuration

In the future this project will come with an interactive setup program to get your bot ready in minutes. For now you need to manually edit the config files to add bot token, database credentials, among other configurable stuff.

## Plugin installation

To install a plugin, for example the [Core plugin](https://github.com/ultimategdbot/ultimategdbot-core-plugin) or the [Geometry Dash plugin](https://github.com/ultimategdbot/ultimategdbot-gd-plugin), download the ZIP file in the Releases page of each plugin you want to install, and unzip the contents in the `plugins/` directory. In the end you should only have folders in the plugins directory, each folder containing the JARs necessary for the plugin. You don't need to keep the ZIP file itself in the folder.

## Running

To run the bot, make sure everything is setup first, then run `start.sh` (on Linux/Mac) or `start.bat` (Windows). A console will open with everything logging, indicating that the bot runs.

## Running on a remote server

To keep your bot running on a remote server, use `start-detached` script files instead of `start`. It won't output anything in console, depending on how you configured `logback.xml` it will log everything in the `logs/` directory, newly created (this is the default behavior).

## Troubleshooting

If you can't make your bot work, that is, if the bot spits out an error message in the output console/log file on startup and don't know how to solve, here are some indications to help fixing the issue:

* `The bot could not be started. Make sure that all configuration files are present and have a valid content`. The error is self-explanatory: something is missing in `bot.properties`. Make sure all required fields are set and try running again
* `Access denied for username@host (using password: YES)`. Your database credentials are wrong. Make sure they are correct in `hikari.properties`
* `ClientException [...] 401 Unauthorized`. Your bot token is incorrect.

This list is of course not comprehensive and more will be added according to the most frequently reported problems. Generally, if it doesn't work, analyze the error message and google it if necessary. If you still don't know what the problem is, feel free to open an issue in this repository, or talk about it in the [official Discord server](https://discord.gg/VpVdKvg).

## Contributing

You may use snapshot and alpha/beta versions if you want to report bugs and give feedback on this project. Issues and pull requests on the master branch are welcome, just follow the instructions on the issue creation menu.

## License

This project is released under the MIT license.

## Contact

E-mail: ultimategdbot@gmail.com

Discord: `Alex1304#9704` (Server [https://discord.gg/VpVdKvg](https://discord.gg/VpVdKvg))

Twitter: [@gd_alex1304](https://twitter.com/gd_alex1304)