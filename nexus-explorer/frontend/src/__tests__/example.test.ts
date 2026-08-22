import { describe, it, expect } from 'vitest';

describe('基础断言测试', () => {
  it('应该正确执行加法运算', () => {
    expect(1 + 1).toBe(2);
  });

  it('应该正确判断字符串相等', () => {
    expect('NexusChain').toBe('NexusChain');
  });

  it('应该正确处理数组操作', () => {
    const arr = [1, 2, 3];
    expect(arr).toHaveLength(3);
    expect(arr).toContain(2);
  });

  it('应该正确处理对象比较', () => {
    const obj = { name: 'Nexus', version: '0.1.0' };
    expect(obj).toEqual({ name: 'Nexus', version: '0.1.0' });
  });
});