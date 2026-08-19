-- ASM 监控数据表（TimescaleDB：raw 明细 hypertable + 分钟/5分/时三级 PG 声明式月度分区父表
-- [app 自管物化 + app 自管分区 ensure] + 每 series 聚合/单位配置表 + 计算审计表 + 压缩 + retention）。
-- 库：ruoyi 主库（PostgreSQL）public schema + TimescaleDB 扩展。app 不自动跑（无 flyway）；运维执行。
-- 前置：CREATE EXTENSION IF NOT EXISTS timescaledb;（本文件首行已含，幂等）
-- 业务键：logic_device_unique_id + attr_id（非运行时 deviceId；airstation 逻辑设备 uniqueId 前缀
--   logicdevice_station.*，重启稳定——设备生命周期归 logicdevice-airstation，ASM 不建/不删）。
-- 设计要点（设计 doc §3，D3=ASM 内精简 avg-only 三级引擎，不依赖 ADM jar）：
--   minute←raw、5min←minute、hour←minute 级联；INSERT…ON CONFLICT PK DO UPDATE 幂等；
--   interval_mode 进 PK 支持多 mode 并存（BACK (L,R] / FRONT [S,E)，同 ADM 模式）；
--   raw 压缩 7 天 + 2 年 retention（Timescale）；stat 表月度裸分区长期保留。
-- 无生产：改表直接改 DDL 清旧用新，不写迁移/不兼容旧数据（同 ADM 治理）。

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ===== asm_data_sample — raw 明细 hypertable =====
-- 一行 = 一站房逻辑设备 + 一参数 + 一值 + 一时间（纵表，参数增减=加行不动表结构）。
-- unit 存 UnitInfo.getFullUnitString() key 形式（如 TemperatureUnit.CELSIUS；NULL=无量纲/无单位定义，
-- 读出口不换算显原生）；站房设备多为数值量（温湿度/电力/UPS），少数开关/文本量走 value_text。
CREATE TABLE asm_data_sample
(
    logic_device_unique_id varchar(64)  NOT NULL,            -- 站房逻辑设备 uniqueId（logicdevice_station.*）
    attr_id                varchar(64)  NOT NULL,            -- logic attr id（airstation AttrIdStation 常量）
    data_time             timestamptz  NOT NULL,            -- 采样时间（hypertable 分区列；timestamptz 保跨时区不歧义）
    value_num              numeric(18,6),                     -- 数值业务值（温度/电压等；非数值参数为 NULL）
    value_text             text,                              -- 文本业务值（状态串/开关量；数值参数为 NULL）
    unit                   varchar(64),                       -- 采集原生单位（UnitInfo.getFullUnitString() key 形式；NULL=无单位）
    source                 varchar(16)  NOT NULL DEFAULT 'POLL' -- 数据来源标记：POLL=总线实时 / 其他来源预留
);
SELECT create_hypertable('asm_data_sample', 'data_time', chunk_time_interval => INTERVAL '1 day', if_not_exists => TRUE);

-- 列存压缩：segmentby 设备+参数加速「按设备+参数+时间段」查询（ALTER TABLE SET 是唯一合法 API，
-- create_compression_hypertable 不是 TimescaleDB 合法函数——ADM 曾误用致整文件隐式回滚，勿重蹈）。
ALTER TABLE asm_data_sample SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'logic_device_unique_id, attr_id',
    timescaledb.compress_orderby   = 'data_time DESC'
);
CREATE INDEX IF NOT EXISTS idx_asm_sample_logic_attr_time ON asm_data_sample (logic_device_unique_id, attr_id, data_time DESC);

