const IRAN_CENTER = [53.6880, 32.4279];
const COMPACT_MARKER_ZOOM = 5.8;
const FOCUS_MARKER_ZOOM = 6.8;
let map;
let infoWindow;
let markers = new Map();
let imageMarkers = new Map();
let events = [];
let allEvents = [];
let imageAssets = [];
let groups = new Map();
let activeGroupKey = null;
let activeGroupIndex = 0;
let searchMode = false;

const newsList = document.querySelector('#newsList');
const eventCount = document.querySelector('#eventCount');
const modal = document.querySelector('#detailModal');
const closeModal = document.querySelector('#closeModal');
const searchForm = document.querySelector('#searchForm');
const searchInput = document.querySelector('#searchInput');
const searchButton = document.querySelector('#searchButton');
const clearSearchButton = document.querySelector('#clearSearchButton');
const searchStatus = document.querySelector('#searchStatus');
const imageModal = document.querySelector('#imageModal');
const closeImageModal = document.querySelector('#closeImageModal');
const imageModalImg = document.querySelector('#imageModalImg');
const imageModalMeta = document.querySelector('#imageModalMeta');

async function bootstrap() {
    const config = await fetchJson('/api/config/frontend');
    await initMap(config.amapJsApiKey, config.amapSecurityJsCode);
    await loadInitialData();
    connectWebSocket();
}

async function initMap(amapKey, securityJsCode) {
    if (!amapKey) {
        return;
    }
    if (securityJsCode) {
        window._AMapSecurityConfig = { securityJsCode };
    }
    await loadScript(`https://webapi.amap.com/maps?v=2.0&key=${amapKey}&plugin=AMap.DistrictLayer`);
    map = new AMap.Map('map', {
        center: IRAN_CENTER,
        zoom: 6.0,
        viewMode: '2D',
        pitch: 38,
        mapStyle: 'amap://styles/darkblue'
    });
    map.on('zoomend', updateMarkerContents);
    drawIranCountryLayer();
}

async function loadInitialData() {
    const [mapEvents, latestNews, images] = await Promise.all([
        fetchJson('/api/events/map'),
        fetchJson('/api/news/latest?limit=20'),
        fetchJson('/api/images')
    ]);
    allEvents = Array.isArray(mapEvents) ? mapEvents : [];
    imageAssets = Array.isArray(images) ? images : [];
    events = allEvents.slice();
    rebuildMapMarkers();
    rebuildImageMarkers();
    renderNewsList(Array.isArray(latestNews) ? latestNews : []);
    updateEventCount();
}

function connectWebSocket() {
    if (!window.SockJS || !window.Stomp) {
        return;
    }
    const socket = new SockJS('/ws/news');
    const stompClient = Stomp.over(socket);
    stompClient.debug = null;
    stompClient.connect({}, () => {
        stompClient.subscribe('/topic/news', message => {
            const payload = JSON.parse(message.body);
            if (payload.type === 'NEW_EVENT') {
                const event = normalizeSocketEvent(payload);
                allEvents.unshift(event);
                if (!searchMode) {
                    events = allEvents.slice();
                    rebuildMapMarkers();
                    prependNewsItem(event);
                    updateEventCount();
                }
            }
        });
    });
}

async function handleSearch(event) {
    event.preventDefault();
    const keyword = searchInput.value.trim();
    if (!keyword) {
        clearSearch();
        return;
    }
    setSearchLoading(true);
    try {
        const result = await fetchJson(`/api/events/search?q=${encodeURIComponent(keyword)}&limit=200`);
        searchMode = true;
        events = Array.isArray(result.events) ? result.events : [];
        rebuildMapMarkers();
        renderNewsList(events);
        updateEventCount();
        renderSearchStatus(result.keyword || keyword, result.count ?? events.length, result.cypher);
        clearSearchButton.hidden = false;
    } catch (error) {
        console.error(error);
        renderSearchStatus(keyword, 0, '', '查询失败，请稍后重试。');
    } finally {
        setSearchLoading(false);
    }
}

