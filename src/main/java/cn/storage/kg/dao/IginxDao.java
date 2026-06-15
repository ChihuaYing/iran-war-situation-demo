package cn.storage.kg.dao;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.storage.kg.config.AppProperties;
import cn.storage.kg.model.NewsEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class IginxDao {
    private static final Logger log = LoggerFactory.getLogger(IginxDao.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<List<String>>() {};

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, NewsEvent> cache = new ConcurrentHashMap<String, NewsEvent>();
    private final Map<String, String> guidIndex = new ConcurrentHashMap<String, String>();
    private Session session;
    private boolean available = true;

    public IginxDao(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean existsByGuid(String guid) {
        return guid != null && guidIndex.containsKey(guid);
    }

    public synchronized long saveNewsWithNextKey(NewsEvent event) {
        long key = getMaxNewsKey() + 1;
        event.setKey(key);
        event.setId(String.valueOf(key));
        writeToIginx(event);
        cache.put(event.getId(), event);
        if (event.getGuid() != null) {
            guidIndex.put(event.getGuid(), event.getId());
        }
        return key;
    }

    public synchronized long getMaxNewsKey() {
        long max = -1L;
        for (NewsEvent event : cache.values()) {
            if (event.getKey() != null) {
                max = Math.max(max, event.getKey());
            }
        }
        try {
            SessionExecuteSqlResult result = executeSql("select last(*) from " + newsPath() + ";");
            long[] keys = result.getKeys();
            if (keys != null && keys.length > 0) {
                max = Math.max(max, keys[keys.length - 1]);
            }
        } catch (Exception e) {
            log.warn("Failed to query maximum IGinX key, using cached maximum: {}", e.getMessage());
        }
        return max;
    }

    public Optional<NewsEvent> findById(String id) {
        NewsEvent cached = cache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            return queryByKey(Long.parseLong(id));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void writeToIginx(NewsEvent event) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("key", event.getKey());
        values.put("guid", event.getGuid());
        values.put("title", event.getTitle());
        values.put("summary", event.getSummary());
        values.put("content", event.getContent());
        values.put("source", event.getSource());
        values.put("publish_time", formatTime(event.getPublishTime()));
        values.put("url", event.getUrl());
        values.put("location_name", event.getLocationName());
        values.put("latitude", event.getLatitude());
        values.put("longitude", event.getLongitude());
        try {
            values.put("countries", objectMapper.writeValueAsString(event.getCountries()));
            values.put("organizations", objectMapper.writeValueAsString(event.getOrganizations()));
            values.put("persons", objectMapper.writeValueAsString(event.getPersons()));
            values.put("locations", objectMapper.writeValueAsString(event.getLocations()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize news entities", e);
        }
        values.put("create_time", formatTime(event.getCreateTime()));

        StringBuilder columns = new StringBuilder();
        StringBuilder row = new StringBuilder();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (columns.length() > 0) {
                columns.append(", ");
                row.append(", ");
            }
            columns.append(entry.getKey());
            row.append(sqlValue(entry.getValue()));
        }
        executeSql("insert into " + newsPath() + " (" + columns + ") values (" + row + ");");
        log.info("News written to IGinX: key={} path={}", event.getKey(), newsPath());
    }

    private Optional<NewsEvent> queryByKey(long key) {
        SessionExecuteSqlResult result = executeSql("select * from " + newsPath() + " where key = " + key + ";");
        if (result.getKeys() == null || result.getKeys().length == 0
                || result.getValues() == null || result.getValues().isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> row = rowAsMap(result);
            NewsEvent event = new NewsEvent();
            event.setKey(key);
            event.setId(String.valueOf(key));
            event.setGuid(asString(row.get("guid")));
            event.setTitle(asString(row.get("title")));
            event.setSummary(asString(row.get("summary")));
            event.setContent(asString(row.get("content")));
            event.setSource(asString(row.get("source")));
            event.setPublishTime(parseTime(asString(row.get("publish_time"))));
            event.setUrl(asString(row.get("url")));
            event.setLocationName(asString(row.get("location_name")));
            event.setLatitude(asDouble(row.get("latitude")));
            event.setLongitude(asDouble(row.get("longitude")));
            event.setCountries(parseList(asString(row.get("countries"))));
            event.setOrganizations(parseList(asString(row.get("organizations"))));
            event.setPersons(parseList(asString(row.get("persons"))));
            event.setLocations(parseList(asString(row.get("locations"))));
            event.setCreateTime(parseTime(asString(row.get("create_time"))));
            cache.put(event.getId(), event);
            return Optional.of(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to map IGinX result", e);
        }
    }

    public SessionExecuteSqlResult executeSql(String sql) {
        ensureConnected();
        try {
            return session.executeSql(sql);
        } catch (SessionException e) {
            throw new IllegalStateException("Failed to execute IGinX SQL: " + sql, e);
        }
    }

    private synchronized void ensureConnected() {
        if (session != null) {
            return;
        }
        if (!available) {
            throw new IllegalStateException("IGinX is unavailable");
        }
        AppProperties.Iginx config = properties.getIginx();
        try {
            session = new Session(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
            session.openSession();
        } catch (Exception e) {
            available = false;
            session = null;
            throw new IllegalStateException("Failed to connect to IGinX", e);
        }
    }

    private Map<String, Object> rowAsMap(SessionExecuteSqlResult result) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        List<Object> values = result.getValues().get(0);
        for (int i = 0; i < result.getPaths().size() && i < values.size(); i++) {
            String path = result.getPaths().get(i);
            row.put(path.substring(path.lastIndexOf('.') + 1), values.get(i));
        }
        return row;
    }

    private String newsPath() {
        String path = properties.getIginx().getNewsPathPrefix();
        return path == null ? "sys.news" : path.replaceAll("[^A-Za-z0-9_.]+", "_");
    }

    private String sqlValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private List<String> parseList(String value) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<String>();
        }
        return objectMapper.readValue(value, STRING_LIST);
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private LocalDateTime parseTime(String value) {
        try {
            return value == null ? null : LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}
