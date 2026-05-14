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

@Slf4j
public class LootWriter {
    private final Gson compactGson = new Gson();
    private PrintWriter fileWriter;
    private String supabaseUrl;
    private String supabaseKey;

    // OPTIMIZATION: Instantiate the client ONCE
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static class SupabasePayload {
        public Object log_data;

        public SupabasePayload(Object data) {
            this.log_data = data;
        }
    }

    private final List<SupabasePayload> batch = new ArrayList<>();

    public void init() throws IOException {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("gradle.properties")) {
            prop.load(fis);
            supabaseUrl = prop.getProperty("SUPABASE_URL");
            supabaseKey = prop.getProperty("SUPABASE_KEY");
        } catch (IOException e) {
            log.warn("Could not load gradle.properties!");
        }

        try {
            FileWriter fw = new FileWriter("loot_log.jsonl", true);
            fileWriter = new PrintWriter(fw, true);
        } catch (IOException e) {
            log.error("Failed to open local log file!", e);
        }
    }

    public synchronized void queueRecord(Object record) {
        if (fileWriter != null) {
            fileWriter.println(compactGson.toJson(record));
            fileWriter.flush();
        }
        batch.add(new SupabasePayload(record));
    }

    public void close() throws IOException {
        if (fileWriter != null) fileWriter.close();
    }

    public synchronized void flush() {
        if (batch.isEmpty()) return;

        String jsonPayload = compactGson.toJson(batch);
        batch.clear();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + "/rest/v1/loot_logs"))
                    .header("Content-Type", "application/json")
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .header("Prefer", "return=minimal")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            // Use the reused httpClient
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            // OPTIMIZATION: Swap System.out for Slf4j logging
                            log.debug("Successfully bulk-inserted to Supabase at {}", ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                        } else {
                            log.error("Supabase Error: {} - {}", response.statusCode(), response.body());
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to send bulk HTTP request!", e);
        }
    }
}