function clearSearch() {
    searchMode = false;
    searchInput.value = '';
    events = allEvents.slice();
    rebuildMapMarkers();
    loadLatestNews();
    updateEventCount();
    clearSearchButton.hidden = true;
    searchStatus.hidden = true;
    searchStatus.innerHTML = '';
}

async function loadLatestNews() {
    try {
        const latestNews = await fetchJson('/api/news/latest?limit=20');
        renderNewsList(Array.isArray(latestNews) ? latestNews : []);
    } catch (error) {
        console.error(error);
        renderNewsList([]);
    }
}

function setSearchLoading(loading) {
    searchButton.disabled = loading;
    searchInput.disabled = loading;
    searchButton.textContent = loading ? '查询中' : '查询';
}

function renderSearchStatus(keyword, count, cypher, message) {
    searchStatus.hidden = false;
    const safeKeyword = escapeHtml(keyword);
    const safeMessage = message ? `<span>${escapeHtml(message)}</span>` : `<span>命中 <strong>${count}</strong> 条地图事件。</span>`;
    const cypherLine = cypher ? `<div>Cypher: <code>${escapeHtml(compactCypher(cypher))}</code></div>` : '';
    searchStatus.innerHTML = `<div>当前查询: <strong>${safeKeyword}</strong> ${safeMessage}</div>${cypherLine}`;
}

function updateEventCount() {
    eventCount.textContent = events.length;
}

function rebuildMapMarkers() {
    if (!map) {
        return;
    }
    markers.forEach(marker => map.remove(marker));
    markers.clear();
    groups = groupEventsByLocation(events);
    groups.forEach((group, key) => addGroupMarker(key, group));
}

function rebuildImageMarkers() {
    if (!map) {
        return;
    }
    imageMarkers.forEach(marker => map.remove(marker));
    imageMarkers.clear();
    imageAssets.forEach(asset => addImageMarker(asset));
}

function addImageMarker(asset) {
    if (!asset || asset.longitude == null || asset.latitude == null || !asset.thumbUrl) {
        return;
    }
    const marker = new AMap.Marker({
        position: [Number(asset.longitude), Number(asset.latitude)],
        title: `Image ${asset.key}`,
        content: imageMarkerHtml(asset),
        offset: imageMarkerOffset(),
        zIndex: 140
    });
    marker.on('click', () => openImageModal(asset));
    map.add(marker);
    imageMarkers.set(String(asset.key), marker);
}

function updateMarkerContents() {
    markers.forEach((marker, key) => {
        const group = groups.get(key);
        if (group) {
            marker.setContent(markerHtml(group));
            marker.setOffset(markerOffset());
        }
    });
    updateImageMarkerContents();
}

function updateImageMarkerContents() {
    imageMarkers.forEach((marker, key) => {
        const asset = imageAssets.find(item => String(item.key) === String(key));
        if (asset) {
            marker.setContent(imageMarkerHtml(asset));
            marker.setOffset(imageMarkerOffset());
        }
    });
}

function groupEventsByLocation(items) {
    const result = new Map();
    items.forEach(event => {
        if (!event.longitude || !event.latitude) {
            return;
        }
        const key = locationKey(event);
        if (!result.has(key)) {
            result.set(key, {
                key,
                longitude: Number(event.longitude),
                latitude: Number(event.latitude),
                locationName: event.locationName || 'Unknown',
                events: []
            });
        }
        result.get(key).events.push(event);
    });
    return result;
}

function addGroupMarker(key, group) {
    const marker = new AMap.Marker({
        position: [group.longitude, group.latitude],
        title: group.locationName,
        content: markerHtml(group),
        offset: markerOffset()
    });
    marker.on('mouseover', () => showMarkerPreview(marker, group, 0));
    marker.on('mouseout', closeInfoWindow);
    marker.on('click', () => handleGroupMarkerClick(key, group, marker));
    map.add(marker);
    markers.set(key, marker);
}

