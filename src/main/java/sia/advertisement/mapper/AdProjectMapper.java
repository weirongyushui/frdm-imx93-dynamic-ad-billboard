package sia.advertisement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import sia.advertisement.entity.AdProject;
import java.util.List;

@Mapper
public interface AdProjectMapper {
    
    AdProject selectById(@Param("id") Long id);
    
    List<AdProject> selectAll();
    
    List<AdProject> selectByStatus(@Param("status") Integer status);
    
    List<AdProject> selectByUserId(@Param("userId") Long userId);
    
    int insert(AdProject project);
    
    int update(AdProject project);
    
    int deleteById(@Param("id") Long id);

    int updateAiTags(@Param("id") Long id, @Param("aiTags") String aiTags);
}
