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

    private static int sessionChecked = 0;
    private static int sessionMatched = 0;
    private static int sessionSeen = 0;

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

            log("⏳ Sleeping 2 minutes...");
            Thread.sleep(120_000);
        }
    }

    private static void runOnce() throws Exception {

        Map<String, Instant> seen = loadSeen();

        cleanup(seen);

        String token = resolveToken();

        List<Map<String, Object>> items = fetchItems(token);

        items.sort(
                Comparator
                        .comparing((Map<String, Object> i) -> getStorePriority(i))
                        .thenComparing(i -> Double.parseDouble(i.get("price").toString()))
        );

        int totalChecked = 0;
        int matches = 0;

        Instant now = Instant.now();

        for (Map<String, Object> item : items) {

            totalChecked++;

            if (item.get("id") == null || item.get("name") == null || item.get("price") == null)
                continue;

            String id = item.get("id").toString();
            String name = item.get("name").toString();
            double price = Double.parseDouble(item.get("price").toString());

            if (seen.containsKey(id)) continue;

            if (!isInteresting(name, price)) continue;

            try {
                sendPhoto(item);
                Thread.sleep(800);
            } catch (Exception ignored) {}

            seen.put(id, now);
            matches++;
            sessionSeen++;
        }

        sessionChecked += totalChecked;
        sessionMatched += matches;

        saveSeen(seen);

        sendSummaryIfNeeded(totalChecked, matches, seen.size());
    }

    private static boolean isInteresting(String name, double price) {

        name = name.toLowerCase();

        // ✅ новые фильтры (приоритет выше)
        if (name.contains("mushrooms"))
            return price <= 2;

        if (name.contains("steak"))
            return price <= 10;

        if (name.contains("pork"))
            return price <= 10;

        if (name.contains("ribs"))
            return price <= 10;

        if (name.contains("fish") || name.contains("salmon"))
            return price <= 5;

        // ✅ старая логика (bundle)
        boolean isBundle =
                name.contains("bundle") &&
                        (name.contains("fruit") ||
                                name.contains("veggie") ||
                                name.contains("produce"));

        return isBundle && price <= 5.01;
    }

    private static boolean isHolland(String storeId) {
        return storeId.equals("5f766e5d21b06610425b1851") ||
                storeId.equals("5f766e5d21b06610425b183e");
    }

    private static int getStorePriority(Map<String, Object> item) {
        String storeId = String.valueOf(item.get("storeId"));
        return isHolland(storeId) ? 1 : 100;
    }

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

        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_URL))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

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

        return (String) new ObjectMapper().readValue(res.body(), Map.class).get("access_token");
    }

    private static List<Map<String, Object>> fetchItems(String token) throws Exception {

        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        log("📨 RAW RESPONSE: " + res.body());

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

    private static void sendPhoto(Map<String, Object> item) throws Exception {

        long ts = Long.parseLong(item.get("bestBeforeDate").toString());

        LocalDate dueDate = Instant.ofEpochSecond(ts)
                .atZone(ZoneId.of("America/Detroit"))
                .toLocalDate()
                .plusDays(1); // ✅ фикс -1 день

        LocalDate today = LocalDate.now(ZoneId.of("America/Detroit"));

        String urgency = "";
        if (dueDate.equals(today)) {
            urgency = "🔥 EXPIRES TODAY\n";
        } else if (dueDate.equals(today.plusDays(1))) {
            urgency = "⚠️ Expires tomorrow\n";
        }

        String caption =
                urgency +
                        "$" + item.get("price") + "\n" +
                        item.get("storeName") + "\n" +
                        item.get("name") + "\n" +
                        "Best before: " + dueDate;

        String body = "chat_id=" + System.getenv("TG_CHAT_ID")
                + "&photo=" + item.get("imageUrl")
                + "&caption=" + caption;

        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("https://api.telegram.org/bot"
                                + System.getenv("TG_TOKEN") + "/sendPhoto"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
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

    private static void sendSummaryIfNeeded(int checked, int matches, int seenSize) {

        Instant now = Instant.now();

        if (Duration.between(lastSummaryTime, now).toMinutes() < 60) return;

        lastSummaryTime = now;

        try {
            Duration uptime = Duration.between(startTime, now);

            long hours = uptime.toHours();
            long minutes = uptime.toMinutes() % 60;

            String uptimeStr = hours + " hrs " + minutes + " min";

            String text =
                    (matches > 0 ? "🔥 Active" : "✅ Alive") +
                            " - " + uptimeStr + "\n" +
                            "Checked today: " + sessionChecked + "\n" +
                            "Matched today: " + sessionMatched + "\n" +
                            "Seen today: " + sessionSeen;

            sendMessage(text);

        } catch (Exception ignored) {}
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

        seen.entrySet().removeIf(e ->
                e.getValue().isBefore(now.minus(TTL))
        );
    }

    private static void log(String msg) {
        String time = ZonedDateTime.now(ZoneId.of("America/Detroit"))
                .toLocalTime()
                .withNano(0)
                .toString();

        System.out.println("[" + time + "] " + msg);
    }
}