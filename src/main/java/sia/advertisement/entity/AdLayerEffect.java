package sia.advertisement.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AdLayerEffect {
    private Long id;
    private Long layerId;
    private Boolean shadowEnabled;
    private Integer shadowX;
    private Integer shadowY;
    private Integer shadowBlur;
    private String shadowColor;
    private Boolean glowEnabled;
    private Integer glowSize;
    private String glowColor;
    private Boolean borderEnabled;
    private Integer borderWidth;
    private String borderColor;
    private String borderStyle;
    private Integer brightness;
    private Integer contrast;
    private Integer saturation;
    private Integer blur;
    private Boolean perspectiveEnabled;
    private Integer perspectiveX;
    private Integer perspectiveY;
    private String presetEffect;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
