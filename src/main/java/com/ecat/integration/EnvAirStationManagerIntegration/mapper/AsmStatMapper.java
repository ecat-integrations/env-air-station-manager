package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmStatBucket;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ASM 三级 stat 表 mapper——分钟/5分钟/小时 upsert（avg-only 桶）。
 *
 * <p>三表同构（列逐字一致），每粒度一个 statement（表名静态写死在 XML，非 ${} 插值——防注入且
 * 分区父表名编译期闭合）；ON CONFLICT 4 列 PK DO UPDATE 全量覆盖（avg/valid/total + 刷 updated_at），
 * 重算/回补幂等。批量入参 foreach 多行 VALUES。</p>
 *
 * @author coffee
 */
public interface AsmStatMapper {

    /** 分钟桶批量 upsert（asm_stat_minute；minute←raw 物化）。 */
    int upsertMinute(@Param("list") List<AsmStatBucket> buckets);

    /** 5 分钟桶批量 upsert（asm_stat_5min；5min←minute 物化）。 */
    int upsertFiveMin(@Param("list") List<AsmStatBucket> buckets);

    /** 小时桶批量 upsert（asm_stat_hour；hour←minute 物化）。 */
    int upsertHour(@Param("list") List<AsmStatBucket> buckets);
}
