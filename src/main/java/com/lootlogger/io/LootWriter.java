package com.lootlogger.io;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Transport sink for raw game events.
 * <p>
 * Each call to queueRecord() appends the event to loot_log.jsonl (local mirror)
 * and buffers it in memory. Every 10 s (driven by @Schedule in the plugin)
 * flush() POSTs the batch as a JSON array to the Next.js ingest route:
 * <p>
 * POST {BACKEND_URL}/api/ingest   Content-Type: application/json
 * Body: [ RawEvent, RawEvent, ... ]
 * <p>
 * Required gradle.properties key: BACKEND_URL (e.g. http://localhost:3000)
 * If BACKEND_URL is absent, events are still written locally to loot_log.jsonl.
 */
@Slf4j
public class LootWriter {
    private final Gson gson = new Gson();
    private PrintWriter fileWriter;
    private String backendUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final List<Object> batch = new ArrayList<>();

    public void init() throws IOException {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("gradle.properties")) {
            prop.load(fis);
            backendUrl = prop.getProperty("BACKEND_URL");
        } catch (IOException e) {
            log.warn("[LootWriter] Could not load gradle.properties — HTTP ingest disabled.");
        }

        try {
            fileWriter = new PrintWriter(new FileWriter("loot_log.jsonl", true), true);
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
        if (batch.isEmpty()) return;

        String jsonPayload = gson.toJson(batch);
        batch.clear();

        if (backendUrl == null || backendUrl.isBlank()) {
            log.debug("[LootWriter] BACKEND_URL not set — batch written to local file only.");
            log.info("[LootWriter] Batch ingested at {}", ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(backendUrl + "/api/ingest"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            log.debug("[LootWriter] Batch ingested at {}",
                                    ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                        } else {
                            log.error("[LootWriter] Ingest error: {} — {}", response.statusCode(), response.body());
                        }
                    })
                    .exceptionally(e -> {
                        log.error("[LootWriter] Critical network failure trying to reach Next.js!", e);
                        return null;
                    });
        } catch (Exception e) {
            log.error("[LootWriter] Failed to send batch!", e);
        }
    }
}
