package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * ASM 报警记录 mapper（{@code asm_alarm_record}，D2 自有表）——评估触发 insert + 列表/SDK 查询；
 * 另承载 SDK 报警标识目录源（{@link #selectTypeCatalog} 查 {@code asm_alarm_rule} 投影）。
 *
 * @author coffee
 */
public interface AsmAlarmRecordMapper {

    /** 落一条报警记录（触发/恢复；心跳窗模型下新 episode 才走此处，续期走 {@link #extendActive}）。 */
    int insert(AsmAlarmRecord record);

    /** 查某身份 (uid,attrId,alarmType) 当前 ACTIVE 行（无则 null）——recordTrigger 续期/新插判定。 */
    AsmAlarmRecord selectActive(@Param("logicDeviceUniqueId") String logicDeviceUniqueId,
                                @Param("attrId") String attrId,
                                @Param("alarmType") String alarmType);

    /** ACTIVE 行续期：只推 last_breach_time（start_time 不动，end_time 保持 null）。 */
    int extendActive(@Param("id") long id, @Param("lastBreachTime") Instant lastBreachTime);

    /** 全量 ACTIVE 行（sweep 判闭 / 启动 registry 重建）。 */
    List<AsmAlarmRecord> selectAllActive();

    /** 批量闭单：status→INACTIVE + end_time=now（只闭仍 ACTIVE 的行，幂等）。 */
    int closeBatch(@Param("ids") List<Long> ids, @Param("now") Instant now);

    /**
     * 时间窗查询（SDK 出口共用）。
     *
     * @param logicDeviceUniqueId 设备过滤（null=全部）
     * @param status              生命周期过滤（null=全部；ACTIVE/INACTIVE）
     * @param start               起始时刻（含；窗锚点 end_time，INACTIVE 行才有值）
     * @param end                 结束时刻（不含）
     * @param limit               行数上限（调用方已校验 1..1000）
     */
    List<AsmAlarmRecord> selectList(@Param("logicDeviceUniqueId") String logicDeviceUniqueId,
                                    @Param("status") String status,
                                    @Param("start") Instant start,
                                    @Param("end") Instant end,
                                    @Param("limit") int limit);

    /** 同 {@link #selectList} 条件的计数（REST 分页 total）。 */
    long countList(@Param("logicDeviceUniqueId") String logicDeviceUniqueId,
                   @Param("status") String status,
                   @Param("start") Instant start,
                   @Param("end") Instant end);

    /**
     * SDK 按报警标识 + 时间窗查询（episode 区间重叠）。查询窗左开右闭 {@code (start, end]}：
     * end 时刻触发的算本期、恰在 start 闭单的归上期，连续分窗查询无缝无重；end_time=null 的
     * ACTIVE 行（持续中）开区间到 +∞ 天然命中。trigger_time 降序。
     *
     * @param alarmType 报警标识（等值过滤，调用方已校验非空白）
     * @param start     窗口起（开）
     * @param end       窗口止（闭）
     * @param limit     行数上限（调用方已校验 1..1000）
     */
    List<AsmAlarmRecord> selectEntriesByType(@Param("alarmType") String alarmType,
                                             @Param("start") Instant start,
                                             @Param("end") Instant end,
                                             @Param("limit") int limit);

    /**
     * SDK 报警标识目录源（asm_alarm_rule 全量投影：alarm_type/severity/setting_content 三列；
     * ruleName 在 setting_content JSON 内，由服务层经规则定义解析，坏行隔离跳过）。按 alarmType 升序。
     */
    List<AsmAlarmRule> selectTypeCatalog();
}
