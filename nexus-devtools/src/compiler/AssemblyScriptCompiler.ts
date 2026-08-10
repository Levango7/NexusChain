/**
 * NexusChain DevTools — AssemblyScript 合约编译器
 *
 * 使用 asc (AssemblyScript 编译器) 将 .ts 源码编译为 WASM 字节码。
 * 依赖外部工具链：node、asc (通过 npm 安装)。
 */

import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { existsSync, mkdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';
import type { WasmCompiler, CompileResult } from './WasmCompiler.js';

const execFileAsync = promisify(execFile);

/**
 * AssemblyScriptCompiler — AssemblyScript 合约编译器实现
 *
 * 工作流程：
 * 1. 检查 asc 是否可用
 * 2. 运行 `asc` 编译入口文件为 WASM
 * 3. 将生成的 .wasm 文件放到输出目录
 */
export class AssemblyScriptCompiler implements WasmCompiler {
  /** asc 编译器命令名（可能是全局或 npx 调用） */
  private readonly ascCommand = 'asc';

  /** 编译器默认超时时间 */
  private readonly timeout = 60000;

  /**
   * 编译 AssemblyScript 合约项目为 WASM
   */
  async compile(projectPath: string, outputPath: string): Promise<CompileResult> {
    // 校验入口文件
    const entryFile = resolve(projectPath, 'assembly', 'index.ts');
    if (!existsSync(entryFile)) {
      return {
        success: false,
        error: new Error(`未找到合约入口文件: ${entryFile}`),
      };
    }

    // 创建输出目录
    mkdirSync(outputPath, { recursive: true });

    // WASM 输出路径
    const wasmOutputPath = join(outputPath, 'contract.wasm');

    try {
      // 执行 asc 编译
      const { stdout, stderr } = await execFileAsync(
        'npx',
        [
          this.ascCommand,
          entryFile,
          '--outFile',
          wasmOutputPath,
          '--optimize',
          '--exportRuntime',
        ],
        {
          cwd: projectPath,
          timeout: this.timeout,
          maxBuffer: 10 * 1024 * 1024,
        }
      );

      // 校验输出文件
      if (!existsSync(wasmOutputPath)) {
        return {
          success: false,
          stdout,
          stderr,
          error: new Error('编译完成但未找到输出 .wasm 文件'),
        };
      }

      const wasmSize = statSync(wasmOutputPath).size;

      return {
        success: true,
        wasmPath: wasmOutputPath,
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
   * 检查 AssemblyScript 工具链是否已安装
   */
  async checkToolchain(): Promise<boolean> {
    try {
      // 尝试通过 npx 检查 asc
      await execFileAsync('npx', [this.ascCommand, '--version'], {
        timeout: 10000,
      });
      return true;
    } catch {
      console.warn('AssemblyScript 编译器 (asc) 未安装或不可用');
      console.warn('请运行: npm install -g assemblyscript');
      return false;
    }
  }
}
