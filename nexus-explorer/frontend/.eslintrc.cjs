/* ESLint config for the explorer frontend (fixes the audit's "lint cannot run"
   finding: package.json declared a lint script but shipped no eslint dep/config). */
module.exports = {
  root: true,
  env: { browser: true, es2021: true },
  parser: "@typescript-eslint/parser",
  parserOptions: { ecmaVersion: "latest", sourceType: "module" },
  plugins: ["@typescript-eslint"],
  extends: ["eslint:recommended", "plugin:@typescript-eslint/recommended"],
  rules: {
    "no-unused-vars": "off",
    "@typescript-eslint/no-unused-vars": ["warn"],
    "@typescript-eslint/no-explicit-any": "off",
  },
  ignorePatterns: ["dist", "node_modules", "build"],
};
