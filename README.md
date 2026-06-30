# LumaBot

[![Discord Shield](https://discordapp.com/api/guilds/146404426746167296/widget.png?style=shield)](https://discord.com/invite/hRwE4Zr)

Discord Bot and web dashboard for verifying server members and other cool
features like stream announcements, moderation tools, and more!

## Usage

You'll need a Discord bot token, a MySQL database, and API keys from Steam and Twitch.

1. Set up a MySQL `luma` database with a privileged user. You can do this on
   a local Docker MySQL instance or any MySQL server.

   ```sql
   CREATE DATABASE IF NOT EXISTS luma; GRANT ALL PRIVILEGES ON luma.* TO '<username>'@'%' IDENTIFIED BY '<password>';
   ```

2. Create an `.env` file with the following contents:

   ```env
   DISCORD_BOT_TOKEN=<>
   DISCORD_CLIENT_ID=<>
   DISCORD_CLIENT_SECRET=<>
   MYSQL_HOST=localhost
   MYSQL_USER=<username>
   MYSQL_PASS=<password>
   STEAM_KEY=<>
   TWITCH_CLIENT_ID=<>
   TWITCH_CLIENT_SECRET=<>
   ```

   To enable forum thread auto-close, also set both of these values:

   ```env
   FORUM_AUTO_CLOSE_CHANNEL_ID=<forum channel id>
   FORUM_AUTO_CLOSE_TAG_ID=<closed tag id>
   ```

   Adding the configured closed tag archives and locks the thread. A thread
   owner, or the author of a thread message, can also react with the check mark
   emoji to archive and lock the thread, applying the closed tag when the thread
   has room for another tag.

3. Build and run the Docker image with

   ```sh
   docker build -t LumaBot .
   docker run --env-file .env -p 80:80 --restart unless-stopped -d LumaBot
   ```

## #bot-spam `?l ping`

What time can you get?
