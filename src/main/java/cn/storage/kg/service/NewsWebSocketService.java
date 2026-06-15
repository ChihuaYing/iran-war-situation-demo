package cn.storage.kg.service;

import cn.storage.kg.model.NewsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class NewsWebSocketService {
    private static final Logger log = LoggerFactory.getLogger(NewsWebSocketService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public NewsWebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void pushNewEvent(NewsEvent event) {
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("type", "NEW_EVENT");
        payload.put("id", event.getId());
        payload.put("key", event.getKey());
        payload.put("title", event.getTitle());
        payload.put("location", event.getLocationName());
        payload.put("lat", event.getLatitude());
        payload.put("lon", event.getLongitude());
        payload.put("source", event.getSource());
        payload.put("summary", event.getSummary());
        payload.put("url", event.getUrl());
        payload.put("publishTime", event.getPublishTime());
        messagingTemplate.convertAndSend("/topic/news", payload);
        log.info("WebSocket pushed new event: key={} topic=/topic/news title={}", event.getKey(), event.getTitle());
    }
}
