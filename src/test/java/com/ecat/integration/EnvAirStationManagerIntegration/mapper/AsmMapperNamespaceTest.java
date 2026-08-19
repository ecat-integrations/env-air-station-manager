package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigUnit;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmDataSample;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmStatBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmStatComputeLog;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ASM 5 mapper 的 namespace/SQL 结构冒烟测试（对齐 ADM AdmMapperNamespaceTest 的 DB 桩模式：
 * mybatis Configuration + XMLMapperBuilder 解析，无真实 DB）。
 *
 * <p>覆盖：xml 解析无异常 / statementId 可寻址 / 关键 SQL 文本断言（on conflict 4 列 upsert /
 * do nothing seed / foreach 批量 / timestamptz TypeHandler 引用）。</p>
 *
 * <p><b>非 BaseEntity 实体护栏</b>：ASM 全部 domain 实体故意不继承 BaseEntity（DynamicJarLoader 只对
 * domain/ + extends BaseEntity 注册短别名），本测试不注册任何短别名——xml 须全用 FQCN，误退短别名
 * parse() 即抛红测。</p>
 */
class AsmMapperNamespaceTest {

    private static Configuration configuration;

    @BeforeAll
    static void setUp() throws Exception {
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 不注册任何实体短别名（ASM 全部非 BaseEntity；xml 须用 FQCN）
        configuration.addMapper(AsmDataSampleMapper.class);
        configuration.addMapper(AsmStatMapper.class);
        configuration.addMapper(AsmConfigStatMapper.class);
        configuration.addMapper(AsmConfigUnitMapper.class);
        configuration.addMapper(AsmStatComputeLogMapper.class);

        String[] xmls = {
                "mapper/AsmDataSampleMapper.xml",
                "mapper/AsmStatMapper.xml",
                "mapper/AsmConfigStatMapper.xml",
                "mapper/AsmConfigUnitMapper.xml",
                "mapper/AsmStatComputeLogMapper.xml",
        };
        for (String xml : xmls) {
            try (InputStream in = Resources.getResourceAsStream(xml)) {
                if (in == null) {
                    fail("mapper xml 不存在: " + xml);
                }
                XMLMapperBuilder parser =
                        new XMLMapperBuilder(in, configuration, xml, configuration.getSqlFragments());
                parser.parse();
            }
        }
    }

    private static BoundSql bound(String shortId, Object param) {
        String fqn = "com.ecat.integration.EnvAirStationManagerIntegration.mapper." + shortId;
        return configuration.getMappedStatement(fqn).getBoundSql(param);
    }

