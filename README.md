# 空气站房管理集成(env-air-station-manager)

管理 `logicdevice-airstation` 的 22 类站房逻辑设备（uniqueId 前缀 `logicdevice_station.*`）的监测数据：消费 `device.data.update` 总线事件，做站房参数（温度/湿度/门禁/排风等动环量）的实时快照、三级均值聚合（minute/5min/hour）、历史查询、动环报警与控制审计。

REST 前缀 `/asm-monitor/*`（8080 ruoyi 上下文，经 ruoyi 鉴权）。设备生命周期归 airstation 集成，本集成不建/不删逻辑设备；与 env-air-device-manager 为兄弟集成，互不依赖。

## Agent 怎么用

- 服务型集成（ruoyi REST + 动态 jar 装载），core 启动后由 `integration-ecat-core-ruoyi` 桥接装载，REST 经 8080 `/adm-monitor` 同款 ruoyi 上下文访问（前缀 `/asm-monitor`）。
- 数据入口是总线消费（过滤 airstation 设备），不是主动轮询；调试数据问题先看总线事件是否到达。
- 对外 SDK：`AirStationSdk`（api 包，进程内 registry 取用，见入口类 TODO 注释），跨集成消费 maven 依赖本 jar（provided）。

## 怎么配置

- `src/main/resources/ecat-config.yml`：requires_core `^3.1.0`；运行时依赖 integration-ecat-core-ruoyi / integration-logicdevice / integration-logicdevice-airstation（均 provided）。
- 聚合粒度、单位偏好（STORAGE/MONITOR/HISTORY）、报警规则均经 REST 配置接口管理，不直接改 entry yml。
- 构建：模块目录 `mvnd install -DskipTests`；改 `.vue` 后须 `npm run release` 重建 dist（P5 前端落地后适用）。

## 当前状态

P0 脚手架：目录结构 + 动态 jar 装载 + 四清单注册完成；数据管道（P1）/历史查询与 SDK（P2）/报警（P3）/控制（P4）/前端（P5）分期落地。
