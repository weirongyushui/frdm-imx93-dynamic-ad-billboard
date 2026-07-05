package sia.advertisement.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserProfile {
    private Long id;
    private Long userId;
    private String ageRange;
    private String gender;
    private String matchedAdIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
