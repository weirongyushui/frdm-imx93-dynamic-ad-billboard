package sia.advertisement.service;

import sia.advertisement.entity.AdLayer;
import sia.advertisement.mapper.AdLayerMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AdLayerServiceImpl implements AdLayerService {
    
    @Autowired
    private AdLayerMapper adLayerMapper;
    
    @Override
    public AdLayer getById(Long id) {
        return adLayerMapper.selectById(id);
    }
    
    @Override
    public List<AdLayer> getByProjectId(Long projectId) {
        return adLayerMapper.selectByProjectId(projectId);
    }
    
    @Override
    public List<AdLayer> getByProjectIdAndType(Long projectId, String layerType) {
        return adLayerMapper.selectByProjectIdAndType(projectId, layerType);
    }
    
    @Override
    public AdLayer create(AdLayer layer) {
        adLayerMapper.insert(layer);
        return layer;
    }
    
    @Override
    public AdLayer update(AdLayer layer) {
        adLayerMapper.update(layer);
        return adLayerMapper.selectById(layer.getId());
    }
    
    @Override
    public void delete(Long id) {
        adLayerMapper.deleteById(id);
    }
    
    @Override
    public void deleteByProjectId(Long projectId) {
        adLayerMapper.deleteByProjectId(projectId);
    }

    @Override
    public List<AdLayer> getByPageId(Long pageId) {
        return adLayerMapper.selectByPageId(pageId);
    }

    @Override
    public void deleteByPageId(Long pageId) {
        adLayerMapper.deleteByPageId(pageId);
    }
}
