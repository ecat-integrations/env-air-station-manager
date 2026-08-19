package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

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
 *   <li>表名 ${table} 白名单插值（service 按 granularity 映射，非用户输入）。</li>
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
    void tableMustBeWhitelistInterpolation() {
        String xml = loadXml();
        assertTrue(xml.contains("FROM ${table}"), "表名须 ${table} 白名单插值（service 按 granularity 映射）");
    }

    @Test
    void latestRawMustUseDistinctOnPerSeries() {
        String xml = loadXml();
        // snapshot 的 raw 最新值回放：每 series 取 data_time 最大一行（DISTINCT ON 单 SQL，禁 per-series N+1）
        assertTrue(xml.contains("DISTINCT ON (logic_device_unique_id, attr_id)"),
                "raw 最新值查询须 DISTINCT ON (uid, attr_id) 单 SQL");
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
