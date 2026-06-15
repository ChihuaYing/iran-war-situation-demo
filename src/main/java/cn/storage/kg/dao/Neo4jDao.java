package cn.storage.kg.dao;

import cn.storage.kg.config.AppProperties;
import cn.storage.kg.model.NewsEvent;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class Neo4jDao {
    private static final Logger log = LoggerFactory.getLogger(Neo4jDao.class);
    private static final String MAP_SEARCH_CYPHER = "MATCH (n:News) "
            + "WHERE n.latitude IS NOT NULL AND n.longitude IS NOT NULL "
            + "OPTIONAL MATCH (n)-[]->(e) "
            + "WITH n, collect(toLower(coalesce(e.name, ''))) AS entityNames "
            + "WHERE $keyword = '' "
            + "OR toLower(coalesce(n.title, '')) CONTAINS $keyword "
            + "OR toLower(coalesce(n.summary, '')) CONTAINS $keyword "
            + "OR toLower(coalesce(n.source, '')) CONTAINS $keyword "
            + "OR toLower(coalesce(n.locationName, '')) CONTAINS $keyword "
            + "OR any(name IN entityNames WHERE name CONTAINS $keyword) "
            + "RETURN n ORDER BY n.publishTime DESC, n.key DESC LIMIT $limit";

    private final Driver driver;
    private volatile boolean available = true;

    public Neo4jDao(AppProperties properties) {
        AppProperties.Neo4j config = properties.getNeo4j();
        this.driver = GraphDatabase.driver(config.getUri(), AuthTokens.basic(config.getUsername(), config.getPassword()));
    }

    public boolean existsByGuid(String guid) {
        if (!available || !hasText(guid)) {
            return false;
        }
        try (Session session = driver.session()) {
            return session.readTransaction(tx -> {
                Result result = tx.run("MATCH (n:News {guid: $guid}) RETURN count(n) AS c", mapOf("guid", guid));
                return result.single().get("c").asLong() > 0;
            });
        } catch (Exception e) {
            available = false;
            log.warn("Neo4j duplicate check failed: guid={} error={}", guid, e.getMessage());
            return false;
        }
    }

    public void saveKnowledgeGraph(NewsEvent event) {
        if (!available) {
            log.info("Neo4j unavailable, skip graph write: key={}", event.getKey());
            return;
        }
        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                mergeNews(tx, event);
                mergeEntities(tx, event, "Country", event.getCountries(), "MENTIONS_COUNTRY");
                mergeEntities(tx, event, "Organization", event.getOrganizations(), "MENTIONS_ORG");
                mergeEntities(tx, event, "Person", event.getPersons(), "MENTIONS_PERSON");
                mergeEntities(tx, event, "Location", event.getLocations(), "MENTIONS_LOCATION");
                mergeLocation(tx, event);
                mergeAttackRelation(tx, event);
                return null;
            });
            log.info("News summary written to Neo4j: key={} title={}", event.getKey(), event.getTitle());
        } catch (Exception e) {
            available = false;
            log.warn("Neo4j write failed, future graph writes will be skipped: key={} error={}", event.getKey(), e.getMessage());
        }
    }

    public List<NewsEvent> findLatestSummary(int limit) {
        return queryNewsSummary("MATCH (n:News) RETURN n ORDER BY n.publishTime DESC, n.key DESC LIMIT $limit", limit);
    }

    public List<NewsEvent> findMapEvents() {
        return queryNewsSummary("MATCH (n:News) WHERE n.latitude IS NOT NULL AND n.longitude IS NOT NULL "
                + "RETURN n ORDER BY n.publishTime DESC, n.key DESC LIMIT $limit", 200);
    }

    public List<NewsEvent> searchMapEvents(String keyword, int limit) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("keyword", normalizeKeyword(keyword));
        params.put("limit", Math.max(limit, 0));
        return queryNewsSummary(MAP_SEARCH_CYPHER, params);
    }

    public String mapSearchCypher() {
        return MAP_SEARCH_CYPHER;
    }

    private List<NewsEvent> queryNewsSummary(String cypher, int limit) {
        return queryNewsSummary(cypher, mapOf("limit", Math.max(limit, 0)));
    }

    private List<NewsEvent> queryNewsSummary(String cypher, Map<String, Object> params) {
        List<NewsEvent> events = new ArrayList<NewsEvent>();
        if (!available) {
            return events;
        }
        try (Session session = driver.session()) {
            return session.readTransaction(tx -> {
                List<NewsEvent> result = new ArrayList<NewsEvent>();
                Result rows = tx.run(cypher, params);
                while (rows.hasNext()) {
                    Record record = rows.next();
                    result.add(toEvent(record.get("n")));
                }
                return result;
            });
        } catch (Exception e) {
            available = false;
            log.warn("Neo4j summary query failed: error={}", e.getMessage());
            return events;
        }
    }

    private void mergeNews(Transaction tx, NewsEvent event) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("key", event.getKey());
        params.put("id", event.getId());
        params.put("guid", event.getGuid());
        params.put("title", event.getTitle());
        params.put("summary", event.getSummary());
        params.put("source", event.getSource());
        params.put("url", event.getUrl());
        params.put("publishTime", event.getPublishTime() == null ? null : event.getPublishTime().toString());
        params.put("locationName", event.getLocationName());
        params.put("latitude", event.getLatitude());
        params.put("longitude", event.getLongitude());
        tx.run("MERGE (n:News {key: $key}) "
                + "SET n.id = $id, n.guid = $guid, n.title = $title, n.summary = $summary, "
                + "n.source = $source, n.url = $url, n.publishTime = $publishTime, "
                + "n.locationName = $locationName, n.latitude = $latitude, n.longitude = $longitude", params);
    }

    private void mergeEntities(Transaction tx, NewsEvent event, String label, Iterable<String> values, String relation) {
        for (String value : values) {
            if (!hasText(value)) {
                continue;
            }
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("key", event.getKey());
            params.put("name", value.trim());
            tx.run("MATCH (n:News {key: $key}) MERGE (e:" + label + " {name: $name}) MERGE (n)-[:" + relation + "]->(e)", params);
        }
    }

    private void mergeLocation(Transaction tx, NewsEvent event) {
        if (!hasText(event.getLocationName())) {
            return;
        }
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("key", event.getKey());
        params.put("name", event.getLocationName());
        params.put("lat", event.getLatitude());
        params.put("lon", event.getLongitude());
        tx.run("MATCH (n:News {key: $key}) MERGE (l:Location {name: $name}) "
                + "SET l.lat = $lat, l.lon = $lon MERGE (n)-[:OCCURRED_IN]->(l)", params);
    }

    private void mergeAttackRelation(Transaction tx, NewsEvent event) {
        if (event.getCountries().contains("Israel") && event.getCountries().contains("Iran")) {
            tx.run("MERGE (a:Country {name: 'Israel'}) MERGE (b:Country {name: 'Iran'}) MERGE (a)-[:ATTACKS_OR_CONFLICTS_WITH]->(b)");
        }
        if (event.getCountries().contains("Israel") && event.getOrganizations().contains("IRGC")) {
            tx.run("MERGE (a:Country {name: 'Israel'}) MERGE (b:Organization {name: 'IRGC'}) MERGE (a)-[:TARGETS]->(b)");
        }
        if (event.getOrganizations().contains("IRGC") && hasText(event.getLocationName())) {
            tx.run("MERGE (a:Organization {name: 'IRGC'}) MERGE (b:Location {name: $location}) MERGE (a)-[:LOCATED_IN]->(b)",
                    mapOf("location", event.getLocationName()));
        }
    }

    private NewsEvent toEvent(Value node) {
        NewsEvent event = new NewsEvent();
        event.setKey(node.get("key").isNull() ? null : node.get("key").asLong());
        event.setId(node.get("id").isNull() ? String.valueOf(event.getKey()) : node.get("id").asString());
        event.setGuid(node.get("guid").isNull() ? null : node.get("guid").asString());
        event.setTitle(node.get("title").isNull() ? null : node.get("title").asString());
        event.setSummary(node.get("summary").isNull() ? null : node.get("summary").asString());
        event.setSource(node.get("source").isNull() ? null : node.get("source").asString());
        event.setUrl(node.get("url").isNull() ? null : node.get("url").asString());
        event.setPublishTime(parseTime(node.get("publishTime").isNull() ? null : node.get("publishTime").asString()));
        event.setLocationName(node.get("locationName").isNull() ? null : node.get("locationName").asString());
        event.setLatitude(node.get("latitude").isNull() ? null : node.get("latitude").asDouble());
        event.setLongitude(node.get("longitude").isNull() ? null : node.get("longitude").asDouble());
        return event;
    }

    private Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    private LocalDateTime parseTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    @PreDestroy
    public void close() {
        driver.close();
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    private String normalizeKeyword(String value) {
        return hasText(value) ? value.trim().toLowerCase() : "";
    }
}
