package cn.storage.kg.model;

import java.time.LocalDateTime;

public class RawNews {
    private String guid;
    private String title;
    private String summary;
    private String content;
    private String link;
    private LocalDateTime publishTime;
    private String source;

    public String getGuid() { return guid; }
    public void setGuid(String guid) { this.guid = guid; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
