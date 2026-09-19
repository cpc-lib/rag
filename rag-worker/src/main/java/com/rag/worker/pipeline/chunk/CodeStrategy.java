package com.rag.worker.pipeline.chunk;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * CODE（指南 §30）：
 * .java 用 JavaParser 真 AST，按类成员（方法/构造器/字段组）切片并补包名/类名元数据；
 * .py/.go/.ts/.js 用边界启发式（def/class/func/function 声明行起片）。
 * 尽量保持代码结构完整，超长片才做 token 硬切兜底。
 */
@Component
@RequiredArgsConstructor
public class CodeStrategy implements ChunkStrategy {

    private static final Pattern PY_BOUND = Pattern.compile(
            "^(?:async\\s+)?def\\s+\\w+|^class\\s+\\w+");
    private static final Pattern GO_BOUND = Pattern.compile(
            "^func\\s+(?:\\([^)]*\\)\\s*)?\\w+");
    private static final Pattern JS_BOUND = Pattern.compile(
            "^(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\w*"
                    + "|^(?:export\\s+)?class\\s+\\w+"
                    + "|^(?:export\\s+)?const\\s+\\w+\\s*=\\s*(?:async\\s*)?\\(");

    private final ChunkSupport support;

    @Override
    public ChunkMode mode() {
        return ChunkMode.CODE;
    }

    @Override
    public List<ChunkPlan> plan(ChunkContext ctx) {
        int size = ctx.params().childSize();
        String ext = ext(ctx.fileName());
        List<ChunkPlan> plans = new ArrayList<>();
        for (var page : ctx.pages()) {
            String text = page.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            List<String> rawChunks = "java".equals(ext)
                    ? javaAstChunks(text)
                    : heuristicChunks(text, ext);
            List<PlannedChild> children = new ArrayList<>();
            for (String raw : rawChunks) {
                for (String bounded : support.hardSplit(raw, size)) {
                    children.add(new PlannedChild(page.page(), bounded));
                }
            }
            if (!children.isEmpty()) {
                plans.add(ChunkPlan.independent(null, null, children));
            }
        }
        return plans;
    }

    // ---------------------------------------------------------------- Java AST

    private List<String> javaAstChunks(String src) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(src);
            String pkg = cu.getPackageDeclaration()
                    .map(p -> p.getNameAsString()).orElse(null);
            List<String> out = new ArrayList<>();
            for (TypeDeclaration<?> type : cu.getTypes()) {
                String cls = type.getNameAsString();
                for (BodyDeclaration<?> member : type.getMembers()) {
                    String meta = (pkg != null ? "Package: " + pkg + "\n" : "")
                            + "Class: " + cls + "\n";
                    out.add(meta + member.toString().strip());
                }
            }
            return out;
        } catch (Exception parseError) {
            // 解析失败（语法不完整等）回退启发式
            return heuristicChunks(src, "java");
        }
    }

    // ---------------------------------------------------------------- 启发式

    private List<String> heuristicChunks(String src, String ext) {
        Pattern bound = switch (ext) {
            case "py" -> PY_BOUND;
            case "go" -> GO_BOUND;
            case "js", "jsx", "ts", "tsx" -> JS_BOUND;
            default -> Pattern.compile(
                    "^(?:public|private|protected)?\\s*(?:static\\s+)?[\\w<>\\[\\]]+\\s+\\w+\\s*\\(");
        };
        List<String> out = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (String line : src.split("\n", -1)) {
            if (bound.matcher(line).find() && !group.isEmpty()) {
                out.add(String.join("\n", group).strip());
                group = new ArrayList<>();
            }
            group.add(line);
        }
        if (!group.isEmpty()) {
            out.add(String.join("\n", group).strip());
        }
        return out;
    }

    private String ext(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
