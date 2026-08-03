/**
 * NexusChain DevTools — 合约测试器
 *
 * 发现并运行合约项目中的测试文件，
 * 在 WASM 运行时中实例化合约并执行测试用例。
 */

import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, join, extname } from 'node:path';

const execFileAsync = promisify(execFile);

/** 单个测试用例结果 */
export interface TestCaseResult {
  /** 测试名称 */
  name: string;
  /** 测试结果 */
  status: 'passed' | 'failed' | 'skipped';
  /** 执行耗时（毫秒） */
  duration: number;
  /** 失败时的错误信息 */
  error?: string;
}

/** 测试运行汇总结果 */
export interface TestRunResult {
  /** 通过数 */
  passed: number;
  /** 失败数 */
  failed: number;
  /** 跳过数 */
  skipped: number;
  /** 总耗时（毫秒） */
  totalDuration: number;
  /** 各测试用例详情 */
  results: TestCaseResult[];
}

/**
 * ContractTester — 合约测试器
 *
 * 在 NexusChain 合约项目中查找测试文件，
 * 使用 WASM 运行时实例化合约并运行测试。
 */
export class ContractTester {
  /** 合约项目根目录 */
  private readonly projectPath: string;

  /** 测试目录名称 */
  private readonly testDir = 'tests';

  /**
   * @param projectPath 合约项目根目录
   */
  constructor(projectPath: string) {
    this.projectPath = projectPath;
  }

  /**
   * 运行合约测试
   * @param pattern 测试文件匹配模式（glob）
   * @returns 测试运行结果
   */
  async runTests(pattern: string = '**/*.test.ts'): Promise<TestRunResult> {
    // 查找测试文件
    const testFiles = this.findTestFiles(this.testDir, pattern);

    if (testFiles.length === 0) {
      console.warn('未找到测试文件');
      return {
        passed: 0,
        failed: 0,
        skipped: 0,
        totalDuration: 0,
        results: [],
      };
    }

    console.log(`找到 ${testFiles.length} 个测试文件`);

    const allResults: TestCaseResult[] = [];

    // 逐个文件运行测试
    for (const file of testFiles) {
      const results = await this.runTestFile(file);
      allResults.push(...results);
    }

    // 统计结果
    const passed = allResults.filter((r) => r.status === 'passed').length;
    const failed = allResults.filter((r) => r.status === 'failed').length;
    const skipped = allResults.filter((r) => r.status === 'skipped').length;
    const totalDuration = allResults.reduce((sum, r) => sum + r.duration, 0);

    return {
      passed,
      failed,
      skipped,
      totalDuration,
      results: allResults,
    };
  }

  /**
   * 运行单个测试文件
   * @param filePath 测试文件路径
   * @returns 该文件中的测试结果列表
   */
  private async runTestFile(filePath: string): Promise<TestCaseResult[]> {
    const results: TestCaseResult[] = [];
    const startTime = Date.now();

    try {
      // 骨架阶段：使用 node 直接执行测试文件
      // 实际实现应使用 WASM 运行时实例化合约后运行
      const { stdout, stderr } = await execFileAsync(
        'npx',
        ['tsx', filePath],
        {
          cwd: this.projectPath,
          timeout: 30000,
          maxBuffer: 5 * 1024 * 1024,
        }
      );

      // 解析输出（骨架阶段简单处理）
      const duration = Date.now() - startTime;
      const testName = this.extractTestName(filePath);

      results.push({
        name: testName,
        status: 'passed',
        duration,
      });

      if (stdout) console.log(stdout);
      if (stderr) console.warn(stderr);
    } catch (error) {
      const duration = Date.now() - startTime;
      const testName = this.extractTestName(filePath);

      results.push({
        name: testName,
        status: 'failed',
        duration,
        error: error instanceof Error ? error.message : String(error),
      });
    }

    return results;
  }

  /**
   * 递归查找测试文件
   * @param dir 搜索目录
   * @param pattern 文件名匹配模式
   * @returns 匹配的文件路径列表
   */
  private findTestFiles(dir: string, pattern: string): string[] {
    const fullDir = resolve(this.projectPath, dir);
    if (!existsSync(fullDir)) return [];

    const results: string[] = [];
    const globRegex = this.globToRegex(pattern);

    const walk = (currentDir: string): void => {
      const entries = readdirSync(currentDir);
      for (const entry of entries) {
        const fullPath = join(currentDir, entry);
        const stat = statSync(fullPath);

        if (stat.isDirectory()) {
          walk(fullPath);
        } else if (globRegex.test(entry)) {
          results.push(fullPath);
        }
      }
    };

    walk(fullDir);
    return results;
  }

  /**
   * 将简单 glob 模式转为正则表达式
   * 支持 * 和 ** 通配符
   */
  private globToRegex(pattern: string): RegExp {
    const escaped = pattern
      .replace(/[.+^${}()|[\]\\]/g, '\\$&')
      .replace(/\*\*/g, '<<DOUBLESTAR>>')
      .replace(/\*/g, '[^/]*')
      .replace(/<<DOUBLESTAR>>/g, '.*');
    return new RegExp(`^${escaped}$`);
  }

  /**
   * 从文件路径提取测试名称
   */
  private extractTestName(filePath: string): string {
    const base = filePath.split(/[\\/]/).pop() ?? filePath;
    return base.replace(extname(base), '');
  }
}
