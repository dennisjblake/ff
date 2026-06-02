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

        log("🚀 Starting Flashfood monitor...");

        Map<String, Instant> seen = loadSeen();
        cleanup(seen);

        String token = resolveToken();

        List<Map<String, Object>> items = fetchItems(token);

        log("📦 Total items: " + items.size());

        // ✅ сортировка (Holland first)
        items.sort(Comparator.comparing(Main::getStorePriority));

        Instant now = Instant.now();

        int matches = 0;

        for (Map<String, Object> item : items) {

            String id = item.get("id").toString();
            String name = item.get("name").toString();
            double price = Double.parseDouble(item.get("price").toString());

            log("🔎 Checking: " + name + " ($" + price + ")");

            if (seen.containsKey(id)) continue;

            if (isInteresting(name, price)) {

                log("🔥 MATCH: " + name);

                try {
                    sendPhoto(item);
                    log("✅ Sent: " + name);
                    Thread.sleep(300); // ✅ anti-rate-limit
                } catch (Exception e) {
                    log("❌ Telegram failed: " + e.getMessage());
                }

                matches++;
            }

            seen.put(id, now);
        }

        saveSeen(seen);

        log("✅ Done. Matches: " + matches);
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
                .header("Accept", "application/json")
                .GET()
                .build();

        String resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString()).body();

        Map<String, Object> root = new ObjectMapper().readValue(resp, Map.class);

        List<Map<String, Object>> result = new ArrayList<>();

        List<Map<String, Object>> stores = (List<Map<String, Object>>) root.get("data");

        for (Map<String, Object> store : stores) {

            String storeName = store.get("name").toString();

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

    // ================= FILTER =================

    private static boolean isInteresting(String name, double price) {

        name = name.toLowerCase();

        if ((name.contains("fruit bundle")
                || name.contains("veggie bundle")
                || name.contains("mixed produce bundle")
                || name.contains("produce bundle"))
        )
            return price <= 3.01;

        if ((name.contains("kombucha")
        ))
            return price <= 2;
        if ((name.contains("mushrooms")
        ))
            return price <= 2;
        if ((name.contains("pork loin")
        ))
            return price <= 10;

        if ((name.contains("fish") || name.contains("salmon")))
            return price <= 5;

        return false;
    }

    // ================= SORT =================

    private static int getStorePriority(Map<String, Object> item) {

        String storeId = item.get("storeId").toString();

        if (storeId.equals("5f766e5d21b06610425b1851") ||
                storeId.equals("5f766e5d21b06610425b183e"))
            return 1;

        if (storeId.equals("5f766e5d21b06610425b1842")) return 2;
        if (storeId.equals("5f766e5d21b06610425b1854")) return 3;
        if (storeId.equals("5f766e5d21b06610425b1840")) return 4;
        if (storeId.equals("5f766e5d21b06610425b1848")) return 5;

        return 100;
    }

    // ================= TELEGRAM =================

    private static void sendPhoto(Map<String, Object> item) throws Exception {

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String name = item.get("name").toString();
        double price = Double.parseDouble(item.get("price").toString());
        String store = item.get("storeName").toString();
        int qty = Integer.parseInt(item.get("quantityAvailable").toString());

        Long bestBefore = Long.parseLong(item.get("bestBeforeDate").toString());
        String expiry = formatDate(bestBefore);

        List<String> images = (List<String>) item.get("imageGallery");

        String imageUrl = (images != null && !images.isEmpty())
                ? images.get(0)
                : item.get("imageUrl").toString();

        String caption =
                "🔥 DEAL\n\n" +
                        name + "\n" +
                        "$" + price + "\n" +
                        "📍 " + store + "\n" +
                        "📦 Qty: " + qty + "\n" +
                        "🗓 Best before: " + expiry;

        String url = "https://api.telegram.org/bot"
                + System.getenv("TG_TOKEN")
                + "/sendPhoto";

        String body = "chat_id=" + System.getenv("TG_CHAT_ID")
                + "&photo=" + imageUrl
                + "&caption=" + caption;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        client.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private static String formatDate(Long ts) {
        Instant i = Instant.ofEpochSecond(ts);
        return i.atZone(ZoneId.of("America/Detroit")).toLocalDate().toString();
    }

    // ================= SEEN =================

    private static Map<String, Instant> loadSeen() throws Exception {
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

        List<String> lines = new ArrayList<>();

        for (var e : seen.entrySet()) {
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