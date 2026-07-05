package sia.advertisement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import sia.advertisement.entity.UserProfileAnalysis;
import java.util.List;

@Mapper
public interface UserProfileAnalysisMapper {

    int insert(UserProfileAnalysis analysis);

    List<UserProfileAnalysis> selectByUserId(@Param("userId") Long userId);
}
