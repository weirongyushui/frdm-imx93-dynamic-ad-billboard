package sia.advertisement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sia.advertisement.entity.*;
import sia.advertisement.mapper.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserProfileService {

    @Autowired
    private AiService aiService;

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Autowired
    private UserProfileAnalysisMapper analysisMapper;

    @Autowired
    private UserAdFeedbackMapper feedbackMapper;

    @Autowired
    private AdProjectMapper adProjectMapper;

    @Autowired
    private AdUserMapper adUserMapper;

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public Map<String, Object> analyzeProfile(File photoFile, String photoUrl, Long userId) throws Exception {
        // 1. 转 base64
        byte[] bytes = Files.readAllBytes(photoFile.toPath());
        String base64 = "data:image/" + getExt(photoFile.getName()) + ";base64," +
                Base64.getEncoder().encodeToString(bytes);

        // 2. AI 分析画像
        String portraitRaw = aiService.analyzeUserPortrait(base64);
        JsonNode portrait = parseJson(portraitRaw);
        String ageRange = safeGet(portrait, "ageRange", "未知");
        String gender = safeGet(portrait, "gender", "未知");

        // 3. 收集所有广告标签 + 用户历史反馈
        List<AdProject> allProjects = adProjectMapper.selectAll();
        List<UserAdFeedback> feedbacks = feedbackMapper.selectByUserId(userId);
        String allAdsJson = buildAllAdsJson(allProjects, feedbacks);

        // 4. AI 匹配广告
        String matchRaw = aiService.matchAdsForUser(portraitRaw, allAdsJson);
        JsonNode matchResult = parseJson(matchRaw);
        String matchedIds = extractAdIds(matchResult);

        // 5. 入库 user_profile（覆盖更新）
        UserProfile existed = userProfileMapper.selectByUserId(userId);
        if (existed != null) {
            existed.setAgeRange(ageRange);
            existed.setGender(gender);
            existed.setMatchedAdIds(matchedIds);
            userProfileMapper.update(existed);
        } else {
            UserProfile profile = new UserProfile();
            profile.setUserId(userId);
            profile.setAgeRange(ageRange);
            profile.setGender(gender);
            profile.setMatchedAdIds(matchedIds);
            userProfileMapper.insert(profile);
        }

        // 6. 入库分析日志
        UserProfileAnalysis analysis = new UserProfileAnalysis();
        analysis.setUserId(userId);
        analysis.setPhotoUrl(photoUrl);
        analysis.setPortraitResult(portraitRaw);
        analysis.setMatchedResult(matchRaw);
        analysis.setMatchedAdIds(matchedIds);
        analysisMapper.insert(analysis);

        // 7. 构造返回
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("portrait", portraitRaw);
        result.put("recommendations", matchRaw);
        return result;
    }

    // 新广告创建后，为所有用户初始化反馈记录
    @Transactional
    public void initFeedbackForProject(Long projectId) {
        List<AdUser> users = adUserMapper.selectAll();
        for (AdUser user : users) {
            UserAdFeedback fb = new UserAdFeedback();
            fb.setUserId(user.getId());
            fb.setProjectId(projectId);
            fb.setFeedback(null);
            fb.setExposureCount(0);
            feedbackMapper.insert(fb);
        }
    }

    private String buildAllAdsJson(List<AdProject> projects, List<UserAdFeedback> feedbacks) {
        List<Map<String, Object>> ads = new ArrayList<>();
        Map<Long, UserAdFeedback> fbMap = feedbacks.stream()
                .collect(Collectors.toMap(UserAdFeedback::getProjectId, f -> f, (a, b) -> a));

        for (AdProject p : projects) {
            Map<String, Object> ad = new LinkedHashMap<>();
            ad.put("projectId", p.getId());
            ad.put("name", p.getName());
            ad.put("aiTags", p.getAiTags() != null ? p.getAiTags() : "{}");

            UserAdFeedback fb = fbMap.get(p.getId());
            if (fb != null) {
                ad.put("feedback", fb.getFeedback());
                ad.put("exposureCount", fb.getExposureCount());
            } else {
                ad.put("feedback", null);
                ad.put("exposureCount", 0);
            }
            ads.add(ad);
        }
        try {
            return objectMapper.writeValueAsString(ads);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String extractAdIds(JsonNode matchResult) {
        if (matchResult.has("recommendations")) {
            JsonNode arr = matchResult.get("recommendations");
            List<String> ids = new ArrayList<>();
            for (JsonNode n : arr) {
                if (n.has("projectId")) ids.add(n.get("projectId").asText());
            }
            return String.join(",", ids);
        }
        return "";
    }

    private JsonNode parseJson(String raw) {
        try {
            String cleaned = raw.replaceAll("^```(?:json)?\\s*|```\\s*$", "").trim();
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String safeGet(JsonNode node, String key, String defaultVal) {
        if (node.has(key) && !node.get(key).asText().isEmpty()) {
            return node.get(key).asText();
        }
        return defaultVal;
    }

    // 直接保存画像和日志（供 ActionController 调用）
    @Transactional
    public void savePortraitAndLog(Long userId, String photoUrl,
                                    String portraitJson, String matchedJson, String matchedAdIds) {
        // 解析画像
        JsonNode portrait = parseJson(portraitJson);
        String ageRange = safeGet(portrait, "ageRange", "未知");
        String gender = safeGet(portrait, "gender", "未知");

        // user_profile 覆盖更新
        UserProfile existed = userProfileMapper.selectByUserId(userId);
        if (existed != null) {
            existed.setAgeRange(ageRange);
            existed.setGender(gender);
            existed.setMatchedAdIds(matchedAdIds);
            userProfileMapper.update(existed);
        } else {
            UserProfile profile = new UserProfile();
            profile.setUserId(userId);
            profile.setAgeRange(ageRange);
            profile.setGender(gender);
            profile.setMatchedAdIds(matchedAdIds);
            userProfileMapper.insert(profile);
        }

        // 追加分析日志
        UserProfileAnalysis analysis = new UserProfileAnalysis();
        analysis.setUserId(userId);
        analysis.setPhotoUrl(photoUrl);
        analysis.setPortraitResult(portraitJson);
        analysis.setMatchedResult(matchedJson);
        analysis.setMatchedAdIds(matchedAdIds);
        analysisMapper.insert(analysis);
    }

    private String getExt(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "jpeg";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
