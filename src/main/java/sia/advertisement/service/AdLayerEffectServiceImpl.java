package sia.advertisement.service;

import sia.advertisement.entity.AdLayerEffect;
import sia.advertisement.mapper.AdLayerEffectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdLayerEffectServiceImpl implements AdLayerEffectService {
    
    @Autowired
    private AdLayerEffectMapper adLayerEffectMapper;
    
    @Override
    public AdLayerEffect getById(Long id) {
        return adLayerEffectMapper.selectById(id);
    }
    
    @Override
    public AdLayerEffect getByLayerId(Long layerId) {
        return adLayerEffectMapper.selectByLayerId(layerId);
    }
    
    @Override
    public AdLayerEffect create(AdLayerEffect effect) {
        adLayerEffectMapper.insert(effect);
        return effect;
    }
    
    @Override
    public AdLayerEffect update(AdLayerEffect effect) {
        adLayerEffectMapper.update(effect);
        return adLayerEffectMapper.selectByLayerId(effect.getLayerId());
    }
    
    @Override
    public void delete(Long id) {
        adLayerEffectMapper.deleteById(id);
    }
    
    @Override
    public void deleteByLayerId(Long layerId) {
        adLayerEffectMapper.deleteByLayerId(layerId);
    }

    @Override
    public void deleteByPageId(Long pageId) {
        adLayerEffectMapper.deleteByPageId(pageId);
    }
}
