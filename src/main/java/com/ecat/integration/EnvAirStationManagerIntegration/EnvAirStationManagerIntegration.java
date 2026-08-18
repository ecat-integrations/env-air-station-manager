package com.ecat.integration.EnvAirStationManagerIntegration;

import java.net.URLClassLoader;

import com.ecat.core.Integration.IntegrationBase;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;

/**
 * ASM（Air Station Manager）空气站房管理集成入口。
 *
 * <p>service 型集成（ruoyi REST，前缀 /asm-monitor，8080 ruoyi 上下文），管理对象为
 * logicdevice-airstation 的站房逻辑设备（uniqueId 前缀 logicdevice_station.*）；
 * 设备生命周期归 airstation 集成，本集成只消费 device.data.update 总线数据做
 * 聚合/历史/报警/控制，不建/不删逻辑设备。与 env-air-device-manager 为兄弟集成，互不依赖。</p>
 *
 * <p>P0 脚手架：仅完成动态 jar 装载（ruoyi 反射注入链）与启动日志，空 onStart 装载成功即可；
 * 数据管道/聚合引擎/SDK 等按分期 P1-P5 落地。</p>
 */
public class EnvAirStationManagerIntegration extends IntegrationBase {

    private final Log log = LogFactory.getLogger(getClass());

    /** ruoyi 桥接集成（onInit 取得，onStart 用于把本 jar/vue 装载进 ruoyi）。 */
    private EcatCoreRuoyiIntegration mry;

    @Override
    public void onInit() {
        mry = (EcatCoreRuoyiIntegration) integrationRegistry.getIntegration("integration-ecat-core-ruoyi");
    }

    @Override
    public void onStart() {
        // 类加载器必须是 URLClassLoader 才能被 ruoyi 反射加载（与 env-air-device-manager 同模式）
        if (!(this.loadOption.getClassLoader() instanceof URLClassLoader)) {
            throw new IllegalStateException("类加载器不是URLClassLoader，无法动态加载");
        }
        URLClassLoader classLoader = (URLClassLoader) this.loadOption.getClassLoader();
        // 三层反射注入链：本集成 onStart → mry.loadJarAndVue → RuoyiJarApp → EcatRuoyiAdapter
        // → StaticResourceDynamicRegistry 注册资源到 ruoyi。P0 无 vue，仅装载 jar。
        try {
            mry.loadJarAndVue(classLoader, this);
        } catch (Exception e) {
            // 严格模式：加载失败 fail-fast（jar 装不进 ruoyi 则模块无意义），不静默吞异常
            throw new IllegalStateException("加载 air-station-manager jar/vue 到 ruoyi 失败", e);
        }
        log.info("{} integration started", getName());
    }

    @Override
    public void onPause() {
        // P0 无后台任务/消费者，暂停暂无需要释放的资源；P1 起在此 shutdown 总线 consumer 与调度器
        log.info("Pausing {} integration", getName());
    }

    @Override
    public void onRelease() {
        // P0 无后台任务/消费者，释放暂无需要清理的资源；P1 起在此 shutdown 总线 consumer 与调度器
        log.info("Releasing {} integration", getName());
    }

    // TODO P2: 对外 SDK 出口 AirStationSdk（api 包，接口 + 不可变 DTO，零 ruoyi/vue/Spring 依赖）。
    //  取用方式（进程内，不经 Spring bean）：
    //  ((EnvAirStationManagerIntegration) core.getIntegrationRegistry()
    //      .getIntegration("com.ecat:integration-env-air-station-manager")).getAirStationSdk();
}
