package com.ecat.integration.EnvAirStationManagerIntegration;

import java.net.URLClassLoader;
import java.time.Instant;

import com.ecat.core.Bus.BusTopic;
import com.ecat.core.Bus.consumer.BusConsumerBase;
import com.ecat.core.Bus.event.DeviceDataChangedEvent;
import com.ecat.core.Integration.IntegrationBase;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.integration.EnvAirStationManagerIntegration.consumer.AsmDataSampleConsumer;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmDataSampleMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.scheduler.AsmStatRefreshScheduler;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmSeedService;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmStatPartitionManager;
import com.ecat.integration.logicdevice.LogicDeviceManager;

/**
 * ASM（Air Station Manager）空气站房管理集成入口。
 *
 * <p>service 型集成（ruoyi REST，前缀 /asm-monitor，8080 ruoyi 上下文），管理对象为
 * logicdevice-airstation 的站房逻辑设备（uniqueId 前缀 logicdevice_station.*）；
 * 设备生命周期归 airstation 集成，本集成只消费 device.data.update 总线数据做
 * 聚合/历史/报警/控制，不建/不删逻辑设备。与 env-air-device-manager 为兄弟集成，互不依赖。</p>
 *
 * <p>P0 脚手架 + P1b 数据管道：onStart 装载 jar 后装配 raw consumer（订阅总线攒批落
 * asm_data_sample + 首见 seed）+ 启动预 seed（枚举 LogicDeviceManager 现存站房设备）+ stat 月分区
 * ensure + 三粒度物化调度器；onPause/onRelease 反向收口。REST/SDK 等按分期 P2-P5 落地。</p>
 */
public class EnvAirStationManagerIntegration extends IntegrationBase {

    /** raw consumer 容量（drop-oldest 反压上限，对齐 ADM DataSample 同量级）。 */
    private static final int CONSUMER_CAPACITY = 1000;
    /** raw 攒批阈值（满即 flush，对齐 ADM=50）。 */
    private static final int SAMPLE_BATCH_SIZE = 50;
    /** raw 超时 flush 间隔（buffer 非空时定期触发，毫秒，对齐 ADM=2000）。 */
    private static final long SAMPLE_FLUSH_INTERVAL_MS = 2000L;

    private final Log log = LogFactory.getLogger(getClass());

    /** ruoyi 桥接集成（onInit 取得，onStart 用于把本 jar/vue 装载进 ruoyi）。 */
    private EcatCoreRuoyiIntegration mry;

