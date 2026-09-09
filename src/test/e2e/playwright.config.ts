import { defineConfig, devices } from '@playwright/test';

/**
 * ASM（env-air-station-manager）Playwright E2E 配置。
 *
 * 测 8081（ruoyi-ui-v3 vite dev server，/dev_api → 8080 ruoyi-admin）；不碰 9999 core-api。
 * 仿 env-air-device-manager/src/test/e2e 范式（自包含 package + page-objects + specs）。
 *
 * 契约（见 asm-e2e-test skill）：
 *  - 路由 `/#/ecat-integrations/integration-env-air-station-manager/air-station-manager/index/{page}`
 *  - 页面 keep-alive 缓存 → 断言前点「刷新/查询」重取，且首跳用 ignoreCache reload。
 *  - ruoyi HTTP 恒 200，真实状态看 body code。
 *  - 测试数据用后还原（规则阈值 / 排风扇 speed / config 模式）。
 */
export default defineConfig({
  testDir: './specs',
  testMatch: '**/*.spec.ts',

  timeout: 90 * 1000,
  expect: { timeout: 15 * 1000 },

  use: {
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
    baseURL: process.env.BASE_URL || 'http://localhost:8081',
    actionTimeout: 15 * 1000,
    // 套件级登录态（globalSetup 生成）：用例零登录交互，慢渲染登录页只在 setup 面对一次
    storageState: 'test-results/.asm-auth.json',
    navigationTimeout: 30 * 1000,
  },

  outputDir: './test-results',

  fullyParallel: false,   // 服务端有状态（控制下发/规则编辑落库），串行避免互染
  forbidOnly: !!process.env.CI,
  retries: 0,             // flaky 不放过：失败即红，确定性等待代替重试
  workers: 1,

  projects: [
    // 双层分组（2026-09-09 提速改造，全套 10-11min → ~6min）：
    //  ro   = 纯读/渲染/mock spec（零服务端写操作，grep 实证）——fullyParallel + CLI --workers=4 并行；
    //  real = 有状态写操作 spec（g2 交互/g3 角色/g9 单位设置/g11 控制/g12 设备配置）——CLI --workers=1 串行防互染。
    // project 间由 npm script 链式顺序执行（ro 先 real 后），跨组不并发；
    // 兼容旧入口：`--project=chromium` 已不存在，全量走 npm run test:asm-e2e。
    {
      name: 'ro',
      testMatch: /asm-g[45678].*\.spec\.ts|asm-g1[03].*\.spec\.ts/,
      fullyParallel: true,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1920, height: 1080 } },
    },
    {
      name: 'real',
      testMatch: /asm-e2e\.spec\.ts|asm-g9.*\.spec\.ts|asm-g1[12].*\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1920, height: 1080 } },
    },
  ],

  globalSetup: require.resolve('./fixtures/setup-env.ts'),

  reporter: [
    ['list'],
    ['html', { outputFolder: './reports/html-report', open: 'never' }],
    ['json', { outputFile: './reports/test-results.json' }],
  ],
});
