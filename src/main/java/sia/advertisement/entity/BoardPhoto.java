package sia.advertisement.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BoardPhoto {
    private Long id;
    private Long userId;
    private String photoUrl;
    private String photoPath;
    private String status;
    private String portraitJson;
    private String matchedJson;
    private String matchedAdIds;
    private LocalDateTime createdAt;
    private LocalDateTime analyzedAt;
}
