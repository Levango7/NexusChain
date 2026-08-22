import { describe, it, expect } from 'vitest';
import i18n from '../i18n';

describe('i18n 国际化', () => {
  it('应该正确加载中文翻译', () => {
    expect(i18n.t('common.loading')).toBe('加载中...');
    expect(i18n.t('nav.home')).toBe('首页');
  });

  it('应该能切换到英文', async () => {
    await i18n.changeLanguage('en');
    expect(i18n.t('common.loading')).toBe('Loading...');
    expect(i18n.t('nav.home')).toBe('Home');
  });

  it('应该能切换回中文', async () => {
    await i18n.changeLanguage('zh');
    expect(i18n.t('common.loading')).toBe('加载中...');
  });
});