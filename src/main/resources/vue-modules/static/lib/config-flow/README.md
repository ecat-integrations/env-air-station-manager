# config-flow lit lib（env-air 自包含副本）

## 这是什么
env-air ConfigFlow 对话框（`<flow-form>` 自定义元素）依赖的 lit config-flow lib。
原 lib 由 ecat-core-api 集成提供（static/lib/config-flow/dist/），经 8080 SpringBoot
默认 classpath:/static serve。本目录是 env-air **自带的源副本**，与 core-api 解耦。

## 为什么要自带（而不是继续用 core-api 的）
lib 由 ecat-core-api 单方维护、env-air 单方消费，跨 jar 版本耦合。core-api 若改了
lit 接口签名，env-air 的 flow 渲染会挂而 env-air jar 内无 fallback。自带副本让
env-air 锁定自己验证过的版本，独立部署/分发时不依赖 core-api 的 static 资源是否就绪。

## 文件来源
- ecat-config-flow.esm.js      ← lit config-flow lib 构建产物（从 ecat-core-api 拷贝）
- ecat-config-flow.esm.js.map  ← sourcemap

拷贝自：ecat-integrations/ecat-core-api/src/main/resources/static/lib/config-flow/dist/

## 怎么被 serve
webpack CopyPlugin（见 vue-modules/webpack.config.js）把本目录 copy 到
vue-modules/dist/lib/config-flow/。EcatRuoyiAdapter 注册的 publicPath(**) serve 到 dist 子路径。
前端引用：/dev-api/ecat-integrations/integration-env-air-device-manager/lib/config-flow/dist/ecat-config-flow.esm.js

## 何时升级 / 怎么升级（2026-08-15 起构建即同步）
本目录是**已登记消费方**（登记方式=本 dist/ 目录存在，见上游 README 同步策略）：上游每次
`node build.js`（ecat-core-api/src/main/resources/static/lib/config-flow/）构建后自动覆盖同步
本目录的 esm.js / .map / **version.json**（版本单一真相源在上游 version.js，semver）。升级流程：
1. 上游改源码 + bump version.js + `node build.js`（本目录已自动同步，无需手工拷贝）；
2. cd vue-modules && npm run release 重建 dist；
3. browser MCP 回归 ConfigFlow（新建 + reconfigure），确认 <flow-form> 渲染 + 提交正常；
4. mvnd install env-air + restart core + 浏览器 bypassCache reload 验证。
**版本可观测**：utils/configFlowLib.js 加载时 fetch 本目录 version.json 打 info；运行时
`window.__ecatConfigFlowVersion` 可查。不做上游运行时对照（插件不一定运行，对照无实际保证——
2026-08-15 拍板移除）；一致性由构建期保证：上游 build.js 构建即同步本目录，副本是否最新看 git diff / version.json。
不要直接改本目录产物内容（构建产物副本，手改会和上游分叉，失去版本可追溯性）。
