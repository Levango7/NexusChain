import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import zh from "./locales/zh.json";
import en from "./locales/en.json";

i18n.use(initReactI18next).init({
  resources: {
    zh: { translation: zh },
    en: { translation: en },
  },
  lng: "zh",
  fallbackLng: "zh",
  interpolation: {
    escapeValue: false,
  },
});

/**
 * 同步 `<html lang>` 属性。
 *
 * 2026-09-16 审查修复（P2）：`index.html` 此前写死 `lang="en"`，而默认语言是
 * `zh`，且切换语言时不更新该属性 —— 屏幕阅读器会按错误语言的发音规则朗读。
 */
function syncHtmlLang(lng: string): void {
  if (typeof document !== "undefined") {
    document.documentElement.lang = lng;
  }
}

syncHtmlLang(i18n.language);
i18n.on("languageChanged", syncHtmlLang);

export default i18n;