-- ===== 三级 stat 分区父表（minute ← raw / 5min ← minute / hour ← minute）=====
-- avg-only 精简引擎（D3）：每桶 avg_value + valid_count/total_count 计数，无 min/max/statuses 等扩展列。
-- PK(data_time, logic_device_unique_id, attr_id, interval_mode)：多 mode 并存下同桶 BACK/FRONT 各自独立一行；
-- ON CONFLICT 按 4 列定冲突（重算/回补幂等，DO UPDATE 全量覆盖 + 刷 updated_at）。
-- 月度分区（PARTITION BY RANGE (data_time) 按 UTC 月一表如 asm_stat_minute_202608）：分区由 app 层
--   AsmStatPartitionManager 幂等 ensure（写路径收口），不引 pg_partman。不建 DEFAULT 分区（严格模式，
--   有意为之）：缺分区时 INSERT 显式报错暴露 ensure 收口漏洞；SELECT 天然安全。
-- 本 DDL 只建父表不建分区：stat 是派生数据（raw 是真相源），部署后 recompute 时分区由写路径 ensure 自动建。

-- 分钟统计（层级基础层 ← raw）
CREATE TABLE IF NOT EXISTS asm_stat_minute (
    data_time              timestamptz NOT NULL,   -- 桶标注（BACK=右沿 (L,R] 桶标=R / FRONT=左沿 [S,E) 桶标=S）
    logic_device_unique_id varchar(64) NOT NULL,
    attr_id                varchar(64) NOT NULL,
    interval_mode          smallint    NOT NULL,   -- 区间模式（AsmIntervalMode.code：FRONT=1 / BACK=2）；进 PK 多 mode 并存
    avg_value              double precision,       -- 桶内均值（avg-only 引擎唯一聚合值）
    valid_count            bigint,                 -- 有效样本数（非空数值且状态有效）
    total_count            bigint,                 -- 非空值计数（占比分母）
    updated_at             timestamptz NOT NULL DEFAULT now(),  -- 最近一次物化覆盖时刻（upsert 刷）
    CONSTRAINT pk_asm_stat_minute PRIMARY KEY (data_time, logic_device_unique_id, attr_id, interval_mode)
) PARTITION BY RANGE (data_time);
CREATE INDEX IF NOT EXISTS idx_asm_stat_minute_logic_attr_time
    ON asm_stat_minute (logic_device_unique_id, attr_id, interval_mode, data_time DESC);
COMMENT ON TABLE asm_stat_minute IS '分钟统计月度分区父表(基础层←raw;app 自管 avg-only 物化;PG 声明式 RANGE 分区按 UTC 月,分区由 AsmStatPartitionManager 写路径 ensure,无 DEFAULT 分区缺分区 INSERT 显式报错);PK 含 interval_mode 多 mode 并存(BACK/FRONT);ON CONFLICT 4 列 DO UPDATE 全量覆盖幂等';

-- 5 分钟统计（层级 ← minute）
CREATE TABLE IF NOT EXISTS asm_stat_5min (
    data_time              timestamptz NOT NULL,
    logic_device_unique_id varchar(64) NOT NULL,
    attr_id                varchar(64) NOT NULL,
    interval_mode          smallint    NOT NULL,
    avg_value              double precision,
    valid_count            bigint,
    total_count            bigint,
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_asm_stat_5min PRIMARY KEY (data_time, logic_device_unique_id, attr_id, interval_mode)
) PARTITION BY RANGE (data_time);
CREATE INDEX IF NOT EXISTS idx_asm_stat_5min_logic_attr_time
    ON asm_stat_5min (logic_device_unique_id, attr_id, interval_mode, data_time DESC);
COMMENT ON TABLE asm_stat_5min IS '5分钟统计月度分区父表(层级←minute mean-of-means;物化/分区机制同 asm_stat_minute)';

