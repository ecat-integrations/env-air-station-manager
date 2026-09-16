package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryParamKey;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 历史/SDK 查询 mapper SQL 形状护栏——不连 DB，断言 XML 里的结构性闸门不被后续改动破坏：
 * <ol>
 *   <li>interval_mode = #{modeCode} 过滤（防串台唯一闸，ADM 教训——BACK/FRONT 同 data_time 双行并存，
 *       不过滤会串台）；</li>
 *   <li>tuple IN-list foreach（batch 单 SQL，禁 N+1）；</li>
 *   <li>窗口过滤（data_time &gt;= start AND &lt; end）；</li>
 *   <li>data_time 升序（外部按时间序列旧→新）；</li>
 *   <li>表名 ${table} 白名单插值（service 按 granularity 映射，非用户输入）；</li>
 *   <li>分页 total 计数（countStatRows）与行集同 <code>&lt;include&gt;</code> 过滤段、无 LIMIT/ORDER，
 *       且真实过 MyBatis 解析、绑定出参含 COUNT(*) 与 tuple IN 占位（XML 解析失败会级联瘫痪整个
 *       mapper 装配，文本断言抓不住这类结构性损坏）。</li>
 * </ol>
 *
 * @author coffee
 */
class AsmHistoryQueryMapperSqlShapeTest {

    private static final String XML_PATH = "/mapper/AsmHistoryQueryMapper.xml";

    private static String loadXml() {
        InputStream in = AsmHistoryQueryMapperSqlShapeTest.class.getResourceAsStream(XML_PATH);
        assertNotNull(in, "mapper XML 不存在: " + XML_PATH);
        try (Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return sc.next();
        }
    }

    @Test
    void statRowsSelectMustFilterIntervalMode() {
        String xml = loadXml();
        // 防串台唯一闸：必须按 interval_mode 过滤（PK 含 interval_mode，多 mode 并存）
        assertTrue(xml.contains("interval_mode = #{modeCode}"),
                "stat 查询必须含 interval_mode = #{modeCode} 过滤（防串台唯一闸）");
    }

    @Test
    void statRowsSelectMustUseTupleInListBatch() {
        String xml = loadXml();
        assertTrue(xml.contains("(logic_device_unique_id, attr_id) IN"),
                "batch 查询必须 tuple IN-list（单 SQL 取全参数，禁 N+1）");
        assertTrue(xml.contains("<foreach collection=\"seriesList\""),
                "tuple IN 必须经 seriesList foreach 展开");
    }

    @Test
    void statRowsSelectMustFilterWindowAndOrderAsc() {
        String xml = loadXml();
        assertTrue(xml.contains("data_time &gt;= #{start}"), "须窗口下界过滤 data_time >= #{start}");
        assertTrue(xml.contains("data_time &lt; #{end}"), "须窗口上界过滤 data_time < #{end}");
        assertTrue(xml.contains("ORDER BY data_time ASC"), "须 data_time 升序（SDK 机对机旧→新序列）");
    }

    @Test
    void statRowsDescMustMirrorAscFilteringWithReversedOrderOnly() {
        // 降序语句（历史页网格分页专用）与升序语句（SDK 契约语句）唯一差异=ORDER BY 方向：
        // SDK 升序语句零改动是机对机契约的硬前提，故降序独立成语句并在此锁形状——
        // 过滤段必须同一 <include>（两语句口径漂移=同窗两套 WHERE）、必须有界分页、方向必须 DESC。
        String xml = loadXml();
        String desc = sliceById(xml, "select", "selectStatRowsDesc");
        assertTrue(desc.contains("ORDER BY data_time DESC"), "降序语句须 ORDER BY data_time DESC：" + desc);
        assertFalse(desc.contains("ORDER BY data_time ASC"), "降序语句不得混入 ASC：" + desc);
        assertTrue(desc.contains("<include refid=\"statRowsWhere\"/>"),
                "降序语句须 include 与升序/计数同一过滤段（防口径漂移）");
        assertTrue(desc.contains("LIMIT #{limit} OFFSET #{offset}"), "降序语句须带界分页");
        assertTrue(desc.contains("FROM ${table}"), "降序语句表名同须 ${table} 白名单插值");

        // 升序语句零回归：仍恰好一条 ASC 方向语句且不带 DESC（SDK queryStat 形状不变）
        String asc = sliceById(xml, "select", "selectStatRows");
        assertTrue(asc.contains("ORDER BY data_time ASC"), "SDK 升序语句须保持 data_time ASC：" + asc);
        assertFalse(asc.contains("DESC"), "SDK 升序语句不得被降序改动波及：" + asc);
    }

    @Test
    void tableMustBeWhitelistInterpolation() {
        String xml = loadXml();
        assertTrue(xml.contains("FROM ${table}"), "表名须 ${table} 白名单插值（service 按 granularity 映射）");
    }

