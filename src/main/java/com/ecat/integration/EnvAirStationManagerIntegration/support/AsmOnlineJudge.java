package com.ecat.integration.EnvAirStationManagerIntegration.support;

import com.ecat.core.State.AttrState;
import com.ecat.core.State.AttributeBase;
import lombok.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * ASM 设备级在线/离线判定（总览页瓦片「在线●绿/离线灰 + 离线时长红字」的数据源，纯函数便于单测）。
 *
 * <p>判定契约（总览页精修设计定案 5）：
 * <ol>
 *   <li><b>优先 online_status attr</b>（{@code LOnlineStatusAttribute}，由物理绑定 attr 60s 内更新
 *       驱动）的 {@code state.lastUpdated}——它的更新节奏即代表物理侧活性；</li>
 *   <li><b>兜底</b>：无该 attr（部分站房设备类型未建）或其尚无 state（未喂数）时，用全部 attr
 *       <b>最新</b> lastUpdated——任一 attr 还在更新（60s 内有更新）即视为在线；</li>
 *   <li><b>窗口 60s</b>（与 LOnlineStatusAttribute「一分钟内更新过=online」同语义）：age &le; 60_000ms
 *       为在线，60s 恰好=在线（边界含）；</li>
 *   <li>全部 attr 无 state（设备从未喂数/重启后未到首帧）→ 离线 + {@code offlineMs=null}（时长未知，
 *       前端只显「离线」不显时长）。</li>
 * </ol>
 *
 * <p>无撕裂读（属性状态契约 §15）：每 attr 经 {@code getState()} 一次取不可变 {@link AttrState}。</p>
 */
public final class AsmOnlineJudge {

    /** 在线窗口（毫秒），对齐 LOnlineStatusAttribute 的 60s 语义。 */
    public static final long ONLINE_WINDOW_MS = 60_000L;

    /** 在线状态属性 id（StationLogicDevice 自动创建；部分设备类型可能缺席走兜底）。 */
    public static final String ONLINE_STATUS_ATTR_ID = "online_status";

    private AsmOnlineJudge() {
    }

    /**
     * 判定一台站房设备的在线态。
     *
     * @param attrs 设备属性表（uid 维度全量；允许空 map）
     * @param now   判定基准时刻（由调用方注入，测试可固定）
     * @return 判定结果（online + offlineMs；offlineMs=null 表示时长未知=从未喂数）
     */
    public static Judgement judge(Map<String, ? extends AttributeBase<?>> attrs, Instant now) {
        Instant basis = null;
        AttributeBase<?> onlineAttr = attrs.get(ONLINE_STATUS_ATTR_ID);
        if (onlineAttr != null) {
            basis = lastUpdatedOf(onlineAttr);
        }
        if (basis == null) {
            // 兜底：全部 attr 最新 lastUpdated（任一还在更新=在线；离线时长=距最新更新）
            for (AttributeBase<?> attr : attrs.values()) {
                Instant t = lastUpdatedOf(attr);
                if (t != null && (basis == null || t.isAfter(basis))) {
                    basis = t;
                }
            }
        }
        if (basis == null) {
            return new Judgement(false, null);
        }
        long ageMs = Duration.between(basis, now).toMillis();
        return new Judgement(ageMs <= ONLINE_WINDOW_MS, ageMs);
    }

    private static Instant lastUpdatedOf(AttributeBase<?> attr) {
        AttrState<?> state = attr.getState();
        return state != null ? state.getLastUpdated() : null;
    }

    /** 判定结果（不可变）。offlineMs=最后更新距今毫秒；null=从未喂数时长未知。 */
    @Value
    public static class Judgement {

        /** 是否在线（age &le; 60s）。 */
        boolean online;

        /** 最后更新距今毫秒（在线时也有值，前端按需展示）；null=从未喂数。 */
        Long offlineMs;
    }
}
