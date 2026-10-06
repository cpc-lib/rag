package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.UserFeatureEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserFeatureMapper {

    List<UserFeatureEntity> selectByUserIds(@Param("userIds") List<Long> userIds);

    List<UserFeatureEntity> selectListByUserIdAndType(@Param("userId") Long userId, @Param("featureType") String featureType);

    long countByUserIdAndTypeAndCode(@Param("userId") Long userId, @Param("featureType") String featureType, @Param("code") String code);

    int insert(UserFeatureEntity entity);

    int deleteByUserIdAndType(@Param("userId") Long userId, @Param("featureType") String featureType);
}
