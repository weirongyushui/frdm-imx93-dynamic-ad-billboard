package sia.advertisement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class AiService {

    @Value("${ai.api.key}")
    private String apiKey;

    @Value("${ai.api.url}")
    private String apiUrl;

    @Value("${ai.api.model}")
    private String model;

    @Value("${ai.api.model-vision}")
    private String modelVision;

    @Value("${server.public-url:http://localhost:8083}")
    private String serverPublicUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public AiService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public String chat(List<Map<String, String>> messages) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);

            List<Map<String, String>> allMessages = new ArrayList<>();
            Map<String, String> systemMsg = new LinkedHashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content",
                "你是一个广告设计助手，可以帮助用户设计广告文案、提供创意建议、优化广告内容。\n" +
                "\n" +
                "### 画布状态感知\n" +
                "在用户的消息中，会包含 [画布状态] 标记的 JSON 数据，包含当前画布的所有信息：\n" +
                "- width/height: 画布尺寸\n" +
                "- backgroundColor: 背景色\n" +
                "- layers: 图层数组，每个图层包含 id、type(text/image)、x、y、width、height、content、fontSize、color 等属性\n" +
                "\n" +
                "你必须先分析画布状态，了解当前页面上已有的内容，然后再决定如何操作。例如：\n" +
                "- 用户说'把标题变大'，你需要先找到标题图层的ID，然后用 update_layer 命令修改 fontSize\n" +
                "- 用户说'删除图片'，你需要找到图片类型的图层，然后用 delete_layer 命令删除\n" +
                "- 用户说'添加副标题'，你需要根据已有图层的位置，合理安排新图层的位置\n" +
                "\n" +
                "你还可以直接操作广告画布。当用户要求你添加、修改或删除画布上的内容时，你需要在回复中使用 JSON 命令块来执行操作。\n" +
                "\n" +
                "## 可用的画布操作命令\n" +
                "\n" +
                "在回复中，当你需要操作画布时，请将 JSON 命令包裹在 ```json 代码块中，可以包含多个命令组成的 JSON 数组。\n" +
                "\n" +
                "### add_text_layer - 添加文字图层\n" +
                "```json\n" +
                "{\"action\": \"add_text_layer\", \"params\": {\"text\": \"文字内容\", \"x\": 300, \"y\": 100, \"width\": 240, \"height\": 60, \"fontSize\": 32, \"color\": \"#ffffff\", \"fontWeight\": \"bold\", \"fontFamily\": \"Microsoft YaHei\", \"fontStyle\": \"normal\", \"textDecoration\": \"none\", \"lineHeight\": 1.5, \"letterSpacing\": 0, \"opacity\": 1.0, \"textAlign\": \"center\", \"layerName\": \"标题\"}}\n" +
                "```\n" +
                "\n" +
                "### add_image_layer - 添加图片图层\n" +
                "```json\n" +
                "{\"action\": \"add_image_layer\", \"params\": {\"x\": 200, \"y\": 150, \"width\": 300, \"height\": 200, \"layerName\": \"产品图\", \"imageUrl\": \"\", \"borderRadius\": 8, \"opacity\": 1.0}}\n" +
                "```\n" +
                "imageUrl可选，若提供则直接显示该图片（需是可访问的URL或相对路径）；若不提供则创建一个待上传的图片占位区，提示用户上传图片。borderRadius是圆角，默认4px。\n" +
                "\n" +
                "### set_image - 设置图片图层的图片内容\n" +
                "```json\n" +
                "{\"action\": \"set_image\", \"params\": {\"layerId\": \"layer_xxx\", \"imageUrl\": \"\", \"borderRadius\": 8, \"opacity\": 1.0}}\n" +
                "```\n" +
                "给已有的图片图层设置图片。imageUrl是图片地址（可以是http开头的URL或/upload/开头的本地路径）。如果layerId找不到，会尝试按索引匹配（0,1,2...）。\n" +
                "\n" +
                "### update_text - 更新文字图层内容\n" +
                "```json\n" +
                "{\"action\": \"update_text\", \"params\": {\"layerId\": \"layer_xxx\", \"text\": \"新的文字内容\"}}\n" +
                "```\n" +
                "\n" +
                "### update_layer - 更新图层属性\n" +
                "```json\n" +
                "{\"action\": \"update_layer\", \"params\": {\"layerId\": \"layer_xxx\", \"x\": 100, \"y\": 50, \"width\": 200, \"height\": 80, \"fontSize\": 28, \"color\": \"#FF6B6B\", \"fontWeight\": \"bold\", \"fontFamily\": \"SimHei\", \"fontStyle\": \"italic\", \"textDecoration\": \"underline\", \"lineHeight\": 1.8, \"letterSpacing\": 2, \"opacity\": 0.85, \"textAlign\": \"center\", \"borderRadius\": 10, \"imageUrl\": \"\"}}\n" +
                "```\n" +
                "通用图层属性更新。对于图片图层，可以额外传 imageUrl 来更换图片。支持layerId用图层ID字符串，也支持用数字索引（0是第一个图层）。\n" +
                "\n" +
                "### delete_layer - 删除指定图层\n" +
                "```json\n" +
                "{\"action\": \"delete_layer\", \"params\": {\"layerId\": \"layer_xxx\"}}\n" +
                "```\n" +
                "\n" +
                "### set_background - 设置画布背景色\n" +
                "```json\n" +
                "{\"action\": \"set_background\", \"params\": {\"color\": \"#1a1a2e\"}}\n" +
                "```\n" +
                "\n" +
                "### set_canvas_size - 设置画布尺寸\n" +
                "```json\n" +
                "{\"action\": \"set_canvas_size\", \"params\": {\"width\": 800, \"height\": 450}}\n" +
                "```\n" +
                "\n" +
                "### apply_effect - 给图层应用特效（glow/shadow/gradient/glass/neon/emboss）\n" +
                "```json\n" +
                "{\"action\": \"apply_effect\", \"params\": {\"layerId\": \"layer_xxx\", \"effect\": \"glow\"}}\n" +
                "```\n" +
                "\n" +
                "### clear_all - 清空所有图层\n" +
                "```json\n" +
                "{\"action\": \"clear_all\", \"params\": {}}\n" +
                "```\n" +
                "\n" +
                "### create_ad_layout - 一键创建完整广告布局\n" +
                "```json\n" +
                "{\"action\": \"create_ad_layout\", \"params\": {\"title\": \"主标题\", \"subtitle\": \"副标题\", \"cta\": \"立即购买\", \"style\": \"modern\", \"imageUrl\": \"\"}}\n" +
                "```\n" +
                "style可选: modern(现代紫)/warm(暖橙)/clean(科技蓝)。imageUrl可选，提供则直接显示图片，否则创建占位区。\n" +
                "\n" +
                "### reorder_layer - 调整图层层级\n" +
                "```json\n" +
                "{\"action\": \"reorder_layer\", \"params\": {\"direction\": \"front\"}}\n" +
                "```\n" +
                "direction可选: front/back/up/down\n" +
                "\n" +
                "### switch_to_project - 切换到指定广告项目\n" +
                "```json\n" +
                "{\"action\": \"switch_to_project\", \"params\": {\"projectId\": 1}}\n" +
                "```\n" +
                "\n" +
                "### preview_project - 预览指定广告项目\n" +
                "```json\n" +
                "{\"action\": \"preview_project\", \"params\": {\"projectId\": 1, \"page\": 0}}\n" +
                "```\n" +
                "如果用户没有指定projectId，系统会使用当前打开的项目ID。\n" +
                "\n" +
                "### next_page - 切换到当前项目的下一页\n" +
                "```json\n" +
                "{\"action\": \"next_page\", \"params\": {}}\n" +
                "```\n" +
                "如果已经是最后一页，会自动新建一页。\n" +
                "\n" +
                "### prev_page - 切换到当前项目的上一页\n" +
                "```json\n" +
                "{\"action\": \"prev_page\", \"params\": {}}\n" +
                "```\n" +
                "\n" +
                "## 图片图层操作重要说明\n" +
                "1. 你不能直接上传图片文件，但可以设置图片的URL地址（imageUrl参数）。\n" +
                "2. 如果用户说'放一张产品图'、'添加图片'等但没有提供具体图片URL，你应该：\n" +
                "   - 先创建图片图层占位区（不传imageUrl），放在合适的位置和大小\n" +
                "   - 告诉用户'我已在画布上放置了图片区域，请点击上传您的产品图片'\n" +
                "3. 如果用户提到了具体的图片URL或路径（http开头或/upload/开头），直接用set_image或update_layer设置。\n" +
                "4. 图片图层支持的属性：x, y, width, height, borderRadius, opacity, imageUrl\n" +
                "5. 修改图片图层的位置大小用update_layer命令，更换图片用set_image或update_layer的imageUrl参数。\n" +
                "\n" +
                "## 文字图层可用属性说明\n" +
                "fontFamily: 字体（Microsoft YaHei/SimSun/SimHei/KaiTi/FangSong/Arial/Times New Roman/Georgia/Verdana/Roboto）\n" +
                "fontStyle: 斜体（normal/italic）\n" +
                "textDecoration: 文字装饰（none/underline/line-through）\n" +
                "lineHeight: 行高倍数（1.0~2.5）\n" +
                "letterSpacing: 字间距px（0~20）\n" +
                "opacity: 透明度（0.0~1.0）\n" +
                "\n" +
                "## 使用规则\n" +
                "1. 每个命令 JSON 块可以包含单个命令对象，也可以包含一个命令数组来执行多个操作\n" +
                "2. 先用自然语言解释你要做什么，然后用 JSON 代码块执行操作\n" +
                "3. 对于文字图层，合理设计文字大小、位置和颜色，确保视觉效果美观\n" +
                "4. 默认画布尺寸为 800x450\n" +
                "5. layerId 用于更新和删除操作，添加图层时不需要指定\n" +
                "6. 请用中文回复。");
            allMessages.add(systemMsg);
            allMessages.addAll(messages);

            body.put("messages", allMessages);
            body.put("stream", false);

            String response = callApi(body);
            return extractContent(response);
        } catch (Exception e) {
            return "AI 服务暂时不可用，请稍后重试。错误：" + e.getMessage();
        }
    }

    public String analyzeImage(String base64Image, String prompt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelVision);

            List<Map<String, Object>> contentList = new ArrayList<>();

            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", prompt != null && !prompt.isEmpty()
                    ? prompt
                    : "请详细描述这张图片中人物的动作、姿态和行为，以及图片的整体内容。");
            contentList.add(textPart);

            Map<String, Object> imagePart = new LinkedHashMap<>();
            if (base64Image.startsWith("data:image/")) {
                imagePart.put("type", "image_base64");
                String base64Data = base64Image.split(",", 2)[1];
                imagePart.put("image_base64", base64Data);
            } else {
                imagePart.put("type", "image_url");
                Map<String, String> imageUrlObj = new LinkedHashMap<>();
                String absoluteUrl = convertToAbsoluteUrl(base64Image);
                imageUrlObj.put("url", absoluteUrl);
                imagePart.put("image_url", imageUrlObj);
            }
            contentList.add(imagePart);

            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", contentList);
            messages.add(userMsg);

            body.put("messages", messages);
            body.put("stream", false);

            String response = callApi(body);
            return extractContent(response);
        } catch (Exception e) {
            return "视觉识别服务暂时不可用，请稍后重试。错误：" + e.getMessage();
        }
    }

    public String analyzeAudience(String projectName, String textContents, String bgColor, List<String> imageUrls) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            boolean hasImages = imageUrls != null && !imageUrls.isEmpty();
            body.put("model", hasImages ? modelVision : model);

            List<Map<String, Object>> contentList = new ArrayList<>();

            String systemPrompt = 
                "你是一个专业广告受众与市场分析专家。请根据广告项目的完整信息（包括项目名称、所有图层的详细属性、文字内容、字体样式、颜色、位置布局、图片内容等），精准分析以下6个维度。\n" +
                "严格只返回一个JSON对象，不要任何解释性文字，不要markdown代码块。字段说明：\n" +
                "1. product: 该广告推销/宣传的商品或服务名称（如：智能手机、奶茶、英语培训、新能源汽车等）。若无法判断则填'未知商品'\n" +
                "2. category: 商品类别（数码家电/食品饮料/教育培训/美妆护肤/母婴用品/服装配饰/交通出行/医疗健康/金融理财/文化娱乐/房地产/其他）\n" +
                "3. ageRange: 主要适合年龄范围（儿童0-12/青少年13-17/青年18-25/年轻白领25-35/中年35-50/中老年50+/全年龄段）\n" +
                "4. gender: 适合性别（男性为主/女性为主/男女皆宜）\n" +
                "5. audience: 目标人群标签，2-3个词组描述（如：都市白领、大学生、新婚夫妇、健身达人、宝妈等）\n" +
                "6. style: 广告视觉风格（科技简约/活泼可爱/高端奢华/温馨生活/商务稳重/运动活力/文艺清新/国潮古风等）\n" +
                "字段全部用中文，保持简洁。示例：\n" +
                "{\"product\":\"无糖奶茶\",\"category\":\"食品饮料\",\"ageRange\":\"青年18-25\",\"gender\":\"男女皆宜\",\"audience\":\"大学生,都市白领,健身人群\",\"style\":\"活泼可爱\"}\n\n";

            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text",
                systemPrompt +
                "请分析以下广告的目标受众：\n" +
                "完整广告信息：\n" +
                (textContents != null && !textContents.isEmpty() ? textContents : "无详细信息") + "\n" +
                "请返回包含6个字段的JSON。");
            contentList.add(textPart);

            if (imageUrls != null && !imageUrls.isEmpty()) {
                for (String imgUrl : imageUrls) {
                    if (imgUrl != null && !imgUrl.isEmpty()) {
                        Map<String, Object> imagePart = new LinkedHashMap<>();
                        imagePart.put("type", "image_url");
                        Map<String, String> imageUrlObj = new LinkedHashMap<>();
                        String absoluteUrl = convertToAbsoluteUrl(imgUrl);
                        imageUrlObj.put("url", absoluteUrl);
                        imagePart.put("image_url", imageUrlObj);
                        contentList.add(imagePart);
                        System.out.println("[AI分析] 添加图片URL: " + absoluteUrl);
                    }
                }
                System.out.println("[AI分析] 共添加 " + imageUrls.size() + " 张图片");
            } else {
                System.out.println("[AI分析] 没有图片需要分析");
            }

            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", contentList);
            messages.add(userMsg);

            body.put("messages", messages);
            body.put("stream", false);

            String response = callApi(body);
            String content = extractContent(response);

            // 尝试解析返回值，确保包含所有6个字段
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(content.replaceAll("^```(?:json)?\\s*|```\\s*$", "").trim());
                Map<String, String> cleaned = new LinkedHashMap<>();
                cleaned.put("product", node.has("product") && !node.get("product").asText().isEmpty() ? node.get("product").asText() : "未知商品");
                cleaned.put("category", node.has("category") && !node.get("category").asText().isEmpty() ? node.get("category").asText() : "其他");
                cleaned.put("ageRange", node.has("ageRange") && !node.get("ageRange").asText().isEmpty() ? node.get("ageRange").asText() : "全年龄段");
                cleaned.put("gender", node.has("gender") && !node.get("gender").asText().isEmpty() ? node.get("gender").asText() : "男女皆宜");
                cleaned.put("audience", node.has("audience") && !node.get("audience").asText().isEmpty() ? node.get("audience").asText() : "大众人群");
                cleaned.put("style", node.has("style") && !node.get("style").asText().isEmpty() ? node.get("style").asText() : "通用");
                return objectMapper.writeValueAsString(cleaned);
            } catch (Exception parseErr) {
                return "{\"product\":\"未知商品\",\"category\":\"其他\",\"ageRange\":\"全年龄段\",\"gender\":\"男女皆宜\",\"audience\":\"大众人群\",\"style\":\"通用\"}";
            }
        } catch (Exception e) {
            return "{\"product\":\"未知商品\",\"category\":\"其他\",\"ageRange\":\"全年龄段\",\"gender\":\"男女皆宜\",\"audience\":\"大众人群\",\"style\":\"通用\"}";
        }
    }

    private String convertToAbsoluteUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("/")) {
            return serverPublicUrl + url;
        }
        return serverPublicUrl + "/" + url;
    }

    public String analyzeUserPortrait(String base64Image) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelVision);

            List<Map<String, Object>> contentList = new ArrayList<>();

            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text",
                "请分析这张照片中人物的年龄范围和性别。\n" +
                "严格只返回一个JSON对象，不要任何解释、不要markdown代码块。\n" +
                "字段说明：\n" +
                "ageRange: 年龄段（儿童0-12/青少年13-17/青年18-25/年轻白领25-35/中年35-50/中老年50+/无法判断）\n" +
                "gender: 性别（男/女/无法判断）\n" +
                "示例：{\"ageRange\":\"青年18-25\",\"gender\":\"女\"}");
            contentList.add(textPart);

            Map<String, Object> imagePart = new LinkedHashMap<>();
            if (base64Image.startsWith("data:image/")) {
                imagePart.put("type", "image_base64");
                String base64Data = base64Image.split(",", 2)[1];
                imagePart.put("image_base64", base64Data);
            } else {
                imagePart.put("type", "image_url");
                Map<String, String> imageUrlObj = new LinkedHashMap<>();
                String absoluteUrl = convertToAbsoluteUrl(base64Image);
                imageUrlObj.put("url", absoluteUrl);
                imagePart.put("image_url", imageUrlObj);
            }
            contentList.add(imagePart);

            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", contentList);
            messages.add(userMsg);

            body.put("messages", messages);
            body.put("stream", false);

            String response = callApi(body);
            String content = extractContent(response);

            // 清洗确保有效JSON
            try {
                JsonNode node = objectMapper.readTree(content.replaceAll("^```(?:json)?\\s*|```\\s*$", "").trim());
                Map<String, String> cleaned = new LinkedHashMap<>();
                cleaned.put("ageRange", node.has("ageRange") && !node.get("ageRange").asText().isEmpty()
                        ? node.get("ageRange").asText() : "无法判断");
                cleaned.put("gender", node.has("gender") && !node.get("gender").asText().isEmpty()
                        ? node.get("gender").asText() : "无法判断");
                return objectMapper.writeValueAsString(cleaned);
            } catch (Exception parseErr) {
                return "{\"ageRange\":\"无法判断\",\"gender\":\"无法判断\"}";
            }
        } catch (Exception e) {
            return "{\"ageRange\":\"无法判断\",\"gender\":\"无法判断\"}";
        }
    }

    public String matchAdsForUser(String portraitJson, String allAdsJson) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> systemMsg = new LinkedHashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content",
                "你是一个广告推荐专家。根据用户画像，从广告列表中推荐最匹配的5个广告。\n" +
                "严格只返回一个JSON对象，不要任何解释、不要markdown代码块。\n" +
                "格式：{\"recommendations\":[{\"projectId\":1,\"projectName\":\"名称\",\"score\":95,\"reason\":\"年龄和性别高度匹配\"}]}\n" +
                "\n" +
                "【严格排除规则】\n" +
                "1. feedback=-1 的广告：绝对不推荐，直接跳过\n" +
                "2. 用户性别为\"男\" 且 广告ai_tags.gender=\"女性为主\" → 不推荐\n" +
                "3. 用户性别为\"女\" 且 广告ai_tags.gender=\"男性为主\" → 不推荐\n" +
                "4. 年龄完全不匹配（如用户\"中老年50+\"推广告\"儿童0-12\"）→ 不推荐\n" +
                "\n" +
                "【匹配加减分规则】\n" +
                "1. 年龄匹配：用户ageRange与广告ai_tags.ageRange重叠 → +30分\n" +
                "2. 性别匹配：相同或\"男女皆宜\" → +25分\n" +
                "3. audience/品类/风格吻合 → +25分\n" +
                "4. feedback=1（历史感兴趣）→ +20分\n" +
                "5. feedback=null/exposureCount>0但未反馈 → +0分\n" +
                "\n" +
                "score 为 0-100 整数，reason 用简短中文说明推荐原因。\n" +
                "最多返回5条，按 score 降序排列。如果符合条件的不足5条，返回实际条数。");
            messages.add(systemMsg);

            Map<String, String> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content",
                "用户画像：\n" + portraitJson + "\n\n" +
                "所有广告列表（含ai_tags和历史feedback）：\n" + allAdsJson + "\n\n" +
                "请推荐最匹配的5个广告。");
            messages.add(userMsg);

            body.put("messages", messages);
            body.put("stream", false);

            String response = callApi(body);
            String content = extractContent(response);

            try {
                JsonNode node = objectMapper.readTree(content.replaceAll("^```(?:json)?\\s*|```\\s*$", "").trim());
                return objectMapper.writeValueAsString(node);
            } catch (Exception parseErr) {
                return "{\"recommendations\":[]}";
            }
        } catch (Exception e) {
            return "{\"recommendations\":[]}";
        }
    }

    private String callApi(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        String url = apiUrl + "/v1/chat/completions";

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return response.getBody();
    }

    private String extractContent(String responseJson) {
        try {
            Map<String, Object> map = objectMapper.readValue(responseJson, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null) {
                    Object content = message.get("content");
                    return content != null ? content.toString() : "暂无回复";
                }
            }
            return "暂无回复";
        } catch (Exception e) {
            return "解析响应失败";
        }
    }
}
