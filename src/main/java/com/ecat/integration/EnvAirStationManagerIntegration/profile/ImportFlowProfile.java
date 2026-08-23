package com.ecat.integration.EnvAirStationManagerIntegration.profile;

/**
 * IMPORT_FLOW 策略 profile（与 ADM 同构）。
 *
 * <p>配套集成已实现 IMPORT_FLOW discovery handler（如 saimosen {@code stepDiscoveryImportFlow}）。
 * profile 拼 import data 串 → driver 调 {@code startDiscoveryFlow(coordinate, IMPORT_FLOW, payload)}，
 * 集成自校验 class/model/SN + 自预填 entryData + 直达连接配置步。import data 格式由集成自定义：
 * saimosen v1 = {@code class|model|sn|name}。
 */
public interface ImportFlowProfile extends StationProvisionProfile {

    @Override
    default ProvisionStrategy getStrategy() {
        return ProvisionStrategy.IMPORT_FLOW;
    }

    /** IMPORT_FLOW payload 版本（派发键之一）。saimosen=1。 */
    int getImportVersion();

    /** 构造 import data 串：class/model 来自 profile，sn/name 来自用户。 */
    String buildImportData(String sn, String name);
}
