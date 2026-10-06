package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.SubtitleCueBackupEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SubtitleCueBackupMapper {

    int insert(SubtitleCueBackupEntity entity);

    List<SubtitleCueBackupEntity> selectListBySubtitleId(@Param("subtitleId") Long subtitleId);

    int updateTranslatedTextBySubtitleIdAndSeq(@Param("subtitleId") Long subtitleId,
                                               @Param("seq") Integer seq,
                                               @Param("translatedText") String translatedText);

    int updateContentAndTranslatedBySubtitleIdAndSeq(@Param("subtitleId") Long subtitleId,
                                                     @Param("seq") Integer seq,
                                                     @Param("content") String content,
                                                     @Param("translatedText") String translatedText);
}
