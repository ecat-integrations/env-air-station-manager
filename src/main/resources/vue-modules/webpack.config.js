const path = require("path");
const getCommonWebpackConfig = require("../../../../../env-dev-utils/vue-package/webpack.config.js");
const { VueLoaderPlugin } = require("vue-loader");

// ASM vue 打包：骨架（entry/output/UMD/externals）全部由 env-dev-utils 基座产出，
// 本文件只补本集成局部规则：.vue loader（comments:false 白屏底线）+ babel + css。
const customConfig = {
  plugins: [
    new VueLoaderPlugin(),
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
