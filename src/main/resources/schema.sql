CREATE DATABASE IF NOT EXISTS advertisement
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE advertisement;

DROP TABLE IF EXISTS ad_layer_effect;
DROP TABLE IF EXISTS ad_layer;
DROP TABLE IF EXISTS ad_page;
DROP TABLE IF EXISTS ad_project;
DROP TABLE IF EXISTS ad_user;

CREATE TABLE ad_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(100) NOT NULL,
    nickname VARCHAR(50) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户表';

CREATE TABLE ad_project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL DEFAULT '未命名项目',
    canvas_width INT NOT NULL DEFAULT 800,
    canvas_height INT NOT NULL DEFAULT 450,
    background_color VARCHAR(20) NOT NULL DEFAULT '#1a1a1a',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=草稿 2=已完成',
    ai_tags VARCHAR(500) DEFAULT NULL COMMENT 'AI人群分析标签JSON',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    CONSTRAINT fk_project_user FOREIGN KEY (user_id) REFERENCES ad_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='广告项目表';

CREATE TABLE ad_page (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    page_name VARCHAR(100) NOT NULL DEFAULT '第1页',
    sort_order INT NOT NULL DEFAULT 0,
    background_color VARCHAR(20) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_page_project (project_id),
    CONSTRAINT fk_page_project FOREIGN KEY (project_id) REFERENCES ad_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='广告页面表';

CREATE TABLE ad_layer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    page_id BIGINT DEFAULT NULL,
    layer_name VARCHAR(100) NOT NULL DEFAULT '新图层',
    layer_type VARCHAR(20) NOT NULL DEFAULT 'text' COMMENT 'text=文字 image=图片',
    z_index INT NOT NULL DEFAULT 0,
    pos_x INT NOT NULL DEFAULT 0,
    pos_y INT NOT NULL DEFAULT 0,
    width INT NOT NULL DEFAULT 200,
    height INT NOT NULL DEFAULT 50,
    text_content VARCHAR(500) DEFAULT NULL,
    font_size INT NOT NULL DEFAULT 24,
    font_color VARCHAR(20) NOT NULL DEFAULT '#ffffff',
    font_weight VARCHAR(20) NOT NULL DEFAULT 'bold',
    font_family VARCHAR(100) NOT NULL DEFAULT 'Microsoft YaHei',
    font_style VARCHAR(20) NOT NULL DEFAULT 'normal',
    text_decoration VARCHAR(20) NOT NULL DEFAULT 'none',
    line_height DECIMAL(4,2) NOT NULL DEFAULT 1.50,
    letter_spacing DECIMAL(5,2) NOT NULL DEFAULT 0,
    opacity DECIMAL(3,2) NOT NULL DEFAULT 1.00,
    text_align VARCHAR(20) NOT NULL DEFAULT 'center',
    border_radius INT NOT NULL DEFAULT 0,
    background VARCHAR(30) DEFAULT 'rgba(0,0,0,0.6)',
    image_url VARCHAR(500) DEFAULT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_project_id (project_id),
    CONSTRAINT fk_layer_project FOREIGN KEY (project_id) REFERENCES ad_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='广告图层表';

CREATE TABLE ad_layer_effect (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    layer_id BIGINT NOT NULL,
    shadow_enabled TINYINT(1) NOT NULL DEFAULT 0,
    shadow_x INT NOT NULL DEFAULT 5,
    shadow_y INT NOT NULL DEFAULT 5,
    shadow_blur INT NOT NULL DEFAULT 10,
    shadow_color VARCHAR(20) NOT NULL DEFAULT 'rgba(0,0,0,0.5)',
    glow_enabled TINYINT(1) NOT NULL DEFAULT 0,
    glow_size INT NOT NULL DEFAULT 20,
    glow_color VARCHAR(20) NOT NULL DEFAULT 'rgba(108,92,231,0.8)',
    border_enabled TINYINT(1) NOT NULL DEFAULT 0,
    border_width INT NOT NULL DEFAULT 1,
    border_color VARCHAR(20) NOT NULL DEFAULT '#6c5ce7',
    border_style VARCHAR(20) NOT NULL DEFAULT 'solid',
    brightness INT NOT NULL DEFAULT 100,
    contrast INT NOT NULL DEFAULT 100,
    saturation INT NOT NULL DEFAULT 100,
    blur INT NOT NULL DEFAULT 0,
    perspective_enabled TINYINT(1) NOT NULL DEFAULT 0,
    perspective_x INT NOT NULL DEFAULT 0,
    perspective_y INT NOT NULL DEFAULT 0,
    preset_effect VARCHAR(50) DEFAULT NULL COMMENT 'glow/shadow/gradient/glass/neon/emboss',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_layer_id (layer_id),
    CONSTRAINT fk_effect_layer FOREIGN KEY (layer_id) REFERENCES ad_layer(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='图层特效表';

DROP TABLE IF EXISTS user_ad_feedback;
DROP TABLE IF EXISTS user_profile_analysis;
DROP TABLE IF EXISTS user_profile;

CREATE TABLE user_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    age_range VARCHAR(30) DEFAULT NULL COMMENT 'AI分析年龄范围',
    gender VARCHAR(10) DEFAULT NULL COMMENT 'AI分析性别',
    matched_ad_ids VARCHAR(500) DEFAULT NULL COMMENT '最近AI推荐项目ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_profile_user (user_id),
    CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES ad_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户画像表';

CREATE TABLE user_profile_analysis (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    photo_url VARCHAR(500) DEFAULT NULL COMMENT '分析的照片路径',
    portrait_result TEXT DEFAULT NULL COMMENT 'AI画像原始JSON',
    matched_result TEXT DEFAULT NULL COMMENT 'AI推荐原始JSON',
    matched_ad_ids VARCHAR(500) DEFAULT NULL COMMENT '本次推荐项目ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_analysis_user (user_id),
    CONSTRAINT fk_analysis_user FOREIGN KEY (user_id) REFERENCES ad_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='画像分析日志表';

CREATE TABLE user_ad_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    portrait_snapshot VARCHAR(500) DEFAULT NULL COMMENT '反馈时画像快照',
    feedback INT DEFAULT NULL COMMENT 'NULL=未反馈 1=感兴趣 0=中立 -1=不感兴趣',
    exposure_count INT NOT NULL DEFAULT 0 COMMENT '曝光次数',
    first_exposure_time DATETIME DEFAULT NULL,
    feedback_time DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_feedback_user_ad (user_id, project_id),
    INDEX idx_feedback_project (project_id),
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES ad_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_project FOREIGN KEY (project_id) REFERENCES ad_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户广告反馈表';

DROP TABLE IF EXISTS face_encodings;

CREATE TABLE face_encodings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '关联用户ID',
    face_vector MEDIUMTEXT NOT NULL COMMENT '人脸特征向量JSON',
    photo_sample_url VARCHAR(500) DEFAULT NULL COMMENT '样本照片URL',
    first_seen DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_face_user (user_id),
    CONSTRAINT fk_face_user FOREIGN KEY (user_id) REFERENCES ad_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人脸特征向量表';

DROP TABLE IF EXISTS board_photo;

CREATE TABLE board_photo (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '固定板子用户ID',
    photo_url VARCHAR(500) DEFAULT NULL COMMENT 'HTTP访问路径',
    photo_path VARCHAR(500) DEFAULT NULL COMMENT '磁盘绝对路径',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ANALYZED/FAILED',
    portrait_json VARCHAR(500) DEFAULT NULL COMMENT 'AI画像JSON',
    matched_json TEXT DEFAULT NULL COMMENT 'AI推荐结果JSON',
    matched_ad_ids VARCHAR(500) DEFAULT NULL COMMENT '推荐项目ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    analyzed_at DATETIME DEFAULT NULL COMMENT '分析完成时间',
    INDEX idx_board_user (user_id),
    INDEX idx_board_status (status),
    CONSTRAINT fk_board_user FOREIGN KEY (user_id) REFERENCES ad_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='板子照片分析记录表';
