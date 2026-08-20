package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * snapshot 单设备行——一个站房逻辑设备的全属性当前态。
 *
 * @author coffee
 */
@Value
@Builder
public class AsmSnapshotDeviceDto {

    /** 站房逻辑设备 uniqueId（logicdevice_station.*）。 */
    String logicDeviceUniqueId;

    /** 设备中文名（entry name，如「1智能视频监控系统」；瓦片标题）。 */
    String displayName;

    /** 设备级在线态（AsmOnlineJudge：online_status attr 优先，60s 窗）。 */
    boolean online;

    /** 离线时长毫秒（now-lastUpdate；null=从未喂数时长未知；在线时也有值）。 */
    Long offlineMs;

    /** 全属性当前态（live 优先，raw 回放兜）。 */
    List<AsmSnapshotAttrDto> attrs;

    /** 活跃报警明细（两源并集；空列表=无报警）。卡片报警徽章=前端对全 attr statuses 求并集（ADM 同构），无设备级 alarm boolean——SSE 增量帧只覆 attr 级字段，结构性杜绝「帧 alarm=false 覆盖 snapshot true」的撕裂。 */
    List<AsmAlarmActiveDto> activeAlarms;
}
