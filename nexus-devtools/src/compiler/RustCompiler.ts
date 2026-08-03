/**
 * NexusChain DevTools — Rust 合约编译器
 *
 * 使用 rustc + wasm32 target 将 Rust 源码编译为 WASM 字节码。
 * 依赖外部工具链：rustc、cargo、wasm-pack。
 */

import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { existsSync, mkdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';
import type { WasmCompiler, CompileResult } from './WasmCompiler.js';

const execFileAsync = promisify(execFile);

/**
 * RustCompiler — Rust 合约编译器实现
 *
 * 工作流程：
 * 1. 检查 wasm-pack 是否已安装
 * 2. 运行 `wasm-pack build` 编译为 WASM
 * 3. 将生成的 .wasm 文件复制到输出目录
 */
export class RustCompiler implements WasmCompiler {
  /** Rust 工具链组件列表 */
  private readonly requiredTools = ['rustc', 'cargo', 'wasm-pack'];

  /**
   * 编译 Rust 合约项目为 WASM
   */
  async compile(projectPath: string, outputPath: string): Promise<CompileResult> {
    // 校验项目目录
    const cargoTomlPath = resolve(projectPath, 'Cargo.toml');
    if (!existsSync(cargoTomlPath)) {
      return {
        success: false,
        error: new Error(`未找到 Cargo.toml: ${cargoTomlPath}`),
      };
    }

    // 创建输出目录
    mkdirSync(outputPath, { recursive: true });

    try {
      // 执行 wasm-pack 编译
      const { stdout, stderr } = await execFileAsync(
        'wasm-pack',
        [
          'build',
          projectPath,
          '--target',
          'web',
          '--out-dir',
          outputPath,
          '--release',
        ],
        {
          timeout: 120000, // 2 分钟超时
          maxBuffer: 10 * 1024 * 1024, // 10MB
        }
      );

      // 查找生成的 WASM 文件
      const wasmPath = this.findWasmFile(outputPath);
      if (!wasmPath) {
        return {
          success: false,
          stdout,
          stderr,
          error: new Error('编译完成但未找到 .wasm 文件'),
        };
      }

      const wasmSize = statSync(wasmPath).size;

      return {
        success: true,
        wasmPath,
        wasmSize,
        stdout,
        stderr,
      };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error : new Error(String(error)),
        stderr: error instanceof Error ? error.message : undefined,
      };
    }
  }

  /**
   * 检查 Rust 工具链是否已安装
   */
  async checkToolchain(): Promise<boolean> {
    for (const tool of this.requiredTools) {
      try {
        await execFileAsync(tool, ['--version'], { timeout: 5000 });
      } catch {
        console.warn(`工具未安装: ${tool}`);
        return false;
      }
    }
    return true;
  }

  /**
   * 在输出目录中查找 .wasm 文件
   */
  private findWasmFile(outputPath: string): string | undefined {
    const candidates = [
      join(outputPath, 'contract_bg.wasm'),
      join(outputPath, 'index_bg.wasm'),
      join(outputPath, `${outputPath.split(/[\\/]/).pop()}.wasm`),
    ];

    for (const candidate of candidates) {
      if (existsSync(candidate)) return candidate;
    }

    // 回退：在输出目录中搜索任意 .wasm
    try {
      const { readdirSync } = require('node:fs');
      const files = readdirSync(outputPath) as string[];
      const wasm = files.find((f) => f.endsWith('.wasm'));
      if (wasm) return join(outputPath, wasm);
    } catch {
      // 忽略读取错误
    }

    return undefined;
  }
}