    private static Map<String, Object> param(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 归一空白便于 contains 断言。 */
    private static String norm(BoundSql bs) {
        return bs.getSql().replaceAll("\\s+", " ").toLowerCase();
    }

    /** BoundSql 占位符个数（?计数）。 */
    private static int countPlaceholders(BoundSql bs) {
        return bs.getSql().split("\\?", -1).length - 1;
    }

    /** 该 statement 是否有参数绑定走 TimestamptzInstantTypeHandler（BoundSql 文本不含 handler 名，看映射）。 */
    private static boolean hasInstantTypeHandler(BoundSql bs) {
        for (org.apache.ibatis.mapping.ParameterMapping pm : bs.getParameterMappings()) {
            if (pm.getTypeHandler() instanceof com.ecat.integration.EnvAirStationManagerIntegration.support.TimestamptzInstantTypeHandler) {
                return true;
            }
        }
        return false;
    }

    // ===== AsmDataSampleMapper =====

    @Test
    void dataSample_batchInsert_foreachMultiRow() {
        AsmDataSample s1 = sample("logicdevice_station.th", "temperature", "2026-08-18T00:00:05Z");
        AsmDataSample s2 = sample("logicdevice_station.th", "humidity", "2026-08-18T00:00:05Z");
        BoundSql bs = bound("AsmDataSampleMapper.batchInsert", param("list", Arrays.asList(s1, s2)));
        String sql = norm(bs);
        assertTrue(sql.contains("insert into asm_data_sample"), "目标表 asm_data_sample");
        assertEquals(12, countPlaceholders(bs), "两行 × 各 6 占位符（source 走 'POLL' 字面量）: " + sql);
        assertTrue(sql.contains("'poll'"), "source null 落 DDL 默认 POLL");
        assertTrue(hasInstantTypeHandler(bs), "data_time 走 TypeHandler");
    }

    @Test
    void dataSample_selectByLogicAttrTimeRange_filtersSeriesAndWindow() {
        String sql = norm(bound("AsmDataSampleMapper.selectByLogicAttrTimeRange",
                param("logicDeviceUniqueId", "u", "attrId", "a", "from", Instant.EPOCH, "to", Instant.EPOCH)));
        assertTrue(sql.contains("logic_device_unique_id ="));
        assertTrue(sql.contains("and attr_id ="));
        assertTrue(sql.contains("and data_time between"));
        assertTrue(sql.contains("order by data_time asc"));
    }

    // ===== AsmStatMapper：三级 upsert 形状 =====

    @Test
    void stat_upsertMinute_onConflictFourColumnsFullOverwrite() {
        assertStatUpsert("AsmStatMapper.upsertMinute", "asm_stat_minute");
    }

    @Test
    void stat_upsertFiveMin_onConflictFourColumnsFullOverwrite() {
        assertStatUpsert("AsmStatMapper.upsertFiveMin", "asm_stat_5min");
    }

    @Test
    void stat_upsertHour_onConflictFourColumnsFullOverwrite() {
        assertStatUpsert("AsmStatMapper.upsertHour", "asm_stat_hour");
    }

    private static void assertStatUpsert(String statement, String table) {
        BoundSql bs = bound(statement, param("list", Arrays.asList(bucket(), bucket())));
        String sql = norm(bs);
        assertTrue(sql.contains("insert into " + table), "目标表 " + table);
        assertTrue(sql.contains("on conflict (data_time, logic_device_unique_id, attr_id, interval_mode) do update set"),
                "ON CONFLICT 4 列 PK: " + sql);
        assertTrue(sql.contains("avg_value = excluded.avg_value"), "全量覆盖 avg_value");
        assertTrue(sql.contains("valid_count = excluded.valid_count"), "覆盖 valid_count");
        assertTrue(sql.contains("total_count = excluded.total_count"), "覆盖 total_count");
        assertTrue(sql.contains("updated_at = now()"), "刷 updated_at");
        assertEquals(14, countPlaceholders(bs), "两桶 × 各 7 占位符（updated_at 走 now()）: " + sql);
        assertTrue(hasInstantTypeHandler(bs), "data_time 走 TypeHandler");
    }

    // ===== AsmConfigStatMapper =====

    @Test
    void configStat_upsert_and_insertIfAbsent_differOnConflict() {
        AsmConfigStat row = AsmConfigStat.builder()
                .logicDeviceUniqueId("logicdevice_station.th").attrId("temperature")
                .enabled(true).granularityMask(7).materializationMode("BOTH").build();
        String upsert = norm(bound("AsmConfigStatMapper.upsert", row));
        assertTrue(upsert.contains("on conflict (logic_device_unique_id, attr_id) do update set"));
        assertTrue(upsert.contains("enabled = excluded.enabled"));
        assertTrue(upsert.contains("materialization_mode = excluded.materialization_mode"));

        String seed = norm(bound("AsmConfigStatMapper.insertIfAbsent", row));
        assertTrue(seed.contains("on conflict (logic_device_unique_id, attr_id) do nothing"),
                "seed insertIfAbsent 冲突让路不覆盖人工配置: " + seed);
    }

    @Test
    void configStat_selectBySeries_filtersSeries() {
        String sql = norm(bound("AsmConfigStatMapper.selectBySeries",
                param("logicDeviceUniqueId", "u", "attrId", "a")));
        assertTrue(sql.contains("from asm_config_stat"));
        assertTrue(sql.contains("and attr_id ="));
    }

    // ===== AsmConfigUnitMapper =====

    @Test
    void configUnit_upsert_and_insertIfAbsent() {
        AsmConfigUnit row = AsmConfigUnit.builder()
                .logicDeviceUniqueId("logicdevice_station.th").attrId("temperature")
                .purpose("STORAGE").unit("TemperatureUnit.CELSIUS").build();
        String upsert = norm(bound("AsmConfigUnitMapper.upsert", row));
        assertTrue(upsert.contains("on conflict (logic_device_unique_id, attr_id, purpose) do update set"));
        assertTrue(upsert.contains("unit = excluded.unit"));

        String seed = norm(bound("AsmConfigUnitMapper.insertIfAbsent", row));
        assertTrue(seed.contains("on conflict (logic_device_unique_id, attr_id, purpose) do nothing"));
    }

    @Test
    void configUnit_selectByPurpose_filtersPurpose() {
        String sql = norm(bound("AsmConfigUnitMapper.selectByPurpose", param("purpose", "STORAGE")));
        assertTrue(sql.contains("where purpose = ?"), "按 purpose 等值过滤: " + sql);
    }

    // ===== AsmStatComputeLogMapper =====

    @Test
    void computeLog_insert_carriesTypeHandlers() {
        AsmStatComputeLog row = AsmStatComputeLog.builder()
                .granularity("minute").triggerSource("SCHEDULE")
                .windowStart(Instant.EPOCH).windowEnd(Instant.EPOCH)
                .intervalMode("BACK").bucketCount(10)
                .startedAt(Instant.EPOCH).endedAt(Instant.EPOCH)
                .status("SUCCESS").build();
        BoundSql bs = bound("AsmStatComputeLogMapper.insert", row);
        assertTrue(norm(bs).contains("insert into asm_stat_compute_log"));
        assertTrue(hasInstantTypeHandler(bs), "4 个 timestamptz 列走 TypeHandler");
    }

    // ===== 测试数据工厂 =====

    private static AsmDataSample sample(String uid, String attrId, String time) {
        return AsmDataSample.builder()
                .logicDeviceUniqueId(uid).attrId(attrId)
                .dataTime(Instant.parse(time))
                .valueNum(new BigDecimal("25.5"))
                .unit("TemperatureUnit.CELSIUS")
                .build();
    }

    private static AsmStatBucket bucket() {
        return AsmStatBucket.builder()
                .dataTime(Instant.parse("2026-08-18T00:01:00Z"))
                .logicDeviceUniqueId("logicdevice_station.th")
                .attrId("temperature")
                .intervalMode(2)
                .avgValue(25.5).validCount(1L).totalCount(1L)
                .build();
    }
}
