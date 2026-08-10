/**
 * NexusChain AssemblyScript 合约模板入口
 *
 * NexusChain (NEX) 链上 WASM 合约的起点。
 * 实现合约的初始化和业务逻辑入口函数。
 */

// ---- 合约存储 ----

/** 简易 Map 实现（AS 标准库无内置 Map） */
export class ContractStorage {
  private keys: string[] = [];
  private values: string[] = [];

  set(key: string, value: string): void {
    const idx = this.keys.indexOf(key);
    if (idx >= 0) {
      this.values[idx] = value;
    } else {
      this.keys.push(key);
      this.values.push(value);
    }
  }

  get(key: string): string | null {
    const idx = this.keys.indexOf(key);
    if (idx >= 0) {
      return this.values[idx];
    }
    return null;
  }

  has(key: string): bool {
    return this.keys.indexOf(key) >= 0;
  }

  size(): i32 {
    return this.keys.length;
  }
}

// ---- 合约全局状态 ----

let owner: string = '';
let storage: ContractStorage = new ContractStorage();

// ---- 合约入口函数 ----

/**
 * 合约初始化函数
 * 部署时由 NexusChain 虚拟机自动调用。
 * @param ownerAddress 合约所有者地址
 */
export function init(ownerAddress: string): void {
  owner = ownerAddress;
  storage = new ContractStorage();
}

/**
 * 设置存储值
 * @param key 存储键
 * @param value 存储值
 */
export function set(key: string, value: string): void {
  storage.set(key, value);
}

/**
 * 读取存储值
 * @param key 存储键
 * @returns 存储值或 null
 */
export function get(key: string): string | null {
  return storage.get(key);
}

/**
 * 检查键是否存在
 * @param key 存储键
 */
export function has(key: string): bool {
  return storage.has(key);
}

/**
 * 获取合约所有者地址
 */
export function getOwner(): string {
  return owner;
}

/**
 * 获取存储条目数
 */
export function getStorageSize(): i32 {
  return storage.size();
}

// ---- 测试 ----

/** 初始化测试 */
export function testInit(): bool {
  init('0xABC123');
  return getOwner() === '0xABC123';
}

/** 设置和获取测试 */
export function testSetGet(): bool {
  init('0xOWNER');
  set('name', 'NexusChain');
  const value = get('name');
  return value !== null && value === 'NexusChain';
}

/** 缺失键测试 */
export function testMissingKey(): bool {
  init('0xOWNER');
  return get('nonexistent') === null;
}
