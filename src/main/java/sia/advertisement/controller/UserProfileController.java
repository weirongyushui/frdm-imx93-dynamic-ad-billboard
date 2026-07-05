package sia.advertisement.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import sia.advertisement.entity.AdUser;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/user/profile")
public class UserProfileController {

    @Autowired
    private sia.advertisement.service.UserProfileService userProfileService;

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    @Value("${upload.base-url:/uploads}")
    private String baseUrl;

    @PostMapping("/analyze")
    public ApiResponse<Map<String, Object>> analyze(
            @RequestParam("file") MultipartFile file,
            HttpSession session) {

        AdUser user = (AdUser) session.getAttribute("loginUser");
        if (user == null) {
            return ApiResponse.error(401, "请先登录");
        }

        if (file == null || file.isEmpty()) {
            return ApiResponse.error(400, "未接收到照片");
        }

        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        Set<String> allowed = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp"));
        if (!allowed.contains(ext)) {
            return ApiResponse.error(400, "仅支持图片格式");
        }

        // 保存到磁盘
        String dateFolder = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String relativeDir = "profile/" + dateFolder;

        File baseDir = new File(uploadDir);
        if (!baseDir.isAbsolute()) {
            baseDir = new File(System.getProperty("user.dir"), uploadDir);
        }
        File targetDir = new File(baseDir, relativeDir);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return ApiResponse.error(500, "无法创建上传目录");
        }

        String fileName = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
        File targetFile = new File(targetDir, fileName);
        try {
            file.transferTo(targetFile);
        } catch (Exception e) {
            return ApiResponse.error(500, "保存文件失败: " + e.getMessage());
        }

        String accessUrl = baseUrl + "/" + relativeDir + "/" + fileName;

        // 调用 Service 分析画像 + 匹配广告 + 入库
        try {
            Map<String, Object> result = userProfileService.analyzeProfile(targetFile, accessUrl, user.getId());
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "分析失败: " + e.getMessage());
        }
    }
}
