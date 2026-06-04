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

    private static final Instant startTime = Instant.now();
    private static Instant lastSummaryTime = Instant.now();

    private static final Set<String> printedStores = new HashSet<>();

    public static void main(String[] args) throws Exception {

        log("🚀 Flashfood bot started (infinite mode)");

        while (true) {

            int hour = ZonedDateTime.now(ZoneId.of("America/Detroit")).getHour();

            if (hour >= 20 || hour < 8) {
                log("🌙 Outside working hours → sleeping 5 min...");
                Thread.sleep(300_000);
                continue;
            }

            try {
                runOnce();
            } catch (Exception e) {
                log("❌ Error: " + e.getMessage());
            }

            log("⏳ Sleeping 3 minutes...");
            Thread.sleep(180_000);
        }
    }

    private static void runOnce() throws Exception {

        Map<String, Instant> seen = loadSeen();
        log("📂 Seen loaded: " + seen.size());

        cleanup(seen);
        log("🧹 Seen after cleanup: " + seen.size());

        String token = resolveToken();

        Instant fetchStart = Instant.now();
        List<Map<String, Object>> items = fetchItems(token);
        long fetchMs = Duration.between(fetchStart, Instant.now()).toMillis();

        log("🌐 Fetch time: " + fetchMs + " ms");
        log("📦 Total items: " + items.size());

        items.sort(
                Comparator
                        .comparing((Map<String, Object> i) -> getStorePriority(i))
                        .thenComparing(i -> Double.parseDouble(i.get("price").toString()))
        );

        int totalChecked = 0;
        int matches = 0;
        int skipped = 0;

        Instant now = Instant.now();

        for (Map<String, Object> item : items) {

            totalChecked++;

            if (item.get("id") == null || item.get("name") == null || item.get("price") == null)
                continue;

            String id = item.get("id").toString();
            String name = item.get("name").toString();
            double price = Double.parseDouble(item.get("price").toString());

            if (seen.containsKey(id)) {
                skipped++;
                continue;
            }

            if (!isInteresting(name, price)) continue;

            log("🔥 MATCH: " + name + " | $" + price + " | " + item.get("storeName"));

            try {
                sendPhoto(item);
                log("✅ Sent: " + name);
                Thread.sleep(800);
            } catch (Exception e) {
                log("❌ Telegram failed: " + e.getMessage());
            }

            seen.put(id, now);
            matches++;
        }

        saveSeen(seen);

        log("📊 Checked: " + totalChecked);
        log("⏭ Skipped: " + skipped);

        if (matches == 0) {
            log("✅ Alive | Seen: " + seen.size());
        } else {
            log("✅ Matches: " + matches);
        }

        sendSummaryIfNeeded(totalChecked, matches, seen.size());
    }

    // ================= FILTER =================

    private static boolean isInteresting(String name, double price) {

        name = name.toLowerCase();

        boolean isBundle =
                name.contains("bundle") &&
                        (name.contains("fruit") ||
                                name.contains("veggie") ||
                                name.contains("produce"));

        return isBundle && price <= 5.01;
    }

    // ================= PRIORITY =================

    private static boolean isHolland(String storeId) {
        return storeId.equals("5f766e5d21b06610425b1851") ||
                storeId.equals("5f766e5d21b06610425b183e");
    }

    private static int getStorePriority(Map<String, Object> item) {
        String storeId = String.valueOf(item.get("storeId"));
        return isHolland(storeId) ? 1 : 100;
    }

    // ================= TOKEN =================

    private static String resolveToken() throws Exception {
        try {
            String token = refreshToken();
            log("🔑 Token: REFRESH");
            return token;
        } catch (Exception e) {
            log("⚠️ Refresh failed → LOGIN");
            return loginToken();
        }
    }

    private static String refreshToken() throws Exception {

        String body = "grant_type=refresh_token"
                + "&client_id=" + System.getenv("CLIENT_ID")
                + "&refresh_token=" + System.getenv("REFRESH_TOKEN");

        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_URL))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        log("🔐 Refresh status: " + res.statusCode());

        return (String) new ObjectMapper().readValue(res.body(), Map.class).get("access_token");
    }

    private static String loginToken() throws Exception {

        String body = "{ \"audience\":\"https://api.flashfood.com/\"," +
                "\"client_id\":\"" + System.getenv("CLIENT_ID") + "\"," +
                "\"grant_type\":\"http://auth0.com/oauth/grant-type/password-realm\"," +
                "\"password\":\"" + System.getenv("FF_PASSWORD") + "\"," +
                "\"realm\":\"flashfood-shoppers\"," +
                "\"scope\":\"offline_access openid profile email\"," +
                "\"username\":\"" + System.getenv("FF_USERNAME") + "\"}";

        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_URL))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        log("🔐 Login status: " + res.statusCode());

        return (String) new ObjectMapper().readValue(res.body(), Map.class).get("access_token");
    }

    // ================= FETCH =================

    private static List<Map<String, Object>> fetchItems(String token) throws Exception {

        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        Map<String, Object> root = new ObjectMapper().readValue(res.body(), Map.class);

        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> stores = (List<Map<String, Object>>) root.get("data");

        for (Map<String, Object> store : stores) {

            String storeName = String.valueOf(store.get("name"));

            List<Map<String, Object>> items =
                    (List<Map<String, Object>>) store.get("items");

            if (items == null) continue;

            for (Map<String, Object> item : items) {
                Map<String, Object> enriched = new HashMap<>(item);
                enriched.put("storeName", storeName);
                result.add(enriched);
            }
        }

        return result;
    }

    // ================= TELEGRAM =================

    private static void sendPhoto(Map<String, Object> item) throws Exception {

        String caption =
                "$" + item.get("price") + "\n" +
                        item.get("storeName") + "\n" +
                        item.get("name");

        String body = "chat_id=" + System.getenv("TG_CHAT_ID")
                + "&photo=" + item.get("imageUrl")
                + "&caption=" + caption;

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("https://api.telegram.org/bot"
                                + System.getenv("TG_TOKEN") + "/sendPhoto"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        log("📡 Telegram: " + response.statusCode());
    }

    private static void sendMessage(String text) throws Exception {

        String body = "chat_id=" + System.getenv("TG_CHAT_ID")
                + "&text=" + text;

        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("https://api.telegram.org/bot"
                                + System.getenv("TG_TOKEN") + "/sendMessage"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    // ================= SUMMARY =================

    private static void sendSummaryIfNeeded(int checked, int matches, int seenSize) {

        Instant now = Instant.now();

        if (Duration.between(lastSummaryTime, now).toMinutes() < 30) return;

        lastSummaryTime = now;

        try {
            String text =
                    (matches > 0 ? "🔥 Active" : "✅ Alive") + "\n" +
                            "Checked: " + checked + "\n" +
                            "Matched: " + matches + "\n" +
                            "Seen: " + seenSize;

            sendMessage(text);
            log("📨 Summary sent");

        } catch (Exception e) {
            log("❌ Summary error: " + e.getMessage());
        }
    }

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

        log("🧹 Removed: " + (before - seen.size()));
    }

    private static void log(String msg) {
        String time = ZonedDateTime.now(ZoneId.of("America/Detroit"))
                .toLocalTime()
                .withNano(0)
                .toString();

        System.out.println("[" + time + "] " + msg);
    }
}