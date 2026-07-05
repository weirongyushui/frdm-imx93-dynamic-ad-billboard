package sia.advertisement.dto;

import lombok.Data;
import java.util.List;

@Data
public class AdConfigDTO {
    private Long projectId;
    private String name;
    private Integer canvasWidth;
    private Integer canvasHeight;
    private String backgroundColor;
    private Integer status;
    private String aiTags;
    private List<AdLayerDTO> layers;
    private List<AdPageVO> pages;
}
