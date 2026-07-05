package sia.advertisement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import sia.advertisement.entity.BoardPhoto;

@Mapper
public interface BoardPhotoMapper {

    int insert(BoardPhoto photo);

    BoardPhoto selectById(@Param("id") Long id);

    int updateAnalysisResult(@Param("id") Long id,
                             @Param("status") String status,
                             @Param("portraitJson") String portraitJson,
                             @Param("matchedJson") String matchedJson,
                             @Param("matchedAdIds") String matchedAdIds);
}