function handleGroupMarkerClick(key, group, marker) {
    if (isCompactMarkerMode()) {
        map.setZoomAndCenter(FOCUS_MARKER_ZOOM, marker.getPosition());
        return;
    }
    showGroupDetail(key, 0);
}

function drawIranCountryLayer() {
    if (!map || !window.AMap || !AMap.DistrictLayer || !AMap.DistrictLayer.Country) {
        return;
    }
    const iranLayer = new AMap.DistrictLayer.Country({
        zIndex: 80,
        SOC: 'IRN',
        depth: 0,
        styles: {
            'nation-stroke': '#ff2f4f',
            'coastline-stroke': '#ff2f4f',
            'province-stroke': 'rgba(255, 47, 79, 0.18)',
            fill: 'rgba(255, 47, 79, 0.10)'
        }
    });
    iranLayer.setMap(map);
    map.setZoomAndCenter(5.6, IRAN_CENTER);
}

function showMarkerPreview(marker, group, index) {
    if (!map || !window.AMap) {
        return;
    }
    if (!infoWindow) {
        infoWindow = new AMap.InfoWindow({
            isCustom: true,
            offset: new AMap.Pixel(0, -18)
        });
    }
    infoWindow.setContent(markerPreviewHtml(group, index));
    infoWindow.open(map, marker.getPosition());
}

function closeInfoWindow() {
    if (infoWindow) {
        infoWindow.close();
    }
}

function markerHtml(group) {
    if (isCompactMarkerMode()) {
        return `<div class="amap-event-dot" style="width:34px;height:34px;display:grid;place-items:center;border:3px solid rgba(255,69,104,.98);border-radius:50%;color:#e6f7ff;background:rgba(5,12,26,.9);box-shadow:0 0 0 2px rgba(5,12,26,.75),0 0 22px rgba(255,69,104,.6),0 0 12px rgba(53,216,255,.25);"><span style="display:block;min-width:18px;text-align:center;font-size:12px;font-weight:900;line-height:1;">${group.events.length}</span></div>`;
    }
    const first = group.events[0] || {};
    const title = escapeHtml(truncate(first.title || '', 48));
    const location = escapeHtml(group.locationName);
    return `<div class="amap-event-marker" style="width:220px;min-width:220px;max-width:220px;"><div class="marker-head" style="display:flex;align-items:center;justify-content:space-between;gap:18px;width:100%;min-width:0;margin-bottom:4px;"><b style="display:block;flex:1 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:left;">${location}</b><em style="display:inline-flex;flex:0 0 auto;align-items:center;justify-content:center;min-width:24px;height:22px;padding:0 7px;border-radius:999px;color:#050914;background:#ff4568;font-size:12px;font-style:normal;font-weight:800;">${group.events.length}</em></div><span>${title}</span></div>`;
}

function imageMarkerHtml(asset) {
    return `<button type="button" class="map-image-marker" title="Image ${escapeHtml(asset.key)}" style="display:block;box-sizing:border-box;width:24px;height:24px;min-width:24px;min-height:24px;max-width:24px;max-height:24px;padding:1px;margin:0;border:1px solid rgba(46,242,165,.92);border-radius:5px;background:rgba(3,8,20,.86);box-shadow:0 0 0 1px rgba(3,8,20,.78),0 0 8px rgba(46,242,165,.42);cursor:pointer;overflow:hidden;appearance:none;-webkit-appearance:none;"><img src="${escapeHtml(asset.thumbUrl)}" alt="" style="display:block;width:100%;height:100%;border-radius:3px;object-fit:cover;"></button>`;
}

