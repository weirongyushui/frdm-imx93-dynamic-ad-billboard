package sia.advertisement.controller;

import sia.advertisement.dto.AdConfigDTO;
import sia.advertisement.dto.AdLayerDTO;
import sia.advertisement.dto.AdLayerEffectDTO;
import sia.advertisement.dto.AdPageVO;
import sia.advertisement.entity.AdLayer;
import sia.advertisement.entity.AdLayerEffect;
import sia.advertisement.entity.AdPage;
import sia.advertisement.entity.AdProject;
import sia.advertisement.entity.AdUser;
import sia.advertisement.mapper.AdProjectMapper;
import sia.advertisement.service.AdLayerEffectService;
import sia.advertisement.service.AdLayerService;
import sia.advertisement.service.AdPageService;
import sia.advertisement.service.AdProjectService;
import sia.advertisement.service.AiService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ad")
public class AdController {

    @Autowired
    private AdProjectService adProjectService;

    @Autowired
    private AdLayerService adLayerService;

    @Autowired
    private AdLayerEffectService adLayerEffectService;

    @Autowired
    private AdPageService adPageService;

    @Autowired
    private AiService aiService;

    @Autowired
    private AdProjectMapper adProjectMapper;

    @Autowired
    private sia.advertisement.service.UserProfileService userProfileService;

    private AdUser getLoginUser(HttpSession session) {
        AdUser user = (AdUser) session.getAttribute("loginUser");
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return user;
    }

