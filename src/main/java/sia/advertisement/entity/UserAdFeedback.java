package sia.advertisement.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserAdFeedback {
    private Long id;
    private Long userId;
    private Long projectId;
    private String portraitSnapshot;
    private Integer feedback;
    private Integer exposureCount;
    private LocalDateTime firstExposureTime;
    private LocalDateTime feedbackTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
