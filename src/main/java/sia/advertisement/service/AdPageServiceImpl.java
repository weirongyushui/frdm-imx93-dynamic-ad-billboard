package sia.advertisement.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sia.advertisement.entity.AdPage;
import sia.advertisement.mapper.AdPageMapper;

import java.util.List;

@Service
public class AdPageServiceImpl implements AdPageService {

    @Autowired
    private AdPageMapper adPageMapper;

    @Override
    public AdPage getById(Long id) {
        return adPageMapper.selectById(id);
    }

    @Override
    public List<AdPage> getByProjectId(Long projectId) {
        return adPageMapper.selectByProjectId(projectId);
    }

    @Override
    public void create(AdPage page) {
        adPageMapper.insert(page);
    }

    @Override
    public void update(AdPage page) {
        adPageMapper.update(page);
    }

    @Override
    public void delete(Long id) {
        adPageMapper.deleteById(id);
    }

    @Override
    public void deleteByProjectId(Long projectId) {
        adPageMapper.deleteByProjectId(projectId);
    }
}
