package com.rag.api.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.infrastructure.mq.MediaTranscodePublisher;
import com.rag.api.infrastructure.persistence.entity.LibraryFileEntity;
import com.rag.api.infrastructure.persistence.mapper.LibraryFileMapper;
import com.rag.api.interfaces.dto.Dtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 媒体转码状态机：视频统一由 Worker 异步转码为 HLS（1080p/720p/480p 自适应码率）。
 * 查询与触发分离：playbackStatus 纯查询；startTranscode 显式投递 MQ（幂等）；stopTranscode 经 Redis 通知 Worker 强杀 ffmpeg。
 */
@Service
@Slf4j
public class MediaTranscodeService {

    /** 停止转码频道（与 rag-worker 侧一致）。 */
    private static final String STOP_CHANNEL = "rag:media:stop";
    /** 需要转码的视频扩展名。 */
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "mkv", "avi", "ts");
    private static final String ST_PROCESSING = "PROCESSING";
    private static final String ST_READY = "READY";

    private final LibraryFileMapper libraryFileMapper;
    private final MediaTranscodePublisher publisher;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public MediaTranscodeService(LibraryFileMapper libraryFileMapper, MediaTranscodePublisher publisher,
                                 StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.libraryFileMapper = libraryFileMapper;
        this.publisher = publisher;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 查询播放状态（纯查询，不触发转码）：NONE/PROCESSING/READY/FAILED，非视频返回 NATIVE。positionMs 由 FileLibraryService 填充。 */
    public Dtos.PlaybackResp playbackStatus(LibraryFileEntity e) {
        if (!isVideo(e.getFileName())) {
            return new Dtos.PlaybackResp("NATIVE", false, null, null, null, null);
        }
        String status = e.getPlaybackStatus() == null ? "NONE" : e.getPlaybackStatus();
        boolean hls = ST_READY.equals(status) && e.getPlaybackKey() != null
                && e.getPlaybackKey().endsWith(".m3u8");
        return new Dtos.PlaybackResp(status, hls, e.getPlaybackProgress(), null,
                e.getVideoWidth(), e.getVideoHeight());
    }

    /** 开始/重新转码（幂等）：视频且非 PROCESSING 时置 PROCESSING 并投递 MQ。 */
    public Dtos.PlaybackResp startTranscode(LibraryFileEntity e) {
        if (!isVideo(e.getFileName())) {
            return new Dtos.PlaybackResp("NATIVE", false, null, null, null, null);
        }
        if (!ST_PROCESSING.equals(e.getPlaybackStatus())) {
            e.setPlaybackStatus(ST_PROCESSING);
            e.setPlaybackProgress(0);
            libraryFileMapper.updateById(e);
            publisher.publish(e.getId());
            log.info("视频投递转码 file={} id={}", e.getFileName(), e.getId());
        }
        return playbackStatus(e);
    }

    /** 停止转码：状态置回 NONE（可重新开始），并广播停止事件让 Worker 强杀 ffmpeg。 */
    public Dtos.PlaybackResp stopTranscode(LibraryFileEntity e) {
        if (ST_PROCESSING.equals(e.getPlaybackStatus())) {
            e.setPlaybackStatus("NONE");
            e.setPlaybackProgress(0);
            libraryFileMapper.updateById(e);
            try {
                redis.convertAndSend(STOP_CHANNEL, objectMapper.writeValueAsString(Map.of("fileId", e.getId())));
            } catch (Exception ex) {
                // 事件丢失仅影响强杀时机（Worker 阶段边界仍会感知不到，保守记日志；状态已置 NONE 可重新发起）
                log.warn("停止转码事件发布失败 fileId={} err={}", e.getId(), ex.getMessage());
            }
            log.info("停止转码 file={} id={}", e.getFileName(), e.getId());
        }
        return playbackStatus(e);
    }

    private boolean isVideo(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        return VIDEO_EXT.contains(ext);
    }
}