-- 小时统计（层级 ← minute）
CREATE TABLE IF NOT EXISTS asm_stat_hour (
    data_time              timestamptz NOT NULL,
    logic_device_unique_id varchar(64) NOT NULL,
    attr_id                varchar(64) NOT NULL,
    interval_mode          smallint    NOT NULL,
    avg_value              double precision,
    valid_count            bigint,
    total_count            bigint,
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_asm_stat_hour PRIMARY KEY (data_time, logic_device_unique_id, attr_id, interval_mode)
) PARTITION BY RANGE (data_time);
CREATE INDEX IF NOT EXISTS idx_asm_stat_hour_logic_attr_time
    ON asm_stat_hour (logic_device_unique_id, attr_id, interval_mode, data_time DESC);
COMMENT ON TABLE asm_stat_hour IS '小时统计月度分区父表(层级←minute mean-of-means;物化/分区机制同 asm_stat_minute)';

-- ===== asm_config_stat — 每 series 聚合配置（seed 默认行 + 人工覆写）=====
-- 一 series（uid+attr）一行；enabled 关该 series 全部物化（D1 双报警/双聚合人工管控开关同思路）。
-- granularity_mask 位掩码 bit0=minute / bit1=5min / bit2=hour（AsmGranularityMask）；
-- materialization_mode FRONT/BACK/BOTH（默认 BOTH——前端切 view 永远有数据；切换不删旧桶不回溯历史）。
-- 默认行由 AsmSeedService 首见 series 时 insertIfAbsent（ON CONFLICT DO NOTHING 不覆盖人工配置）。
CREATE TABLE asm_config_stat (
    logic_device_unique_id varchar(64) NOT NULL,
    attr_id                varchar(64) NOT NULL,
    enabled                boolean      NOT NULL DEFAULT TRUE,   -- 该 series 物化总开关（默认开）
    granularity_mask       smallint     NOT NULL DEFAULT 7,      -- 适用粒度位掩码（默认 7=minute|5min|hour 全开）
    materialization_mode   varchar(8)   NOT NULL DEFAULT 'BOTH', -- FRONT/BACK/BOTH（AsmMaterializationMode.name()）
    created_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_asm_config_stat PRIMARY KEY (logic_device_unique_id, attr_id)
);
COMMENT ON TABLE asm_config_stat IS 'ASM 每 series 聚合配置;enabled 总开关/granularity_mask 位掩码(bit0=分/bit1=5分/bit2=时)/materialization_mode FRONT|BACK|BOTH(默认 BOTH,切换不删旧桶);默认行 seed insertIfAbsent 不覆盖人工配置';

-- ===== asm_config_unit — 每 series 单位偏好（用途窄行；D4 同 adm_config_unit 语义）=====
-- purpose=STORAGE/MONITOR/HISTORY 每 (series, purpose) 一行；unit 存 UnitInfo.getFullUnitString() key
-- 形式经 UnitInfoFactory 反解；NULL/缺行=显原生（native 保底是设计语义非兜底）。
-- STORAGE 行由 AsmSeedService 首见 series 时 insertIfAbsent（unit=airstation attr 定义 nativeUnit，
-- 无单位则空串占位行——行存在表达「已 seed」，unit 值可人工改）。
CREATE TABLE asm_config_unit (
    logic_device_unique_id varchar(64) NOT NULL,
    attr_id                varchar(64) NOT NULL,
    purpose                varchar(16) NOT NULL,   -- 用途（AsmUnitPurpose 枚举名）：STORAGE 物化 / MONITOR 卡片 / HISTORY 历史
    unit                   varchar(64),            -- 目标单位（getFullUnitString key 形式；NULL=无量纲/显原生）
    created_by             varchar(64),
    updated_by             varchar(64),
    created_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_asm_config_unit PRIMARY KEY (logic_device_unique_id, attr_id, purpose)
);
COMMENT ON TABLE asm_config_unit IS 'ASM 每 series 单位偏好(用途窄行);purpose STORAGE/MONITOR/HISTORY;unit getFullUnitString key 形式(NULL=显原生);STORAGE 行 seed insertIfAbsent unit=airstation attr nativeUnit;读出口缺行 native 保底(设计语义)';

