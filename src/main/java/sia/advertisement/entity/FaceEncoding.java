package sia.advertisement.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class FaceEncoding {
    private Long id;
    private Long userId;
    private String faceVector;
    private String photoSampleUrl;
    private LocalDateTime firstSeen;
    private LocalDateTime lastSeen;
}
