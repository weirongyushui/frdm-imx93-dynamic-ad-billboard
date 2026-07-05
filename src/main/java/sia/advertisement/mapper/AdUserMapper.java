package sia.advertisement.mapper;

import sia.advertisement.entity.AdUser;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface AdUserMapper {
    AdUser selectById(Long id);
    AdUser selectByUsername(String username);
    List<AdUser> selectAll();
    int insert(AdUser user);
    int update(AdUser user);
    int deleteById(Long id);
}
