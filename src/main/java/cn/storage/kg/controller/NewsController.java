package cn.storage.kg.controller;

import cn.storage.kg.model.ImageAsset;
import cn.storage.kg.model.NewsEvent;
import cn.storage.kg.model.NewsSearchResult;
import cn.storage.kg.service.NewsService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;
import java.util.List;

@RestController
@RequestMapping("/api")
public class NewsController {
    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping("/news/latest")
    public List<NewsEvent> latest(@RequestParam(defaultValue = "20") int limit) {
        return newsService.latest(limit);
    }

    @GetMapping("/news/{id}")
    public ResponseEntity<NewsEvent> detail(@PathVariable String id) {
        return newsService.detail(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/events/map")
    public List<NewsEvent> mapEvents() {
        return newsService.mapEvents();
    }

    @GetMapping("/events/search")
    public NewsSearchResult searchMapEvents(@RequestParam(defaultValue = "") String q,
                                            @RequestParam(defaultValue = "200") int limit) {
        return newsService.searchMapEvents(q, limit);
    }

    @GetMapping("/images")
    public List<ImageAsset> images() {
        return newsService.imageAssets();
    }

    @GetMapping(value = "/images/{key}/thumb", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> imageThumb(@PathVariable long key) {
        return newsService.imageThumb(key)
                .map(bytes -> imageResponse(bytes, MediaType.IMAGE_JPEG))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/images/{key}/content")
    public ResponseEntity<byte[]> imageContent(@PathVariable long key) {
        return newsService.imageContent(key)
                .map(bytes -> imageResponse(bytes, MediaType.parseMediaType("image/bmp")))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<byte[]> imageResponse(byte[] bytes, MediaType mediaType) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES))
                .body(bytes);
    }
}
