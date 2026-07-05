/*
 Navicat Premium Dump SQL

 Source Server         : 127.0.0.1
 Source Server Type    : MySQL
 Source Server Version : 80042 (8.0.42)
 Source Host           : localhost:3306
 Source Schema         : advertisement

 Target Server Type    : MySQL
 Target Server Version : 80042 (8.0.42)
 File Encoding         : 65001

 Date: 06/07/2026 02:54:31
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for ad_layer
-- ----------------------------
DROP TABLE IF EXISTS `ad_layer`;
CREATE TABLE `ad_layer`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `page_id` bigint NULL DEFAULT NULL,
  `layer_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '新图层',
  `layer_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'text' COMMENT 'text=文字 image=图片',
  `z_index` int NULL DEFAULT 0,
  `pos_x` int NOT NULL DEFAULT 0,
  `pos_y` int NOT NULL DEFAULT 0,
  `width` int NOT NULL DEFAULT 200,
  `height` int NOT NULL DEFAULT 50,
  `text_content` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `font_size` int NOT NULL DEFAULT 24,
  `font_color` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '#ffffff',
  `font_weight` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'bold',
  `font_family` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'Microsoft YaHei',
  `font_style` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'normal',
  `text_decoration` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'none',
  `line_height` decimal(4, 2) NOT NULL DEFAULT 1.50,
  `letter_spacing` decimal(5, 2) NOT NULL DEFAULT 0.00,
  `opacity` decimal(3, 2) NOT NULL DEFAULT 1.00,
  `text_align` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'center',
  `border_radius` int NOT NULL DEFAULT 0,
  `background` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'rgba(0,0,0,0.6)',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_project_id`(`project_id` ASC) USING BTREE,
  CONSTRAINT `fk_layer_project` FOREIGN KEY (`project_id`) REFERENCES `ad_project` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 126 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '广告图层表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for ad_layer_effect
-- ----------------------------
DROP TABLE IF EXISTS `ad_layer_effect`;
CREATE TABLE `ad_layer_effect`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `layer_id` bigint NOT NULL,
  `shadow_enabled` tinyint(1) NOT NULL DEFAULT 0,
  `shadow_x` int NOT NULL DEFAULT 5,
  `shadow_y` int NOT NULL DEFAULT 5,
  `shadow_blur` int NOT NULL DEFAULT 10,
  `shadow_color` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'rgba(0,0,0,0.5)',
  `glow_enabled` tinyint(1) NOT NULL DEFAULT 0,
  `glow_size` int NOT NULL DEFAULT 20,
  `glow_color` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'rgba(108,92,231,0.8)',
  `border_enabled` tinyint(1) NOT NULL DEFAULT 0,
  `border_width` int NOT NULL DEFAULT 1,
  `border_color` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '#6c5ce7',
  `border_style` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'solid',
  `brightness` int NOT NULL DEFAULT 100,
  `contrast` int NOT NULL DEFAULT 100,
  `saturation` int NOT NULL DEFAULT 100,
  `blur` int NOT NULL DEFAULT 0,
  `perspective_enabled` tinyint(1) NOT NULL DEFAULT 0,
  `perspective_x` int NOT NULL DEFAULT 0,
  `perspective_y` int NOT NULL DEFAULT 0,
  `preset_effect` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'glow/shadow/gradient/glass/neon/emboss',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_layer_id`(`layer_id` ASC) USING BTREE,
  CONSTRAINT `fk_effect_layer` FOREIGN KEY (`layer_id`) REFERENCES `ad_layer` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 31 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '图层特效表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for ad_page
-- ----------------------------
DROP TABLE IF EXISTS `ad_page`;
CREATE TABLE `ad_page`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `page_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '第1页',
  `sort_order` int NOT NULL DEFAULT 0,
  `background_color` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_page_project`(`project_id` ASC) USING BTREE,
  CONSTRAINT `fk_page_project` FOREIGN KEY (`project_id`) REFERENCES `ad_project` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 18 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for ad_project
-- ----------------------------
DROP TABLE IF EXISTS `ad_project`;
CREATE TABLE `ad_project`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '未命名项目',
  `canvas_width` int NOT NULL DEFAULT 800,
  `canvas_height` int NOT NULL DEFAULT 450,
  `background_color` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '#1a1a1a',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1=草稿 2=已完成',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `ai_tags` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'AI人群分析标签JSON',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_project_user` FOREIGN KEY (`user_id`) REFERENCES `ad_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '广告项目表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for ad_user
-- ----------------------------
DROP TABLE IF EXISTS `ad_user`;
CREATE TABLE `ad_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for board_photo
-- ----------------------------
DROP TABLE IF EXISTS `board_photo`;
CREATE TABLE `board_photo`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '固定板子用户ID',
  `photo_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'HTTP访问路径',
  `photo_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '磁盘绝对路径',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ANALYZED/FAILED',
  `portrait_json` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'AI画像JSON',
  `matched_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT 'AI推荐结果JSON',
  `matched_ad_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '推荐项目ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `analyzed_at` datetime NULL DEFAULT NULL COMMENT '分析完成时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_board_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_board_status`(`status` ASC) USING BTREE,
  CONSTRAINT `fk_board_user` FOREIGN KEY (`user_id`) REFERENCES `ad_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 22 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '板子照片分析记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for face_encodings
-- ----------------------------
DROP TABLE IF EXISTS `face_encodings`;
CREATE TABLE `face_encodings`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `face_vector` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '人脸特征向量JSON',
  `photo_sample_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `first_seen` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_seen` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_last_seen`(`last_seen` ASC) USING BTREE,
  CONSTRAINT `face_encodings_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `ad_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user_ad_feedback
-- ----------------------------
DROP TABLE IF EXISTS `user_ad_feedback`;
CREATE TABLE `user_ad_feedback`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `project_id` bigint NOT NULL,
  `portrait_snapshot` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '反馈时画像快照',
  `feedback` int NULL DEFAULT NULL COMMENT 'NULL=未反馈 1=感兴趣 0=中立 -1=不感兴趣',
  `exposure_count` int NOT NULL DEFAULT 0 COMMENT '曝光次数',
  `first_exposure_time` datetime NULL DEFAULT NULL,
  `feedback_time` datetime NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_feedback_user_ad`(`user_id` ASC, `project_id` ASC) USING BTREE,
  INDEX `idx_feedback_project`(`project_id` ASC) USING BTREE,
  CONSTRAINT `fk_feedback_project` FOREIGN KEY (`project_id`) REFERENCES `ad_project` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_feedback_user` FOREIGN KEY (`user_id`) REFERENCES `ad_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户广告反馈表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user_profile
-- ----------------------------
DROP TABLE IF EXISTS `user_profile`;
CREATE TABLE `user_profile`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `age_range` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'AI分析年龄范围',
  `gender` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'AI分析性别',
  `matched_ad_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '最近AI推荐项目ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_profile_user`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_profile_user` FOREIGN KEY (`user_id`) REFERENCES `ad_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户画像表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user_profile_analysis
-- ----------------------------
DROP TABLE IF EXISTS `user_profile_analysis`;
CREATE TABLE `user_profile_analysis`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `photo_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '分析的照片路径',
  `portrait_result` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT 'AI画像原始JSON',
  `matched_result` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT 'AI推荐原始JSON',
  `matched_ad_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '本次推荐项目ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_analysis_user`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_analysis_user` FOREIGN KEY (`user_id`) REFERENCES `ad_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 23 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '画像分析日志表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