-- ===== asm_stat_compute_log — 物化执行审计（逐粒度一行；排障第一入口，对齐 adm_stat_compute_log）=====
CREATE TABLE asm_stat_compute_log (
    id                bigserial    PRIMARY KEY,
    granularity       varchar(8)   NOT NULL,   -- 本次计算粒度（minute/5min/hour）
    trigger_source    varchar(16)  NOT NULL,   -- 触发源（AsmComputeTrigger：SCHEDULE 定时 / RECONFIG 配置切换 / MANUAL 手动）
    window_start      timestamptz  NOT NULL,   -- 本次计算覆盖窗口起点（开闭由 interval_mode 定）
    window_end        timestamptz  NOT NULL,   -- 本次计算覆盖窗口终点
    interval_mode     varchar(8)   NOT NULL,   -- 配置快照：本次物化的区间模式（FRONT/BACK）
    bucket_count      integer,                 -- 摘要：本次产出桶数（SUCCESS 非 NULL；FAILED NULL）
    started_at        timestamptz  NOT NULL,
    ended_at          timestamptz  NOT NULL,
    status            varchar(8)   NOT NULL,   -- SUCCESS / FAILED（严格枚举，未知抛）
    error             text,                    -- FAILED 异常摘要
    created_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_asm_compute_log_window ON asm_stat_compute_log (granularity, window_start, window_end);
CREATE INDEX IF NOT EXISTS idx_asm_compute_log_started ON asm_stat_compute_log (started_at DESC);
COMMENT ON TABLE asm_stat_compute_log IS 'ASM 物化执行审计(逐粒度一行);trigger_source 触发源/window 覆盖窗/bucket_count 摘要/status+error 追溯失败;排障第一入口';

-- ===== 压缩策略（raw 7 天；stat 月度裸分区不压缩）=====
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM timescaledb_information.jobs WHERE proc_name = 'policy_compression' AND hypertable_name = 'asm_data_sample') THEN
    PERFORM add_compression_policy('asm_data_sample', INTERVAL '7 days');
  END IF;
END $$;

-- ===== retention（raw 2 年；stat 月分区长期保留，极老月份按需 DROP 整月表）=====
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM timescaledb_information.jobs WHERE proc_name = 'policy_retention' AND hypertable_name = 'asm_data_sample') THEN
    PERFORM add_retention_policy('asm_data_sample', INTERVAL '2 years');
  END IF;
END $$;

-- ===== asm_alarm_rule — 动环报警规则（P3；setting_content JSON 契约兼容 env_alarm_settings：device_info: uniqueId→[attrId]、name/description/icon/category/enabled/configurable、configs[type=range/number/duration/setting]，扩展 check/compare/match/severity）=====
CREATE TABLE asm_alarm_rule (
    id                bigserial     PRIMARY KEY,
    alarm_type        varchar(16)   NOT NULL,   -- 报警类型编码（动环域语义，移植 env-alarm-manager 1/2/3/4/5/6/8*/9*/12/14/15/17/18/22/23*/1010；*为参数化改写）
    severity          varchar(2)    NOT NULL DEFAULT '0',  -- 0普通/1重要/2紧急（规则级可配）
    setting_content   text          NOT NULL,   -- 规则 JSON（设备/参数引用一律参数化，零硬编码）
    sort              integer       DEFAULT 0,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_asm_alarm_rule_type UNIQUE (alarm_type),
    CONSTRAINT ck_asm_alarm_rule_severity CHECK (severity IN ('0','1','2'))
);
CREATE INDEX IF NOT EXISTS idx_asm_alarm_rule_sort ON asm_alarm_rule (sort, id);
COMMENT ON TABLE asm_alarm_rule IS 'ASM 动环报警规则;坏配置在索引加载层隔离(error log+跳过该行);CRUD 变更后热加载重建内存索引';

