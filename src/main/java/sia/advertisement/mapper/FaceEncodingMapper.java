package sia.advertisement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import sia.advertisement.entity.FaceEncoding;
import java.util.List;

@Mapper
public interface FaceEncodingMapper {

    int insert(FaceEncoding face);

    List<FaceEncoding> selectAll();

    int updateLastSeen(@Param("id") Long id);
}
