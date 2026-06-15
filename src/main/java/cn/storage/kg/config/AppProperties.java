package cn.storage.kg.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Collector collector = new Collector();
    private Iginx iginx = new Iginx();
    private Neo4j neo4j = new Neo4j();
    private Llm llm = new Llm();
    private Amap amap = new Amap();

    public Collector getCollector() { return collector; }
    public void setCollector(Collector collector) { this.collector = collector; }
    public Iginx getIginx() { return iginx; }
    public void setIginx(Iginx iginx) { this.iginx = iginx; }
    public Neo4j getNeo4j() { return neo4j; }
    public void setNeo4j(Neo4j neo4j) { this.neo4j = neo4j; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Amap getAmap() { return amap; }
    public void setAmap(Amap amap) { this.amap = amap; }

    public static class Collector {
        private boolean enabled = true;
        private long fixedDelayMs = 30000;
        private int workerThreads = 4;
        private List<String> keywords = new ArrayList<>();
        private List<RssSource> rssSources = new ArrayList<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords; }
        public List<RssSource> getRssSources() { return rssSources; }
        public void setRssSources(List<RssSource> rssSources) { this.rssSources = rssSources; }
    }

    public static class RssSource {
        private String name;
        private String url;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public static class Iginx {
        private String host;
        private int port;
        private String username;
        private String password;
        private String newsPathPrefix = "news";
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getNewsPathPrefix() { return newsPathPrefix; }
        public void setNewsPathPrefix(String newsPathPrefix) { this.newsPathPrefix = newsPathPrefix; }
    }

    public static class Neo4j {
        private String uri;
        private String username;
        private String password;
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class Llm {
        private boolean enabled;
        private String baseUrl;
        private String apiKey;
        private String model;
        private int timeoutSeconds = 30;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Amap {
        private String webApiKey;
        private String jsApiKey;
        private String securityJsCode;
        public String getWebApiKey() { return webApiKey; }
        public void setWebApiKey(String webApiKey) { this.webApiKey = webApiKey; }
        public String getJsApiKey() { return jsApiKey; }
        public void setJsApiKey(String jsApiKey) { this.jsApiKey = jsApiKey; }
        public String getSecurityJsCode() { return securityJsCode; }
        public void setSecurityJsCode(String securityJsCode) { this.securityJsCode = securityJsCode; }
    }
}
