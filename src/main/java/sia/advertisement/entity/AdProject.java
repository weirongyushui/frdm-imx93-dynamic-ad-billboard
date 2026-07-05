package sia.advertisement.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AdProject {
    private Long id;
    private Long userId;
    private String name;
    private Integer canvasWidth;
    private Integer canvasHeight;
    private String backgroundColor;
    private Integer status;
    private String aiTags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
