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

    public static void main(String[] args) throws Exception {


        log("🚀 Starting Flashfood service (Docker mode)...");

        while (true) {

            int hour = ZonedDateTime.now(ZoneId.of("America/Detroit")).getHour();

            try {

                if (hour >= 20 || hour < 8) {
                    log("🌙 Outside working hours → sleeping...");
                    Thread.sleep(300_000); // 5 min
                    continue;
                }

                runOnce();

            } catch (Exception e) {
                log("❌ Error: " + e.getMessage());
            }

            log("⏳ Waiting 3 minutes...");
            Thread.sleep(180_000);
        }
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
                        .thenComparing(i -> Double.parseDouble(i.get("price").toString()))
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

                boolean success = sendPhoto(item);

                if (success) {
                    log("✅ Sent: " + name);
                    matches++;
                    seen.put(id, now);
                } else {
                    log("❌ Failed: " + name);
                }

                Thread.sleep(800);
            }
        }

        saveSeen(seen);

        log("📊 Checked: " + totalChecked);

        if (matches == 0) {
            log("ℹ️ No new deals");
        } else {
            log("✅ Sent: " + matches);
        }

        sendSummaryIfNeeded(totalChecked, matches, seen.size());
    }

    // ================= SUMMARY =================

    private static void sendSummaryIfNeeded(int checked, int matches, int seenSize) {

        Instant now = Instant.now();

        if (Duration.between(lastSummaryTime, now).toMinutes() < 30) {
            return;
        }

        lastSummaryTime = now;

        try {
            String uptime = formatDuration(Duration.between(startTime, now));

            String status = matches > 0 ? "🔥 Active" : "✅ Alive";

            String text =
                    status + "\n" +
                            "Checked: " + checked + "\n" +
                            "Matched: " + matches + "\n" +
                            "Seen: " + seenSize + "\n" +
                            "Uptime: " + uptime + "\n" +
                            "Time: " + ZonedDateTime.now(ZoneId.of("America/Detroit"))
                            .toLocalTime().withNano(0);

            sendMessage(text);

        } catch (Exception e) {
            log("❌ Summary error: " + e.getMessage());
        }
    }

    private static String formatDuration(Duration d) {
        long h = d.toHours();
        long m = d.toMinutes() % 60;
        return String.format("%02d:%02d", h, m);
    }

    // ================= TOKEN =================

    private static String resolveToken() throws Exception {
        try {
            return refreshToken();
        } catch (Exception e) {
            return loginToken();
        }
    }

    private static String refreshToken() throws Exception {

        String body = "grant_type=refresh_token"
                + "&client_id=" + System.getenv("CLIENT_ID")
                + "&refresh_token=" + System.getenv("REFRESH_TOKEN");

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_URL))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        Map json = new ObjectMapper().readValue(resp.body(), Map.class);
        return (String) json.get("access_token");
    }

    private static String loginToken() throws Exception {

        String body = "{ \"client_id\":\"" + System.getenv("CLIENT_ID") + "\"," +
                "\"username\":\"" + System.getenv("FF_USERNAME") + "\"," +
                "\"password\":\"" + System.getenv("FF_PASSWORD") + "\"" +
                "}";

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_URL))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        Map json = new ObjectMapper().readValue(resp.body(), Map.class);
        return (String) json.get("access_token");
    }

    // ================= FETCH =================

    private static List<Map<String, Object>> fetchItems(String token) throws Exception {

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        Map<String, Object> root = new ObjectMapper().readValue(resp.body(), Map.class);

        List<Map<String, Object>> result = new ArrayList<>();

        List<Map<String, Object>> stores = (List<Map<String, Object>>) root.get("data");

        for (Map<String, Object> store : stores) {

            List<Map<String, Object>> items =
                    (List<Map<String, Object>>) store.get("items");

            if (items == null) continue;

            for (Map<String, Object> item : items) {
                item.put("storeName", store.get("name"));
                result.add(item);
            }
        }

        return result;
    }

    // ================= FILTER =================

    private static boolean isInteresting(String name, double price, Map<String, Object> item) {

        name = name.toLowerCase();
        String storeId = item.get("storeId").toString();

        boolean isProduce =
                name.contains("bundle");

        if (isProduce) {
            return isHolland(storeId) ? price <= 5.01 : price <= 3.01;
        }

        if (name.contains("mushrooms")) return price <= 2;

        return false;
    }

    private static boolean isHolland(String id) {
        return id.equals("5f766e5d21b06610425b1851") ||
                id.equals("5f766e5d21b06610425b183e");
    }

    private static int getStorePriority(Map<String, Object> item) {
        return isHolland(item.get("storeId").toString()) ? 1 : 100;
    }

    // ================= TELEGRAM =================

    private static boolean sendPhoto(Map<String, Object> item) {

        try {
            String caption =
                    "$" + item.get("price") + "\n" +
                            item.get("storeName") + "\n" +
                            item.get("name");

            String body = "chat_id=" + System.getenv("TG_CHAT_ID")
                    + "&photo=" + item.get("imageUrl")
                    + "&caption=" + caption;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot"
                            + System.getenv("TG_TOKEN") + "/sendPhoto"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            for (int i = 0; i < 3; i++) {

                HttpResponse<String> res = HttpClient.newHttpClient()
                        .send(req, HttpResponse.BodyHandlers.ofString());

                if (res.statusCode() == 200) return true;

                Thread.sleep(1000);
            }

        } catch (Exception ignored) {}

        return false;
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

        List<Map.Entry<String, Instant>> list =
                new ArrayList<>(seen.entrySet());

        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        if (list.size() > 1000) {
            list = list.subList(0, 1000);
        }

        List<String> lines = new ArrayList<>();

        for (var e : list) {
            lines.add(e.getKey() + "," + e.getValue());
        }

        Files.write(Paths.get("seen.txt"), lines);
    }

    private static void cleanup(Map<String, Instant> seen) {

        Instant now = Instant.now();

        seen.entrySet().removeIf(e ->
                e.getValue().isBefore(now.minus(TTL))
        );
    }

    private static void log(String msg) {
        System.out.println(msg);
    }
}