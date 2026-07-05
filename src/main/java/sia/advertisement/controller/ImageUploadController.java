package sia.advertisement.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import sia.advertisement.entity.AdUser;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/image")
public class ImageUploadController {

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    @Value("${upload.base-url:/uploads}")
    private String baseUrl;

    private static final Set<String> ALLOWED_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp"
    ));

    @PostMapping("/upload")
    public ApiResponse<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", defaultValue = "general") String folder,
            HttpSession session) {

        AdUser user = (AdUser) session.getAttribute("loginUser");
        if (user == null) {
            return ApiResponse.error(401, "请先登录");
        }

        if (file == null || file.isEmpty()) {
            return ApiResponse.error(400, "未接收到文件");
        }

        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        if (ext.isEmpty() || !ALLOWED_EXT.contains(ext)) {
            return ApiResponse.error(400, "仅支持图片格式: " + ALLOWED_EXT);
        }

        long size = file.getSize();
        if (size <= 0) {
            return ApiResponse.error(400, "文件内容为空");
        }
        if (size > 10 * 1024 * 1024) {
            return ApiResponse.error(400, "文件过大，超过 10MB");
        }

        String dateFolder = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String relativeDir = folder + "/" + dateFolder;

        File baseDir = new File(uploadDir);
        if (!baseDir.isAbsolute()) {
            String userDir = System.getProperty("user.dir");
            baseDir = new File(userDir, uploadDir);
        }
        File targetDir = new File(baseDir, relativeDir);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return ApiResponse.error(500, "无法创建上传目录");
        }

        String fileName = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
        File targetFile = new File(targetDir, fileName);
        try {
            file.transferTo(targetFile);
        } catch (IOException e) {
            return ApiResponse.error(500, "保存文件失败: " + e.getMessage());
        }

        String accessUrl = baseUrl + "/" + folder + "/" + dateFolder + "/" + fileName;

        Map<String, String> result = new LinkedHashMap<>();
        result.put("url", accessUrl);
        result.put("path", targetFile.getAbsolutePath());
        result.put("fileName", fileName);
        result.put("size", String.valueOf(size));
        result.put("originalName", originalName == null ? "" : originalName);

        return ApiResponse.success(result);
    }

    @PostMapping("/upload-multi")
    public ApiResponse<List<Map<String, String>>> uploadMultiple(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "folder", defaultValue = "general") String folder,
            HttpSession session) {

        AdUser user = (AdUser) session.getAttribute("loginUser");
        if (user == null) {
            return ApiResponse.error(401, "请先登录");
        }

        List<Map<String, String>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            ApiResponse<Map<String, String>> r = upload(file, folder, session);
            if (r.getCode() == 200 && r.getData() != null) {
                results.add(r.getData());
            }
        }
        if (results.isEmpty()) {
            return ApiResponse.error(400, "没有可上传的有效文件");
        }
        return ApiResponse.success(results);
    }
}
