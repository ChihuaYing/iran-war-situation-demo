package cn.storage.kg.service;

import cn.storage.kg.config.AppProperties;
import cn.storage.kg.model.RawNews;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
public class NewsCollector implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(NewsCollector.class);

    private final AppProperties properties;
    private final NewsService newsService;
    private final ExecutorService newsExecutor;

    public NewsCollector(AppProperties properties, NewsService newsService) {
        this.properties = properties;
        this.newsService = newsService;
        this.newsExecutor = Executors.newFixedThreadPool(Math.max(1, properties.getCollector().getWorkerThreads()));
    }

    @Scheduled(fixedDelayString = "${app.collector.fixed-delay-ms:30000}")
    public void collect() {
        if (!properties.getCollector().isEnabled()) {
            log.info("News collector disabled: app.collector.enabled=false");
            return;
        }
        long startedAt = System.currentTimeMillis();
        int totalEntries = 0;
        int totalMatched = 0;
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (AppProperties.RssSource source : properties.getCollector().getRssSources()) {
            if (!hasText(source.getUrl())) {
                continue;
            }
            SourceCollectStats stats = collectSource(source, futures);
            totalEntries += stats.totalEntries;
            totalMatched += stats.matchedEntries;
        }
        waitForTasks(futures);
        log.info("News collection finished: entries={} keywordMatched={} submitted={} elapsed={}ms",
                totalEntries, totalMatched, futures.size(), System.currentTimeMillis() - startedAt);
    }

    private SourceCollectStats collectSource(AppProperties.RssSource source, List<Future<?>> futures) {
        SourceCollectStats stats = new SourceCollectStats();
        log.info("Fetching RSS: source={} url={}", source.getName(), source.getUrl());
        try (InputStream inputStream = openRssStream(source.getUrl())) {
            SyndFeed feed = new SyndFeedInput().build(new XmlReader(inputStream));
            stats.totalEntries = feed.getEntries().size();
            for (SyndEntry entry : feed.getEntries()) {
                RawNews rawNews = toRawNews(source.getName(), entry);
                if (matchesConfiguredKeywords(rawNews)) {
                    stats.matchedEntries++;
                    futures.add(newsExecutor.submit(new Runnable() {
                        @Override
                        public void run() {
                            newsService.processRawNews(rawNews);
                        }
                    }));
                }
            }
            log.info("RSS fetched: source={} entries={} keywordMatched={}",
                    source.getName(), stats.totalEntries, stats.matchedEntries);
        } catch (Exception e) {
            log.warn("RSS fetch failed: source={} url={} error={}", source.getName(), source.getUrl(), e.getMessage());
        }
        return stats;
    }

    private void waitForTasks(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.warn("News processing task failed: {}", e.getMessage());
            }
        }
    }

    private InputStream openRssStream(String rssUrl) throws Exception {
        URL url = new URL(rssUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 IranWarSituationDemo/1.0");
        connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
        int statusCode = connection.getResponseCode();
        if (statusCode >= 300 && statusCode < 400 && connection.getHeaderField("Location") != null) {
            connection.disconnect();
            return openRssStream(new URL(url, connection.getHeaderField("Location")).toString());
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("HTTP " + statusCode + " " + connection.getResponseMessage());
        }
        return connection.getInputStream();
    }

    private RawNews toRawNews(String sourceName, SyndEntry entry) {
        RawNews rawNews = new RawNews();
        rawNews.setGuid(firstText(entry.getUri(), entry.getLink()));
        rawNews.setTitle(entry.getTitle());
        rawNews.setSummary(entry.getDescription() == null ? "" : entry.getDescription().getValue());
        rawNews.setContent(rawNews.getSummary());
        rawNews.setLink(entry.getLink());
        rawNews.setSource(sourceName);
        if (entry.getPublishedDate() != null) {
            rawNews.setPublishTime(LocalDateTime.ofInstant(entry.getPublishedDate().toInstant(), ZoneId.systemDefault()));
        } else {
            rawNews.setPublishTime(LocalDateTime.now());
        }
        return rawNews;
    }

    private boolean matchesConfiguredKeywords(RawNews rawNews) {
        List<String> keywords = properties.getCollector().getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            log.warn("No collector keywords configured; skip RSS item: title={}", rawNews.getTitle());
            return false;
        }
        String text = (safe(rawNews.getTitle()) + " " + safe(rawNews.getSummary()) + " " + safe(rawNews.getContent()))
                .toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (hasText(keyword) && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    private static class SourceCollectStats {
        private int totalEntries;
        private int matchedEntries;
    }

    @Override
    public void destroy() {
        newsExecutor.shutdown();
    }
}
