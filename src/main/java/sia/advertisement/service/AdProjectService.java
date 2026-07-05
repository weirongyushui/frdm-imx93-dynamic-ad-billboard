package sia.advertisement.service;

import sia.advertisement.entity.AdProject;
import java.util.List;

public interface AdProjectService {
    
    AdProject getById(Long id);
    
    List<AdProject> getAll();
    
    List<AdProject> getByStatus(Integer status);
    
    List<AdProject> getByUserId(Long userId);
    
    AdProject create(AdProject project);
    
    AdProject update(AdProject project);
    
    void delete(Long id);
}