    private void checkProjectOwner(Long projectId, Long userId) {
        AdProject project = adProjectService.getById(projectId);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
        }
        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此项目");
        }
    }

    private void checkLayerExists(Long projectId, Long layerId) {
        AdLayer layer = adLayerService.getById(layerId);
        if (layer == null || !layer.getProjectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图层不存在或不属于该项目");
        }
    }

    // ==================== 项目 CRUD ====================

    @GetMapping("/projects")
    public ApiResponse<List<AdProject>> getMyProjects(HttpSession session) {
        AdUser user = getLoginUser(session);
        List<AdProject> projects = adProjectService.getByUserId(user.getId());
        return ApiResponse.success(projects);
    }

    @GetMapping("/project/{id}")
    public ApiResponse<AdConfigDTO> getProject(@PathVariable Long id, HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(id, user.getId());

        AdProject project = adProjectService.getById(id);

        AdConfigDTO config = new AdConfigDTO();
        config.setProjectId(project.getId());
        config.setName(project.getName());
        config.setCanvasWidth(project.getCanvasWidth());
        config.setCanvasHeight(project.getCanvasHeight());
        config.setBackgroundColor(project.getBackgroundColor());
        config.setStatus(project.getStatus());
        config.setAiTags(project.getAiTags());

        List<AdPage> pages = adPageService.getByProjectId(project.getId());
        if (pages == null || pages.isEmpty()) {
            AdPage defaultPage = new AdPage();
            defaultPage.setProjectId(project.getId());
            defaultPage.setPageName("第1页");
            defaultPage.setSortOrder(0);
            adPageService.create(defaultPage);
            pages = List.of(defaultPage);
        }

        List<AdPageVO> pageVOs = pages.stream().map(page -> {
            AdPageVO vo = new AdPageVO();
            vo.setPageId(page.getId());
            vo.setPageName(page.getPageName());
            vo.setSortOrder(page.getSortOrder());
            vo.setBackgroundColor(page.getBackgroundColor());

            List<AdLayer> pageLayers = adLayerService.getByPageId(page.getId());
            List<AdLayerDTO> layerDTOs = pageLayers.stream().map(layer -> {
                AdLayerDTO dto = convertToLayerDTO(layer);
                AdLayerEffect effect = adLayerEffectService.getByLayerId(layer.getId());
                if (effect != null) {
                    dto.setEffect(convertToEffectDTO(effect));
                }
                return dto;
            }).collect(Collectors.toList());
            vo.setLayers(layerDTOs);
            return vo;
        }).collect(Collectors.toList());

        config.setPages(pageVOs);

        if (!pageVOs.isEmpty()) {
            config.setLayers(pageVOs.get(0).getLayers());
        }

        return ApiResponse.success(config);
    }

    @GetMapping("/projects/showcase")
    public ApiResponse<List<AdConfigDTO>> getShowcaseProjects(HttpSession session) {
        // 展示屏可能没有登录 session，此时返回全部已发布广告
        List<AdProject> projects;
        Object login = session.getAttribute("loginUser");
        if (login instanceof AdUser) {
            projects = adProjectService.getByUserId(((AdUser) login).getId());
        } else {
            projects = adProjectService.getAll();
        }

        List<AdConfigDTO> result = projects.stream().map(project -> {
            AdConfigDTO config = new AdConfigDTO();
            config.setProjectId(project.getId());
            config.setName(project.getName());
            config.setCanvasWidth(project.getCanvasWidth());
            config.setCanvasHeight(project.getCanvasHeight());
            config.setBackgroundColor(project.getBackgroundColor());
            config.setAiTags(project.getAiTags());

            List<AdPage> pages = adPageService.getByProjectId(project.getId());
            List<AdPageVO> pageVOs = new ArrayList<>();
            if (pages != null) {
                pageVOs = pages.stream().map(page -> {
                    AdPageVO vo = new AdPageVO();
                    vo.setPageId(page.getId());
                    vo.setPageName(page.getPageName());
                    vo.setSortOrder(page.getSortOrder());
                    vo.setBackgroundColor(page.getBackgroundColor());

                    List<AdLayer> pageLayers = adLayerService.getByPageId(page.getId());
                    List<AdLayerDTO> layerDTOs = pageLayers.stream().map(layer -> {
                        AdLayerDTO dto = convertToLayerDTO(layer);
                        AdLayerEffect effect = adLayerEffectService.getByLayerId(layer.getId());
                        if (effect != null) {
                            dto.setEffect(convertToEffectDTO(effect));
                        }
                        return dto;
                    }).collect(Collectors.toList());
                    vo.setLayers(layerDTOs);
                    return vo;
                }).collect(Collectors.toList());
            }
            config.setPages(pageVOs);
            return config;
        }).collect(Collectors.toList());

        return ApiResponse.success(result);
    }

    @PostMapping("/project")
    public ApiResponse<AdProject> createProject(@RequestBody AdConfigDTO config, HttpSession session) {
        AdUser user = getLoginUser(session);

        AdProject project = new AdProject();
        project.setUserId(user.getId());
        project.setName(config.getName() != null ? config.getName() : "未命名项目");
        project.setCanvasWidth(config.getCanvasWidth() != null ? config.getCanvasWidth() : 800);
        project.setCanvasHeight(config.getCanvasHeight() != null ? config.getCanvasHeight() : 450);
        project.setBackgroundColor(config.getBackgroundColor() != null ? config.getBackgroundColor() : "#1a1a1a");
        project.setStatus(config.getStatus() != null ? config.getStatus() : 1);
        adProjectService.create(project);

        AdPage defaultPage = new AdPage();
        defaultPage.setProjectId(project.getId());
        defaultPage.setPageName("第1页");
        defaultPage.setSortOrder(0);
        adPageService.create(defaultPage);

        if (config.getLayers() != null) {
            for (AdLayerDTO layerDTO : config.getLayers()) {
                AdLayer layer = convertToEntity(layerDTO);
                layer.setProjectId(project.getId());
                layer.setPageId(defaultPage.getId());
                if (layer.getSortOrder() == null) layer.setSortOrder(0);
                adLayerService.create(layer);

                if (layerDTO.getEffect() != null) {
                    AdLayerEffect effect = convertToEffectEntity(layerDTO.getEffect());
                    effect.setLayerId(layer.getId());
                    adLayerEffectService.create(effect);
                }
            }
        }

        // 为所有用户初始化该广告的反馈记录
        userProfileService.initFeedbackForProject(project.getId());

        return ApiResponse.success(project);
    }

    @PutMapping("/project/{id}")
    public ApiResponse<AdProject> updateProject(@PathVariable Long id, @RequestBody AdConfigDTO config,
                                                 HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(id, user.getId());

        AdProject project = adProjectService.getById(id);
        if (config.getName() != null) project.setName(config.getName());
        if (config.getCanvasWidth() != null) project.setCanvasWidth(config.getCanvasWidth());
        if (config.getCanvasHeight() != null) project.setCanvasHeight(config.getCanvasHeight());
        if (config.getBackgroundColor() != null) project.setBackgroundColor(config.getBackgroundColor());
        if (config.getStatus() != null) project.setStatus(config.getStatus());
        adProjectService.update(project);

        return ApiResponse.success(project);
    }

    @DeleteMapping("/project/{id}")
    public ApiResponse<String> deleteProject(@PathVariable Long id, HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(id, user.getId());
        adProjectService.delete(id);
        return ApiResponse.success("ok");
    }

    // ==================== 页面 CRUD ====================

    @GetMapping("/project/{projectId}/pages")
    public ApiResponse<List<AdPageVO>> getPages(@PathVariable Long projectId, HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(projectId, user.getId());

        List<AdPage> pages = adPageService.getByProjectId(projectId);
        List<AdPageVO> result = pages.stream().map(page -> {
            AdPageVO vo = new AdPageVO();
            vo.setPageId(page.getId());
            vo.setPageName(page.getPageName());
            vo.setSortOrder(page.getSortOrder());
            vo.setBackgroundColor(page.getBackgroundColor());

            List<AdLayer> pageLayers = adLayerService.getByPageId(page.getId());
            List<AdLayerDTO> layerDTOs = pageLayers.stream().map(layer -> {
                AdLayerDTO dto = convertToLayerDTO(layer);
                AdLayerEffect effect = adLayerEffectService.getByLayerId(layer.getId());
                if (effect != null) {
                    dto.setEffect(convertToEffectDTO(effect));
                }
                return dto;
            }).collect(Collectors.toList());
            vo.setLayers(layerDTOs);
            return vo;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @PostMapping("/project/{projectId}/page")
    public ApiResponse<AdPageVO> addPage(@PathVariable Long projectId, @RequestBody AdPageVO pageVO,
                                          HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(projectId, user.getId());

        List<AdPage> pages = adPageService.getByProjectId(projectId);
        int nextOrder = pages != null ? pages.size() : 0;

        AdPage page = new AdPage();
        page.setProjectId(projectId);
        page.setPageName(pageVO.getPageName() != null ? pageVO.getPageName() : "第" + (nextOrder + 1) + "页");
        page.setSortOrder(nextOrder);
        page.setBackgroundColor(pageVO.getBackgroundColor());
        adPageService.create(page);

        AdPageVO result = new AdPageVO();
        result.setPageId(page.getId());
        result.setPageName(page.getPageName());
        result.setSortOrder(page.getSortOrder());
        result.setBackgroundColor(page.getBackgroundColor());
        result.setLayers(List.of());
        return ApiResponse.success(result);
    }

    @PutMapping("/project/{projectId}/page/{pageId}")
    public ApiResponse<AdPageVO> updatePage(@PathVariable Long projectId, @PathVariable Long pageId,
                                             @RequestBody AdPageVO pageVO, HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(projectId, user.getId());

        AdPage page = adPageService.getById(pageId);
        if (page == null || !page.getProjectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在");
        }
        if (pageVO.getPageName() != null) page.setPageName(pageVO.getPageName());
        if (pageVO.getSortOrder() != null) page.setSortOrder(pageVO.getSortOrder());
        if (pageVO.getBackgroundColor() != null) page.setBackgroundColor(pageVO.getBackgroundColor());
        adPageService.update(page);

        AdPageVO result = new AdPageVO();
        result.setPageId(page.getId());
        result.setPageName(page.getPageName());
        result.setSortOrder(page.getSortOrder());
        result.setBackgroundColor(page.getBackgroundColor());
        return ApiResponse.success(result);
    }

    @DeleteMapping("/project/{projectId}/page/{pageId}")
    public ApiResponse<String> deletePage(@PathVariable Long projectId, @PathVariable Long pageId,
                                           HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(projectId, user.getId());

        AdPage page = adPageService.getById(pageId);
        if (page == null || !page.getProjectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在");
        }

        List<AdPage> pages = adPageService.getByProjectId(projectId);
        if (pages != null && pages.size() <= 1) {
            return ApiResponse.error(400, "至少保留一个页面");
        }

        adLayerService.deleteByPageId(pageId);
        adPageService.delete(pageId);
        return ApiResponse.success("ok");
    }

    @PutMapping("/project/{projectId}/page/{pageId}/layers")
    public ApiResponse<String> savePageLayers(@PathVariable Long projectId, @PathVariable Long pageId,
                                               @RequestBody List<AdLayerDTO> layerDTOs,
                                               HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(projectId, user.getId());

        AdPage page = adPageService.getById(pageId);
        if (page == null || !page.getProjectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在");
        }

        adLayerEffectService.deleteByPageId(pageId);
        adLayerService.deleteByPageId(pageId);

        if (layerDTOs != null) {
            for (int i = 0; i < layerDTOs.size(); i++) {
                AdLayerDTO layerDTO = layerDTOs.get(i);
                AdLayer layer = convertToEntity(layerDTO);
                layer.setProjectId(projectId);
                layer.setPageId(pageId);
                if (layer.getSortOrder() == null) layer.setSortOrder(i);
                adLayerService.create(layer);

                if (layerDTO.getEffect() != null) {
                    AdLayerEffect effect = convertToEffectEntity(layerDTO.getEffect());
                    effect.setLayerId(layer.getId());
                    adLayerEffectService.create(effect);
                }
            }
        }
        return ApiResponse.success("ok");
    }

    // ==================== AI 人群分析 ====================

    @PostMapping("/project/{id}/analyze-audience")
    public ApiResponse<String> analyzeAudience(@PathVariable Long id, HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(id, user.getId());

        AdProject project = adProjectService.getById(id);

        // 收集所有页面的完整图层信息
        List<AdPage> pages = adPageService.getByProjectId(id);
        StringBuilder allLayersInfo = new StringBuilder();
        List<String> imageUrls = new ArrayList<>();
        
        allLayersInfo.append("【广告项目信息】\n");
        allLayersInfo.append("项目名称：").append(project.getName() != null ? project.getName() : "未命名").append("\n");
        allLayersInfo.append("主背景色：").append(project.getBackgroundColor() != null ? project.getBackgroundColor() : "未知").append("\n");
        allLayersInfo.append("\n【图层详情】\n");

        int layerCount = 0;
        if (pages != null) {
            for (AdPage page : pages) {
                List<AdLayer> layers = adLayerService.getByPageId(page.getId());
                if (layers != null) {
                    for (AdLayer layer : layers) {
                        layerCount++;
                        allLayersInfo.append("--- 图层").append(layerCount).append(" ---\n");
                        allLayersInfo.append("类型：").append(layer.getLayerType() != null ? layer.getLayerType() : "未知").append("\n");
                        allLayersInfo.append("名称：").append(layer.getLayerName() != null ? layer.getLayerName() : "未命名").append("\n");
                        allLayersInfo.append("位置：(").append(layer.getPosX() != null ? layer.getPosX() : 0).append(", ")
                            .append(layer.getPosY() != null ? layer.getPosY() : 0).append(")\n");
                        allLayersInfo.append("尺寸：").append(layer.getWidth() != null ? layer.getWidth() : 0)
                            .append("×").append(layer.getHeight() != null ? layer.getHeight() : 0).append("\n");
                        allLayersInfo.append("层级：").append(layer.getZIndex() != null ? layer.getZIndex() : 0).append("\n");
                        allLayersInfo.append("透明度：").append(layer.getOpacity() != null ? layer.getOpacity() : 1.0).append("\n");
                        
                        if ("text".equals(layer.getLayerType())) {
                            allLayersInfo.append("文字内容：").append(layer.getTextContent() != null ? layer.getTextContent() : "无").append("\n");
                            allLayersInfo.append("字体大小：").append(layer.getFontSize() != null ? layer.getFontSize() : 24).append("px\n");
                            allLayersInfo.append("字体颜色：").append(layer.getFontColor() != null ? layer.getFontColor() : "#ffffff").append("\n");
                            allLayersInfo.append("字重：").append(layer.getFontWeight() != null ? layer.getFontWeight() : "normal").append("\n");
                            allLayersInfo.append("字体：").append(layer.getFontFamily() != null ? layer.getFontFamily() : "Microsoft YaHei").append("\n");
                            allLayersInfo.append("对齐：").append(layer.getTextAlign() != null ? layer.getTextAlign() : "left").append("\n");
                            allLayersInfo.append("行高：").append(layer.getLineHeight() != null ? layer.getLineHeight() : 1.5).append("\n");
                            allLayersInfo.append("字间距：").append(layer.getLetterSpacing() != null ? layer.getLetterSpacing() : 0).append("px\n");
                            allLayersInfo.append("文字样式：").append(layer.getFontStyle() != null ? layer.getFontStyle() : "normal").append("\n");
                            allLayersInfo.append("文字装饰：").append(layer.getTextDecoration() != null ? layer.getTextDecoration() : "none").append("\n");
                        } else if ("image".equals(layer.getLayerType())) {
                            allLayersInfo.append("图片URL：").append(layer.getImageUrl() != null ? layer.getImageUrl() : "未上传").append("\n");
                            if (layer.getImageUrl() != null && !layer.getImageUrl().isEmpty()) {
                                imageUrls.add(layer.getImageUrl());
                            }
                        }
                        
                        allLayersInfo.append("背景：").append(layer.getBackground() != null ? layer.getBackground() : "无").append("\n");
                        allLayersInfo.append("圆角：").append(layer.getBorderRadius() != null ? layer.getBorderRadius() : 0).append("px\n");
                        allLayersInfo.append("\n");
                    }
                }
            }
        }

        String result = aiService.analyzeAudience(
            project.getName(),
            allLayersInfo.toString(),
            project.getBackgroundColor(),
            imageUrls
        );

        // 保存标签到数据库
        adProjectMapper.updateAiTags(id, result);

        return ApiResponse.success(result);
    }

    // ==================== 手动编辑标签 ====================

    @PutMapping("/project/{id}/tags")
    public ApiResponse<String> updateTags(@PathVariable Long id, @RequestBody Map<String, Object> tagData, HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(id, user.getId());

        String tagsJson = (String) tagData.get("tags");
        if (tagsJson == null || tagsJson.isEmpty()) {
            return ApiResponse.error(400, "标签数据不能为空");
        }

        // 验证是否为有效JSON
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(tagsJson);
        } catch (Exception e) {
            return ApiResponse.error(400, "标签数据格式错误，必须是有效的JSON");
        }

        adProjectMapper.updateAiTags(id, tagsJson);
        return ApiResponse.success("标签更新成功");
    }

    @GetMapping("/project/{id}/tags")
    public ApiResponse<String> getTags(@PathVariable Long id, HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(id, user.getId());

        AdProject project = adProjectService.getById(id);
        String tags = project.getAiTags();
        if (tags == null || tags.isEmpty()) {
            tags = "{\"product\":\"\",\"category\":\"其他\",\"ageRange\":\"全年龄段\",\"gender\":\"男女皆宜\",\"audience\":\"\",\"style\":\"\"}";
        }
        return ApiResponse.success(tags);
    }

    // ==================== 图层 CRUD ====================

    @GetMapping("/project/{projectId}/layers")
    public ApiResponse<List<AdLayerDTO>> getLayers(@PathVariable Long projectId, HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(projectId, user.getId());

        List<AdLayer> layers = adLayerService.getByProjectId(projectId);
        List<AdLayerDTO> result = layers.stream().map(layer -> {
            AdLayerDTO dto = convertToLayerDTO(layer);
            AdLayerEffect effect = adLayerEffectService.getByLayerId(layer.getId());
            if (effect != null) {
                dto.setEffect(convertToEffectDTO(effect));
            }
            return dto;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @PostMapping("/project/{projectId}/layer")
    public ApiResponse<AdLayerDTO> addLayer(@PathVariable Long projectId, @RequestBody AdLayerDTO layerDTO,
                                             HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(projectId, user.getId());

        AdLayer layer = convertToEntity(layerDTO);
        layer.setProjectId(projectId);
        adLayerService.create(layer);

        if (layerDTO.getEffect() != null) {
            AdLayerEffect effect = convertToEffectEntity(layerDTO.getEffect());
            effect.setLayerId(layer.getId());
            adLayerEffectService.create(effect);
        }

        AdLayerDTO result = convertToLayerDTO(layer);
        AdLayerEffect savedEffect = adLayerEffectService.getByLayerId(layer.getId());
        if (savedEffect != null) {
            result.setEffect(convertToEffectDTO(savedEffect));
        }
        return ApiResponse.success(result);
    }

    @PutMapping("/project/{projectId}/layer/{layerId}")
    public ApiResponse<AdLayerDTO> updateLayer(@PathVariable Long projectId,
                                                @PathVariable Long layerId,
                                                @RequestBody AdLayerDTO layerDTO,
                                                HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(projectId, user.getId());
        checkLayerExists(projectId, layerId);

        AdLayer existingLayer = adLayerService.getById(layerId);
        if (layerDTO.getLayerName() != null) existingLayer.setLayerName(layerDTO.getLayerName());
        if (layerDTO.getLayerType() != null) existingLayer.setLayerType(layerDTO.getLayerType());
        if (layerDTO.getZIndex() != null) existingLayer.setZIndex(layerDTO.getZIndex());
        if (layerDTO.getPosX() != null) existingLayer.setPosX(layerDTO.getPosX());
        if (layerDTO.getPosY() != null) existingLayer.setPosY(layerDTO.getPosY());
        if (layerDTO.getWidth() != null) existingLayer.setWidth(layerDTO.getWidth());
        if (layerDTO.getHeight() != null) existingLayer.setHeight(layerDTO.getHeight());
        if (layerDTO.getTextContent() != null) existingLayer.setTextContent(layerDTO.getTextContent());
        if (layerDTO.getFontSize() != null) existingLayer.setFontSize(layerDTO.getFontSize());
        if (layerDTO.getFontColor() != null) existingLayer.setFontColor(layerDTO.getFontColor());
        if (layerDTO.getFontWeight() != null) existingLayer.setFontWeight(layerDTO.getFontWeight());
        if (layerDTO.getFontFamily() != null) existingLayer.setFontFamily(layerDTO.getFontFamily());
        if (layerDTO.getFontStyle() != null) existingLayer.setFontStyle(layerDTO.getFontStyle());
        if (layerDTO.getTextDecoration() != null) existingLayer.setTextDecoration(layerDTO.getTextDecoration());
        if (layerDTO.getLineHeight() != null) existingLayer.setLineHeight(layerDTO.getLineHeight());
        if (layerDTO.getLetterSpacing() != null) existingLayer.setLetterSpacing(layerDTO.getLetterSpacing());
        if (layerDTO.getOpacity() != null) existingLayer.setOpacity(layerDTO.getOpacity());
        if (layerDTO.getTextAlign() != null) existingLayer.setTextAlign(layerDTO.getTextAlign());
        if (layerDTO.getBorderRadius() != null) existingLayer.setBorderRadius(layerDTO.getBorderRadius());
        if (layerDTO.getBackground() != null) existingLayer.setBackground(layerDTO.getBackground());
        if (layerDTO.getImageUrl() != null) existingLayer.setImageUrl(layerDTO.getImageUrl());
        if (layerDTO.getSortOrder() != null) existingLayer.setSortOrder(layerDTO.getSortOrder());
        adLayerService.update(existingLayer);

        if (layerDTO.getEffect() != null) {
            AdLayerEffect existingEffect = adLayerEffectService.getByLayerId(layerId);
            AdLayerEffect newEffect = convertToEffectEntity(layerDTO.getEffect());
            newEffect.setLayerId(layerId);
            if (existingEffect != null) {
                newEffect.setId(existingEffect.getId());
                adLayerEffectService.update(newEffect);
            } else {
                adLayerEffectService.create(newEffect);
            }
        }

        AdLayerDTO result = convertToLayerDTO(existingLayer);
        AdLayerEffect savedEffect = adLayerEffectService.getByLayerId(layerId);
        if (savedEffect != null) {
            result.setEffect(convertToEffectDTO(savedEffect));
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/project/{projectId}/layer/{layerId}/duplicate")
    public ApiResponse<AdLayerDTO> duplicateLayer(@PathVariable Long projectId,
                                                   @PathVariable Long layerId,
                                                   HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(projectId, user.getId());
        checkLayerExists(projectId, layerId);

        AdLayer srcLayer = adLayerService.getById(layerId);
        AdLayerEffect srcEffect = adLayerEffectService.getByLayerId(layerId);

        AdLayer newLayer = new AdLayer();
        newLayer.setProjectId(projectId);
        newLayer.setPageId(srcLayer.getPageId());
        newLayer.setLayerName((srcLayer.getLayerName() != null ? srcLayer.getLayerName() : "新图层") + " 副本");
        newLayer.setLayerType(srcLayer.getLayerType());
        newLayer.setZIndex(srcLayer.getZIndex());
        newLayer.setPosX(srcLayer.getPosX() != null ? srcLayer.getPosX() + 20 : 20);
        newLayer.setPosY(srcLayer.getPosY() != null ? srcLayer.getPosY() + 20 : 20);
        newLayer.setWidth(srcLayer.getWidth());
        newLayer.setHeight(srcLayer.getHeight());
        newLayer.setTextContent(srcLayer.getTextContent());
        newLayer.setFontSize(srcLayer.getFontSize());
        newLayer.setFontColor(srcLayer.getFontColor());
        newLayer.setFontWeight(srcLayer.getFontWeight());
        newLayer.setFontFamily(srcLayer.getFontFamily());
        newLayer.setFontStyle(srcLayer.getFontStyle());
        newLayer.setTextDecoration(srcLayer.getTextDecoration());
        newLayer.setLineHeight(srcLayer.getLineHeight());
        newLayer.setLetterSpacing(srcLayer.getLetterSpacing());
        newLayer.setOpacity(srcLayer.getOpacity());
        newLayer.setTextAlign(srcLayer.getTextAlign());
        newLayer.setBorderRadius(srcLayer.getBorderRadius());
        newLayer.setBackground(srcLayer.getBackground());
        newLayer.setImageUrl(srcLayer.getImageUrl());
        newLayer.setSortOrder(srcLayer.getSortOrder() != null ? srcLayer.getSortOrder() + 1 : 0);
        adLayerService.create(newLayer);

        if (srcEffect != null) {
            AdLayerEffect newEffect = new AdLayerEffect();
            newEffect.setLayerId(newLayer.getId());
            newEffect.setShadowEnabled(srcEffect.getShadowEnabled());
            newEffect.setShadowX(srcEffect.getShadowX());
            newEffect.setShadowY(srcEffect.getShadowY());
            newEffect.setShadowBlur(srcEffect.getShadowBlur());
            newEffect.setShadowColor(srcEffect.getShadowColor());
            newEffect.setGlowEnabled(srcEffect.getGlowEnabled());
            newEffect.setGlowSize(srcEffect.getGlowSize());
            newEffect.setGlowColor(srcEffect.getGlowColor());
            newEffect.setBorderEnabled(srcEffect.getBorderEnabled());
            newEffect.setBorderWidth(srcEffect.getBorderWidth());
            newEffect.setBorderColor(srcEffect.getBorderColor());
            newEffect.setBorderStyle(srcEffect.getBorderStyle());
            newEffect.setBrightness(srcEffect.getBrightness());
            newEffect.setContrast(srcEffect.getContrast());
            newEffect.setSaturation(srcEffect.getSaturation());
            newEffect.setBlur(srcEffect.getBlur());
            newEffect.setPerspectiveEnabled(srcEffect.getPerspectiveEnabled());
            newEffect.setPerspectiveX(srcEffect.getPerspectiveX());
            newEffect.setPerspectiveY(srcEffect.getPerspectiveY());
            newEffect.setPresetEffect(srcEffect.getPresetEffect());
            adLayerEffectService.create(newEffect);
        }

        AdLayerDTO result = convertToLayerDTO(newLayer);
        AdLayerEffect savedEffect = adLayerEffectService.getByLayerId(newLayer.getId());
        if (savedEffect != null) {
            result.setEffect(convertToEffectDTO(savedEffect));
        }
        return ApiResponse.success(result);
    }

    @DeleteMapping("/project/{projectId}/layer/{layerId}")
    public ApiResponse<String> deleteLayer(@PathVariable Long projectId, @PathVariable Long layerId,
                                            HttpSession session) {
        AdUser user = getLoginUser(session);
        checkProjectOwner(projectId, user.getId());
        checkLayerExists(projectId, layerId);

        adLayerEffectService.deleteByLayerId(layerId);
        adLayerService.delete(layerId);
        return ApiResponse.success("ok");
    }

    // ==================== DTO 转换 ====================

    private AdLayerDTO convertToLayerDTO(AdLayer layer) {
        AdLayerDTO dto = new AdLayerDTO();
        dto.setId(layer.getId());
        dto.setProjectId(layer.getProjectId());
        dto.setPageId(layer.getPageId());
        dto.setLayerName(layer.getLayerName());
        dto.setLayerType(layer.getLayerType());
        dto.setZIndex(layer.getZIndex());
        dto.setPosX(layer.getPosX());
        dto.setPosY(layer.getPosY());
        dto.setWidth(layer.getWidth());
        dto.setHeight(layer.getHeight());
        dto.setTextContent(layer.getTextContent());
        dto.setFontSize(layer.getFontSize());
        dto.setFontColor(layer.getFontColor());
        dto.setFontWeight(layer.getFontWeight());
        dto.setFontFamily(layer.getFontFamily());
        dto.setFontStyle(layer.getFontStyle());
        dto.setTextDecoration(layer.getTextDecoration());
        dto.setLineHeight(layer.getLineHeight());
        dto.setLetterSpacing(layer.getLetterSpacing());
        dto.setOpacity(layer.getOpacity());
        dto.setTextAlign(layer.getTextAlign());
        dto.setBorderRadius(layer.getBorderRadius());
        dto.setBackground(layer.getBackground());
        dto.setImageUrl(layer.getImageUrl());
        dto.setSortOrder(layer.getSortOrder());
        return dto;
    }

    private AdLayer convertToEntity(AdLayerDTO dto) {
        AdLayer layer = new AdLayer();
        layer.setLayerName(dto.getLayerName());
        layer.setLayerType(dto.getLayerType());
        layer.setZIndex(dto.getZIndex());
        layer.setPosX(dto.getPosX());
        layer.setPosY(dto.getPosY());
        layer.setWidth(dto.getWidth());
        layer.setHeight(dto.getHeight());
        layer.setTextContent(dto.getTextContent());
        layer.setFontSize(dto.getFontSize());
        layer.setFontColor(dto.getFontColor());
        layer.setFontWeight(dto.getFontWeight());
        layer.setFontFamily(dto.getFontFamily());
        layer.setFontStyle(dto.getFontStyle());
        layer.setTextDecoration(dto.getTextDecoration());
        layer.setLineHeight(dto.getLineHeight());
        layer.setLetterSpacing(dto.getLetterSpacing());
        layer.setOpacity(dto.getOpacity());
        layer.setTextAlign(dto.getTextAlign());
        layer.setBorderRadius(dto.getBorderRadius());
        layer.setBackground(dto.getBackground());
        layer.setImageUrl(dto.getImageUrl());
        layer.setSortOrder(dto.getSortOrder());
        return layer;
    }

    private AdLayerEffectDTO convertToEffectDTO(AdLayerEffect effect) {
        AdLayerEffectDTO dto = new AdLayerEffectDTO();
        dto.setShadowEnabled(effect.getShadowEnabled());
        dto.setShadowX(effect.getShadowX());
        dto.setShadowY(effect.getShadowY());
        dto.setShadowBlur(effect.getShadowBlur());
        dto.setShadowColor(effect.getShadowColor());
        dto.setGlowEnabled(effect.getGlowEnabled());
        dto.setGlowSize(effect.getGlowSize());
        dto.setGlowColor(effect.getGlowColor());
        dto.setBorderEnabled(effect.getBorderEnabled());
        dto.setBorderWidth(effect.getBorderWidth());
        dto.setBorderColor(effect.getBorderColor());
        dto.setBorderStyle(effect.getBorderStyle());
        dto.setBrightness(effect.getBrightness());
        dto.setContrast(effect.getContrast());
        dto.setSaturation(effect.getSaturation());
        dto.setBlur(effect.getBlur());
        dto.setPerspectiveEnabled(effect.getPerspectiveEnabled());
        dto.setPerspectiveX(effect.getPerspectiveX());
        dto.setPerspectiveY(effect.getPerspectiveY());
        dto.setPresetEffect(effect.getPresetEffect());
        return dto;
    }

    private AdLayerEffect convertToEffectEntity(AdLayerEffectDTO dto) {
        AdLayerEffect effect = new AdLayerEffect();
        effect.setShadowEnabled(dto.getShadowEnabled());
        effect.setShadowX(dto.getShadowX());
        effect.setShadowY(dto.getShadowY());
        effect.setShadowBlur(dto.getShadowBlur());
        effect.setShadowColor(dto.getShadowColor());
        effect.setGlowEnabled(dto.getGlowEnabled());
        effect.setGlowSize(dto.getGlowSize());
        effect.setGlowColor(dto.getGlowColor());
        effect.setBorderEnabled(dto.getBorderEnabled());
        effect.setBorderWidth(dto.getBorderWidth());
        effect.setBorderColor(dto.getBorderColor());
        effect.setBorderStyle(dto.getBorderStyle());
        effect.setBrightness(dto.getBrightness());
        effect.setContrast(dto.getContrast());
        effect.setSaturation(dto.getSaturation());
        effect.setBlur(dto.getBlur());
        effect.setPerspectiveEnabled(dto.getPerspectiveEnabled());
        effect.setPerspectiveX(dto.getPerspectiveX());
        effect.setPerspectiveY(dto.getPerspectiveY());
        effect.setPresetEffect(dto.getPresetEffect());
        return effect;
    }
}
