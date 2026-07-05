package sia.advertisement.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AdPage {
    private Long id;
    private Long projectId;
    private String pageName;
    private Integer sortOrder;
    private String backgroundColor;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
