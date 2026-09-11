package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报警记录 mapper SQL 形状护栏——不连 DB：episode 区间重叠谓词（左开右闭窗）以共享
 * {@code overlapWindow} 片段为单一真相源，SDK {@code selectEntriesByType} 与 REST 列表
 * {@code selectList}/{@code countList} 三处必须同源引用（bug-record-20260911-200004：
 * 列表语句曾按「是否带 status」分叉出 end_time-only 锚点，ACTIVE 行 end_time=null 永不命中，
 * 默认视图查不出持续中报警）；{@code selectTypeCatalog} 的目录投影列锁定。
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
        String xml = loadXml();
        String section = section(xml, "selectEntriesByType");
        assertTrue(section.contains("from asm_alarm_record"), "目标表 asm_alarm_record");
        assertTrue(section.contains("alarm_type = #{alarmType}"), "报警标识等值过滤（查询键）");
        assertTrue(section.contains("<include refid=\"overlapWindow\"/>"),
                "窗口谓词须引用共享片段 overlapWindow（单一真相源，不内联副本）");
        assertTrue(section.contains("order by start_time desc"), "trigger_time 降序（新 episode 在前）");
        assertTrue(section.contains("limit #{limit}"), "行数上限");
    }

    @Test
    void overlapWindowFragmentMustLockLeftOpenRightClosedPredicate() {
        String xml = loadXml();
        int idx = xml.indexOf("<sql id=\"overlapWindow\">");
        assertTrue(idx >= 0, "缺少共享片段 <sql id=\"overlapWindow\">（窗口谓词单一真相源）");
        String fragment = xml.substring(idx, xml.indexOf("</sql>", idx));
        // 右闭：end 时刻触发的报警算本期——上窗 end=下窗 start 的连续分窗不漏
        assertTrue(fragment.contains("start_time &lt;= #{end}"),
                "右闭 start_time <= end：end 时刻触发的报警须算本期");
        // 左开 + 持续中可见：恰在 start 闭单归上期（跨窗不重）；end_time=null 的 ACTIVE 行
        // （持续中）不受比较约束天然命中——end_time 落窗锚点永远查不出持续中
        assertTrue(fragment.contains("end_time is null or end_time &gt; #{start}"),
                "左开 (end_time is null or end_time > start)：恰在 start 闭单归上期，ACTIVE 行天然命中");
    }

    @Test
    void listQueriesMustShareOverlapWindowPredicateWithSdk() {
        String xml = loadXml();
        for (String id : new String[]{"selectList", "countList"}) {
            String section = section(xml, id);
            assertTrue(section.contains("<include refid=\"overlapWindow\"/>"),
                    id + " 须引用共享片段 overlapWindow（与 SDK 同口径，ACTIVE 行默认视图可见）");
            // 分叉废除：不允许再出现「不带 status 只锚 end_time」的分支（ACTIVE 行 end_time=null 永不命中）
            assertTrue(!section.contains("<choose>"),
                    id + " 窗口谓词不得按 status 分叉（end_time-only 分支滤掉持续中报警）");
            assertTrue(!section.contains("COALESCE(end_time, last_breach_time) &gt;="),
                    id + " 不得以 COALESCE 锚点作窗口谓词（旧口径残留）");
        }
        String selectList = section(xml, "selectList");
        // 排序锚保持：已恢复行按恢复时刻、活跃行按最近续期时刻降序（持续报警自动浮顶）
        assertTrue(selectList.contains("order by COALESCE(end_time, last_breach_time) desc"),
                "selectList 排序须保持 COALESCE(end_time, last_breach_time) desc");
        assertTrue(selectList.contains("limit #{limit}"), "selectList 行数上限（页切依据）");
        String countList = section(xml, "countList");
        assertTrue(countList.contains("count(*)"), "countList 须 count(*)（分页 total）");
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
