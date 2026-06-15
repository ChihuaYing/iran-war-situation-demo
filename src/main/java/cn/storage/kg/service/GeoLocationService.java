package cn.storage.kg.service;

import cn.storage.kg.config.AppProperties;
import cn.storage.kg.dao.LlmDao;
import cn.storage.kg.model.GeoPoint;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class GeoLocationService {
    private static final Logger log = LoggerFactory.getLogger(GeoLocationService.class);
    private static final String DEFAULT_LOCATION = "Iran";

    private final AppProperties properties;
    private final LlmDao llmDao;
    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, GeoPoint> locations = new HashMap<String, GeoPoint>();

    public GeoLocationService(AppProperties properties, LlmDao llmDao) {
        this.properties = properties;
        this.llmDao = llmDao;
        register("Iran", 32.4279, 53.6880, "伊朗");
        register("Israel", 31.0461, 34.8516, "以色列", "以方");
        register("Pakistan", 30.3753, 69.3451, "巴基斯坦", "巴方");
        register("United States", 39.8283, -98.5795, "美国", "美方");
        register("China", 35.8617, 104.1954, "中国", "中方");
        register("Russia", 61.5240, 105.3188, "俄罗斯", "俄方");
        register("Tehran", 35.6892, 51.3890, "德黑兰");
        register("Isfahan", 32.6546, 51.6680, "伊斯法罕");
        register("Bandar Abbas", 27.1832, 56.2666, "阿巴斯港");
        register("Tabriz", 38.0800, 46.2919, "大不里士");
        register("Kermanshah", 34.3142, 47.0650, "克尔曼沙阿");
        register("Shiraz", 29.5926, 52.5836, "设拉子");
        register("Mashhad", 36.2605, 59.6168, "马什哈德");
        register("Qom", 34.6416, 50.8746, "库姆");
        register("Ahvaz", 31.3183, 48.6706, "阿瓦士");
        register("Natanz", 33.5112, 51.9188, "纳坦兹");
        register("Bushehr", 28.9234, 50.8203, "布什尔");
        register("Karaj", 35.8400, 50.9391, "卡拉季");
        register("Rasht", 37.2808, 49.5832, "拉什特");
        register("Arak", 34.0917, 49.6892, "阿拉克");
        register("Fordow", 34.8840, 50.9950, "福尔多");
        register("Parchin", 35.5200, 51.7700, "帕尔钦");
        register("Tel Aviv", 32.0853, 34.7818, "特拉维夫");
        register("Jerusalem", 31.7683, 35.2137, "耶路撒冷");
        register("Haifa", 32.7940, 34.9896, "海法");
        register("Islamabad", 33.6844, 73.0479, "伊斯兰堡");
    }

    public Optional<GeoPoint> resolveFirst(List<String> locationNames, String text) {
        Optional<GeoPoint> speakerPoint = resolveSpeakerLocation(text);
        if (speakerPoint.isPresent()) {
            return speakerPoint;
        }
        if (locationNames != null) {
            for (String location : locationNames) {
                Optional<GeoPoint> point = resolve(location);
                if (point.isPresent()) {
                    return point;
                }
            }
        }
        Optional<GeoPoint> textPoint = resolveByText(text);
        return textPoint.isPresent() ? textPoint : resolve(DEFAULT_LOCATION);
    }

    public Optional<GeoPoint> resolve(String locationName) {
        if (locationName == null) {
            return Optional.empty();
        }
        String normalized = normalize(locationName);
        GeoPoint localPoint = locations.get(normalized);
        if (localPoint != null) {
            return Optional.of(localPoint);
        }
        Optional<GeoPoint> amapPoint = resolveByAmap(locationName);
        amapPoint.ifPresent(point -> locations.put(normalized, point));
        if (amapPoint.isPresent()) {
            return amapPoint;
        }
        Optional<GeoPoint> llmPoint = llmDao.resolveLocation(locationName);
        llmPoint.ifPresent(point -> locations.put(normalized, point));
        return llmPoint;
    }

    private Optional<GeoPoint> resolveSpeakerLocation(String text) {
        String lower = normalize(text);
        if (!hasText(lower)) {
            return Optional.empty();
        }
        if (containsAny(lower, "以防长", "以总理", "以军", "以色列防长", "以色列总理", "以色列军方", "israeli")) {
            return resolve("Israel");
        }
        if (containsAny(lower, "伊朗外长", "伊朗总统", "伊朗最高领袖", "伊朗军方", "伊朗革命卫队", "iranian")) {
            return resolve("Iran");
        }
        if (containsAny(lower, "巴基斯坦总理", "巴基斯坦外长", "巴方", "pakistani")) {
            return resolve("Pakistan");
        }
        if (containsAny(lower, "美国总统", "美国国务卿", "美防长", "美方", "us ", "u.s.", "american")) {
            return resolve("United States");
        }
        return Optional.empty();
    }

    private Optional<GeoPoint> resolveByText(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String lowerText = normalize(text);
        for (Map.Entry<String, GeoPoint> entry : locations.entrySet()) {
            if (lowerText.contains(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private Optional<GeoPoint> resolveByAmap(String locationName) {
        String key = properties.getAmap().getWebApiKey();
        if (!hasText(key)) {
            return Optional.empty();
        }
        try {
            String url = "https://restapi.amap.com/v3/geocode/geo?key={key}&address={address}";
            JsonNode response = restTemplate.getForObject(url, JsonNode.class, key, locationName);
            JsonNode first = response == null ? null : response.path("geocodes").path(0);
            if (first == null || !first.hasNonNull("location")) {
                return Optional.empty();
            }
            String[] parts = first.path("location").asText().split(",");
            if (parts.length != 2) {
                return Optional.empty();
            }
            double longitude = Double.parseDouble(parts[0]);
            double latitude = Double.parseDouble(parts[1]);
            return validPoint(locationName, latitude, longitude);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<GeoPoint> validPoint(String name, double latitude, double longitude) {
        if (latitude == 0.0 && longitude == 0.0) {
            return Optional.empty();
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            return Optional.empty();
        }
        return Optional.of(new GeoPoint(name, latitude, longitude));
    }

    private void register(String name, double lat, double lon, String... aliases) {
        GeoPoint point = new GeoPoint(name, lat, lon);
        locations.put(normalize(name), point);
        for (String alias : aliases) {
            locations.put(normalize(alias), point);
        }
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(normalize(term))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
