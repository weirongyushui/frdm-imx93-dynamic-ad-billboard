package sia.advertisement.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AdLayer {
    private Long id;
    private Long projectId;
    private Long pageId;
    private String layerName;
    private String layerType;
    private Integer zIndex;
    private Integer posX;
    private Integer posY;
    private Integer width;
    private Integer height;
    private String textContent;
    private Integer fontSize;
    private String fontColor;
    private String fontWeight;
    private String fontFamily;
    private String fontStyle;
    private String textDecoration;
    private Double lineHeight;
    private Double letterSpacing;
    private Double opacity;
    private String textAlign;
    private Integer borderRadius;
    private String background;
    private String imageUrl;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
