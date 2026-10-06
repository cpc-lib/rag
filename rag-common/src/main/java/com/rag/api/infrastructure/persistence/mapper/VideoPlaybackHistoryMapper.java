package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.VideoPlaybackHistoryEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface VideoPlaybackHistoryMapper {

    int insert(VideoPlaybackHistoryEntity entity);

    List<VideoPlaybackHistoryEntity> selectListByTenantUser(@Param("tenantId") String tenantId,
                                                            @Param("userId") Long userId,
                                                            @Param("limit") Integer limit);

    VideoPlaybackHistoryEntity selectLatestByTenantUserFile(@Param("tenantId") String tenantId,
                                                            @Param("userId") Long userId,
                                                            @Param("fileId") Long fileId);
}
