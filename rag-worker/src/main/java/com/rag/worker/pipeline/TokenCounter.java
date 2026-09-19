package com.rag.worker.pipeline;

import org.springframework.stereotype.Component;

/**
 * Token 计数近似实现（指南 §24）：不引入重量级 tokenizer 库，
 * 中文按字计 1 token，连续 ASCII 字母数字按 ~4 字符/token，其余非空白字符计 1。
 * 用于切片大小控制；同一套口径贯穿整个链路。
 */
@Component
public class TokenCounter {

    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isAsciiAlnum(c)) {
                asciiRun++;
                continue;
            }
            if (asciiRun > 0) {
                tokens += (asciiRun + 3) / 4;
                asciiRun = 0;
            }
            if (isIdeographic(c)) {
                tokens++;
            } else if (!Character.isWhitespace(c)) {
                tokens++;
            }
        }
        if (asciiRun > 0) {
            tokens += (asciiRun + 3) / 4;
        }
        return tokens;
    }

    /** 取文本尾部约 tokens 个 token 的子串（尽量在空白处断）。 */
    public String tail(String text, int tokens) {
        if (text == null || tokens <= 0) {
            return "";
        }
        double budget = tokens;
        int i = text.length() - 1;
        while (i > 0 && budget > 0) {
            char c = text.charAt(i);
            if (isAsciiAlnum(c)) {
                budget -= 0.25;
            } else if (!Character.isWhitespace(c)) {
                budget -= 1;
            }
            i--;
        }
        return text.substring(i + 1).stripLeading();
    }

    /** 按 token 预算硬切：返回不超过 maxTokens 的片段（尽量在空白边界断）。 */
    public java.util.List<String> hardSplitByTokens(String text, int maxTokens) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String rest = text;
        while (!rest.isEmpty()) {
            int used = 0;
            int i = 0;
            int lastBreak = -1;
            while (i < rest.length() && used < maxTokens) {
                char c = rest.charAt(i);
                if (isAsciiAlnum(c)) {
                    // 4 ascii 字符 ≈ 1 token
                    if (i % 4 == 0) used++;
                } else if (!Character.isWhitespace(c)) {
                    used++;
                }
                if (Character.isWhitespace(c) && used <= maxTokens - 1) {
                    lastBreak = i;
                }
                i++;
            }
            int cut = used > maxTokens && lastBreak > 0 ? lastBreak : i;
            out.add(rest.substring(0, cut).strip());
            rest = rest.substring(Math.min(cut, rest.length())).stripLeading();
        }
        return out;
    }

    static boolean isIdeographic(char c) {
        return c >= 0x4E00 && c <= 0x9FFF   // CJK
                || c >= 0x3400 && c <= 0x4DBF
                || c >= 0x3000 && c <= 0x303F; // CJK 标点
    }

    public static boolean isAsciiAlnum(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }
}
