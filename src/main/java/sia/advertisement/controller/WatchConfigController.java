package sia.advertisement.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sia.advertisement.service.FolderWatchService;
import sia.advertisement.service.WatchConfigService;

import java.util.Map;

@RestController
@RequestMapping("/api/watch")
public class WatchConfigController {

    @Autowired
    private WatchConfigService watchConfigService;

    @Autowired
    private FolderWatchService folderWatchService;

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfig() {
        return ResponseEntity.ok(ApiResponse.success(
                watchConfigService.toMap(folderWatchService.isWatching())));
    }

    @PostMapping("/config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateConfig(
            @RequestBody Map<String, String> body) {
        try {
            watchConfigService.update(
                    body.get("personUrl"),
                    body.get("actionUrl"),
                    body.get("photosDir"),
                    body.get("actionPollInterval"),
                    body.get("photoHttpUrl"));
            folderWatchService.restartWatch();
            return ResponseEntity.ok(ApiResponse.success(
                    watchConfigService.toMap(folderWatchService.isWatching())));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "保存配置失败: " + e.getMessage()));
        }
    }

    @PostMapping("/scan")
    public ResponseEntity<ApiResponse<String>> triggerScan() {
        folderWatchService.triggerScan();
        return ResponseEntity.ok(ApiResponse.success("ok"));
    }
}
