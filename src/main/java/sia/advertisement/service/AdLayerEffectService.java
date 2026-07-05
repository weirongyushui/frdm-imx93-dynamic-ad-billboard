package sia.advertisement.service;

import sia.advertisement.entity.AdLayerEffect;

public interface AdLayerEffectService {
    
    AdLayerEffect getById(Long id);
    
    AdLayerEffect getByLayerId(Long layerId);
    
    AdLayerEffect create(AdLayerEffect effect);
    
    AdLayerEffect update(AdLayerEffect effect);
    
    void delete(Long id);
    
    void deleteByLayerId(Long layerId);

    void deleteByPageId(Long pageId);
}
