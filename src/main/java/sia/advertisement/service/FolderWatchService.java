package sia.advertisement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sia.advertisement.controller.ActionStateHolder;
import sia.advertisement.entity.AdUser;
import sia.advertisement.entity.AdProject;
import sia.advertisement.entity.BoardPhoto;
import sia.advertisement.mapper.AdProjectMapper;
import sia.advertisement.mapper.AdUserMapper;
import sia.advertisement.mapper.BoardPhotoMapper;
import sia.advertisement.mapper.UserAdFeedbackMapper;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 文件夹监控 + HTTP 轮询：
 * - input_photos：检测到新图片后，延迟 0.5s 再通过 HTTP 获取 person_latest.json 并匹配广告
 * - HTTP 轮询 action_latest.json 获取手势指令
 */
@Service
public class FolderWatchService {

    @Value("${board.user-id:1}")
    private Long boardUserId;

    @Value("${face.enabled:true}")
    private boolean faceEnabled;

    @Autowired
    private WatchConfigService watchConfigService;

    @Autowired
    private FaceRecognitionService faceRecognitionService;

    @Autowired
    private AdProjectMapper adProjectMapper;

    @Autowired
    private UserAdFeedbackMapper userAdFeedbackMapper;

    @Autowired
    private BoardPhotoMapper boardPhotoMapper;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private AdUserMapper adUserMapper;

