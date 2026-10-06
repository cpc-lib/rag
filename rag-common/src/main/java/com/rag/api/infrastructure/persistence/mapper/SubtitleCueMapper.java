package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.SubtitleCueEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SubtitleCueMapper {

    int insert(SubtitleCueEntity entity);

    int updateById(SubtitleCueEntity entity);

    List<SubtitleCueEntity> selectListBySubtitleId(@Param("subtitleId") Long subtitleId);

    Long countBySubtitleId(@Param("subtitleId") Long subtitleId);

    int updateContentAndTranslatedById(@Param("id") Long id,
                                       @Param("content") String content,
                                       @Param("translatedText") String translatedText);

    int logicDeleteBySubtitleId(@Param("subtitleId") Long subtitleId);
}
