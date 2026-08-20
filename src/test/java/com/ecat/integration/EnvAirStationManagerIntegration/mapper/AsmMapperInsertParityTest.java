package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全 mapper insert 列/值数量 parity 护栏——不连 DB 的静态对账。
 *
 * <p>应用场景：加列时容易只改列清单漏 values 占位符（{@code display_precision} 一列曾连环漏两次——
 * upsert 漏被 e2e PUT 抓到；insertIfAbsent 漏致 seed 抛 BadSqlGrammar，consumer 首见路整批
 * raw 数据丢弃两万余条）。单测 mock mapper 时 XML 不经真实解析执行，这类失衡只有真库才爆，
 * 本测试逐条 insert 静态对账「列数 == values 表达式数」把整类 bug 拦在编译期旁边。</p>
 */
class AsmMapperInsertParityTest {

    /** mapper XML 目录（surefire 工作目录=模块根）。 */
    private static final Path MAPPER_DIR = Paths.get("src/main/resources/mapper");

    private static final Pattern INSERT_PATTERN = Pattern.compile("<insert\\s+id=\"([^\"]+)\"[^>]*>(.*?)</insert>", Pattern.DOTALL);
    /** 列清单：insert into 表 (cols)——ASM 列名无嵌套括号。 */
    private static final Pattern COLUMNS_PATTERN = Pattern.compile("insert\\s+into\\s+\\w+\\s*\\(([^()]+)\\)");
    /** values 关键字（本仓 mapper 恒小写）。 */
    private static final Pattern VALUES_KEYWORD = Pattern.compile("\\bvalues\\b");

    @Test
    void everyInsertColumnCountMustMatchValueExpressionCount() throws IOException {
        assertTrue(Files.isDirectory(MAPPER_DIR), "mapper 目录不存在: " + MAPPER_DIR.toAbsolutePath());
        List<Path> xmls = Files.list(MAPPER_DIR)
                .filter(p -> p.toString().endsWith(".xml"))
                .sorted()
                .collect(Collectors.toList());
        assertTrue(xmls.size() >= 9, "ASM 应有 9 个 mapper XML，实际 " + xmls.size());

        int checked = 0;
        for (Path xml : xmls) {
            String text = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
            Matcher insert = INSERT_PATTERN.matcher(text);
            while (insert.find()) {
                String id = insert.group(1);
                // 去标签（foreach 等）+ 压空白后做括号对账；foreach 的 open/close 属性可能带括号，先剥掉防误判
                String body = insert.group(2)
                        .replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s+", " ");
                assertParity(xml.getFileName() + "#" + id, body);
                checked++;
            }
        }
        assertTrue(checked >= 11, "应至少对账 11 条 insert（9 个 mapper 累计），实际 " + checked);
    }

    /** 单条 insert 对账：列清单 token 数 == values 首行模板 token 数。 */
    private static void assertParity(String where, String body) {
        Matcher cols = COLUMNS_PATTERN.matcher(body);
        assertTrue(cols.find(), where + ": 解析不到列清单（insert into 表 (cols) 形态约定被破坏）");
        int columnCount = splitTopLevel(cols.group(1)).size();

        Matcher keyword = VALUES_KEYWORD.matcher(body);
        assertTrue(keyword.find(cols.end()), where + ": 解析不到 values 关键字");
        String afterValues = body.substring(keyword.end());
        int open = afterValues.indexOf('(');
        assertTrue(open >= 0, where + ": values 后无表达式括号");
        String row = balancedGroup(afterValues, open);
        int valueCount = splitTopLevel(row).size();

        assertTrue(columnCount == valueCount,
                where + ": 列数 " + columnCount + " != values 表达式数 " + valueCount + "（加列漏占位符类 bug）");
    }

    /** 从 openIdx（'(' 下标）取平衡括号组内容（容忍 now() 等嵌套）。 */
    private static String balancedGroup(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return s.substring(openIdx + 1, i);
                }
            }
        }
        throw new IllegalStateException("括号不平衡: " + s);
    }

    /**
     * 顶层逗号切分——以下三类组内逗号不算分隔：嵌套括号（coalesce(a, b) / now()）与
     * MyBatis 占位符组（{@code #{x, typeHandler=...}} 内部逗号属于同一条表达式）。
     */
    private static List<String> splitTopLevel(String s) {
        List<String> tokens = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || (i + 1 < s.length() && (c == '#' || c == '$') && s.charAt(i + 1) == '{')) {
                depth++;
                current.append(c);
                if (c != '(') {
                    current.append(s.charAt(++i));
                }
            } else if (c == ')' || c == '}') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                tokens.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.toString().trim().isEmpty() && tokens.isEmpty()) {
            throw new IllegalStateException("空表达式: " + s);
        }
        if (!current.toString().trim().isEmpty()) {
            tokens.add(current.toString().trim());
        }
        return tokens;
    }
}
