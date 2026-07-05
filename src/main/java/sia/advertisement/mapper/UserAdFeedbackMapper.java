package sia.advertisement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import sia.advertisement.entity.UserAdFeedback;
import java.util.List;

@Mapper
public interface UserAdFeedbackMapper {

    int insert(UserAdFeedback feedback);

    int updateFeedback(@Param("userId") Long userId, @Param("projectId") Long projectId,
                       @Param("feedback") Integer feedback);

    List<UserAdFeedback> selectByUserId(@Param("userId") Long userId);

    UserAdFeedback selectByUserAndProject(@Param("userId") Long userId, @Param("projectId") Long projectId);

    int incrementExposure(@Param("userId") Long userId, @Param("projectId") Long projectId);
}
