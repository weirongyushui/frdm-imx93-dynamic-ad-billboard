package sia.advertisement.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import sia.advertisement.entity.AdUser;
import sia.advertisement.entity.BoardPhoto;
import sia.advertisement.mapper.AdUserMapper;
import sia.advertisement.mapper.*;
import sia.advertisement.service.AiService;
import sia.advertisement.service.FaceRecognitionService;
import sia.advertisement.service.UserProfileService;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class ActionController {

    @Value("${action.token:abc123}")
    private String actionToken;

    @Value("${board.user-id:1}")
    private Long boardUserId;

    @Value("${face.enabled:true}")
    private boolean faceEnabled;

    @Value("${ai.analysis.enabled:true}")
    private boolean aiAnalysisEnabled;

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    @Value("${upload.base-url:/uploads}")
    private String baseUrl;

    @Autowired
    private AiService aiService;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private FaceRecognitionService faceRecognitionService;

    @Autowired
    private BoardPhotoMapper boardPhotoMapper;

    @Autowired
    private AdProjectMapper adProjectMapper;

    @Autowired
    private UserAdFeedbackMapper userAdFeedbackMapper;

    @Autowired
    private AdUserMapper adUserMapper;

    @Autowired
    private ActionStateHolder stateHolder;

    private final Map<String, Long> lastExecuteTime = new ConcurrentHashMap<>();
    private static final long THROTTLE_MS = 1000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/action/last")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLastAction() {
        return ResponseEntity.ok(ApiResponse.success(new LinkedHashMap<>(stateHolder.getLastAction())));
    }

    @PostMapping("/action/clear")
    public ResponseEntity<ApiResponse<String>> clearLastAction() {
        stateHolder.clear();
        return ResponseEntity.ok(ApiResponse.success("ok"));
    }

    @PostMapping("/action")
    public ResponseEntity<ApiResponse<String>> handleAction(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ActionRequest request) {

        if (!validateToken(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "Unauthorized"));
        }

        String action = request.getAction();
        if (action == null || action.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "action is required"));
        }

        if (!checkThrottle(action)) {
            System.out.println("[ActionController] 重复请求被限流: " + action);
            return ResponseEntity.ok(ApiResponse.success("throttled"));
        }

        executeAction(action);
        return ResponseEntity.ok(ApiResponse.success("ok"));
    }

    // ==================== 板子JSON匹配接口（无照片，纯gender+age） ====================

    @PostMapping("/action/match")
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleMatch(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {

        if (!validateToken(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "Unauthorized"));
        }

        Object rawAge = body.get("age");
        Object rawGender = body.get("gender");
        String age = rawAge != null ? rawAge.toString() : "";
        String gender = rawGender != null ? rawGender.toString() : "";

        System.out.println("[ActionController] 板子JSON匹配: gender=" + gender + " age=" + age);

        String portraitRaw = "{\"ageRange\":\"" + age + "\",\"gender\":\"" + gender + "\"}";

        var projects = adProjectMapper.selectAll();
        Long realUserId = boardUserId;
        ensureBoardUserExists();
        String matchRaw = matchAdsByRules(age, gender, projects, realUserId);
        String matchedIds = extractAdIds(matchRaw);

        // 写入共享Map
        String top1Id = "";
        String nextFallbackId = "";
        if (!matchedIds.isEmpty()) {
            String[] arr = matchedIds.split(",");
            top1Id = arr[0].trim();
            if (arr.length > 1) nextFallbackId = arr[1].trim();
        }
        Map<String, Object> actionData = new LinkedHashMap<>();
        actionData.put("action", "AUTO_SWITCH");
        actionData.put("userId", realUserId);
        actionData.put("portrait", portraitRaw);
        actionData.put("recommendations", matchRaw);
        actionData.put("matchedAdIds", matchedIds);
        actionData.put("currentProjectId", top1Id);
        actionData.put("nextFallbackId", nextFallbackId);
        actionData.put("timestamp", Instant.now().toEpochMilli());
        stateHolder.putAll(actionData);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", realUserId);
        result.put("portrait", portraitRaw);
        result.put("recommendations", matchRaw);
        result.put("matchedAdIds", matchedIds);
        result.put("currentProjectId", top1Id);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ==================== 照片分析接口（人脸识别 + 画像 + 推荐） ====================

    @PostMapping("/action/photo")
    public ResponseEntity<ApiResponse<Map<String, Object>>> handlePhoto(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "gender", required = false) String gender,
            @RequestParam(value = "age", required = false) String age) {

        if (!validateToken(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "Unauthorized"));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "未接收到照片"));
        }

        try {
            // 记录上一个用户对当前广告的曝光（新人脸出现 = 旧人看完了）
            recordExposureForPreviousUser();

            // 1. 保存到磁盘
            String originalName = file.getOriginalFilename();
            String ext = "jpg";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            }
            String dateFolder = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String relativeDir = "board/" + dateFolder;

            File baseDir = new File(uploadDir);
            if (!baseDir.isAbsolute()) {
                baseDir = new File(System.getProperty("user.dir"), uploadDir);
            }
            File targetDir = new File(baseDir, relativeDir);
            if (!targetDir.exists()) targetDir.mkdirs();

            String fileName = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
            File targetFile = new File(targetDir, fileName);
            file.transferTo(targetFile);

            String accessUrl = baseUrl + "/" + relativeDir + "/" + fileName;
            String absolutePath = targetFile.getAbsolutePath();

            byte[] bytes = java.nio.file.Files.readAllBytes(targetFile.toPath());

            // 2. 人脸识别 → 匹配或创建用户，拿到真实 userId
            Long realUserId;
            if (faceEnabled) {
                try {
                    realUserId = faceRecognitionService.resolveUserId(bytes, accessUrl);
                } catch (Exception faceEx) {
                    System.out.println("[ActionController] 人脸识别失败，兜底用 boardUserId: " + faceEx.getMessage());
                    ensureBoardUserExists();
                    realUserId = boardUserId;
                }
            } else {
                System.out.println("[ActionController] 人脸识别已禁用(face.enabled=false)，使用 boardUserId=" + boardUserId);
                ensureBoardUserExists();
                realUserId = boardUserId;
            }

            // 3. 入库 board_photo
            BoardPhoto photo = new BoardPhoto();
            photo.setUserId(realUserId);
            photo.setPhotoUrl(accessUrl);
            photo.setPhotoPath(absolutePath);
            photo.setStatus("PENDING");
            boardPhotoMapper.insert(photo);

            String portraitRaw;
            String matchRaw;
            String matchedIds;

            // 板子直接传了性别和年龄 → 用规则匹配，不走AI
            boolean boardProvidedProfile = (gender != null && !gender.isEmpty())
                                        || (age != null && !age.isEmpty());

            if (boardProvidedProfile) {
                String resolvedAge = age != null ? age : "无法判断";
                String resolvedGender = gender != null ? gender : "无法判断";
                System.out.println("[ActionController] 板子提供画像: gender=" + resolvedGender + " age=" + resolvedAge);

                portraitRaw = "{\"ageRange\":\"" + resolvedAge + "\",\"gender\":\"" + resolvedGender + "\"}";

                var projects = adProjectMapper.selectAll();
                matchRaw = matchAdsByRules(resolvedAge, resolvedGender, projects, realUserId);
                matchedIds = extractAdIds(matchRaw);

                userProfileService.savePortraitAndLog(realUserId, accessUrl,
                        portraitRaw, matchRaw, matchedIds);

            } else if (aiAnalysisEnabled) {
                // 4. AI 分析画像
                String base64 = "data:image/" + ext + ";base64," + Base64.getEncoder().encodeToString(bytes);
                portraitRaw = aiService.analyzeUserPortrait(base64);

                // 5. 收集所有广告标签 + 该用户历史反馈
                var projects = adProjectMapper.selectAll();
                String allAdsJson = buildAdsJson(projects, realUserId);

                // 6. AI 匹配广告
                matchRaw = aiService.matchAdsForUser(portraitRaw, allAdsJson);

                // 7. 提取 matchedAdIds
                matchedIds = extractAdIds(matchRaw);

                // 8. 保存到 user_profile + user_profile_analysis
                userProfileService.savePortraitAndLog(realUserId, accessUrl,
                        portraitRaw, matchRaw, matchedIds);
            } else {
                System.out.println("[ActionController] AI分析已禁用(ai.analysis.enabled=false)，跳过画像分析和广告推荐");
                portraitRaw = "{\"ageRange\":\"未分析\",\"gender\":\"未分析\"}";
                matchRaw = "{\"recommendations\":[]}";
                matchedIds = "";
            }

            // 9. UPDATE board_photo → ANALYZED
            boardPhotoMapper.updateAnalysisResult(photo.getId(), "ANALYZED",
                    portraitRaw, matchRaw, matchedIds);

            // 10. 写入共享Map → showcase 轮询使用
            String top1Id = "";
            if (!matchedIds.isEmpty()) {
                String[] arr = matchedIds.split(",");
                top1Id = arr[0].trim();
            }
            String nextFallbackId = "";
            if (matchedIds.length() > 0) {
                String[] arr = matchedIds.split(",");
                if (arr.length > 1) nextFallbackId = arr[1].trim();
            }
            Map<String, Object> actionData = new LinkedHashMap<>();
            actionData.put("action", "AUTO_SWITCH");
            actionData.put("userId", realUserId);
            actionData.put("portrait", portraitRaw);
            actionData.put("recommendations", matchRaw);
            actionData.put("matchedAdIds", matchedIds);
            actionData.put("currentProjectId", top1Id);
            actionData.put("nextFallbackId", nextFallbackId);
            actionData.put("timestamp", Instant.now().toEpochMilli());
            stateHolder.putAll(actionData);

            // 11. 返回
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("photoId", photo.getId());
            result.put("userId", realUserId);
            result.put("portrait", portraitRaw);
            result.put("recommendations", matchRaw);

            return ResponseEntity.ok(ApiResponse.success(result));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, "分析失败: " + e.getMessage()));
        }
    }

    // ==================== 反馈接口 ====================

    @PostMapping("/action/feedback")
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleFeedback(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {

        if (!validateToken(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "Unauthorized"));
        }

        Object rawAction = body.get("action");
        String action = rawAction != null ? rawAction.toString() : "";
        if (!"USER_LEFT".equals(action)) {
            return ResponseEntity.ok(ApiResponse.success(new LinkedHashMap<>()));
        }

        // 优先用最近一次识别出来的真实 userId（共享Map里有），兜底用 boardUserId
        Long feedbackUserId = boardUserId;
        Object lastUidObj = stateHolder.getLastAction().get("userId");
        if (lastUidObj instanceof Number) {
            feedbackUserId = ((Number) lastUidObj).longValue();
        }

        // 记录不感兴趣
        Object currentDisplayed = stateHolder.getLastAction().get("currentProjectId");
        Long currentProjectId = currentDisplayed instanceof Number
                ? ((Number) currentDisplayed).longValue() : null;

        if (currentProjectId != null) {
            userAdFeedbackMapper.updateFeedback(feedbackUserId, currentProjectId, -1);
            System.out.println("[ActionController] feedback=-1 userId=" + feedbackUserId
                    + " projectId=" + currentProjectId);
        }

        // 取下一个推荐
        String nextId = (String) stateHolder.getLastAction().get("nextFallbackId");
        if (nextId == null) {
            String ids = (String) stateHolder.getLastAction().get("matchedAdIds");
            if (ids != null && !ids.isEmpty()) {
                String[] arr = ids.split(",");
                if (arr.length > 1) nextId = arr[1].trim();
            }
        }

        String nextNext = null;
        if (nextId != null) {
            String ids = (String) stateHolder.getLastAction().get("matchedAdIds");
            if (ids != null) {
                String[] arr = ids.split(",");
                for (int i = 0; i < arr.length - 1; i++) {
                    if (arr[i].trim().equals(nextId)) {
                        nextNext = arr[i + 1].trim();
                        break;
                    }
                }
            }
        }

        Map<String, Object> actionData = new LinkedHashMap<>();
        actionData.put("action", "USER_LEFT");
        actionData.put("userId", feedbackUserId);
        actionData.put("nextProjectId", nextId != null ? nextId : "");
        actionData.put("nextFallbackId", nextNext != null ? nextNext : "");
        actionData.put("timestamp", Instant.now().toEpochMilli());
        stateHolder.putAll(actionData);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nextProjectId", nextId);
        result.put("userId", feedbackUserId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ==================== 辅助方法 ====================

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
            System.out.println("[ActionController] 自动创建板子用户 id=" + boardUserId);
        } catch (Exception e) {
            System.out.println("[ActionController] ensureBoardUserExists 失败: " + e.getMessage());
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
                System.out.println("[ActionController] 记录曝光: userId=" + prevUserId + " projectId=" + prevProjectId);
            }
        } catch (Exception e) {
            System.err.println("[ActionController] 记录曝光失败: " + e.getMessage());
        }
    }

    private boolean validateToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return false;
        return actionToken.equals(authorization.substring(7));
    }

    private boolean checkThrottle(String action) {
        long now = Instant.now().toEpochMilli();
        Long lastTime = lastExecuteTime.get(action);
        if (lastTime != null && (now - lastTime) < THROTTLE_MS) return false;
        lastExecuteTime.put(action, now);
        return true;
    }

    private void executeAction(String action) {
        switch (action) {
            case "LEFT_SWIPE":  doLeftSwipe();  break;
            case "RIGHT_SWIPE": doRightSwipe(); break;
            case "CHANGE_AD":   doChangeAd();   break;
            default: System.out.println("[ActionController] 未知动作: " + action);
        }
    }

    private void doLeftSwipe() {
        System.out.println("[ActionController] 左滑，广告牌上一页");
        stateHolder.getLastAction().put("action", "LEFT_SWIPE");
        stateHolder.getLastAction().put("timestamp", Instant.now().toEpochMilli());
    }

    private void doRightSwipe() {
        System.out.println("[ActionController] 右滑，广告牌下一页");
        stateHolder.getLastAction().put("action", "RIGHT_SWIPE");
        stateHolder.getLastAction().put("timestamp", Instant.now().toEpochMilli());
    }

    private void doChangeAd() {
        System.out.println("[ActionController] 更换广告内容");
        stateHolder.getLastAction().put("action", "CHANGE_AD");
        stateHolder.getLastAction().put("timestamp", Instant.now().toEpochMilli());
    }

    private String buildAdsJson(List<sia.advertisement.entity.AdProject> projects, Long userId) {
        var feedbacks = userAdFeedbackMapper.selectByUserId(userId);
        Map<Long, Integer> fbMap = new LinkedHashMap<>();
        for (var f : feedbacks) {
            fbMap.put(f.getProjectId(), f.getFeedback() != null ? f.getFeedback() : -2);
        }

        List<Map<String, Object>> ads = new ArrayList<>();
        for (var p : projects) {
            Map<String, Object> ad = new LinkedHashMap<>();
            ad.put("projectId", p.getId());
            ad.put("name", p.getName());
            ad.put("aiTags", p.getAiTags() != null ? p.getAiTags() : "{}");
            Integer fbVal = fbMap.get(p.getId());
            ad.put("feedback", fbVal != null ? fbVal : -2);
            ads.add(ad);
        }
        try {
            return objectMapper.writeValueAsString(ads);
        } catch (Exception e) {
            return "[]";
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
        } catch (Exception e) { /* ignore */ }
        return "";
    }

    // ==================== 规则匹配（板子直接传性别+年龄时使用） ====================

    private String matchAdsByRules(String userAge, String userGender,
                                    List<sia.advertisement.entity.AdProject> projects,
                                    Long userId) {
        var feedbacks = userAdFeedbackMapper.selectByUserId(userId);
        Map<Long, Integer> fbMap = new LinkedHashMap<>();
        Map<Long, Integer> exposureMap = new LinkedHashMap<>();
        for (var f : feedbacks) {
            fbMap.put(f.getProjectId(), f.getFeedback() != null ? f.getFeedback() : -2);
            exposureMap.put(f.getProjectId(), f.getExposureCount() != null ? f.getExposureCount() : 0);
        }

        String userAgeRange = normalizeAge(userAge);
        String userGenderNorm = normalizeGender(userGender);

        System.out.println("[matchAdsByRules] userAgeRange=" + userAgeRange + " userGender=" + userGenderNorm);

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

            // 年龄匹配（30分）
            if (!adAgeRange.isEmpty() && !userAgeRange.equals("无法判断")) {
                if (adAgeRange.equals(userAgeRange)) {
                    score += 30;
                } else {
                    // 宽松匹配：年龄段有重叠 或 相邻段
                    int adIdx = ageRangeIndex(adAgeRange);
                    int userIdx = ageRangeIndex(userAgeRange);
                    if (adIdx >= 0 && userIdx >= 0 && Math.abs(adIdx - userIdx) <= 1) {
                        score += 15;
                    }
                }
            } else {
                score += 10; // 广告未标年龄，给基础分
            }

            // 性别匹配（25分）
            if (!adGender.isEmpty() && !userGenderNorm.equals("无法判断")) {
                if (adGender.equals("男女皆宜")) {
                    score += 25;
                } else if (("男".equals(userGenderNorm) && "男性为主".equals(adGender))
                        || ("女".equals(userGenderNorm) && "女性为主".equals(adGender))) {
                    score += 25;
                } else {
                    score += 0; // 不匹配不加分
                }
            } else {
                score += 15; // 无法判断，给中性分
            }

            // 历史感兴趣加分（20分）
            if (fb != null && fb == 1) {
                score += 20;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", p.getId());
            item.put("projectName", p.getName());
            item.put("score", score);
            item.put("reason", buildReason(score, userAgeRange, adAgeRange, userGenderNorm, adGender));
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

    private String normalizeAge(String age) {
        if (age == null || age.isEmpty()) return "无法判断";
        // 如果已经是范围格式，直接返回
        if (age.contains("-") || age.contains("儿童") || age.contains("青少年")
                || age.contains("青年") || age.contains("白领") || age.contains("中年") || age.contains("老年")) {
            return age;
        }
        // 尝试解析数字
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
        if (g.contains("男") && g.contains("女")) return "无法判断";
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

    private String buildReason(int score, String userAge, String adAge,
                               String userGender, String adGender) {
        if (score >= 70) return "年龄和性别高度匹配";
        if (score >= 50) return "年龄匹配，性别兼容";
        if (score >= 30) return "部分特征匹配";
        if (score >= 15) return "基本信息吻合";
        return "通用推荐";
    }

    public static class ActionRequest {
        private String action;
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }
}
