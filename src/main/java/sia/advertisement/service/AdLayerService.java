package sia.advertisement.service;

import sia.advertisement.entity.AdLayer;
import java.util.List;

public interface AdLayerService {
    
    AdLayer getById(Long id);
    
    List<AdLayer> getByProjectId(Long projectId);
    
    List<AdLayer> getByProjectIdAndType(Long projectId, String layerType);
    
    AdLayer create(AdLayer layer);
    
    AdLayer update(AdLayer layer);
    
    void delete(Long id);
    
    void deleteByProjectId(Long projectId);

    List<AdLayer> getByPageId(Long pageId);

    void deleteByPageId(Long pageId);
}
