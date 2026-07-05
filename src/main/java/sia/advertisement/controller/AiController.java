package sia.advertisement.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import sia.advertisement.entity.AdUser;
import sia.advertisement.service.AiService;

import java.util.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    @Autowired
    private AiService aiService;

    private AdUser getLoginUser(HttpSession session) {
        AdUser user = (AdUser) session.getAttribute("loginUser");
        if (user == null) {
            return null;
        }
        return user;
    }

    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, Object> request, HttpSession session) {
        AdUser user = getLoginUser(session);
        if (user == null) {
            return ApiResponse.error(401, "请先登录");
        }

        List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");
        if (messages == null || messages.isEmpty()) {
            return ApiResponse.error(400, "消息不能为空");
        }

        String reply = aiService.chat(messages);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", reply);
        return ApiResponse.success(result);
    }

    @PostMapping("/vision")
    public ApiResponse<Map<String, Object>> vision(@RequestBody Map<String, Object> request, HttpSession session) {
        AdUser user = getLoginUser(session);
        if (user == null) {
            return ApiResponse.error(401, "请先登录");
        }

        String image = (String) request.get("image");
        if (image == null || image.isEmpty()) {
            return ApiResponse.error(400, "图片数据不能为空");
        }

        String prompt = (String) request.get("prompt");
        String reply = aiService.analyzeImage(image, prompt);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", reply);
        return ApiResponse.success(result);
    }
}