    @Test
    void countMustReuseSameWhereFragmentAsRowSelect() {
        String xml = loadXml();
        // 真分页 total 的口径闸：count 与行集必须来自同一 <sql> 片段——各写一份 WHERE 必然漂移
        //（改了行集过滤忘改计数 = total 与行集口径不一致，前端页数错误）。
        assertTrue(xml.contains("<sql id=\"statRowsWhere\">"), "行集/计数共用过滤段须以 <sql id=\"statRowsWhere\"> 定义");
        String where = sliceById(xml, "sql", "statRowsWhere");
        assertTrue(where.contains("data_time &gt;= #{start}") && where.contains("data_time &lt; #{end}"),
                "共用过滤段须含窗口过滤");
        assertTrue(where.contains("interval_mode = #{modeCode}"), "共用过滤段须含防串台闸 interval_mode = #{modeCode}");
        assertTrue(where.contains("(logic_device_unique_id, attr_id) IN"), "共用过滤段须含 tuple IN");
        // 两条语句各自 <include> 同一片段（缺一边 = 该语句没吃到同口径过滤）
        assertTrue(sliceById(xml, "select", "selectStatRows").contains("<include refid=\"statRowsWhere\"/>"),
                "selectStatRows 须 include 共用过滤段");
        assertTrue(sliceById(xml, "select", "countStatRows").contains("<include refid=\"statRowsWhere\"/>"),
                "countStatRows 须 include 共用过滤段");
    }

    @Test
    void countMustBeUngroupedUnorderedAndUnpaged() {
        String xml = loadXml();
        String count = sliceById(xml, "select", "countStatRows");
        assertNotNull(count, "countStatRows 语句须存在（历史页真分页 total）");
        assertTrue(count.contains("COUNT(*)"), "计数语句须 COUNT(*)");
        assertTrue(count.contains("resultType=\"long\""), "计数返回须 resultType=\"long\"");
        // 计数不带 LIMIT/OFFSET（带分页 = total 恒等于页大小，页数算死）；无 ORDER BY（计数无序免排序开销）
        assertFalse(count.contains("LIMIT") || count.contains("OFFSET"), "计数语句不得带 LIMIT/OFFSET");
        assertFalse(count.contains("ORDER BY"), "计数语句不得带 ORDER BY");
    }

    @Test
    void historyMapperXmlMustParseAndCountStatementMustBind() throws Exception {
        // 真实 MyBatis 解析（无 DB）：本 mapper 自包含（raw 最新值语句删除后不再引用跨 mapper resultMap）
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AsmHistoryQueryMapper.class);
        try (InputStream in = Resources.getResourceAsStream("mapper/AsmHistoryQueryMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "AsmHistoryQueryMapper.xml", configuration.getSqlFragments()).parse();
        }

        // count 语句可寻址且 <include> 过滤段真实展开成可执行 SQL（COUNT(*) + tuple IN 占位、无 LIMIT/OFFSET）
        MappedStatement ms = configuration.getMappedStatement(
                AsmHistoryQueryMapper.class.getName() + ".countStatRows");
        Map<String, Object> params = new HashMap<>();
        params.put("table", "asm_stat_minute");
        params.put("start", Instant.parse("2026-09-09T00:00:00Z"));
        params.put("end", Instant.parse("2026-09-09T01:00:00Z"));
        params.put("seriesList", java.util.Collections.singletonList(
                AsmHistoryParamKey.of("logicdevice_station.d", "voltage")));
        params.put("modeCode", 2);
        BoundSql boundSql = ms.getBoundSql(params);
        String sql = boundSql.getSql();
        assertTrue(sql.contains("COUNT(*)"), "绑定 SQL 须含 COUNT(*)：" + sql);
        assertTrue(sql.contains("(logic_device_unique_id, attr_id) IN"), "绑定 SQL 须含 tuple IN：" + sql);
        assertTrue(sql.contains("interval_mode = ?"), "绑定 SQL 须含防串台闸占位：" + sql);
        assertFalse(sql.contains("LIMIT") || sql.contains("OFFSET"), "绑定 SQL 不得含 LIMIT/OFFSET：" + sql);
    }

    /** 取出指定标签+id 的语句体（SQL 形状护栏按语句级断言，避免全文 contains 误伤其他语句）。 */
    private static String sliceById(String xml, String tag, String id) {
        String open = "<" + tag + " id=\"" + id + "\"";
        int from = xml.indexOf(open);
        if (from < 0) {
            return "";
        }
        int to = xml.indexOf("</" + tag + ">", from);
        return to < 0 ? "" : xml.substring(from, to);
    }

    @Test
    void rawLatestSelectMustStayDeleted() {
        String xml = loadXml();
        // raw 最新值回放已定案整条删除（2026-09-16）：快照 live 唯一源、无值=无数据（DEF 占位行显 '-'），
        // 不回查 asm_data_sample。回放查询（DISTINCT ON + 多 varchar 排序列）在压缩 chunk 上触发
        // TimescaleDB 通用计划算符缺失缺陷（生产已炸），且命令类/未绑定参数恒缺值使其每分钟必发。
        // 护栏=这条语句不得以任何形态回魂。
        assertFalse(xml.contains("selectLatestSamples"),
                "selectLatestSamples 已废弃删除：快照不回查 raw 表（live 唯一源，无值=DEF 占位）");
        assertFalse(xml.contains("DISTINCT ON"),
                "DISTINCT ON(多 varchar 排序列) 形态在压缩 chunk 上有兼容性雷，禁止回魂");
    }

    @Test
    void paramMetasMustComeFromConfigTables() {
        String xml = loadXml();
        // 参数清单真相源：asm_config_stat（granularity_mask）JOIN asm_config_unit STORAGE 行（unit）
        assertTrue(xml.contains("asm_config_stat"),
                "参数清单须含 asm_config_stat（granularity_mask 真相源）");
        assertTrue(xml.contains("purpose = 'STORAGE'"),
                "参数清单 storage unit 须来自 asm_config_unit purpose='STORAGE' 行");
    }
}
