package cn.storage.kg.model;

import java.util.List;

public class NewsSearchResult {
    private String keyword;
    private String cypher;
    private List<NewsEvent> events;

    public NewsSearchResult(String keyword, String cypher, List<NewsEvent> events) {
        this.keyword = keyword;
        this.cypher = cypher;
        this.events = events;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getCypher() {
        return cypher;
    }

    public void setCypher(String cypher) {
        this.cypher = cypher;
    }

    public List<NewsEvent> getEvents() {
        return events;
    }

    public void setEvents(List<NewsEvent> events) {
        this.events = events;
    }

    public int getCount() {
        return events == null ? 0 : events.size();
    }
}