    /** P1b raw 落库 consumer（onStart 装配，onPause/onRelease shutdown）。 */
    private AsmDataSampleConsumer asmDataSampleConsumer;
    /** P1b 三粒度物化调度器（onStart 启动，onPause/onRelease shutdown）。 */
    private AsmStatRefreshScheduler asmStatRefreshScheduler;
    /** P3 报警评估 consumer（onStart 装配，onPause/onRelease shutdown）。 */
    private com.ecat.integration.EnvAirStationManagerIntegration.consumer.AsmAlarmRuleConsumer asmAlarmRuleConsumer;

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
        wireAsmPipeline();
        log.info("{} integration started", getName());
    }

    /**
     * P1b 数据管道接线：① raw consumer 订阅 device.data.update（攒批落 asm_data_sample + 首见 seed）；
     * ② 启动预 seed（LogicDeviceManager 现存站房设备全集——设备存在性是运行时状态，启动后新建的
     * 由 consumer 首见路补，两路同走 AsmSeedService）；③ stat 月分区启动 ensure；④ 三粒度调度器启动。
     *
     * <p>consumer 是 new 的（带 name/capacity 参数）；service/mapper 经 mry.getSpringBean 取 Spring 单例
     * （动态 jar 单例注册只认 @Service/@RestController，同 ADM 接线模式）。</p>
     */
    private void wireAsmPipeline() {
        String topic = BusTopic.DEVICE_DATA_UPDATE.getTopicName();
        asmDataSampleConsumer = new AsmDataSampleConsumer("asm-data-sample",
                CONSUMER_CAPACITY, SAMPLE_BATCH_SIZE, SAMPLE_FLUSH_INTERVAL_MS,
                core.getDeviceRegistry(),
                mry.getSpringBean(AsmDataSampleMapper.class),
                mry.getSpringBean(AsmSeedService.class));
        subscribeAsm(topic, asmDataSampleConsumer);

        // 启动预 seed：airstation 集成已装载的现存站房设备（loader 顺序不保证时集合可能为空，
        // consumer 首见路兜住后建设备——非兜底而是 seed 双路设计，见 AsmSeedService javadoc）
        AsmSeedService seedService = mry.getSpringBean(AsmSeedService.class);
        int seeded = seedService.seedStartup(LogicDeviceManager.getInstance().getRegisteredDevices());

        AsmStatPartitionManager partitionManager = mry.getSpringBean(AsmStatPartitionManager.class);
        partitionManager.ensureStartupPartitions(Instant.now());

        asmStatRefreshScheduler = mry.getSpringBean(AsmStatRefreshScheduler.class);
        asmStatRefreshScheduler.start();

        // P3 报警链路：规则索引启动加载（坏行隔离在 index 内）+ 独立报警评估 consumer（与数据管道 SRP 分离）
        com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleIndex alarmRuleIndex =
                mry.getSpringBean(com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleIndex.class);
        alarmRuleIndex.reload();
        asmAlarmRuleConsumer = new com.ecat.integration.EnvAirStationManagerIntegration.consumer.AsmAlarmRuleConsumer(
                "asm-alarm-rule", CONSUMER_CAPACITY, SAMPLE_BATCH_SIZE, SAMPLE_FLUSH_INTERVAL_MS,
                core.getDeviceRegistry(),
                mry.getSpringBean(com.ecat.integration.EnvAirStationManagerIntegration.rule.AsmAlarmRuleEvaluator.class),
                mry.getSpringBean(com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmAlarmRecordMapper.class),
                alarmRuleIndex,
                mry.getSpringBean(com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService.class));
        subscribeAsm(topic, asmAlarmRuleConsumer);

        log.info("[诊断调试] ASM 数据管道已接线：asm-data-sample consumer 订阅 {}，启动预 seed {} series，"
                + "stat 月分区已 ensure，三粒度物化调度器已启动，报警规则索引已加载 + asm-alarm-rule consumer 已订阅"
                + "（P4：type=8 等联动经 AsmControlService 审计收口）",
                topic, seeded);
    }

    /**
     * Bus EventSubscriber 适配器——总线 BusEvent 信封拆出 DeviceDataChangedEvent 裸 payload 投给
     * consumer.onEvent（BusConsumerBase 非阻塞入队 + 独占 worker；同 ADM subscribeAdm 模式）。
     * payload 类型不符静默跳过（topic 契约载荷是 DeviceDataChangedEvent，误投不臆测强转）。
     */
    private void subscribeAsm(String topic, BusConsumerBase<DeviceDataChangedEvent> consumer) {
        core.getBusRegistry().subscribe(topic, event -> {
            Object payload = event.getPayload();
            if (payload instanceof DeviceDataChangedEvent) {
                consumer.onEvent((DeviceDataChangedEvent) payload);
            }
        });
    }

    @Override
    public void onPause() {
        shutdownPipeline();
        log.info("Pausing {} integration", getName());
    }

    @Override
    public void onRelease() {
        shutdownPipeline();
        log.info("Releasing {} integration", getName());
    }

    /** 反向收口：shutdown raw consumer（drain 残留批）与物化调度器（关线程池）；幂等（null 守卫）。 */
    private void shutdownPipeline() {
        if (asmDataSampleConsumer != null) {
            asmDataSampleConsumer.shutdown();
            asmDataSampleConsumer = null;
        }
        if (asmStatRefreshScheduler != null) {
            asmStatRefreshScheduler.shutdown();
            asmStatRefreshScheduler = null;
        }
        if (asmAlarmRuleConsumer != null) {
            asmAlarmRuleConsumer.shutdown();
            asmAlarmRuleConsumer = null;
        }
    }

    /**
     * P2 对外 SDK 出口（api 包稳定契约）。进程内取用（不经 Spring bean 语义，消费方 maven 依赖
     * ASM jar provided 只 import api 包）：
     * <pre>{@code
     * AirStationSdk sdk = ((EnvAirStationManagerIntegration) core.getIntegrationRegistry()
     *         .getIntegration("com.ecat:integration-env-air-station-manager")).getAirStationSdk();
     * }</pre>
     * 实现是动态 jar Spring 单例（@Service），经 ruoyi 桥接 getSpringBean 按调用时取——onStart 装载
     * 后即就绪，调用于装载前（生命周期外）抛 NoSuchBeanDefinition 由调用方暴露。
     */
    public com.ecat.integration.EnvAirStationManagerIntegration.api.AirStationSdk getAirStationSdk() {
        return mry.getSpringBean(com.ecat.integration.EnvAirStationManagerIntegration.service.AirStationSdkImpl.class);
    }
}
