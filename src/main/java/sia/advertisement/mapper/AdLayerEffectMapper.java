package sia.advertisement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import sia.advertisement.entity.AdLayerEffect;

@Mapper
public interface AdLayerEffectMapper {
    
    AdLayerEffect selectById(@Param("id") Long id);
    
    AdLayerEffect selectByLayerId(@Param("layerId") Long layerId);
    
    int insert(AdLayerEffect effect);
    
    int update(AdLayerEffect effect);
    
    int deleteById(@Param("id") Long id);
    
    int deleteByLayerId(@Param("layerId") Long layerId);

    int deleteByPageId(@Param("pageId") Long pageId);
}
