package cn.storage.kg.dao;

import cn.storage.kg.config.AppProperties;
import cn.storage.kg.model.ExtractedEntities;
import cn.storage.kg.model.GeoPoint;
import cn.storage.kg.model.LlmNewsDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class LlmDao {
    private static final Logger log = LoggerFactory.getLogger(LlmDao.class);
    private static final DateTimeFormatter SQL_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public LlmDao(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getLlm().getTimeoutSeconds() * 1000);
        factory.setReadTimeout(properties.getLlm().getTimeoutSeconds() * 1000);
        this.restTemplate = new RestTemplate(factory);
    }

    public LlmNewsDecision analyzeNews(String title, String summary, String content, String url, LocalDateTime publishTime) {
        String text = safe(title) + "\n" + safe(summary) + "\n" + safe(content);
        LlmNewsDecision fallback = fallbackDecision(title, summary, url, publishTime, text);
        if (!properties.getLlm().isEnabled()) {
            log.warn("LLM disabled, using local fallback decision.");
            return fallback;
        }
        if (!hasText(properties.getLlm().getApiKey())) {
            throw new IllegalStateException("LLM is enabled but app.llm.api-key is empty. Set LLM_API_KEY or configure app.llm.api-key.");
        }
        try {
            LlmNewsDecision result = callOpenAiCompatibleApi(title, summary, content, url, publishTime);
            if (result.isRelated()) {
                fillMissing(result, fallback);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("LLM news analysis failed", e);
        }
    }

    private LlmNewsDecision callOpenAiCompatibleApi(String title, String summary, String content, String sourceUrl,
                                                   LocalDateTime publishTime) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getLlm().getApiKey());

        String userPrompt = "Decide whether this news is truly related to the Iran war, Iran-Israel/US military conflict, "
                + "missile/drone strikes, nuclear facilities, IRGC, or battlefield/security incidents in Iran. "
                + "Return strict JSON with this schema: {\"related\":true,\"title\":\"\",\"summary\":\"\",\"url\":\"\","
                + "\"publish_time\":\"yyyy-MM-dd HH:mm:ss\",\"location\":\"\","
                + "\"countries\":[],\"organizations\":[],\"persons\":[],\"locations\":[]}. "
                + "If not related, set related=false. Normalize countries and locations to English where possible. "
                + "For location, prefer the speaker/source actor's country or place when the title is a statement or diplomatic quote. "
                + "Examples: 伊朗外长 -> Iran, 以防长/以色列防长 -> Israel, 巴基斯坦总理 -> Pakistan. "
                + "For kinetic battlefield events, prefer the actual event location. If uncertain but related to Iran, use the most specific country mentioned by the speaker/source, not always Iran.\n\n"
                + "title: " + safe(title) + "\nsummary: " + safe(summary) + "\ncontent: " + safe(content)
                + "\nurl: " + safe(sourceUrl) + "\npublish_time: " + formatTime(publishTime);

        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        messages.add(message("system", "You are a strict news relevance classifier for an Iran war situation dashboard. Return JSON only."));
        messages.add(message("user", userPrompt));
        log.info("LLM request prompt preview: {}", abbreviate(userPrompt, 1200));

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", properties.getLlm().getModel());
        body.put("messages", messages);
        body.put("temperature", 0.1);
        body.put("stream", false);

        String endpoint = trimTrailingSlash(properties.getLlm().getBaseUrl()) + "/chat/completions";
        JsonNode response = restTemplate.postForObject(endpoint, new HttpEntity<Map<String, Object>>(body, headers), JsonNode.class);
        String responseContent = response == null ? "" : response.path("choices").path(0).path("message").path("content").asText();
        log.info("LLM response preview: {}", abbreviate(responseContent, 2000));
        return parseDecision(responseContent);
    }

    public Optional<GeoPoint> resolveLocation(String locationName) {
        AppProperties.Llm llm = properties.getLlm();
        if (!llm.isEnabled() || !hasText(llm.getApiKey()) || !hasText(llm.getBaseUrl()) || !hasText(llm.getModel())) {
            return Optional.empty();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(llm.getApiKey());

            String prompt = "What are the latitude and longitude of this city/place? "
                    + "Return strict JSON only with this schema: {\"name\":\"\",\"latitude\":0.0,\"longitude\":0.0}. "
                    + "If the place is ambiguous, choose the best-known city/place in the Iran war or Middle East news context. "
                    + "Place: " + locationName;

            Map<String, Object> body = new HashMap<String, Object>();
            body.put("model", llm.getModel());
            body.put("messages", messages(
                    message("system", "You are a precise geographic coordinate resolver. Return JSON only."),
                    message("user", prompt)));
            body.put("temperature", 0);
            body.put("stream", false);

            String endpoint = trimTrailingSlash(llm.getBaseUrl()) + "/chat/completions";
            JsonNode response = restTemplate.postForObject(endpoint, new HttpEntity<Map<String, Object>>(body, headers), JsonNode.class);
            String content = response == null ? "" : response.path("choices").path(0).path("message").path("content").asText();
            JsonNode node = objectMapper.readTree(extractJson(content));
            String name = hasText(node.path("name").asText()) ? node.path("name").asText() : locationName;
            Double latitude = doubleValue(node, "latitude", "lat");
            Double longitude = doubleValue(node, "longitude", "lon");
            if (latitude == null || longitude == null) {
                return Optional.empty();
            }
            return validPoint(name, latitude, longitude);
        } catch (Exception e) {
            log.warn("LLM geocoding failed: location={} error={}", locationName, e.getMessage());
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

    private LlmNewsDecision parseDecision(String value) throws Exception {
        JsonNode node = objectMapper.readTree(extractJson(value));
        LlmNewsDecision decision = new LlmNewsDecision();
        decision.setRelated(node.path("related").asBoolean(false));
        decision.setTitle(textValue(node, "title"));
        decision.setSummary(textValue(node, "summary"));
        decision.setUrl(textValue(node, "url"));
        decision.setPublishTime(parseTime(textValue(node, "publish_time")));
        decision.setLocationName(firstText(node, "location", "location_name"));
        readArray(node, "countries", decision.getCountries());
        readArray(node, "organizations", decision.getOrganizations());
        readArray(node, "persons", decision.getPersons());
        readArray(node, "locations", decision.getLocations());
        if (hasText(decision.getLocationName()) && !decision.getLocations().contains(decision.getLocationName())) {
            decision.getLocations().add(decision.getLocationName());
        }
        return decision;
    }

    private LlmNewsDecision fallbackDecision(String title, String summary, String url, LocalDateTime publishTime, String text) {
        ExtractedEntities entities = fallbackExtract(text);
        LlmNewsDecision decision = new LlmNewsDecision();
        decision.setRelated(entities.getCountries().contains("Iran") && containsConflictTerm(text));
        decision.setTitle(title);
        decision.setSummary(summary);
        decision.setUrl(url);
        decision.setPublishTime(publishTime);
        decision.setCountries(entities.getCountries());
        decision.setOrganizations(entities.getOrganizations());
        decision.setPersons(entities.getPersons());
        decision.setLocations(entities.getLocations());
        if (!entities.getLocations().isEmpty()) {
            decision.setLocationName(entities.getLocations().get(0));
        }
        return decision;
    }

    private ExtractedEntities fallbackExtract(String text) {
        ExtractedEntities entities = new ExtractedEntities();
        if (text == null) {
            return entities;
        }
        addIfContains(text, entities.getCountries(), "Iran");
        addIfContains(text, entities.getCountries(), "伊朗", "Iran");
        addIfContains(text, entities.getCountries(), "Israel");
        addIfContains(text, entities.getCountries(), "以色列", "Israel");
        addIfContains(text, entities.getCountries(), "United States");
        addIfContains(text, entities.getCountries(), "美国", "United States");
        addIfContains(text, entities.getOrganizations(), "IRGC");
        addIfContains(text, entities.getOrganizations(), "革命卫队", "IRGC");
        addIfContains(text, entities.getOrganizations(), "伊斯兰革命卫队", "IRGC");
        addIfContains(text, entities.getOrganizations(), "IDF");
        addIfContains(text, entities.getOrganizations(), "以色列国防军", "IDF");
        addIfContains(text, entities.getOrganizations(), "IAEA");
        addIfContains(text, entities.getOrganizations(), "国际原子能机构", "IAEA");
        addLocation(text, entities, "Iran", "伊朗");
        addLocation(text, entities, "Tehran", "德黑兰");
        addLocation(text, entities, "Isfahan", "伊斯法罕");
        addLocation(text, entities, "Bandar Abbas", "阿巴斯港");
        addLocation(text, entities, "Tabriz", "大不里士");
        addLocation(text, entities, "Kermanshah", "克尔曼沙阿");
        addLocation(text, entities, "Shiraz", "设拉子");
        addLocation(text, entities, "Mashhad", "马什哈德");
        addLocation(text, entities, "Qom", "库姆");
        addLocation(text, entities, "Ahvaz", "阿瓦士");
        addLocation(text, entities, "Natanz", "纳坦兹");
        addLocation(text, entities, "Bushehr", "布什尔");
        addLocation(text, entities, "Fordow", "福尔多");
        addLocation(text, entities, "Parchin", "帕尔钦");
        return entities;
    }

    private void addLocation(String text, ExtractedEntities entities, String english, String chinese) {
        addIfContains(text, entities.getLocations(), english);
        addIfContains(text, entities.getLocations(), chinese, english);
    }

    private void fillMissing(LlmNewsDecision target, LlmNewsDecision fallback) {
        if (!hasText(target.getTitle())) target.setTitle(fallback.getTitle());
        if (!hasText(target.getSummary())) target.setSummary(fallback.getSummary());
        if (!hasText(target.getUrl())) target.setUrl(fallback.getUrl());
        if (target.getPublishTime() == null) target.setPublishTime(fallback.getPublishTime());
        if (!hasText(target.getLocationName())) target.setLocationName(fallback.getLocationName());
        merge(target.getCountries(), fallback.getCountries());
        merge(target.getOrganizations(), fallback.getOrganizations());
        merge(target.getPersons(), fallback.getPersons());
        merge(target.getLocations(), fallback.getLocations());
    }

    private void readArray(JsonNode node, String field, List<String> target) {
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            return;
        }
        Iterator<JsonNode> iterator = array.elements();
        while (iterator.hasNext()) {
            String value = iterator.next().asText();
            if (hasText(value) && !target.contains(value)) {
                target.add(value);
            }
        }
    }

    private void merge(List<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (hasText(value) && !target.contains(value)) {
                target.add(value);
            }
        }
    }

    private boolean containsConflictTerm(String text) {
        String lower = safe(text).toLowerCase();
        String[] terms = {"war", "strike", "attack", "missile", "drone", "nuclear", "explosion", "conflict",
                "military", "airstrike", "战争", "打击", "袭击", "导弹", "无人机", "核", "爆炸", "冲突", "军事", "空袭"};
        for (String term : terms) {
            if (lower.contains(term.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void addIfContains(String text, List<String> target, String value) {
        if (text.toLowerCase().contains(value.toLowerCase()) && !target.contains(value)) {
            target.add(value);
        }
    }

    private void addIfContains(String text, List<String> target, String keyword, String normalizedValue) {
        if (text.toLowerCase().contains(keyword.toLowerCase()) && !target.contains(normalizedValue)) {
            target.add(normalizedValue);
        }
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new HashMap<String, String>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private List<Map<String, String>> messages(Map<String, String> first, Map<String, String> second) {
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        messages.add(first);
        messages.add(second);
        return messages;
    }

    private String extractJson(String value) {
        if (value == null) {
            return "{}";
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = textValue(node, field);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private Double doubleValue(JsonNode node, String first, String second) {
        JsonNode value = node.hasNonNull(first) ? node.get(first) : node.get(second);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isNumber() ? value.asDouble() : null;
    }

    private LocalDateTime parseTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, SQL_TIME_FORMATTER);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? "" : value.format(SQL_TIME_FORMATTER);
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