    @Autowired
    private ActionStateHolder stateHolder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Thread watchThread;
    private volatile boolean running = true;
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "folder-watch-debounce");
        t.setDaemon(true);
        return t;
    });

    /** HTTP 轮询 action JSON 的调度器 */
    private volatile ScheduledFuture<?> actionPollFuture;
    /** 上次获取到的 action JSON 内容（用于检测变化） */
    private volatile String lastActionContent = "";

    /** HTTP 轮询 person JSON 的调度器 */
    private volatile ScheduledFuture<?> personPollFuture;
    /** 上次获取到的 person JSON 内容（用于检测变化） */
    private volatile String lastPersonContent = "";

    /** HTTP 轮询 photo URL 的调度器 */
    private volatile ScheduledFuture<?> photoPollFuture;
    /** 上次获取到的 photo URL 内容的 ETag/时间戳（用于检测变化） */
    private volatile String lastPhotoContent = "";
    private volatile long lastPhotoFetchTime = 0;

    private volatile long lastProcessedPhotoKey = -1;
    private volatile boolean watching = false;
    private volatile long lastSwipeTime = 0;
    private static final long SWIPE_THROTTLE_MS = 500;
    private static final long PORTRAIT_DELAY_MS = 500;

    /** 上次 AOTO_SWITCH 匹配的年龄/性别，用于防止同一人重复推送 */
    private volatile String lastMatchedAge = "";
    private volatile String lastMatchedGender = "";

    /** 上次手势翻页时间，用于防止 AUTO_SWITCH 在手势后立即覆盖翻页结果 */
    private volatile long lastActionJsonProcessedTime = 0;
    private static final long AUTO_SWITCH_COOLDOWN_AFTER_SWIPE_MS = 3000;

    @PostConstruct
    public void init() {
        startWatch();
    }

    public boolean isWatching() {
        return watching;
    }

    public void restartWatch() {
        stopWatchThread();
        stopActionPolling();
        stopPersonPolling();
        stopPhotoPolling();
        lastProcessedPhotoKey = -1;
        lastActionContent = "";
        lastPersonContent = "";
        lastPhotoContent = "";
        lastMatchedAge = "";
        lastMatchedGender = "";
        startWatch();
    }

    public void triggerScan() {
        debouncer.schedule(() -> {
            processPortraitIfNewPhoto();
            fetchAndProcessAction();
        }, 200, TimeUnit.MILLISECONDS);
    }

    private void startActionPolling() {
        stopActionPolling();
        long interval = watchConfigService.getActionPollInterval();
        actionPollFuture = debouncer.scheduleWithFixedDelay(
                this::fetchAndProcessAction, interval, interval, TimeUnit.MILLISECONDS);
        System.out.println("[FolderWatch] 启动 action JSON HTTP 轮询，间隔 " + interval + "ms URL="
                + watchConfigService.getActionUrl());
    }

    private void stopActionPolling() {
        if (actionPollFuture != null) {
            actionPollFuture.cancel(false);
            actionPollFuture = null;
        }
    }

    private void startPersonPolling() {
        stopPersonPolling();
        long interval = watchConfigService.getPersonPollInterval();
        personPollFuture = debouncer.scheduleWithFixedDelay(
                this::fetchAndProcessPerson, interval, interval, TimeUnit.MILLISECONDS);
        System.out.println("[FolderWatch] 启动 person JSON HTTP 轮询，间隔 " + interval + "ms URL="
                + watchConfigService.getPersonUrl());
    }

    private void stopPersonPolling() {
        if (personPollFuture != null) {
            personPollFuture.cancel(false);
            personPollFuture = null;
        }
    }

    private void startPhotoPolling() {
        stopPhotoPolling();
        String photoUrl = watchConfigService.getPhotoHttpUrl();
        if (photoUrl == null || photoUrl.isBlank()) {
            System.out.println("[FolderWatch] 图片 HTTP 地址未配置，跳过 photo 轮询");
            return;
        }
        long interval = watchConfigService.getPersonPollInterval();
        photoPollFuture = debouncer.scheduleWithFixedDelay(
                this::fetchAndProcessPhoto, interval, interval, TimeUnit.MILLISECONDS);
        System.out.println("[FolderWatch] 启动 photo HTTP 轮询，间隔 " + interval + "ms URL=" + photoUrl);
    }

    private void stopPhotoPolling() {
        if (photoPollFuture != null) {
            photoPollFuture.cancel(false);
            photoPollFuture = null;
        }
    }

    private void startWatch() {
        watchConfigService.ensureDirectories();
        File photosDir = watchConfigService.getPhotosDirFile();
        System.out.println("[FolderWatch] 画像 URL: " + watchConfigService.getPersonUrl());
        System.out.println("[FolderWatch] 动作 URL: " + watchConfigService.getActionUrl());
        System.out.println("[FolderWatch] 图片目录(input_photos): " + photosDir.getAbsolutePath());

        running = true;
        watchThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                Path photosPath = photosDir.toPath();
                photosPath.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                watching = true;

                while (running) {
                    WatchKey key;
                    try {
                        key = watchService.poll(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (key == null) continue;

                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        String fileName = event.context().toString();
                        if (isImageFile(fileName)) {
                            schedulePortraitProcess();
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                System.err.println("[FolderWatch] 监控异常: " + e.getMessage());
                e.printStackTrace();
            } finally {
                watching = false;
            }
        }, "folder-watch");
        watchThread.setDaemon(true);
        watchThread.start();

        initLastProcessedPhotoKey(photosDir);

        // 启动 HTTP 轮询 action JSON
        startActionPolling();
        // 启动 HTTP 轮询 person JSON
        startPersonPolling();
        // 启动 HTTP 轮询 photo URL（如果配置了）
        startPhotoPolling();
    }

    @PreDestroy
    public void stopWatch() {
        running = false;
        stopActionPolling();
        stopPersonPolling();
        stopPhotoPolling();
        debouncer.shutdownNow();
        stopWatchThread();
    }

    private void stopWatchThread() {
        watching = false;
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
    }

    private void schedulePortraitProcess() {
        debouncer.schedule(this::processPortraitIfNewPhoto, PORTRAIT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 通过 HTTP 轮询 person_latest.json，检测年龄/性别变化自动推送广告。
     * 不需要新照片——纯靠 JSON 字段变化触发。
     */
    private synchronized void fetchAndProcessPerson() {
        try {
            String url = watchConfigService.getPersonUrl();
            String content = fetchJsonContent(url);
            if (content == null || content.isEmpty()) return;

            // 内容未变化则跳过
            if (content.equals(lastPersonContent)) return;

            JsonNode node;
            try {
                node = objectMapper.readTree(content);
            } catch (Exception e) {
                System.err.println("[FolderWatch] person JSON 解析失败: " + e.getMessage());
                lastPersonContent = content;
                return;
            }

            String age = "";
            String gender = "";
            if (node.has("age")) age = node.get("age").asText();
            if (node.has("gender")) gender = node.get("gender").asText();
            if (node.has("ageRange") && age.isEmpty()) age = node.get("ageRange").asText();

            lastPersonContent = content;

            // age 和 gender 都为空则跳过
            if (age.isEmpty() && gender.isEmpty()) return;

            // 与上次匹配结果相同则跳过（防重复推送）
            if (!age.isEmpty() && !gender.isEmpty()
                    && age.equals(lastMatchedAge) && gender.equals(lastMatchedGender)) {
                return;
            }

            String portraitRaw = buildPortraitJson(age, gender);
            System.out.println("[FolderWatch] person JSON(HTTP) 画像变化 age=" + age
                    + " gender=" + gender + " → 触发广告匹配");

            Long userId = boardUserId;
            ensureBoardUserExists();

            // 记录上一个用户曝光
            recordExposureForPreviousUser();

            // 规则匹配广告
            long now = Instant.now().toEpochMilli();
            if (lastActionJsonProcessedTime > 0
                    && (now - lastActionJsonProcessedTime) < AUTO_SWITCH_COOLDOWN_AFTER_SWIPE_MS) {
                System.out.println("[FolderWatch] 手势翻页冷却期内，跳过 person AUTO_SWITCH");
                lastMatchedAge = age;
                lastMatchedGender = gender;
                return;
            }

            var projects = adProjectMapper.selectAll();
            String matchRaw = matchAds(age, gender, projects, userId);
            String matchedIds = extractAdIds(matchRaw);

            String top1Id = "";
            String nextFallbackId = "";
            if (!matchedIds.isEmpty()) {
                String[] arr = matchedIds.split(",");
                top1Id = arr[0].trim();
                if (arr.length > 1) nextFallbackId = arr[1].trim();
            }

            Map<String, Object> actionData = new LinkedHashMap<>();
            actionData.put("action", "AUTO_SWITCH");
            actionData.put("userId", userId);
            actionData.put("portrait", portraitRaw);
            actionData.put("recommendations", matchRaw);
            actionData.put("matchedAdIds", matchedIds);
            actionData.put("currentProjectId", top1Id);
            actionData.put("nextProjectId", nextFallbackId);
            actionData.put("timestamp", Instant.now().toEpochMilli());
            stateHolder.putAll(actionData);

            lastMatchedAge = age;
            lastMatchedGender = gender;

            System.out.println("[FolderWatch] person JSON 轮询触发广告切换 age=" + age
                    + " gender=" + gender + " matchedAdIds=" + matchedIds);

        } catch (Exception e) {
            System.err.println("[FolderWatch] person JSON 轮询处理失败: " + e.getMessage());
        }
    }

    /**
     * 通过 HTTP 获取并处理 action_latest.json
     */
    private synchronized void fetchAndProcessAction() {
        try {
            String url = watchConfigService.getActionUrl();
            String content = fetchJsonContent(url);
            if (content == null || content.isEmpty()) return;

            // 内容未变化则跳过
            if (content.equals(lastActionContent)) return;

            JsonNode node;
            try {
                node = objectMapper.readTree(content);
            } catch (Exception e) {
                System.err.println("[FolderWatch] 动作 JSON 解析失败: " + e.getMessage());
                lastActionContent = content;
                return;
            }

            if (!node.has("action")) {
                lastActionContent = content;
                return;
            }

            String boardAction = node.get("action").asText("").trim();
            String mappedAction = mapBoardSwipeAction(boardAction);
            if (mappedAction == null) {
                lastActionContent = content;
                return;
            }

            long now = Instant.now().toEpochMilli();
            if (now - lastSwipeTime < SWIPE_THROTTLE_MS) {
                lastActionContent = content;
                return;
            }
            lastSwipeTime = now;
            lastActionJsonProcessedTime = now;

            Map<String, Object> actionData = new LinkedHashMap<>();
            actionData.put("action", mappedAction);
            actionData.put("timestamp", now);
            if (node.has("sequence")) {
                actionData.put("sequence", node.get("sequence").asInt());
            }
            stateHolder.putAll(actionData);

            lastActionContent = content;
            System.out.println("[FolderWatch] 滑动手势(HTTP): " + boardAction + " -> " + mappedAction
                    + " url=" + url
                    + (node.has("sequence") ? " seq=" + node.get("sequence").asInt() : ""));
        } catch (Exception e) {
            System.err.println("[FolderWatch] 滑动手势处理失败: " + e.getMessage());
        }
    }

    private void initLastProcessedPhotoKey(File photosDir) {
        File latestPhoto = findLatestImage(photosDir);
        lastProcessedPhotoKey = photoKey(latestPhoto);
        if (latestPhoto != null) {
            System.out.println("[FolderWatch] 当前最新照片已标记为已处理: " + latestPhoto.getName());
        }
    }

    private long photoKey(File photo) {
        if (photo == null) return 0;
        return photo.lastModified() ^ (long) photo.getName().hashCode();
    }

    private String mapBoardSwipeAction(String action) {
        if (action == null || action.isEmpty()) return null;
        String a = action.trim().toLowerCase(Locale.ROOT);
        switch (a) {
            case "swipe_left":
            case "left_swipe":
                return "LEFT_SWIPE";
            case "swipe_right":
            case "right_swipe":
                return "RIGHT_SWIPE";
            default:
                return null;
        }
    }

    /**
     * input_photos 出现新图片时触发：延迟 0.5s 后通过 HTTP 获取 person_latest.json 并匹配广告。
     */
    private synchronized void processPortraitIfNewPhoto() {
        try {
            File photosDir = watchConfigService.getPhotosDirFile();

            File newPhoto = findLatestImage(photosDir);
            if (newPhoto == null) {
                return;
            }

            long photoKey = photoKey(newPhoto);
            if (photoKey == lastProcessedPhotoKey) {
                System.out.println("[FolderWatch] 最新图片未变化，跳过: " + newPhoto.getName());
                return;
            }

            // 记录上一个用户对当前广告的曝光
            recordExposureForPreviousUser();

            // 通过 HTTP 获取 person_latest.json 当前字段
            JsonProfile profile = fetchPersonJson();
            if (profile.age.isEmpty() && profile.gender.isEmpty()) {
                System.out.println("[FolderWatch] person_latest.json(HTTP) 无 age/gender，仍继续处理新照片: "
                        + newPhoto.getName());
            }

            String age = profile.age;
            String gender = profile.gender;
            String portraitRaw = buildPortraitJson(age, gender);

            // 1. 新图片 → 人脸识别
            Long userId = boardUserId;
            String photoUrl = "/watch-photos/" + newPhoto.getName();
            String photoPath = newPhoto.getAbsolutePath();
            byte[] photoBytes = Files.readAllBytes(newPhoto.toPath());

            if (faceEnabled && photoBytes.length > 0) {
                try {
                    userId = faceRecognitionService.resolveUserId(photoBytes, photoUrl);
                } catch (Exception e) {
                    System.out.println("[FolderWatch] 人脸识别失败，使用 boardUserId: " + e.getMessage());
                    ensureBoardUserExists();
                }
            } else {
                ensureBoardUserExists();
            }

            // 2. JSON 字段 → 规则匹配广告
            var projects = adProjectMapper.selectAll();
            String matchRaw = matchAds(age, gender, projects, userId);
            String matchedIds = extractAdIds(matchRaw);

            // 3. 入库 board_photo + user_profile
            BoardPhoto photo = new BoardPhoto();
            photo.setUserId(userId);
            photo.setPhotoUrl(photoUrl);
            photo.setPhotoPath(photoPath);
            photo.setStatus("PENDING");
            boardPhotoMapper.insert(photo);

            boardPhotoMapper.updateAnalysisResult(photo.getId(), "ANALYZED",
                    portraitRaw, matchRaw, matchedIds);

            userProfileService.savePortraitAndLog(userId, photoUrl,
                    portraitRaw, matchRaw, matchedIds);

            // 4. 防护检查：避免手势翻页后被年龄识别覆盖
            boolean shouldPush = true;
            String profileAge = profile.age != null ? profile.age : "";
            String profileGender = profile.gender != null ? profile.gender : "";

            // 防护1: 同一年龄+性别不重复触发 AUTO_SWITCH（画像非空时才检查）
            if (!profileAge.isEmpty() && !profileGender.isEmpty()
                    && profileAge.equals(lastMatchedAge) && profileGender.equals(lastMatchedGender)) {
                System.out.println("[FolderWatch] 同一用户画像未变(age=" + profileAge
                        + " gender=" + profileGender + ")，跳过 AUTO_SWITCH");
                shouldPush = false;
            }

            // 防护2: 手势翻页后 3 秒内不触发 AUTO_SWITCH
            long now = Instant.now().toEpochMilli();
            if (lastActionJsonProcessedTime > 0
                    && (now - lastActionJsonProcessedTime) < AUTO_SWITCH_COOLDOWN_AFTER_SWIPE_MS) {
                System.out.println("[FolderWatch] 手势翻页后冷却期内，跳过 AUTO_SWITCH (距上次手势"
                        + (now - lastActionJsonProcessedTime) + "ms)");
                shouldPush = false;
            }

            if (!shouldPush) {
                lastProcessedPhotoKey = photoKey;
                lastMatchedAge = profileAge;
                lastMatchedGender = profileGender;
                System.out.println("[FolderWatch] 新图片已记录但不推送 AUTO_SWITCH: " + newPhoto.getName());
                return;
            }

            // 5. 写入 shared state → 前端切广告
            String top1Id = "";
            String nextFallbackId = "";
            if (!matchedIds.isEmpty()) {
                String[] arr = matchedIds.split(",");
                top1Id = arr[0].trim();
                if (arr.length > 1) nextFallbackId = arr[1].trim();
            }

            Map<String, Object> actionData = new LinkedHashMap<>();
            actionData.put("action", "AUTO_SWITCH");
            actionData.put("userId", userId);
            actionData.put("portrait", portraitRaw);
            actionData.put("recommendations", matchRaw);
            actionData.put("matchedAdIds", matchedIds);
            actionData.put("currentProjectId", top1Id);
            actionData.put("nextProjectId", nextFallbackId);
            actionData.put("timestamp", Instant.now().toEpochMilli());
            stateHolder.putAll(actionData);

            // 记录本次匹配的画像，用于下次防重复
            lastMatchedAge = profileAge;
            lastMatchedGender = profileGender;

            lastProcessedPhotoKey = photoKey;
            System.out.println("[FolderWatch] input_photos 新图片触发广告切换 userId=" + userId
                    + " age=" + age + " gender=" + gender
                    + " photo=" + newPhoto.getName()
                    + " matchedAdIds=" + matchedIds);

        } catch (Exception e) {
            System.err.println("[FolderWatch] 画像处理失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 新图片/新人脸出现时，记录上一个用户看过了当前广告（递增曝光次数）
     */
    private void recordExposureForPreviousUser() {
        try {
            Map<String, Object> lastAction = stateHolder.getLastAction();
            Object prevUserIdObj = lastAction.get("userId");
            Object prevProjectIdObj = lastAction.get("currentProjectId");

            Long prevUserId = prevUserIdObj instanceof Number ? ((Number) prevUserIdObj).longValue() : null;
            Long prevProjectId = prevProjectIdObj instanceof Number ? ((Number) prevProjectIdObj).longValue() : null;

            if (prevUserId != null && prevProjectId != null && prevUserId > 0 && prevProjectId > 0) {
                userAdFeedbackMapper.incrementExposure(prevUserId, prevProjectId);
                System.out.println("[FolderWatch] 记录曝光: userId=" + prevUserId + " projectId=" + prevProjectId);
            }
        } catch (Exception e) {
            System.err.println("[FolderWatch] 记录曝光失败: " + e.getMessage());
        }
    }

    /**
     * 通过 HTTP 获取照片，检测变化后触发人脸识别和广告匹配
     */
    private synchronized void fetchAndProcessPhoto() {
        try {
            String photoUrl = watchConfigService.getPhotoHttpUrl();
            if (photoUrl == null || photoUrl.isBlank()) {
                return;
            }

            byte[] photoBytes = fetchBinaryContent(photoUrl);
            if (photoBytes == null || photoBytes.length == 0) {
                return;
            }

            String contentHash = String.valueOf(Arrays.hashCode(photoBytes));
            if (contentHash.equals(lastPhotoContent)) {
                return;
            }
            lastPhotoContent = contentHash;

            System.out.println("[FolderWatch] photo HTTP 轮询检测到图片变化，长度=" + photoBytes.length);

            recordExposureForPreviousUser();

            JsonProfile profile = fetchPersonJson();
            String age = profile.age;
            String gender = profile.gender;
            String portraitRaw = buildPortraitJson(age, gender);

            Long userId = boardUserId;
            if (faceEnabled && photoBytes.length > 0) {
                try {
                    userId = faceRecognitionService.resolveUserId(photoBytes, photoUrl);
                } catch (Exception e) {
                    System.out.println("[FolderWatch] HTTP 照片人脸识别失败，使用 boardUserId: " + e.getMessage());
                    ensureBoardUserExists();
                }
            } else {
                ensureBoardUserExists();
            }

            var projects = adProjectMapper.selectAll();
            String matchRaw = matchAds(age, gender, projects, userId);
            String matchedIds = extractAdIds(matchRaw);

            boolean shouldPush = true;
            String profileAge = profile.age != null ? profile.age : "";
            String profileGender = profile.gender != null ? profile.gender : "";

            if (!profileAge.isEmpty() && !profileGender.isEmpty()
                    && profileAge.equals(lastMatchedAge) && profileGender.equals(lastMatchedGender)) {
                System.out.println("[FolderWatch] HTTP 照片同一用户画像未变(age=" + profileAge
                        + " gender=" + profileGender + ")，跳过 AUTO_SWITCH");
                shouldPush = false;
            }

            long now = Instant.now().toEpochMilli();
            if (lastActionJsonProcessedTime > 0
                    && (now - lastActionJsonProcessedTime) < AUTO_SWITCH_COOLDOWN_AFTER_SWIPE_MS) {
                System.out.println("[FolderWatch] HTTP 照片手势翻页后冷却期内，跳过 AUTO_SWITCH");
                shouldPush = false;
            }

            if (!shouldPush) {
                lastMatchedAge = profileAge;
                lastMatchedGender = profileGender;
                System.out.println("[FolderWatch] HTTP 照片已记录但不推送 AUTO_SWITCH");
                return;
            }

            String top1Id = "";
            String nextFallbackId = "";
            if (!matchedIds.isEmpty()) {
                String[] arr = matchedIds.split(",");
                top1Id = arr[0].trim();
                if (arr.length > 1) nextFallbackId = arr[1].trim();
            }

            Map<String, Object> actionData = new LinkedHashMap<>();
            actionData.put("action", "AUTO_SWITCH");
            actionData.put("userId", userId);
            actionData.put("portrait", portraitRaw);
            actionData.put("recommendations", matchRaw);
            actionData.put("matchedAdIds", matchedIds);
            actionData.put("currentProjectId", top1Id);
            actionData.put("nextProjectId", nextFallbackId);
            actionData.put("timestamp", Instant.now().toEpochMilli());
            stateHolder.putAll(actionData);

            lastMatchedAge = profileAge;
            lastMatchedGender = profileGender;

            System.out.println("[FolderWatch] HTTP 照片触发广告切换 userId=" + userId
                    + " age=" + age + " gender=" + gender + " matchedAdIds=" + matchedIds);

        } catch (Exception e) {
            System.err.println("[FolderWatch] HTTP 照片处理失败: " + e.getMessage());
        }
    }

    /** 通过 HTTP GET 获取二进制内容 */
    private byte[] fetchBinaryContent(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                System.err.println("[FolderWatch] HTTP " + response.statusCode() + " 获取图片失败: " + url);
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 通过 HTTP 获取 person_latest.json 当前内容 */
    private JsonProfile fetchPersonJson() {
        JsonProfile result = new JsonProfile();
        try {
            String url = watchConfigService.getPersonUrl();
            String content = fetchJsonContent(url);
            if (content == null || content.isEmpty()) {
                return result;
            }

            JsonNode node = objectMapper.readTree(content);
            if (node.has("age")) {
                result.age = node.get("age").asText();
            }
            if (node.has("gender")) {
                result.gender = node.get("gender").asText();
            }
            // 兼容 ageRange 字段
            if (node.has("ageRange") && result.age.isEmpty()) {
                result.age = node.get("ageRange").asText();
            }
            System.out.println("[FolderWatch] 读取画像 JSON(HTTP): " + url
                    + " age=" + result.age + " gender=" + result.gender);
        } catch (Exception e) {
            System.err.println("[FolderWatch] 画像 JSON 获取/解析失败: " + e.getMessage());
        }
        return result;
    }

    /** 通过 HTTP GET 获取 JSON 内容 */
    private String fetchJsonContent(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                System.err.println("[FolderWatch] HTTP " + response.statusCode() + " 获取失败: " + url);
                return null;
            }
        } catch (Exception e) {
            // 静默处理（轮询时频繁打印会刷屏）
            return null;
        }
    }

    private File findLatestImage(File photosDir) {
        if (!photosDir.exists()) return null;
        try (Stream<Path> stream = Files.list(photosDir.toPath())) {
            Optional<File> latest = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isImageFile(p.getFileName().toString()))
                    .map(Path::toFile)
                    .filter(f -> f.length() > 0)
                    .max(Comparator.comparingLong(File::lastModified));
            return latest.orElse(null);
        } catch (Exception e) {
            System.err.println("[FolderWatch] 扫描图片目录失败: " + e.getMessage());
            return null;
        }
    }

    private String buildPortraitJson(String age, String gender) {
        String ageRange = normalizeAge(age);
        String genderNorm = normalizeGender(gender);
        try {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("ageRange", ageRange);
            map.put("gender", genderNorm);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"ageRange\":\"" + ageRange + "\",\"gender\":\"" + genderNorm + "\"}";
        }
    }

    private void ensureBoardUserExists() {
        try {
            AdUser existing = adUserMapper.selectById(boardUserId);
            if (existing != null) return;
            AdUser user = new AdUser();
            user.setId(boardUserId);
            user.setUsername("board");
            user.setPassword("board-auto-created");
            user.setNickname("广告牌-" + boardUserId);
            adUserMapper.insert(user);
            System.out.println("[FolderWatch] 自动创建板子用户 id=" + boardUserId);
        } catch (Exception e) {
            System.out.println("[FolderWatch] ensureBoardUserExists 失败: " + e.getMessage());
        }
    }

    private boolean isImageFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".bmp");
    }

    private String matchAds(String userAge, String userGender,
                            List<AdProject> projects, Long userId) {
        var feedbacks = userAdFeedbackMapper.selectByUserId(userId);
        Map<Long, Integer> fbMap = new LinkedHashMap<>();
        Map<Long, Integer> exposureMap = new LinkedHashMap<>();
        for (var f : feedbacks) {
            fbMap.put(f.getProjectId(), f.getFeedback() != null ? f.getFeedback() : -2);
            exposureMap.put(f.getProjectId(), f.getExposureCount() != null ? f.getExposureCount() : 0);
        }

        String userAgeRange = normalizeAge(userAge);
        String userGenderNorm = normalizeGender(userGender);

        List<Map<String, Object>> scored = new ArrayList<>();
        for (var p : projects) {
            Integer fb = fbMap.getOrDefault(p.getId(), -2);
            // 跳过不感兴趣的 + 已看过的广告
            if ((fb != null && fb == -1) || exposureMap.getOrDefault(p.getId(), 0) > 0) continue;

            String aiTags = p.getAiTags() != null ? p.getAiTags() : "{}";
            String adAgeRange = "";
            String adGender = "";
            try {
                JsonNode tagNode = objectMapper.readTree(aiTags);
                adAgeRange = tagNode.has("ageRange") ? tagNode.get("ageRange").asText() : "";
                adGender = tagNode.has("gender") ? tagNode.get("gender").asText() : "";
            } catch (Exception ignored) {}

            int score = 0;
            if (!adAgeRange.isEmpty() && !userAgeRange.equals("无法判断")) {
                if (adAgeRange.equals(userAgeRange)) score += 30;
                else {
                    int adIdx = ageRangeIndex(adAgeRange);
                    int userIdx = ageRangeIndex(userAgeRange);
                    if (adIdx >= 0 && userIdx >= 0 && Math.abs(adIdx - userIdx) <= 1) score += 15;
                }
            } else score += 10;

            if (!adGender.isEmpty() && !userGenderNorm.equals("无法判断")) {
                if (adGender.equals("男女皆宜")) score += 25;
                else if (("男".equals(userGenderNorm) && "男性为主".equals(adGender))
                        || ("女".equals(userGenderNorm) && "女性为主".equals(adGender))) score += 25;
                else score += 15; // 性别不匹配也保底给分，不比未知标签的差
            } else score += 15;

            if (fb != null && fb == 1) score += 20;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", p.getId());
            item.put("projectName", p.getName());
            item.put("score", score);
            item.put("reason", score >= 70 ? "高度匹配" : score >= 50 ? "年龄匹配性别兼容" : score >= 30 ? "部分匹配" : "通用推荐");
            scored.add(item);
        }

        scored.sort((a, b) -> ((Integer) b.get("score")).compareTo((Integer) a.get("score")));

        List<Map<String, Object>> top5 = new ArrayList<>();
        for (int i = 0; i < Math.min(5, scored.size()); i++) {
            top5.add(scored.get(i));
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("recommendations", top5);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"recommendations\":[]}";
        }
    }

    private String extractAdIds(String matchRaw) {
        try {
            String cleaned = matchRaw.replaceAll("^```(?:json)?\\s*|```\\s*$", "").trim();
            JsonNode node = objectMapper.readTree(cleaned);
            if (node.has("recommendations")) {
                List<String> ids = new ArrayList<>();
                for (JsonNode n : node.get("recommendations")) {
                    if (n.has("projectId")) ids.add(n.get("projectId").asText());
                }
                return String.join(",", ids);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String normalizeAge(String age) {
        if (age == null || age.isEmpty()) return "无法判断";
        if (age.contains("-") || age.contains("儿童") || age.contains("青少年")
                || age.contains("青年") || age.contains("白领") || age.contains("中年") || age.contains("老年")) {
            return age;
        }
        try {
            int n = Integer.parseInt(age.trim());
            if (n <= 12) return "儿童0-12";
            if (n <= 17) return "青少年13-17";
            if (n <= 25) return "青年18-25";
            if (n <= 35) return "年轻白领25-35";
            if (n <= 50) return "中年35-50";
            return "中老年50+";
        } catch (NumberFormatException e) {
            return "无法判断";
        }
    }

    private String normalizeGender(String gender) {
        if (gender == null || gender.isEmpty()) return "无法判断";
        String g = gender.trim().toLowerCase(Locale.ROOT);
        if (g.equals("男") || g.equals("male") || g.equals("m")) return "男";
        if (g.equals("女") || g.equals("female") || g.equals("f")) return "女";
        if (g.contains("男") && !g.contains("女")) return "男";
        if (g.contains("女") && !g.contains("男")) return "女";
        return "无法判断";
    }

    private int ageRangeIndex(String range) {
        if (range.contains("儿童")) return 0;
        if (range.contains("青少年")) return 1;
        if (range.contains("青年")) return 2;
        if (range.contains("白领")) return 3;
        if (range.contains("中年")) return 4;
        if (range.contains("老年")) return 5;
        return -1;
    }

    private static class JsonProfile {
        String age = "";
        String gender = "";
    }
}
