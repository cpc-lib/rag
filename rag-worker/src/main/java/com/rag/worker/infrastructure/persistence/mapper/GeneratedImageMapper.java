package com.rag.worker.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** AI 生图记录：Worker 侧仅用于回写 SHA-256 指纹。 */
@Mapper
public interface GeneratedImageMapper {

    @Update("UPDATE generated_image SET sha256 = #{sha256} WHERE id = #{id}")
    int updateSha256ById(@Param("id") long id, @Param("sha256") String sha256);
}
