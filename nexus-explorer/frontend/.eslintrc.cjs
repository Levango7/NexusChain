/* ESLint config for the explorer frontend (fixes the audit's "lint cannot run"
   finding: package.json declared a lint script but shipped no eslint dep/config). */
module.exports = {
  root: true,
  env: { browser: true, es2021: true },
  parser: "@typescript-eslint/parser",
  parserOptions: { ecmaVersion: "latest", sourceType: "module" },
  plugins: ["@typescript-eslint", "react-hooks"],
  // 只启用 exhaustive-deps（本仓库 4 处 eslint-disable 注释引用的规则），
  // 不整包 recommended——新版含 React Compiler 系规则（globals/set-state-in-effect），
  // 存量代码（AuthContext 初始化 ref 等模式）不满足会大面积报错，超出本次范围。
  extends: ["eslint:recommended", "plugin:@typescript-eslint/recommended"],
  rules: {
    "no-unused-vars": "off",
    "@typescript-eslint/no-unused-vars": ["warn"],
    "@typescript-eslint/no-explicit-any": "off",
    "react-hooks/exhaustive-deps": "warn",
  },
  ignorePatterns: ["dist", "node_modules", "build"],
};
