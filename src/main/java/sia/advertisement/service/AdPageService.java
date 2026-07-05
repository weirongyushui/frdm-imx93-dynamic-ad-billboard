package sia.advertisement.service;

import sia.advertisement.entity.AdPage;
import java.util.List;

public interface AdPageService {
    AdPage getById(Long id);
    List<AdPage> getByProjectId(Long projectId);
    void create(AdPage page);
    void update(AdPage page);
    void delete(Long id);
    void deleteByProjectId(Long projectId);
}
