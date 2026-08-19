package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmStatComputeLog;

/**
 * ASM 物化执行审计 mapper（{@code asm_stat_compute_log}，insert-only）。
 *
 * <p>物化引擎每次逐粒度计算落一行（SUCCESS/FAILED）；id 由 bigserial 回填。</p>
 *
 * @author coffee
 */
public interface AsmStatComputeLogMapper {

    /** 落一行审计；useGeneratedKeys 回填 id。 */
    int insert(AsmStatComputeLog log);
}