function openImageModal(asset) {
    imageModalImg.src = asset.imageUrl;
    imageModalImg.alt = `Image ${asset.key}`;
    imageModalImg.style.width = 'auto';
    imageModalImg.style.height = 'auto';
    imageModalImg.style.maxWidth = '100%';
    imageModalImg.style.maxHeight = '100%';
    imageModalImg.style.objectFit = 'contain';
    imageModalMeta.textContent = `key=${asset.key}  lat=${Number(asset.latitude).toFixed(6)}  lon=${Number(asset.longitude).toFixed(6)}`;
    imageModal.classList.remove('hidden');
}

function closeImageViewer() {
    imageModal.classList.add('hidden');
    imageModalImg.removeAttribute('src');
}

function markerOffset() {
    return isCompactMarkerMode() ? new AMap.Pixel(-13, -13) : new AMap.Pixel(-12, -12);
}

function isCompactMarkerMode() {
    return map && map.getZoom() < COMPACT_MARKER_ZOOM;
}

function imageMarkerOffset() {
    return new AMap.Pixel(-12, -12);
}

function markerPreviewHtml(group, index) {
    const event = group.events[index] || group.events[0] || {};
    const source = escapeHtml(event.source || 'News');
    const time = escapeHtml(formatTime(event.publishTime));
    const title = escapeHtml(event.title || '未命名新闻');
    const summary = escapeHtml(truncate(event.summary || event.content || '', 120));
    const location = escapeHtml(group.locationName);
    const counter = group.events.length > 1 ? ` · ${index + 1}/${group.events.length}` : '';
    return `<div class="marker-preview"><div class="marker-preview-meta">${location} · ${source} · ${time}${counter}</div><div class="marker-preview-title">${title}</div><div class="marker-preview-summary">${summary}</div><div class="marker-preview-hint">点击查看详情</div></div>`;
}

function renderNewsList(news) {
    newsList.innerHTML = '';
    if (news.length === 0) {
        const message = searchMode
            ? '未查询到匹配的地图事件。'
            : '暂无新闻数据。采集器写入 IGinX 和 Neo4j 后会自动显示。';
        newsList.innerHTML = `<div class="news-item"><div class="news-title">${message}</div></div>`;
        return;
    }
    news.forEach(item => newsList.appendChild(createNewsItem(item)));
}

function prependNewsItem(item) {
    const empty = newsList.querySelector('.news-item .news-title');
    if (empty && empty.textContent.includes('暂无新闻数据')) {
        newsList.innerHTML = '';
    }
    newsList.prepend(createNewsItem(item));
}

function createNewsItem(item) {
    const div = document.createElement('div');
    div.className = 'news-item';
    div.innerHTML = `
        <div class="news-meta"><span>${escapeHtml(item.source || 'Unknown')}</span><span>${formatTime(item.publishTime)}</span></div>
        <div class="news-title">${escapeHtml(truncate(item.title || item.summary || '', 92))}</div>
    `;
    div.addEventListener('click', () => {
        flyToEvent(item);
        const key = locationKey(item);
        const group = groups.get(key);
        const index = group ? Math.max(0, group.events.findIndex(event => eventKey(event) === eventKey(item))) : 0;
        showGroupDetail(key, index);
    });
    return div;
}

function flyToEvent(item) {
    const marker = markers.get(locationKey(item));
    if (map && marker) {
        map.setZoomAndCenter(7, marker.getPosition());
    }
}

async function showGroupDetail(groupKey, index) {
    const group = groups.get(groupKey);
    if (!group || group.events.length === 0) {
        return;
    }
    activeGroupKey = groupKey;
    activeGroupIndex = normalizeIndex(index, group.events.length);
    await renderGroupDetail();
}

async function renderGroupDetail() {
    const group = groups.get(activeGroupKey);
    if (!group) {
        return;
    }
    const summaryEvent = group.events[activeGroupIndex];
    let detail = summaryEvent;
    try {
        detail = await fetchJson(`/api/news/${eventKey(summaryEvent)}`);
    } catch (_) {
        // The map summary can arrive before IGinX detail is queryable; use the local summary as a fallback.
    }
    renderDetail(detail, group);
}

