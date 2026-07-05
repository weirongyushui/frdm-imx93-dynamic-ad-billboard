package sia.advertisement.dto;

import lombok.Data;
import java.util.List;

@Data
public class AdPageVO {
    private Long pageId;
    private String pageName;
    private Integer sortOrder;
    private String backgroundColor;
    private List<AdLayerDTO> layers;
}
