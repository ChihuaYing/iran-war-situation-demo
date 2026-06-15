package cn.storage.kg.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class LlmNewsDecision {
    private boolean related;
    private String title;
    private String summary;
    private String url;
    private LocalDateTime publishTime;
    private String locationName;
    private Double latitude;
    private Double longitude;
    private List<String> countries = new ArrayList<>();
    private List<String> organizations = new ArrayList<>();
    private List<String> persons = new ArrayList<>();
    private List<String> locations = new ArrayList<>();

    public boolean isRelated() { return related; }
    public void setRelated(boolean related) { this.related = related; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
    public String getLocationName() { return locationName; }
    public void setLocationName(String locationName) { this.locationName = locationName; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public List<String> getCountries() { return countries; }
    public void setCountries(List<String> countries) { this.countries = countries; }
    public List<String> getOrganizations() { return organizations; }
    public void setOrganizations(List<String> organizations) { this.organizations = organizations; }
    public List<String> getPersons() { return persons; }
    public void setPersons(List<String> persons) { this.persons = persons; }
    public List<String> getLocations() { return locations; }
    public void setLocations(List<String> locations) { this.locations = locations; }
}
