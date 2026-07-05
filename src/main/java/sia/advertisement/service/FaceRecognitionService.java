package sia.advertisement.service;

import cn.smartjavaai.face.model.facerec.FaceModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sia.advertisement.entity.AdProject;
import sia.advertisement.entity.AdUser;
import sia.advertisement.entity.FaceEncoding;
import sia.advertisement.entity.UserAdFeedback;
import sia.advertisement.mapper.AdProjectMapper;
import sia.advertisement.mapper.AdUserMapper;
import sia.advertisement.mapper.FaceEncodingMapper;
import sia.advertisement.mapper.UserAdFeedbackMapper;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class FaceRecognitionService {

    @Lazy
    @Autowired(required = false)
    private FaceModel faceModel;

    @Autowired
    private FaceEncodingMapper faceMapper;

    @Autowired
    private AdUserMapper adUserMapper;

    @Autowired
    private UserAdFeedbackMapper feedbackMapper;

    @Autowired
    private AdProjectMapper adProjectMapper;

    @Value("${face.threshold:0.6f}")
    private float threshold;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FaceModel getFaceModel() {
        if (faceModel == null) {
            throw new RuntimeException("人脸识别模型未加载，请检查 SeetaFace6 Native 库是否正常");
        }
        return faceModel;
    }

    // 提取人脸特征向量（128维 float 数组）
    public float[] extractFeature(byte[] jpgBytes) throws Exception {
        FaceModel model = getFaceModel();
        float[] feature = model.extractTopFaceFeature(jpgBytes);
        if (feature == null || feature.length == 0) {
            throw new RuntimeException("未检测到人脸或提取特征失败");
        }
        return feature;
    }

    // 余弦相似度（兜底，SmartJavaAI 的 calculSimilar 可用）
    public double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public String vectorToJson(float[] v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < v.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(v[i]);
            }
            sb.append(']');
            return sb.toString();
        }
    }

    public float[] vectorFromJson(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return objectMapper.readValue(s, float[].class);
        } catch (Exception e) {
            return null;
        }
    }

    // 主入口：匹配或创建用户，返回 userId
    @Transactional
    public Long resolveUserId(byte[] jpgBytes, String photoUrl) throws Exception {
        long start = System.currentTimeMillis();
        float[] feature = extractFeature(jpgBytes);
        log.info("[FaceRec] 提取特征完成, 维度={}, 耗时={}ms", feature.length, (System.currentTimeMillis() - start));

        List<FaceEncoding> all = faceMapper.selectAll();
        Long   bestUserId = null;
        double bestSim    = -1.0;
        FaceEncoding bestRow = null;

        for (FaceEncoding enc : all) {
            float[] dbVec = vectorFromJson(enc.getFaceVector());
            if (dbVec == null) continue;
            double sim = cosineSimilarity(feature, dbVec);
            if (sim > bestSim) {
                bestSim  = sim;
                bestRow  = enc;
                bestUserId = enc.getUserId();
            }
        }

        if (bestUserId != null && bestSim >= threshold) {
            faceMapper.updateLastSeen(bestRow.getId());
            log.info("[FaceRec] ✅ 命中老用户 userId={} 相似度={:.3f} 总耗时={}ms",
                     bestUserId, bestSim, (System.currentTimeMillis() - start));
            return bestUserId;
        }

        // 没命中 → 新建用户
        AdUser u = new AdUser();
        u.setUsername("face_" + System.currentTimeMillis());
        u.setPassword("face-auto-created");
        u.setNickname("访客_" + LocalDateTime.now().toLocalDate());
        adUserMapper.insert(u);

        FaceEncoding enc = new FaceEncoding();
        enc.setUserId(u.getId());
        enc.setFaceVector(vectorToJson(feature));
        enc.setPhotoSampleUrl(photoUrl);
        enc.setFirstSeen(LocalDateTime.now());
        enc.setLastSeen(LocalDateTime.now());
        faceMapper.insert(enc);

        // 初始化反馈（每个项目一行）
        List<AdProject> projects = adProjectMapper.selectAll();
        for (AdProject p : projects) {
            UserAdFeedback fb = new UserAdFeedback();
            fb.setUserId(u.getId());
            fb.setProjectId(p.getId());
            fb.setFeedback(null);
            fb.setExposureCount(0);
            feedbackMapper.insert(fb);
        }

        log.info("[FaceRec] 🆕 新建用户 userId={} 最高相似度={:.3f}(库{}人) 总耗时={}ms",
                 u.getId(),
                 bestSim < 0 ? 0 : bestSim,
                 all.size(),
                 (System.currentTimeMillis() - start));
        return u.getId();
    }
}
