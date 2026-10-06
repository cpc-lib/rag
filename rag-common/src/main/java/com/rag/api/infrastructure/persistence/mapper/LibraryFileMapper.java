package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.LibraryFileEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

public interface LibraryFileMapper {

    LibraryFileEntity selectById(@Param("id") Long id);

    int insert(LibraryFileEntity entity);

    int updateById(LibraryFileEntity entity);

    List<LibraryFileEntity> selectByIds(@Param("ids") Collection<Long> ids);

    /** 文件库列表：当前租户+用户，可按文件名模糊搜索（keyword 已由调用方转义 LIKE 通配符）。 */
    List<LibraryFileEntity> selectByTenantIdAndUserId(@Param("tenantId") String tenantId,
                                                      @Param("userId") Long userId,
                                                      @Param("keyword") String keyword);

    List<LibraryFileEntity> selectBySubtitleIdAndArchiveSource(@Param("subtitleId") Long subtitleId,
                                                               @Param("archiveSource") String archiveSource);

    /** 秒传查询：同租户同 SHA-256 的无业务关联（直接上传）条目，取最早一条。 */
    LibraryFileEntity selectOneDirectBySha256(@Param("tenantId") String tenantId,
                                              @Param("sha256") String sha256);

    /** 字幕秒传查询：同租户同 SHA-256 的原始上传归档（SUBTITLE_UPLOAD），取最新一条。 */
    LibraryFileEntity selectOneSubtitleUploadBySha256(@Param("tenantId") String tenantId,
                                                      @Param("sha256") String sha256);

    /** 租户存储用量（MinIO 不可达时的 DB 回退求和）。 */
    Long sumFileSizeByTenantId(@Param("tenantId") String tenantId);

    int updateSha256ByDocumentId(@Param("documentId") Long documentId, @Param("sha256") String sha256);

    int updateSha256ByImageId(@Param("imageId") Long imageId, @Param("sha256") String sha256);

    int logicDeleteById(@Param("id") Long id);

    int logicDeleteByDocumentId(@Param("documentId") Long documentId);

    int logicDeleteByDocumentIds(@Param("documentIds") Collection<Long> documentIds);

    int logicDeleteByImageId(@Param("imageId") Long imageId);
}
