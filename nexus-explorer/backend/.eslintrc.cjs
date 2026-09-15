/* ESLint 配置（2026-09-16 审查修复）
 *
 * 背景：package.json 声明了 `lint: eslint src --ext .ts`，但既无 eslint 依赖
 * 也无配置文件 —— 实机运行 `npx eslint src --ext .ts` 返回 exit 2
 * （"ESLint couldn't find a configuration file"）。该脚本此前是装饰性的。
 *
 * 与前端 .eslintrc.cjs 保持同一规则基线（eslint:recommended +
 * @typescript-eslint/recommended），差异仅在 env（Node 而非 browser）。
 */
module.exports = {
  root: true,
  env: { node: true, es2022: true },
  parser: "@typescript-eslint/parser",
  parserOptions: { ecmaVersion: "latest", sourceType: "module" },
  plugins: ["@typescript-eslint"],
  extends: ["eslint:recommended", "plugin:@typescript-eslint/recommended"],
  rules: {
    "no-unused-vars": "off",
    "@typescript-eslint/no-unused-vars": ["warn"],
    "@typescript-eslint/no-explicit-any": "off",
    // 本 BFF 为骨架实现，允许显式标注待办
    "no-console": "off",
  },
  ignorePatterns: ["dist", "node_modules", "coverage"],
};
