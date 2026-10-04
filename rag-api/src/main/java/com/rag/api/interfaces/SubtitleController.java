package com.rag.api.interfaces;

import com.rag.api.application.SubtitleService;
import com.rag.api.common.ApiResult;
import com.rag.api.interfaces.dto.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 字幕转换接口：VTT/SRT/ASS 上传（格式校验）→ 解析/翻译/编辑 → 下载/删除。
 * 所有操作按 tenant_id + user_id 隔离。
 */
@RestController
@RequestMapping("/api/v1/subtitles")
@RequiredArgsConstructor
public class SubtitleController {

    private final SubtitleService subtitleService;

    /** 上传字幕，前端已算 SHA-256 时可秒传。 */
    @PostMapping
    public ApiResult<Dtos.SubtitleView> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam(name = "sha256", required = false) String sha256) {
        return ApiResult.ok(subtitleService.upload(file, sha256));
    }

    /** 当前用户的字幕记录列表。 */
    @GetMapping
    public ApiResult<List<Dtos.SubtitleListItem>> list() {
        return ApiResult.ok(subtitleService.list());
    }

    /** 字幕详情（含全部字幕条）。 */
    @GetMapping("/{id}")
    public ApiResult<Dtos.SubtitleView> get(@PathVariable long id) {
        return ApiResult.ok(subtitleService.get(id));
    }

    /** 翻译字幕到目标语言（语言来自维护列表）；indices 为勾选序号（可空=全部），时间轴不变。 */
    @PostMapping("/{id}/translate")
    public ApiResult<Dtos.SubtitleView> translate(@PathVariable long id,
                                                  @RequestBody @Valid Dtos.SubtitleTranslateReq req) {
        return ApiResult.ok(subtitleService.translate(id, req));
    }

    /** 翻译目标语言列表（租户级，首次访问自动播种默认语言）。 */
    @GetMapping("/langs")
    public ApiResult<List<Dtos.TranslateLangView>> listLangs() {
        return ApiResult.ok(subtitleService.listLangs());
    }

    /** 新增翻译目标语言，返回更新后的列表。 */
    @PostMapping("/langs")
    public ApiResult<List<Dtos.TranslateLangView>> addLang(@RequestBody @Valid Dtos.TranslateLangReq req) {
        return ApiResult.ok(subtitleService.addLang(req.name()));
    }

    /** 删除翻译目标语言（至少保留一个）。 */
    @DeleteMapping("/langs/{id}")
    public ApiResult<Void> deleteLang(@PathVariable long id) {
        subtitleService.deleteLang(id);
        return ApiResult.ok(null);
    }

    /** 编辑字幕（原文/译文可人工修改，时间轴取已存记录）。 */
    @PutMapping("/{id}")
    public ApiResult<Dtos.SubtitleView> update(@PathVariable long id,
                                               @RequestBody @Valid Dtos.SubtitleUpdateReq req) {
        return ApiResult.ok(subtitleService.update(id, req.cues()));
    }

    /** 删除字幕记录（级联删除字幕条；文件库归档保留）。 */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        subtitleService.delete(id);
        return ApiResult.ok(null);
    }

    /** 下载 SRT 文件（文件名带目标语言，如 01_繁體中文.srt）。 */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        Dtos.SubtitleView view = subtitleService.get(id);
        byte[] bytes = subtitleService.download(id);
        String name = subtitleService.srtName(view.originalName(), view.targetLang());
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                // 下载内容随翻译动态变化，禁止浏览器缓存旧文件
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("application/x-subrip;charset=UTF-8"))
                .body(bytes);
    }
}
