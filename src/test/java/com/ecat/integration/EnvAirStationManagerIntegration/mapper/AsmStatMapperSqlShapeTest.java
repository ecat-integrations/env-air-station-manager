package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三级 stat upsert value_text 透出护栏——不连 DB，逐语句静态断言非数值值槽三处齐全：
 * <ol>
 *   <li>insert 列清单含 value_text（列缺失 → BadSqlGrammar，真库才爆）；</li>
 *   <li>VALUES 占位符 {@code #{item.valueText}}（列/占位失衡另有 AsmMapperInsertParityTest 数量对账，
 *       本处锁参数名拼写——AsmStatBucket 字段改名时此处红）；</li>
 *   <li>ON CONFLICT DO UPDATE SET value_text = excluded.value_text（最隐蔽的一漏：漏了不报错，
 *       但重算/回补幂等被破坏——冲突行残留旧文本值，只有数据对不上才被发现）。</li>
 * </ol>
 * 三语句（minute/5min/hour）同构逐一断言，防只改其一的复制粘贴漏。
 *
 * @author coffee
 */
class AsmStatMapperSqlShapeTest {

    private static final String XML_PATH = "/mapper/AsmStatMapper.xml";

    private static final String[] UPSERT_IDS = {"upsertMinute", "upsertFiveMin", "upsertHour"};

    private static String loadXml() {
        InputStream in = AsmStatMapperSqlShapeTest.class.getResourceAsStream(XML_PATH);
        assertNotNull(in, "mapper XML 不存在: " + XML_PATH);
        try (Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return sc.next();
        }
    }

    /** 截取单条 insert 语句体（同 id 唯一，重叠无关——各语句块互不嵌套）。 */
    private static String insertBodyOf(String xml, String id) {
        Matcher m = Pattern.compile("<insert\\s+id=\"" + id + "\"[^>]*>(.*?)</insert>", Pattern.DOTALL).matcher(xml);
        assertTrue(m.find(), "缺 insert 语句: " + id);
        return m.group(1);
    }

    @Test
    void everyUpsertMustCarryValueTextInInsertColumns() {
        String xml = loadXml();
        for (String id : UPSERT_IDS) {
            String body = insertBodyOf(xml, id);
            assertTrue(body.contains("value_text"),
                    id + ": insert 列清单缺 value_text（非数值统计值无法落桶）");
        }
    }

    @Test
    void everyUpsertMustBindBucketValueTextPlaceholder() {
        String xml = loadXml();
        for (String id : UPSERT_IDS) {
            String body = insertBodyOf(xml, id);
            assertTrue(body.contains("#{item.valueText}"),
                    id + ": VALUES 缺 #{item.valueText} 占位（参数名须与 AsmStatBucket.valueText 一致）");
        }
    }

    @Test
    void everyUpsertConflictMustOverwriteValueText() {
        String xml = loadXml();
        for (String id : UPSERT_IDS) {
            String body = insertBodyOf(xml, id);
            // excluded.value_text 只出现在 DO UPDATE SET 行（values 段是 #{item.valueText}），
            // 空格对齐变化不影响断言
            assertTrue(body.contains("excluded.value_text"),
                    id + ": ON CONFLICT DO UPDATE 缺 value_text = excluded.value_text（重算残留旧文本值，幂等被破坏）");
        }
    }
}
