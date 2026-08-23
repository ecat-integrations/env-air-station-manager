const path = require("path");
const getCommonWebpackConfig = require("../../../../../env-dev-utils/vue-package/webpack.config.js");
const { VueLoaderPlugin } = require("vue-loader");

// ASM vue 打包：骨架（entry/output/UMD/externals）全部由 env-dev-utils 基座产出，
// 本文件只补本集成局部规则：.vue loader（comments:false 白屏底线）+ babel + css。
const customConfig = {
  plugins: [
    new VueLoaderPlugin(),
    // 把 config-flow lit lib 的源副本 copy 进 dist(lib/config-flow/...)，供设备配置页 ConfigFlowDialog
    // 经本集成 publicPath(/ecat-integrations/integration-env-air-station-manager/**)serve，
    // 与 env-air-device-manager 同机制（副本来自同一 static/lib/config-flow，构建期同步）。
    new (require("copy-webpack-plugin"))({
      patterns: [
        {
          from: path.resolve(__dirname, "static/lib/config-flow"),
          to: "lib/config-flow",
          // lib 已是 esbuild 构建产物，标记 minimized 让 webpack terser 跳过，保字节一致 + sourcemap 对应
          info: { minimized: true },
        },
      ],
    }),
    require("unplugin-auto-import/webpack").default({
      imports: ["vue"],
      dts: false,
    }),
  ],
  module: {
    rules: [
      {
        test: /\.vue$/,
        loader: "vue-loader",
        options: {
          compilerOptions: {
            // lit <flow-form>/<table-field-renderer> 按原生 custom element 处理（设备配置向导用），
            // 否则 Vue 当未解析组件告警；Vue3 对 custom element 的对象 prop（schema/data/errors）
            // 自动以 DOM 属性下发，lit 响应。
            isCustomElement: (tag) =>
              tag === "flow-form" || tag === "table-field-renderer",
            // 模板根注释 → Fragment → 宿主 transition mode=out-in 间歇白屏（ADM bug-record-20260807-184548
            // 教训，env-dev-utils applyBaseline 也会兜底，此处显式声明保可读）。
            comments: false,
          },
        },
      },
      {
        test: /\.js$/,
        loader: "babel-loader",
        exclude: /node_modules/,
      },
      {
        test: /\.css$/,
        use: ["style-loader", "css-loader"],
      },
    ],
  },
};

module.exports = () => getCommonWebpackConfig(path.resolve(__dirname), customConfig);
