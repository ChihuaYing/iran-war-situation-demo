package cn.storage.kg.service;

import cn.storage.kg.dao.IginxDao;
import cn.storage.kg.dao.LlmDao;
import cn.storage.kg.dao.Neo4jDao;
import cn.storage.kg.model.GeoPoint;
import cn.storage.kg.model.LlmNewsDecision;
import cn.storage.kg.model.NewsEvent;
import cn.storage.kg.model.NewsSearchResult;
import cn.storage.kg.model.RawNews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NewsService {
    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private final IginxDao iginxDao;
    private final Neo4jDao neo4jDao;
    private final LlmDao llmDao;
    private final GeoLocationService geoLocationService;
    private final NewsWebSocketService webSocketService;
    private final Set<String> processingGuids = ConcurrentHashMap.newKeySet();
    private final Set<String> rejectedGuids = ConcurrentHashMap.newKeySet();

    public NewsService(IginxDao iginxDao, Neo4jDao neo4jDao, LlmDao llmDao,
                       GeoLocationService geoLocationService, NewsWebSocketService webSocketService) {
        this.iginxDao = iginxDao;
        this.neo4jDao = neo4jDao;
        this.llmDao = llmDao;
        this.geoLocationService = geoLocationService;
        this.webSocketService = webSocketService;
    }

    public void processRawNews(RawNews rawNews) {
        String guid = normalizeGuid(rawNews);
        if (!processingGuids.add(guid)) {
            log.info("News is already being processed, skip duplicate before LLM: source={} title={}", rawNews.getSource(), rawNews.getTitle());
            return;
        }
        try {
            processRawNewsOnce(rawNews, guid);
        } finally {
            processingGuids.remove(guid);
        }
    }

    private void processRawNewsOnce(RawNews rawNews, String guid) {
        if (rejectedGuids.contains(guid)) {
            log.info("News was previously rejected by LLM, skip duplicate before LLM: source={} title={}",
                    rawNews.getSource(), rawNews.getTitle());
            return;
        }
        if (iginxDao.existsByGuid(guid) || neo4jDao.existsByGuid(guid)) {
            log.info("News already exists, skip duplicate: source={} title={}", rawNews.getSource(), rawNews.getTitle());
            return;
        }

        LlmNewsDecision assessment;
        try {
            assessment = llmDao.analyzeNews(
                    rawNews.getTitle(), rawNews.getSummary(), rawNews.getContent(), rawNews.getLink(), rawNews.getPublishTime());
        } catch (Exception e) {
            log.warn("LLM analysis failed, skip news: source={} title={} error={}",
                    rawNews.getSource(), rawNews.getTitle(), e.getMessage());
            return;
        }
        if (!assessment.isRelated()) {
            rejectedGuids.add(guid);
            log.info("LLM marked RSS item as not Iran-war related, cached rejected guid: source={} title={}",
                    rawNews.getSource(), rawNews.getTitle());
            return;
        }

        NewsEvent event = toNewsEvent(rawNews, guid, assessment);
        fillLocation(event);

        long key = iginxDao.saveNewsWithNextKey(event);
        event.setKey(key);
        event.setId(String.valueOf(key));

        neo4jDao.saveKnowledgeGraph(event);
        webSocketService.pushNewEvent(event);
        log.info("News processed and pushed: key={} source={} location={}", event.getKey(), event.getSource(), event.getLocationName());
    }

    public List<NewsEvent> latest(int limit) {
        return neo4jDao.findLatestSummary(limit);
    }

    public Optional<NewsEvent> detail(String id) {
        return iginxDao.findById(id);
    }

    public List<NewsEvent> mapEvents() {
        return neo4jDao.findMapEvents();
    }

    public NewsSearchResult searchMapEvents(String keyword, int limit) {
        String normalized = hasText(keyword) ? keyword.trim() : "";
        List<NewsEvent> events = neo4jDao.searchMapEvents(normalized, limit);
        return new NewsSearchResult(normalized, neo4jDao.mapSearchCypher(), events);
    }

    private NewsEvent toNewsEvent(RawNews rawNews, String guid, LlmNewsDecision assessment) {
        NewsEvent event = new NewsEvent();
        event.setGuid(guid);
        event.setTitle(firstText(assessment.getTitle(), rawNews.getTitle()));
        event.setSummary(firstText(assessment.getSummary(), rawNews.getSummary()));
        event.setContent(rawNews.getContent());
        event.setSource(rawNews.getSource());
        event.setPublishTime(assessment.getPublishTime() == null ? rawNews.getPublishTime() : assessment.getPublishTime());
        event.setUrl(firstText(assessment.getUrl(), rawNews.getLink()));
        event.setLocationName(assessment.getLocationName());
        event.setCountries(assessment.getCountries());
        event.setOrganizations(assessment.getOrganizations());
        event.setPersons(assessment.getPersons());
        event.setLocations(assessment.getLocations());
        event.setCreateTime(LocalDateTime.now());
        return event;
    }

    private void fillLocation(NewsEvent event) {
        Optional<GeoPoint> point = geoLocationService.resolveFirst(
                event.getLocations(),
                event.getTitle() + "\n" + event.getSummary());
        if (point.isPresent()) {
            GeoPoint geoPoint = point.get();
            event.setLocationName(geoPoint.getName());
            event.setLatitude(geoPoint.getLatitude());
            event.setLongitude(geoPoint.getLongitude());
        }
    }

    private String normalizeGuid(RawNews rawNews) {
        if (hasText(rawNews.getGuid())) {
            return rawNews.getGuid();
        }
        return md5((rawNews.getTitle() == null ? "" : rawNews.getTitle()) + (rawNews.getLink() == null ? "" : rawNews.getLink()));
    }

    private String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b & 0xff));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate MD5", e);
        }
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
