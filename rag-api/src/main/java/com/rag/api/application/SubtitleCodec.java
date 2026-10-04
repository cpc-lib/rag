package com.rag.api.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字幕格式编解码：VTT/SRT/ASS → cue 列表 → SRT。
 * <p>
 * 硬规则（与字幕翻译 skill 一致）：
 * <ul>
 *   <li>逐条独立，绝不合并或拆分条目</li>
 *   <li>时间轴原样保留，只替换文本</li>
 *   <li>SRT 毫秒分隔符为逗号，VTT 为点号，ASS 为厘秒（H:MM:SS.cc）</li>
 * </ul>
 */
public final class SubtitleCodec {

    private SubtitleCodec() {
    }

    /** 宽松时间轴（解析用）：小时位可省略（MM:SS.mmm），分隔符可为 . 或 , */
    private static final Pattern TIME_LINE = Pattern.compile(
            "((?:\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{3})\\s*-->\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{3})");
    /** 严格 SRT 时间轴（校验用）：HH:MM:SS,mmm --> HH:MM:SS,mmm */
    private static final Pattern SRT_TIME_LINE = Pattern.compile(
            "^(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})$");
    /** 严格 VTT 时间轴（校验用）：（HH:)?MM:SS.mmm --> ...，行尾允许 cue 设置 */
    private static final Pattern VTT_TIME_LINE = Pattern.compile(
            "^(?:(\\d{2}):)?(\\d{2}):(\\d{2})\\.(\\d{3})\\s+-->\\s+(?:(\\d{2}):)?(\\d{2}):(\\d{2})\\.(\\d{3})(\\s+.+)?$");
    /** ASS Dialogue 时间：H:MM:SS.cc（厘秒） */
    private static final Pattern ASS_TIME = Pattern.compile("^(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})$");
    /** ASS 行内样式覆盖标签 {\...} */
    private static final Pattern ASS_OVERRIDE_TAG = Pattern.compile("\\{[^}]*\\}");

    public record Cue(String start, String end, String text) {
    }

