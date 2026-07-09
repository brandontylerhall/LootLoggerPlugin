package com.lootlogger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

// The string inside ConfigGroup is the internal ID for your settings
@ConfigGroup("lootlogger")
public interface LootLoggerConfig extends Config {

    @ConfigItem(
            keyName = "apiKey",
            name = "API Key",
            description = "Your personal API key from the website's Account page. Get one at your dashboard's /account page.",
            position = 1,
            secret = true
    )
    default String apiKey() {
        return "";
    }

    @ConfigItem(
            keyName = "backendUrl",
            name = "Backend URL",
            description = "The ingest endpoint for your Next.js backend (e.g. http://localhost:3000 or your deployed domain).",
            position = 2
    )
    default String backendUrl() {
        return "http://localhost:3000";
    }

    @ConfigItem(
            keyName = "debugMessages",
            name = "Debug Messages",
            description = "Shows various information.",
            position = 3
    )
    default boolean debugMessages() {
        return true;
    }
}