-- ===== asm_alarm_record — ASM 自有报警记录（D2：不写 env-data-manager 表）=====
CREATE TABLE asm_alarm_record (
    id                       bigserial    PRIMARY KEY,
    alarm_type               varchar(16)  NOT NULL,
    rule_name                varchar(100) NOT NULL,  -- setting_content.name 快照
    logic_device_unique_id   varchar(128) NOT NULL,
    attr_id                  varchar(64)  NOT NULL,
    severity                 varchar(2)   NOT NULL,  -- 来自规则配置（修复点4，不固定 "0"）
    start_time               timestamptz  NOT NULL,  -- 持续类首超限时刻/事件时刻
    end_time                 timestamptz  NOT NULL,  -- 触发/恢复时刻（查询窗锚点）
    description              text,
    status                   varchar(2)   NOT NULL DEFAULT '0',
    result_content           text,                   -- 机读明细 JSON（value/threshold/kind）
    created_at               timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_asm_alarm_record_window ON asm_alarm_record (end_time DESC);
CREATE INDEX IF NOT EXISTS idx_asm_alarm_record_device ON asm_alarm_record (logic_device_unique_id, end_time DESC);
COMMENT ON TABLE asm_alarm_record IS 'ASM 报警记录;断电恢复类含恢复记录(end_time=恢复时刻,description 含恢复语义);同 (uid,attr,alarm_type) 5 分钟窗口内存去重';

-- ===== P3 规则 seed（动环域语义；airdevice 分析仪域规则不移植）=====
INSERT INTO asm_alarm_rule (alarm_type, severity, setting_content, sort) VALUES
('1', '0', '{"name":"设备间温度异常","description":"温度超出范围且持续超时触发","icon":"Thermometer","category":"environment","enabled":true,"configurable":true,"device_info":{"logicdevice_station.th":["temperature","temperature_indoor"]},"configs":[{"label":"温度范围 (℃)","type":"range","value":[18,28]},{"label":"持续时间 (分钟)","type":"duration","value":5}]}', 10),
('2', '0', '{"name":"设备间湿度异常","description":"湿度超出范围且持续超时触发","icon":"Droplet","category":"environment","enabled":true,"configurable":true,"device_info":{"logicdevice_station.th":["humidity","humidity_indoor"]},"configs":[{"label":"湿度范围 (%RH)","type":"range","value":[30,70]},{"label":"持续时间 (分钟)","type":"duration","value":5}]}', 20),
('3', '0', '{"name":"设备间漏水","description":"水浸传感器报警触发","icon":"Droplet","category":"environment","enabled":true,"device_info":{"logicdevice_station.security_alarm":["water_leak"]},"configs":[{"type":"status","value":["报警"],"match":"equals"}]}', 30),
('4', '0', '{"name":"供电电源异常波动","description":"供电电压超出范围且持续超时触发","icon":"Bolt","category":"power","enabled":true,"configurable":true,"device_info":{"logicdevice_station.power_meter":["voltage_a","voltage_b","voltage_c"]},"configs":[{"label":"电压范围 (V)","type":"range","value":[198,242]},{"label":"持续时间 (分钟)","type":"duration","value":5}]}', 40),
('5', '0', '{"name":"稳压电源异常波动","description":"稳压输出超出范围且持续超时触发","icon":"Zap","category":"power","enabled":true,"configurable":true,"device_info":{"logicdevice_station.regulated_power":["voltage"]},"configs":[{"label":"电压范围 (V)","type":"range","value":[198,242]},{"label":"持续时间 (分钟)","type":"duration","value":5}]}', 50),
('6', '0', '{"name":"电流异常波动","description":"电流超出范围且持续超时触发","icon":"Activity","category":"power","enabled":true,"configurable":true,"device_info":{"logicdevice_station.power_meter":["current_a","current_b","current_c"]},"configs":[{"label":"电流范围 (A)","type":"range","value":[0,30]},{"label":"持续时间 (分钟)","type":"duration","value":5}]}', 60),
('9', '1', '{"name":"标准气体更换","description":"钢瓶剩余压力低于阈值预警","icon":"Battery","category":"gas","enabled":true,"configurable":true,"device_info":{"logicdevice_station.standard_gas.co":["gas_pressure_remaining"],"logicdevice_station.standard_gas.nox":["gas_pressure_remaining"],"logicdevice_station.standard_gas.so2":["gas_pressure_remaining"]},"configs":[{"label":"钢瓶气压力阈值 (kPa)","type":"number","class":"gas_pressure_remaining","value":500,"compare":"lt"}]}', 130),
('12', '0', '{"name":"采样总管温度异常","description":"采样总管温度超出范围且持续超时触发","icon":"Thermometer","category":"sampling","enabled":true,"configurable":true,"device_info":{"logicdevice_station.sampling_tube":["temperature"]},"configs":[{"label":"温度范围 (℃)","type":"range","value":[30,40]},{"label":"持续时间 (分钟)","type":"duration","value":5}]}', 70),
('14', '0', '{"name":"设备监测报警","description":"站房监测设备报警状态触发","icon":"Settings","category":"equipment","enabled":true,"device_info":{"logicdevice_station.calibrator":["alarm_status"]},"configs":[{"type":"status","value":["报警","ON","低报警","高报警"],"match":"equals"}]}', 80),
('15', '2', '{"name":"断电和恢复报警","description":"电压低于阈值报警，恢复后记录恢复","icon":"Power","category":"power","enabled":true,"device_info":{"logicdevice_station.power_meter":["voltage_a","voltage_b","voltage_c"]},"configs":[{"label":"断电电压阈值 (V)","type":"number","class":"power","value":100}]}', 90),
('17', '1', '{"name":"异常进入报警","description":"摄像头入侵检测报警触发","icon":"User","category":"security","enabled":true,"device_info":{"logicdevice_station.camera.1":["intrusion_alarm"],"logicdevice_station.camera.2":["intrusion_alarm"],"logicdevice_station.camera.3":["intrusion_alarm"],"logicdevice_station.camera.4":["intrusion_alarm"]},"configs":[{"type":"status","value":["报警"],"match":"equals"}]}', 180),
('18', '1', '{"name":"干扰报警","description":"摄像头干扰检测报警触发","icon":"Radio","category":"security","enabled":true,"device_info":{"logicdevice_station.camera.1":["interference_alarm"],"logicdevice_station.camera.2":["interference_alarm"],"logicdevice_station.camera.3":["interference_alarm"],"logicdevice_station.camera.4":["interference_alarm"]},"configs":[{"type":"status","value":["报警"],"match":"equals"}]}', 190),
('22', '1', '{"name":"门禁异常使用报警","description":"门禁事件含失败/超时/超次/不匹配关键词触发","icon":"Lock","category":"security","enabled":true,"device_info":{"logicdevice_station.access_control":["event_info"]},"configs":[{"type":"status","value":["失败","超时","超次","不匹配"],"match":"contains"}]}', 200),
('23', '1', '{"name":"站房洁净度报警","description":"站房内 PM 浓度超过阈值触发","icon":"Wind","category":"environment","enabled":true,"configurable":true,"device_info":{"logicdevice_station.indoor_pollutant":["pm10_indoor","pm25_indoor"]},"configs":[{"label":"PM10 阈值 (ug/m3)","type":"number","class":"pm10_indoor","value":150},{"label":"PM2.5 阈值 (ug/m3)","type":"number","class":"pm25_indoor","value":75}]}', 210),
('1010', '0', '{"name":"超出范围报警","description":"通用参数超范围持续报警（多参数自定义）","icon":"AlertTriangle","category":"equipment","enabled":false,"configurable":true,"device_info":{"logicdevice_station.th":["temperature"]},"configs":[{"label":"范围","type":"range","value":[18,28]},{"label":"持续时间 (分钟)","type":"duration","value":5}]}', 220)
ON CONFLICT (alarm_type) DO NOTHING;

-- ===== asm_control_record — 控制审计（P4 设计 §7；统一控制服务 AsmControlService 唯一收口，REST REMOTE / SDK LOCAL 两路同源）=====
CREATE TABLE asm_control_record (
    id                     bigserial     PRIMARY KEY,
    origin                 varchar(8)    NOT NULL,   -- LOCAL（SDK，caller=消费方集成坐标）/REMOTE（REST，caller=认证 principal）
    caller                 varchar(128)  NOT NULL,
    logic_device_unique_id varchar(128)  NOT NULL,
    attr_id                varchar(64)   NOT NULL,
    action                 varchar(8)    NOT NULL,   -- WRITE（写阈值等）/COMMAND（Command 型属性下发，按 attr 类型派生）
    before_value           text,                     -- 调用前 AttrState 快照（displayValue[ unit]）
    requested_value        text          NOT NULL,
    after_value            text,                     -- 执行完成后有限超时回读；TIMEOUT 不猜结果（null）
    result                 varchar(8)    NOT NULL,   -- PENDING 先落；终态 SUCCESS/FAILED/TIMEOUT 异步回填
    error                  text,
    duration_ms            bigint,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_asm_control_origin CHECK (origin IN ('LOCAL','REMOTE')),
    CONSTRAINT ck_asm_control_result CHECK (result IN ('PENDING','SUCCESS','FAILED','TIMEOUT')),
    CONSTRAINT ck_asm_control_action CHECK (action IN ('WRITE','COMMAND'))
);
CREATE INDEX IF NOT EXISTS idx_asm_control_record_time ON asm_control_record (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_asm_control_record_device ON asm_control_record (logic_device_unique_id, created_at DESC);
COMMENT ON TABLE asm_control_record IS 'ASM 控制审计;先落 PENDING(before/requested)执行后回填终态(after/result/error/duration);已知边界:绕过 ASM 直打 core/logicdevice-api 的写不经此审计';

-- ===== P4 补移植 alarm_type=8 标准气体泄漏（bug-record-20260818-111500：注释声称移植但 seed 缺行）=====
-- 语义移植 env-alarm-manager checkAlarmGasLeakage：标气泄漏检测数据超阈值触发 + 联动开排风扇；
-- 修复点3：风扇 uid/attr/写值参数化进 configs setting（原硬编码 fanDeviceId），联动经 AsmControlService 以 LOCAL/asm-alarm 收口审计。
-- 2026-08-19 bug-record-20260818-234100：触发 attr 改 standard_gas 实际泄漏检测属性 gas_leak_data
--   （StandardGasLogicDeviceMapping.createGasLeakDataAttr，NUMERIC/PPM，gas_leak_alarm 即由它 UpperThreshold 派生）；
--   联动 param_id fan_speed→speed（ExhaustFanLogicDevice SpeedOptions）。
INSERT INTO asm_alarm_rule (alarm_type, severity, setting_content, sort) VALUES
('8', '1', '{"name":"标准气体泄漏","description":"标气泄漏检测浓度超阈值触发并联动开排风扇","icon":"FlaskConical","category":"gas","enabled":true,"configurable":true,"device_info":{"logicdevice_station.standard_gas.co":["gas_leak_data"],"logicdevice_station.standard_gas.nox":["gas_leak_data"],"logicdevice_station.standard_gas.so2":["gas_leak_data"]},"configs":[{"label":"泄漏检测浓度阈值 (ppm)","type":"number","class":"gas_leak_data","value":50,"compare":"gt"},{"type":"setting","device_id":"logicdevice_station.exhaust_fan","param_id":"speed","value":"high"}]}', 120)
ON CONFLICT (alarm_type) DO NOTHING;
