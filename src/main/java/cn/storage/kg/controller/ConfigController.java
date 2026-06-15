package cn.storage.kg.controller;

import cn.storage.kg.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {
    private final AppProperties properties;

    public ConfigController(AppProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/frontend")
    public Map<String, String> frontendConfig() {
        Map<String, String> config = new HashMap<String, String>();
        config.put("amapJsApiKey", properties.getAmap().getJsApiKey() == null ? "" : properties.getAmap().getJsApiKey());
        config.put("amapSecurityJsCode", properties.getAmap().getSecurityJsCode() == null ? "" : properties.getAmap().getSecurityJsCode());
        return config;
    }
}
