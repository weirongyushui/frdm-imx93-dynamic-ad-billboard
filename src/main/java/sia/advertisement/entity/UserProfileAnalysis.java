package sia.advertisement.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserProfileAnalysis {
    private Long id;
    private Long userId;
    private String photoUrl;
    private String portraitResult;
    private String matchedResult;
    private String matchedAdIds;
    private LocalDateTime createdAt;
}
