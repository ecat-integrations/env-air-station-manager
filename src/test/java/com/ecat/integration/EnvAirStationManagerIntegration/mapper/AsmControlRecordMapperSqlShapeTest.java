package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制审计 mapper SQL 形状护栏——不连 DB：insert 全列落 PENDING 行（useGeneratedKeys 回填 id）、
 * updateResult 只回填 result/after_value/error/duration_ms（先落后补的审计契约）。
 *
 * @author coffee
 */
class AsmControlRecordMapperSqlShapeTest {

    private static final String XML_PATH = "/mapper/AsmControlRecordMapper.xml";

    private static String loadXml() {
        InputStream in = AsmControlRecordMapperSqlShapeTest.class.getResourceAsStream(XML_PATH);
        assertNotNull(in, "mapper XML 不存在: " + XML_PATH);
        try (Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return sc.next();
        }
    }

    @Test
    void insertMustCoverAuditColumnsAndReturnGeneratedId() {
        String xml = loadXml();
        assertTrue(xml.contains("insert into asm_control_record"), "须有 insert into asm_control_record");
        for (String col : new String[]{"origin", "caller", "logic_device_unique_id", "attr_id",
                "action", "before_value", "requested_value", "result"}) {
            assertTrue(xml.contains(col), "insert 须覆盖审计列 " + col);
        }
        assertTrue(xml.contains("useGeneratedKeys=\"true\""), "insert 须回填生成 id（响应含记录 id）");
    }

    @Test
    void updateMustRefillExecutionOutcomeOnly() {
        String xml = loadXml();
        assertTrue(xml.contains("update asm_control_record"), "须有 update asm_control_record");
        for (String col : new String[]{"result", "after_value", "error", "duration_ms"}) {
            assertTrue(xml.contains(col), "update 须回填 " + col);
        }
        String insertSection = xml.substring(xml.indexOf("<insert"), xml.indexOf("</insert>"));
        assertTrue(!insertSection.contains("after_value"),
                "after_value 不在 insert 列（终态回填列，随 result 一并由 updateResult 落，PENDING 行保持 null）");
    }

    @Test
    void selectListMustCoverWindowFiltersOrderAndLimit() {
        String xml = loadXml();
        String selectSection = xml.substring(xml.indexOf("<select id=\"selectList\""), xml.indexOf("</select>"));
        assertTrue(selectSection.contains("from asm_control_record"), "selectList 须查 asm_control_record");
        for (String frag : new String[]{"order by created_at desc", "limit #{limit}"}) {
            assertTrue(selectSection.contains(frag), "selectList 须含 " + frag);
        }
        // 复用 listWhere 且三过滤在 listWhere 内条件化（可空，不强制内联）
        assertTrue(selectSection.contains("<include refid=\"listWhere\"/>"), "selectList 须复用 listWhere");
        String whereSection = xml.substring(xml.indexOf("<sql id=\"listWhere\">"), xml.indexOf("</sql>"));
        assertTrue(whereSection.contains("<if test=\"origin != null\">"), "origin 过滤须条件化（null=全部）");
        assertTrue(whereSection.contains("<if test=\"result != null\">"), "result 过滤须条件化（null=全部）");
        for (String frag : new String[]{"created_at &gt;= #{start}", "created_at &lt; #{end}",
                "logic_device_unique_id = #{logicDeviceUniqueId}", "origin = #{origin}",
                "result = #{result}"}) {
            assertTrue(whereSection.contains(frag), "listWhere 须含 " + frag);
        }
        // 全列含终态回填列（列表页展示 after_value/duration_ms）
        for (String col : new String[]{"after_value", "duration_ms"}) {
            assertTrue(selectSection.contains(col), "selectList 须含终态列 " + col);
        }
    }

    @Test
    void countListMustShareTheSameWhereAsSelectList() {
        String xml = loadXml();
        int idx = xml.indexOf("<select id=\"countList\"");
        String countSection = xml.substring(idx, xml.indexOf("</select>", idx));
        assertTrue(countSection.contains("count(*)"), "countList 须 count(*)");
        assertTrue(countSection.contains("<include refid=\"listWhere\"/>"),
                "countList 须复用 listWhere（与 selectList 同条件，防 total/rows 口径漂移）");
    }
}
