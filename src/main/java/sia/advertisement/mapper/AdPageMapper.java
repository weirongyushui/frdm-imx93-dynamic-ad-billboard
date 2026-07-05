package sia.advertisement.mapper;

import org.apache.ibatis.annotations.Mapper;
import sia.advertisement.entity.AdPage;
import java.util.List;

@Mapper
public interface AdPageMapper {
    AdPage selectById(Long id);
    List<AdPage> selectByProjectId(Long projectId);
    int insert(AdPage page);
    int update(AdPage page);
    int deleteById(Long id);
    int deleteByProjectId(Long projectId);
}
