package com.lootlogger.io;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Transport sink for raw game events.
 * <p>
 * Each call to queueRecord() appends the event to loot_log.jsonl (local mirror,
 * stored under the RuneLite directory) and buffers it in memory. Every 10 s
 * (driven by @Schedule in the plugin) flush() POSTs the batch as a JSON array
 * to the Next.js ingest route:
 * <p>
 * POST {backendUrl}/api/ingest   Content-Type: application/json
 * X-Api-Key: {apiKey}
 * Body: [ RawEvent, RawEvent, ... ]
 * <p>
 * backendUrl and apiKey are supplied by LootLoggerPlugin via the in-game
 * config panel (LootLoggerConfig) — no properties file required.
 * If apiKey is blank, the batch is still written locally but the POST is
 * skipped, since the server will reject unauthenticated requests anyway.
 */
@Slf4j
public class LootWriter {
    private final Gson gson = new Gson();
    private PrintWriter fileWriter;

    private volatile String backendUrl;
    private volatile String apiKey;

    // Pinned to HTTP/1.1: the default client negotiates HTTP/2, which for a
    // cleartext http:// endpoint sends an "Upgrade: h2c" request. Next.js's
    // underlying Node HTTP server has no h2c upgrade handler and destroys the
    // socket without responding, surfacing as
    // "IOException: HTTP/1.1 header parser received no bytes" on every push.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final List<Object> batch = new ArrayList<>();

    /**
     * Called by the plugin on startUp() and whenever config changes.
     */
    public void configure(String backendUrl, String apiKey) {
        this.backendUrl = backendUrl;
        this.apiKey = apiKey;
        log.info("[LootWriter] Configured. endpoint={}/api/ingest, apiKey={}",
                backendUrl, maskKey(apiKey));
    }

    /** Masks an API key for logging: shows first/last 4 chars only. */
    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "<blank>";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    public void init() throws IOException {
        try {
            java.nio.file.Path pluginDir = net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("lootlogger");
            java.nio.file.Files.createDirectories(pluginDir);
            java.nio.file.Path logPath = pluginDir.resolve("loot_log.jsonl");

            fileWriter = new PrintWriter(new FileWriter(logPath.toFile(), true), true);
        } catch (IOException e) {
            log.error("[LootWriter] Failed to open local log file!", e);
        }
    }

    public synchronized void queueRecord(Object record) {
        if (fileWriter != null) {
            fileWriter.println(gson.toJson(record));
            fileWriter.flush();
        }
        batch.add(record);
    }

    public void close() throws IOException {
        if (fileWriter != null) fileWriter.close();
    }

    public synchronized void flush() {
        int size = batch.size();
        if (size == 0) {
            log.debug("[LootWriter] flush: batch empty, nothing to send.");
            return;
        }

        String jsonPayload = gson.toJson(batch);
        batch.clear();

        if (backendUrl == null || backendUrl.isBlank()) {
            log.warn("[LootWriter] Backend URL is blank — {} records dropped (set it in plugin config).", size);
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[LootWriter] API key is blank — {} records dropped (set it in plugin config).", size);
            return;
        }

        String endpoint = backendUrl + "/api/ingest";
        log.info("[LootWriter] POSTing {} records to {} (key {})", size, endpoint, maskKey(apiKey));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, err) -> {
                        // whenComplete catches async failures (connection refused, DNS,
                        // TLS) that .thenAccept + the sync try/catch below cannot see.
                        if (err != null) {
                            log.error("[LootWriter] Async POST to {} failed (no HTTP response): {}",
                                    endpoint, err.toString(), err);
                            return;
                        }
                        int status = response.statusCode();
                        if (status >= 200 && status < 300) {
                            log.info("[LootWriter] Ingest OK: {} — {} (at {})", status, response.body(),
                                    ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                        } else {
                            log.error("[LootWriter] Ingest error: {} — {}", status, response.body());
                        }
                    });
        } catch (Exception e) {
            log.error("[LootWriter] Failed to dispatch batch to {}!", endpoint, e);
        }
    }
}