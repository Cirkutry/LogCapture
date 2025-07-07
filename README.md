# LogCapture

Extremely simple Minecraft Paper/Spigot plugin for monitoring server logs and capturing specific patterns with context lines and Discord webhook notifications. It currently should support most Minecraft versions since it is written with compatible features.

## Features

- **Real-time Log Monitoring**: Continuously monitors server log files for specific patterns
- **Regex Pattern Matching**: Use powerful regular expressions to capture specific log events
- **Context Lines**: Capture lines before and after matches for better context
- **Discord Notifications**: Send captured logs to Discord via webhooks
- **File Size Management**: Automatic file rotation when logs exceed size limits

## Commands

The plugin provides the following commands (requires `logcapture.admin` permission):

- `/logcap help` - Show help message with available commands
- `/logcap reload` - Reload the configuration from file
- `/logcap status` - Display current plugin status and configuration

## Permissions

- `logcapture.admin` - Required for all plugin commands

## Building from Source

1. Clone the repository
2. Ensure you have Maven installed
3. Run `mvn clean install`
4. The compiled JAR will be in the `target/` directory

## Support

If you encounter issues join the Discord server: https://discord.com/invite/EBM9MKkD7F or open a issue in this repo.

## License

This project is licensed under the GPLv3 license.