package com.rag.api.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.infrastructure.persistence.entity.ChunkEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

public interface ChunkMapper {

    ChunkEntity selectById(@Param("id") Long id);

    int insert(ChunkEntity entity);

    int updateById(ChunkEntity entity);

    Page<ChunkEntity> selectPageByDocumentId(Page<ChunkEntity> page, @Param("documentId") Long documentId);

    List<ChunkEntity> selectByIds(@Param("ids") Collection<Long> ids);

    /** 文档下全部有效 CHILD 切片（向量化用），按 seq 升序。 */
    List<ChunkEntity> selectChildrenByDocumentId(@Param("documentId") Long documentId);

    /** 文档下 seq 最大的一条切片（取下一序号用）。 */
    ChunkEntity selectMaxSeqByDocumentId(@Param("documentId") Long documentId);

    /** 文档下有效切片数（status != DELETED，断点续跑判定用）。 */
    long countActiveByDocumentId(@Param("documentId") Long documentId);

    /** 解散 parent：清空其有效 child 的 parent_chunk_id。 */
    int clearParentByParentId(@Param("parentChunkId") Long parentChunkId, @Param("documentId") Long documentId);

    int logicDeleteByDocumentId(@Param("documentId") Long documentId);

    int logicDeleteByKbId(@Param("kbId") Long kbId);

    /** 重解析前删除旧 AUTO 切片（保留 MANUAL）。 */
    int logicDeleteAutoByDocumentId(@Param("documentId") Long documentId);
}
