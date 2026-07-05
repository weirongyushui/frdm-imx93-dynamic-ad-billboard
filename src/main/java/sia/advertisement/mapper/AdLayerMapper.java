package sia.advertisement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import sia.advertisement.entity.AdLayer;
import java.util.List;

@Mapper
public interface AdLayerMapper {
    
    AdLayer selectById(@Param("id") Long id);
    
    List<AdLayer> selectByProjectId(@Param("projectId") Long projectId);
    
    List<AdLayer> selectByProjectIdAndType(@Param("projectId") Long projectId, @Param("layerType") String layerType);
    
    int insert(AdLayer layer);
    
    int update(AdLayer layer);
    
    int deleteById(@Param("id") Long id);
    
    int deleteByProjectId(@Param("projectId") Long projectId);

    List<AdLayer> selectByPageId(@Param("pageId") Long pageId);

    int deleteByPageId(@Param("pageId") Long pageId);
}
