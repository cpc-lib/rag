package com.rag.api.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.api.infrastructure.persistence.entity.VideoPlaybackHistoryEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VideoPlaybackHistoryMapper extends BaseMapper<VideoPlaybackHistoryEntity> {
}
