package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报警记录 mapper SQL 形状护栏——不连 DB：SDK 按报警标识查询 {@code selectEntriesByType} 的
 * episode 区间重叠谓词（左开右闭窗）逐片段锁定，{@code selectTypeCatalog} 的目录投影列锁定。
 *
 * <p>窗口语义是 SDK 对外契约（跨窗连续查询无缝无重），SQL 锚点漂移即契约破坏，故以文本断言锁死。</p>
 *
 * @author coffee
 */
class AsmAlarmRecordMapperSqlShapeTest {

    private static final String XML_PATH = "/mapper/AsmAlarmRecordMapper.xml";

    private static String loadXml() {
        InputStream in = AsmAlarmRecordMapperSqlShapeTest.class.getResourceAsStream(XML_PATH);
        assertNotNull(in, "mapper XML 不存在: " + XML_PATH);
        try (Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return sc.next();
        }
    }

    /** 截取某 select 语句段（id 起 to 匹配关闭标签）。 */
    private static String section(String xml, String statementId) {
        int idx = xml.indexOf("<select id=\"" + statementId + "\"");
        assertTrue(idx >= 0, "缺少语句 select id=\"" + statementId + "\"");
        return xml.substring(idx, xml.indexOf("</select>", idx));
    }

    @Test
    void selectEntriesByTypeMustOverlapEpisodeWithLeftOpenRightClosedWindow() {
        String section = section(loadXml(), "selectEntriesByType");
        assertTrue(section.contains("from asm_alarm_record"), "目标表 asm_alarm_record");
        assertTrue(section.contains("alarm_type = #{alarmType}"), "报警标识等值过滤（查询键）");
        // 右闭：end 时刻触发的报警算本期——上窗 end=下窗 start 的连续分窗不漏
        assertTrue(section.contains("start_time &lt;= #{end}"),
                "右闭 start_time <= end：end 时刻触发的报警须算本期");
        // 左开 + 持续中可见：恰在 start 闭单归上期（跨窗不重）；end_time=null 的 ACTIVE 行
        // （持续中）不受比较约束天然命中——旧锚点 end_time 落窗永远查不出持续中
        assertTrue(section.contains("end_time is null or end_time &gt; #{start}"),
                "左开 (end_time is null or end_time > start)：恰在 start 闭单归上期，ACTIVE 行天然命中");
        assertTrue(section.contains("order by start_time desc"), "trigger_time 降序（新 episode 在前）");
        assertTrue(section.contains("limit #{limit}"), "行数上限");
    }

    @Test
    void selectTypeCatalogMustProjectRuleColumnsForSdkDirectory() {
        String section = section(loadXml(), "selectTypeCatalog");
        assertTrue(section.contains("from asm_alarm_rule"), "目录源=asm_alarm_rule 全量");
        for (String col : new String[]{"alarm_type", "severity", "setting_content"}) {
            assertTrue(section.contains(col), "目录须投影 " + col + "（ruleName 在 setting_content JSON 内，Java 侧解析）");
        }
        assertTrue(section.contains("order by alarm_type"), "目录按 alarmType 升序稳定");
    }
}
