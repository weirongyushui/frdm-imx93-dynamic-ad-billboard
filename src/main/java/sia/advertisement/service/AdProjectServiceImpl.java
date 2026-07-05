package sia.advertisement.service;

import sia.advertisement.entity.AdProject;
import sia.advertisement.mapper.AdProjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AdProjectServiceImpl implements AdProjectService {
    
    @Autowired
    private AdProjectMapper adProjectMapper;
    
    @Override
    public AdProject getById(Long id) {
        return adProjectMapper.selectById(id);
    }
    
    @Override
    public List<AdProject> getAll() {
        return adProjectMapper.selectAll();
    }
    
    @Override
    public List<AdProject> getByStatus(Integer status) {
        return adProjectMapper.selectByStatus(status);
    }
    
    @Override
    public List<AdProject> getByUserId(Long userId) {
        return adProjectMapper.selectByUserId(userId);
    }
    
    @Override
    public AdProject create(AdProject project) {
        adProjectMapper.insert(project);
        return project;
    }
    
    @Override
    public AdProject update(AdProject project) {
        adProjectMapper.update(project);
        return adProjectMapper.selectById(project.getId());
    }
    
    @Override
    public void delete(Long id) {
        adProjectMapper.deleteById(id);
    }
}