    /** 解析 VTT 内容为 cue 列表（忽略 WEBVTT 头、NOTE、cue 标识符、cue 内联样式）。 */
    public static List<Cue> parseVtt(String content) {
        List<Cue> cues = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return cues;
        }
        // 统一换行
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        // 按空行分块
        String[] blocks = normalized.split("\n\\s*\n");
        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // WEBVTT 头或 NOTE 块跳过
            if (trimmed.startsWith("WEBVTT") || trimmed.startsWith("NOTE") || trimmed.startsWith("STYLE")
                    || trimmed.startsWith("REGION")) {
                continue;
            }
            String[] lines = trimmed.split("\n");
            int timeLineIdx = -1;
            Matcher m = null;
            for (int i = 0; i < lines.length; i++) {
                Matcher mm = TIME_LINE.matcher(lines[i]);
                if (mm.find()) {
                    timeLineIdx = i;
                    m = mm;
                    break;
                }
            }
            if (timeLineIdx < 0 || m == null) {
                continue;
            }
            String start = normalizeTime(m.group(1));
            String end = normalizeTime(m.group(2));
            // 文本行：时间轴之后的所有行（时间轴行可能带 cue 设置，取 --> 之前的时间即可）
            StringBuilder text = new StringBuilder();
            for (int i = timeLineIdx + 1; i < lines.length; i++) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(lines[i]);
            }
            cues.add(new Cue(start, end, text.toString()));
        }
        return cues;
    }

    /** 解析 SRT 内容为 cue 列表。 */
    public static List<Cue> parseSrt(String content) {
        List<Cue> cues = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return cues;
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\n\\s*\n");
        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] lines = trimmed.split("\n");
            int timeLineIdx = -1;
            Matcher m = null;
            for (int i = 0; i < lines.length; i++) {
                Matcher mm = TIME_LINE.matcher(lines[i]);
                if (mm.find()) {
                    timeLineIdx = i;
                    m = mm;
                    break;
                }
            }
            if (timeLineIdx < 0 || m == null) {
                continue;
            }
            String start = normalizeTime(m.group(1));
            String end = normalizeTime(m.group(2));
            StringBuilder text = new StringBuilder();
            for (int i = timeLineIdx + 1; i < lines.length; i++) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(lines[i]);
            }
            cues.add(new Cue(start, end, text.toString()));
        }
        return cues;
    }

    // ---------------- 格式规范校验（上传前调用，返回首个错误描述；null = 合法） ----------------

    /** 按扩展名分发校验：vtt / srt / ass。 */
    public static String validate(String ext, String content) {
        if (content == null || content.isBlank()) {
            return "文件内容为空";
        }
        return switch (ext) {
            case "vtt" -> validateVtt(content);
            case "srt" -> validateSrt(content);
            case "ass" -> validateAss(content);
            default -> "不支持的字幕格式: " + ext;
        };
    }

    /** 按扩展名分发解析为 cue 列表（调用前需先通过 validate）。 */
    public static List<Cue> parse(String ext, String content) {
        return switch (ext) {
            case "vtt" -> parseVtt(content);
            case "srt" -> parseSrt(content);
            case "ass" -> parseAss(content);
            default -> List.of();
        };
    }

    /**
     * SRT 规范校验：每个字幕块 = 序号行 + 时间轴行（HH:MM:SS,mmm --> HH:MM:SS,mmm）+ 非空文本。
     */
    public static String validateSrt(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\n\\s*\n");
        int cueNo = 0;
        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            cueNo++;
            String[] lines = trimmed.split("\n");
            if (!lines[0].trim().matches("\\d+")) {
                return "第 " + cueNo + " 块首行不是数字序号: " + abbreviate(lines[0]);
            }
            if (lines.length < 2) {
                return "第 " + cueNo + " 块缺少时间轴行";
            }
            Matcher m = SRT_TIME_LINE.matcher(lines[1].trim());
            if (!m.matches()) {
                return "第 " + cueNo + " 块时间轴不符合 SRT 规范（HH:MM:SS,mmm --> HH:MM:SS,mmm）: "
                        + abbreviate(lines[1]);
            }
            String timeErr = checkTimeRange(m.group(1), m.group(2), m.group(3), m.group(4),
                    m.group(5), m.group(6), m.group(7), m.group(8), cueNo);
            if (timeErr != null) {
                return timeErr;
            }
            if (lines.length < 3 || String.join("", List.of(lines).subList(2, lines.length)).isBlank()) {
                return "第 " + cueNo + " 块缺少字幕文本";
            }
        }
        return cueNo == 0 ? "未解析到任何字幕块" : null;
    }

    /**
     * VTT 规范校验：必须以 WEBVTT 头开头；每个 cue 块含合法时间轴（MM:SS.mmm 或 HH:MM:SS.mmm）。
     */
    public static String validateVtt(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("WEBVTT")) {
            return "缺少 WEBVTT 文件头（文件第一行必须是 WEBVTT）";
        }
        String[] blocks = normalized.split("\n\\s*\n");
        int cueNo = 0;
        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("WEBVTT") || trimmed.startsWith("NOTE")
                    || trimmed.startsWith("STYLE") || trimmed.startsWith("REGION")) {
                continue;
            }
            cueNo++;
            String[] lines = trimmed.split("\n");
            // 首行可能是 cue 标识符，时间轴在前两行内
            Matcher m = null;
            int timeLineIdx = -1;
            for (int i = 0; i < Math.min(2, lines.length); i++) {
                Matcher mm = VTT_TIME_LINE.matcher(lines[i].trim());
                if (mm.matches()) {
                    m = mm;
                    timeLineIdx = i;
                    break;
                }
            }
            if (m == null) {
                return "第 " + cueNo + " 个 cue 时间轴不符合 VTT 规范（MM:SS.mmm --> MM:SS.mmm）: "
                        + abbreviate(lines[0]);
            }
            String timeErr = checkTimeRange(m.group(1), m.group(2), m.group(3), m.group(4),
                    m.group(5), m.group(6), m.group(7), m.group(8), cueNo);
            if (timeErr != null) {
                return timeErr;
            }
            if (timeLineIdx + 1 >= lines.length
                    || String.join("", List.of(lines).subList(timeLineIdx + 1, lines.length)).isBlank()) {
                return "第 " + cueNo + " 个 cue 缺少字幕文本";
            }
        }
        return cueNo == 0 ? "未解析到任何字幕 cue" : null;
    }

    /**
     * ASS/SSA 规范校验：必须含 [Script Info] 与 [Events] 段；[Events] 内须有 Format 行声明字段顺序，
     * 每条 Dialogue 行的 Start/End 须为 H:MM:SS.cc 且文本非空。
     */
    public static String validateAss(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n");
        boolean hasScriptInfo = false;
        boolean inEvents = false;
        List<String> format = null;
        int dialogueCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equalsIgnoreCase("[Script Info]")) {
                hasScriptInfo = true;
            } else if (line.equalsIgnoreCase("[Events]")) {
                inEvents = true;
            } else if (line.startsWith("[") && line.endsWith("]")) {
                inEvents = false;
            } else if (inEvents && line.regionMatches(true, 0, "Format:", 0, 7)) {
                format = parseAssFormat(line.substring(7));
            } else if (inEvents && line.regionMatches(true, 0, "Dialogue:", 0, 9)) {
                dialogueCount++;
                if (format == null) {
                    return "第 " + i + " 行 Dialogue 出现在 Format 声明之前（[Events] 段须先声明 Format）";
                }
                String err = validateAssDialogue(line.substring(9), format, i + 1);
                if (err != null) {
                    return err;
                }
            }
        }
        if (!hasScriptInfo) {
            return "缺少 [Script Info] 段";
        }
        if (format == null) {
            return "[Events] 段缺少 Format 字段声明行";
        }
        return dialogueCount == 0 ? "[Events] 段没有任何 Dialogue 字幕行" : null;
    }

    /** 解析 ASS 内容：[Events] 段 Dialogue 行 → cue 列表；剥离 {\...} 样式标签，\N 转换为换行。 */
    public static List<Cue> parseAss(String content) {
        List<Cue> cues = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return cues;
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n");
        boolean inEvents = false;
        List<String> format = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.equalsIgnoreCase("[Events]")) {
                inEvents = true;
            } else if (line.startsWith("[") && line.endsWith("]")) {
                inEvents = false;
            } else if (inEvents && line.regionMatches(true, 0, "Format:", 0, 7)) {
                format = parseAssFormat(line.substring(7));
            } else if (inEvents && format != null && line.regionMatches(true, 0, "Dialogue:", 0, 9)) {
                int startIdx = format.indexOf("start");
                int endIdx = format.indexOf("end");
                int textIdx = format.indexOf("text");
                String[] parts = line.substring(9).split(",", format.size());
                if (startIdx < 0 || endIdx < 0 || textIdx < 0 || parts.length < format.size()) {
                    continue;
                }
                Matcher ms = ASS_TIME.matcher(parts[startIdx].trim());
                Matcher me = ASS_TIME.matcher(parts[endIdx].trim());
                if (!ms.matches() || !me.matches()) {
                    continue;
                }
                String text = ASS_OVERRIDE_TAG.matcher(parts[textIdx]).replaceAll("")
                        .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                        .trim();
                if (text.isEmpty()) {
                    continue;
                }
                cues.add(new Cue(assTime(ms), assTime(me), text));
            }
        }
        return cues;
    }

    /** ASS Format 行 → 小写字段名列表（如 layer,start,end,style,name,marginl,marginr,marginv,effect,text）。 */
    private static List<String> parseAssFormat(String s) {
        List<String> fields = new ArrayList<>();
        for (String f : s.split(",")) {
            fields.add(f.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return fields;
    }

    /** 校验单条 Dialogue 行（validateAss 用），lineNo 为 1 起行号。 */
    private static String validateAssDialogue(String body, List<String> format, int lineNo) {
        int startIdx = format.indexOf("start");
        int endIdx = format.indexOf("end");
        int textIdx = format.indexOf("text");
        if (startIdx < 0 || endIdx < 0 || textIdx < 0) {
            return "[Events] Format 声明缺少 Start/End/Text 字段";
        }
        String[] parts = body.split(",", format.size());
        if (parts.length < format.size()) {
            return "第 " + lineNo + " 行 Dialogue 字段数不足（Format 声明了 " + format.size() + " 个字段）";
        }
        Matcher ms = ASS_TIME.matcher(parts[startIdx].trim());
        Matcher me = ASS_TIME.matcher(parts[endIdx].trim());
        if (!ms.matches() || !me.matches()) {
            return "第 " + lineNo + " 行 Dialogue 时间不符合 ASS 规范（H:MM:SS.cc）: " + abbreviate(body);
        }
        if (Integer.parseInt(ms.group(2)) > 59 || Integer.parseInt(ms.group(3)) > 59
                || Integer.parseInt(me.group(2)) > 59 || Integer.parseInt(me.group(3)) > 59) {
            return "第 " + lineNo + " 行 Dialogue 时间分量超出范围（分/秒须 ≤59）";
        }
        if (assTimeMs(ms) > assTimeMs(me)) {
            return "第 " + lineNo + " 行 Dialogue 开始时间晚于结束时间";
        }
        String text = ASS_OVERRIDE_TAG.matcher(parts[textIdx]).replaceAll("").trim();
        if (text.isEmpty()) {
            return "第 " + lineNo + " 行 Dialogue 字幕文本为空";
        }
        return null;
    }

    /** ASS 时间 H:MM:SS.cc → 归一化 HH:MM:SS,mmm（厘秒 ×10 = 毫秒）。 */
    private static String assTime(Matcher m) {
        return "%02d:%s:%s,%s0".formatted(Integer.parseInt(m.group(1)), m.group(2), m.group(3), m.group(4));
    }

    private static long assTimeMs(Matcher m) {
        return (Integer.parseInt(m.group(1)) * 3600L + Integer.parseInt(m.group(2)) * 60L
                + Integer.parseInt(m.group(3))) * 1000L + Integer.parseInt(m.group(4)) * 10L;
    }

    /** SRT/VTT 时间分量范围与先后顺序校验（小时组可为空 = MM:SS 形式）。 */
    private static String checkTimeRange(String h1, String m1, String s1, String ms1,
                                         String h2, String m2, String s2, String ms2, int cueNo) {
        int mm1 = Integer.parseInt(m1);
        int ss1 = Integer.parseInt(s1);
        int mm2 = Integer.parseInt(m2);
        int ss2 = Integer.parseInt(s2);
        if (mm1 > 59 || ss1 > 59 || mm2 > 59 || ss2 > 59) {
            return "第 " + cueNo + " 条字幕时间分量超出范围（分/秒须 ≤59）";
        }
        long start = ((h1 == null ? 0 : Integer.parseInt(h1)) * 3600L + mm1 * 60L + ss1) * 1000L
                + Long.parseLong(ms1);
        long end = ((h2 == null ? 0 : Integer.parseInt(h2)) * 3600L + mm2 * 60L + ss2) * 1000L
                + Long.parseLong(ms2);
        if (start > end) {
            return "第 " + cueNo + " 条字幕开始时间晚于结束时间";
        }
        return null;
    }

    private static String abbreviate(String s) {
        String t = s.trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    /** 将 cue 列表组装为标准 SRT 文本（序号从 1 开始，毫秒用逗号）。 */
    public static String toSrt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(i + 1).append("\n");
            sb.append(c.start()).append(" --> ").append(c.end()).append("\n");
            sb.append(c.text() == null ? "" : c.text()).append("\n");
        }
        return sb.toString();
    }

    /** 将 cue 列表组装为标准 VTT 文本（WEBVTT 头，毫秒用点号）。 */
    public static String toVtt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder("WEBVTT\n");
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            sb.append("\n").append(i + 1).append("\n");
            sb.append(c.start().replace(',', '.')).append(" --> ").append(c.end().replace(',', '.')).append("\n");
            sb.append(c.text() == null ? "" : c.text()).append("\n");
        }
        return sb.toString();
    }

    /** 归一化时间轴：补全小时位、毫秒分隔符统一为逗号（SRT 规范）。 */
    private static String normalizeTime(String t) {
        String s = t.trim().replace('.', ',');
        // 若为 MM:SS,mmm 则补 00:
        if (s.chars().filter(ch -> ch == ':').count() == 1) {
            s = "00:" + s;
        }
        // 毫秒位不足 3 位补零
        int comma = s.lastIndexOf(',');
        if (comma >= 0) {
            String ms = s.substring(comma + 1);
            while (ms.length() < 3) {
                ms = ms + "0";
            }
            s = s.substring(0, comma + 1) + ms.substring(0, 3);
        }
        return s;
    }

    /** 从文件名推断扩展名（小写，不含点）。 */
    public static String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
