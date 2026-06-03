package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class Main {

    private static final String TOKEN_URL = "https://identity.flashfood.com/oauth/token";

    private static final String API_URL =
            "https://app.shopper.flashfood.com/api/v1/stores?storeIds=5f766e5d21b06610425b1854" +
                    "&storeIds=5f766e5d21b06610425b1851" +
                    "&storeIds=5f766e5d21b06610425b1840" +
                    "&storeIds=5f766e5d21b06610425b183e" +
                    "&storeIds=5f766e5d21b06610425b1842" +
                    "&storeIds=5f766e5d21b06610425b1848";

    private static final Duration TTL = Duration.ofDays(4);

    public static void main(String[] args) throws Exception {

        log("🚀 Starting Flashfood 4-hour session...");

        for (int i = 0; i < 80; i++) {

            try {
                runOnce();
            } catch (Exception e) {
                log("❌ Error: " + e.getMessage());
            }

            if (i < 79) {
                log("⏳ Sleeping 3 minutes...");
                Thread.sleep(180_000);
            }
        }

        log("✅ 4-hour session finished");
    }

    private static void runOnce() throws Exception {

        Map<String, Instant> seen = loadSeen();
        cleanup(seen);

        String token = resolveToken();
        List<Map<String, Object>> items = fetchItems(token);

        log("📦 Total items: " + items.size());

        items.sort(
                Comparator
                        .comparing(Main::getStorePriority)
                        .thenComparing(item -> Double.parseDouble(item.get("price").toString()))
        );

        Instant now = Instant.now();

        int matches = 0;
        int totalChecked = 0;

        for (Map<String, Object> item : items) {

            totalChecked++;

            String id = item.get("id").toString();
            String name = item.get("name").toString();
            double price = Double.parseDouble(item.get("price").toString());

            if (seen.containsKey(id)) continue;

            if (isInteresting(name, price, item)) {

                log("🔥 MATCH: " + name);

                try {
                    sendPhoto(item);
                    log("✅ Sent: " + name);
                    Thread.sleep(800); // стабильно для Telegram
                } catch (Exception e) {
                    log("❌ Telegram failed: " + e.getMessage());
                }

                matches++;
            }

            seen.put(id, now);
        }

        saveSeen(seen);

        log("📊 Stats:");
        log("Checked: " + totalChecked);

        if (matches == 0) {
            log("ℹ️ No new deals. Seen total: " + seen.size());
        } else {
            log("✅ Sent: " + matches);
        }
    }

    // ================= TOKEN =================

    private static String resolveToken() throws Exception {
        try {
            log("Using refresh token...");
            return refreshToken();
        } catch (Exception e) {
            log("Refresh failed → full login...");
            return loginToken();
        }
    }

    private static String refreshToken() throws Exception {

        String body = "grant_type=refresh_token"
                + "&client_id=" + System.getenv("CLIENT_ID")
                + "&refresh_token=" + System.getenv("REFRESH_TOKEN");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        String resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString()).body();

        Map json = new ObjectMapper().readValue(resp, Map.class);
        return (String) json.get("access_token");
    }

    private static String loginToken() throws Exception {

        String body = "{ \"audience\":\"https://api.flashfood.com/\"," +
                "\"client_id\":\"" + System.getenv("CLIENT_ID") + "\"," +
                "\"grant_type\":\"http://auth0.com/oauth/grant-type/password-realm\"," +
                "\"password\":\"" + System.getenv("FF_PASSWORD") + "\"," +
                "\"realm\":\"flashfood-shoppers\"," +
                "\"scope\":\"offline_access openid profile email\"," +
                "\"username\":\"" + System.getenv("FF_USERNAME") + "\"}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        String resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString()).body();

        Map json = new ObjectMapper().readValue(resp, Map.class);
        return (String) json.get("access_token");
    }

    // ================= FETCH =================

    private static List<Map<String, Object>> fetchItems(String token) throws Exception {

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        String resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString()).body();

        Map<String, Object> root = new ObjectMapper().readValue(resp, Map.class);

        List<Map<String, Object>> result = new ArrayList<>();

        List<Map<String, Object>> stores = (List<Map<String, Object>>) root.get("data");

        for (Map<String, Object> store : stores) {

            String storeName = store.get("name").toString();
            List<Map<String, Object>> items = (List<Map<String, Object>>) store.get("items");

            if (items == null) continue;

            for (Map<String, Object> item : items) {
                Map<String, Object> enriched = new HashMap<>(item);
                enriched.put("storeName", storeName);
                result.add(enriched);
            }
        }

        return result;
    }

    // ================= FILTER =================

    private static boolean isInteresting(String name, double price, Map<String, Object> item) {

        name = name.toLowerCase();
        String storeId = item.get("storeId").toString();

        boolean isProduce =
                name.contains("fruit bundle") ||
                name.contains("veggie bundle") ||
                name.contains("mixed produce bundle") ||
                name.contains("produce bundle");

        if (isProduce) {
            return isHolland(storeId) ? price <= 5.01 : price <= 3.01;
        }

        if (name.contains("kombucha")) return price <= 2;
        if (name.contains("mushrooms")) return price <= 2;
        if (name.contains("pork")) return price <= 10;
        if (name.contains("fish") || name.contains("salmon")) return price <= 5;

        return false;
    }

    private static boolean isHolland(String storeId) {
        return storeId.equals("5f766e5d21b06610425b1851") ||
               storeId.equals("5f766e5d21b06610425b183e");
    }

    // ================= SORT =================

    private static int getStorePriority(Map<String, Object> item) {
        return isHolland(item.get("storeId").toString()) ? 1 : 100;
    }

    // ================= TELEGRAM =================

    private static void sendPhoto(Map<String, Object> item) throws Exception {

        HttpClient client = HttpClient.newHttpClient();

        String price = String.format("%.2f",
                Double.parseDouble(item.get("price").toString()));

        String caption =
                "$" + price + "\n" +
                        "📍 " + item.get("storeName") + "\n" +
                        item.get("name") + "\n" +
                        "🗓 " + formatDate(Long.parseLong(item.get("bestBeforeDate").toString()));

        String url = "https://api.telegram.org/bot"
                + System.getenv("TG_TOKEN") + "/sendPhoto";

        String body = "chat_id=" + System.getenv("TG_CHAT_ID")
                + "&photo=" + item.get("imageUrl")
                + "&caption=" + caption;

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        log("Telegram response: " + response.statusCode());
    }

    private static String formatDate(Long ts) {
        return Instant.ofEpochSecond(ts)
                .atZone(ZoneId.of("America/Detroit"))
                .toLocalDate().toString();
    }

    // ================= SEEN =================

    private static Map<String, Instant> loadSeen() {
        Map<String, Instant> map = new HashMap<>();

        try {
            for (String line : Files.readAllLines(Paths.get("seen.txt"))) {
                String[] p = line.split(",");
                map.put(p[0], Instant.parse(p[1]));
            }
        } catch (Exception ignored) {}

        return map;
    }

    private static void saveSeen(Map<String, Instant> seen) throws Exception {

        List<Map.Entry<String, Instant>> entries = new ArrayList<>(seen.entrySet());

        entries.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        if (entries.size() > 1000) {
            entries = entries.subList(0, 1000);
        }

        List<String> lines = new ArrayList<>();

        for (var e : entries) {
            lines.add(e.getKey() + "," + e.getValue());
        }

        Files.write(Paths.get("seen.txt"), lines);
    }

    private static void cleanup(Map<String, Instant> seen) {

        Instant now = Instant.now();
        int before = seen.size();

        seen.entrySet().removeIf(e ->
                e.getValue().isBefore(now.minus(TTL))
        );

        log("🧹 Cleanup removed: " + (before - seen.size()));
    }

    private static void log(String msg) {
        System.out.println(msg);
    }
}
