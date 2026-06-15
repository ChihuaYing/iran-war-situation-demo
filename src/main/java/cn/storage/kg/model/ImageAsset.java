package cn.storage.kg.model;

public class ImageAsset {
    private Long key;
    private Double latitude;
    private Double longitude;
    private String thumbUrl;
    private String imageUrl;

    public Long getKey() { return key; }
    public void setKey(Long key) { this.key = key; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getThumbUrl() { return thumbUrl; }
    public void setThumbUrl(String thumbUrl) { this.thumbUrl = thumbUrl; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}
