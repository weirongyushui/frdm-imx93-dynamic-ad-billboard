package sia.advertisement.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import sia.advertisement.service.WatchConfigService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@RestController
public class WatchPhotoController {

    @Autowired
    private WatchConfigService watchConfigService;

    @GetMapping("/watch-photos/{filename:.+}")
    public ResponseEntity<Resource> servePhoto(@PathVariable String filename) throws IOException {
        File photosDir = watchConfigService.getPhotosDirFile().getCanonicalFile();
        File file = new File(photosDir, filename).getCanonicalFile();

        if (!file.getPath().startsWith(photosDir.getPath()) || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }

        String contentType = Files.probeContentType(file.toPath());
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(resource);
    }
}
