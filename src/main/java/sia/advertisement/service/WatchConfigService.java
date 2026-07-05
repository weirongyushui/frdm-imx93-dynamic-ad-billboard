package sia.advertisement.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 文件夹监控目录配置：支持 application.properties 默认值 + config/watch-config.properties 用户覆盖。
 */
@Service
public class WatchConfigService {

    @Value("${watch.photos-dir:C:\\Users\\xffff\\Desktop\\user\\input_photos}")
    private String defaultPhotosDir;

    @Value("${watch.person-url:http://192.168.43.11:8000/output_results/backend_events/person_latest.json}")
    private String defaultPersonUrl;

    @Value("${watch.action-url:http://192.168.43.11:8000/output_results/backend_events/action_latest.json}")
    private String defaultActionUrl;

    @Value("${watch.photo-http-url:}")
    private String defaultPhotoHttpUrl;

    @Value("${watch.action-poll-interval:500}")
    private long defaultActionPollInterval;

    @Value("${watch.person-poll-interval:1000}")
    private long defaultPersonPollInterval;

    private volatile String photosDir;
    private volatile String personUrl;
    private volatile String actionUrl;
    private volatile String photoHttpUrl;
    private volatile long actionPollInterval;
    private volatile long personPollInterval;

    private File configFile;

    @PostConstruct
    public void init() {
        configFile = new File(System.getProperty("user.dir"), "config/watch-config.properties");
        photosDir = defaultPhotosDir;
        personUrl = defaultPersonUrl;
        actionUrl = defaultActionUrl;
        photoHttpUrl = defaultPhotoHttpUrl;
        actionPollInterval = defaultActionPollInterval;
        personPollInterval = defaultPersonPollInterval;
        loadFromFile();
    }

    public synchronized void update(String newPersonUrl, String newActionUrl,
                                    String newPhotosDir, String newActionPollInterval,
                                    String newPhotoHttpUrl) throws IOException {
        if (newPersonUrl != null && !newPersonUrl.isBlank()) {
            personUrl = newPersonUrl.trim();
        }
        if (newActionUrl != null && !newActionUrl.isBlank()) {
            actionUrl = newActionUrl.trim();
        }
        if (newPhotosDir != null && !newPhotosDir.isBlank()) {
            photosDir = newPhotosDir.trim();
        }
        if (newActionPollInterval != null && !newActionPollInterval.isBlank()) {
            try {
                actionPollInterval = Long.parseLong(newActionPollInterval.trim());
            } catch (NumberFormatException ignored) {}
        }
        if (newPhotoHttpUrl != null) {
            photoHttpUrl = newPhotoHttpUrl.trim();
        }
        saveToFile();
        ensureDirectories();
    }

    public File getPhotosDirFile() {
        return resolvePhotosDir(photosDir);
    }

    public String getPersonUrl() {
        return personUrl != null && !personUrl.isBlank() ? personUrl : defaultPersonUrl;
    }

    public String getActionUrl() {
        return actionUrl != null && !actionUrl.isBlank() ? actionUrl : defaultActionUrl;
    }

    public long getActionPollInterval() {
        return actionPollInterval > 0 ? actionPollInterval : 500;
    }

    public long getPersonPollInterval() {
        return personPollInterval > 0 ? personPollInterval : 1000;
    }

    public String getPersonUrlRaw() {
        return personUrl;
    }

    public String getActionUrlRaw() {
        return actionUrl;
    }

    public String getPhotosDirRaw() {
        return photosDir;
    }

    public String getPhotoHttpUrl() {
        return photoHttpUrl;
    }

    public Map<String, Object> toMap(boolean watching) {
        File photos = getPhotosDirFile();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("personUrl", getPersonUrl());
        map.put("actionUrl", getActionUrl());
        map.put("photoHttpUrl", photoHttpUrl);
        map.put("photosDir", photosDir);
        map.put("actionPollInterval", getActionPollInterval());
        map.put("personPollInterval", getPersonPollInterval());
        map.put("photosDirResolved", photos.getAbsolutePath());
        map.put("photosDirExists", photos.exists());
        map.put("watching", watching);
        map.put("configFile", configFile.getAbsolutePath());
        return map;
    }

    public void ensureDirectories() {
        File photos = getPhotosDirFile();
        if (!photos.exists()) {
            photos.mkdirs();
        }
    }

    private void loadFromFile() {
        if (!configFile.exists()) {
            return;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(configFile)) {
            props.load(in);
            if (props.containsKey("watch.photos-dir")) {
                photosDir = props.getProperty("watch.photos-dir").trim();
            }
            if (props.containsKey("watch.person-url")) {
                personUrl = props.getProperty("watch.person-url").trim();
            }
            if (props.containsKey("watch.action-url")) {
                actionUrl = props.getProperty("watch.action-url").trim();
            }
            if (props.containsKey("watch.photo-http-url")) {
                photoHttpUrl = props.getProperty("watch.photo-http-url").trim();
            }
            if (props.containsKey("watch.action-poll-interval")) {
                try {
                    actionPollInterval = Long.parseLong(props.getProperty("watch.action-poll-interval").trim());
                } catch (NumberFormatException ignored) {}
            }
            if (props.containsKey("watch.person-poll-interval")) {
                try {
                    personPollInterval = Long.parseLong(props.getProperty("watch.person-poll-interval").trim());
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            System.err.println("[WatchConfig] 读取配置失败: " + e.getMessage());
        }
    }

    private void saveToFile() throws IOException {
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Properties props = new Properties();
        props.setProperty("watch.photos-dir", photosDir);
        props.setProperty("watch.person-url", getPersonUrl());
        props.setProperty("watch.action-url", getActionUrl());
        props.setProperty("watch.photo-http-url", photoHttpUrl != null ? photoHttpUrl : "");
        props.setProperty("watch.action-poll-interval", String.valueOf(getActionPollInterval()));
        props.setProperty("watch.person-poll-interval", String.valueOf(getPersonPollInterval()));
        try (FileOutputStream out = new FileOutputStream(configFile)) {
            props.store(out, "User watch folder configuration");
        }
    }

    static File resolvePhotosDir(String path) {
        if (path == null || path.isBlank()) {
            path = "input_photos";
        }
        File dir = new File(path.trim());
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), path.trim());
        }
        return dir;
    }
}
