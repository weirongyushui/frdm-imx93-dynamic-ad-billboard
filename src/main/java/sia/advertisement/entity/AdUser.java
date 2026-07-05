package sia.advertisement.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AdUser {
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