function renderDetail(detail, group) {
    document.querySelector('#detailTitle').textContent = detail.title || '未命名新闻';
    document.querySelector('#detailSource').textContent = detail.source || 'Unknown';
    document.querySelector('#detailTime').textContent = formatTime(detail.publishTime);
    document.querySelector('#detailSummary').textContent = detail.summary || detail.content || '暂无摘要';
    const link = document.querySelector('#detailLink');
    link.href = detail.url || '#';
    renderEntities(detail);
    renderCarouselControls(group);
    modal.classList.remove('hidden');
}

function renderCarouselControls(group) {
    let controls = document.querySelector('#detailCarousel');
    if (!controls) {
        controls = document.createElement('div');
        controls.id = 'detailCarousel';
        controls.className = 'detail-carousel';
        document.querySelector('.modal-card').insertBefore(controls, document.querySelector('#detailSummary'));
    }
    const total = group.events.length;
    controls.innerHTML = `
        <button type="button" class="carousel-btn" id="prevEvent" ${total <= 1 ? 'disabled' : ''}>‹</button>
        <span>${activeGroupIndex + 1}/${total}</span>
        <button type="button" class="carousel-btn" id="nextEvent" ${total <= 1 ? 'disabled' : ''}>›</button>
    `;
    document.querySelector('#prevEvent').addEventListener('click', () => switchEvent(-1));
    document.querySelector('#nextEvent').addEventListener('click', () => switchEvent(1));
}

function switchEvent(delta) {
    const group = groups.get(activeGroupKey);
    if (!group || group.events.length <= 1) {
        return;
    }
    activeGroupIndex = normalizeIndex(activeGroupIndex + delta, group.events.length);
    renderGroupDetail();
}

function renderEntities(detail) {
    const values = [
        ...(detail.countries || []),
        ...(detail.organizations || []),
        ...(detail.persons || []),
        ...(detail.locations || [])
    ];
    document.querySelector('#detailEntities').innerHTML = values.length
        ? values.map(value => `<span>${escapeHtml(value)}</span>`).join('')
        : '<span>待抽取</span>';
}

function normalizeSocketEvent(payload) {
    return {
        id: String(payload.key ?? payload.id),
        key: payload.key,
        title: payload.title,
        summary: payload.summary,
        url: payload.url,
        source: payload.source,
        publishTime: payload.publishTime,
        locationName: payload.location,
        latitude: payload.lat,
        longitude: payload.lon
    };
}

async function fetchJson(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`Request failed: ${url}`);
    }
    return response.json();
}

function loadScript(src) {
    return new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = src;
        script.onload = resolve;
        script.onerror = reject;
        document.head.appendChild(script);
    });
}

function eventKey(event) {
    return String(event.key ?? event.id);
}

function locationKey(event) {
    return `${Number(event.longitude).toFixed(4)},${Number(event.latitude).toFixed(4)}`;
}

function normalizeIndex(index, total) {
    return ((index % total) + total) % total;
}

function formatTime(value) {
    if (!value) {
        return '--:--';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return String(value).slice(0, 16);
    }
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

function truncate(value, max) {
    return value.length > max ? `${value.slice(0, max)}...` : value;
}

function compactCypher(value) {
    return String(value).replace(/\s+/g, ' ').trim();
}

function escapeHtml(value) {
    return String(value).replace(/[&<>'"]/g, char => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        "'": '&#39;',
        '"': '&quot;'
    }[char]));
}

closeModal.addEventListener('click', () => modal.classList.add('hidden'));
modal.querySelector('.modal-backdrop').addEventListener('click', () => modal.classList.add('hidden'));
closeImageModal.addEventListener('click', closeImageViewer);
imageModal.querySelector('.modal-backdrop').addEventListener('click', closeImageViewer);
searchForm.addEventListener('submit', handleSearch);
clearSearchButton.addEventListener('click', clearSearch);

bootstrap().catch(error => {
    console.error(error);
    renderNewsList([]);
});
