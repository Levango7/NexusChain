#!/usr/bin/env node
/**
 * NexusChain DevTools — CLI 入口
 *
 * 基于 commander.js 构建，提供以下命令：
 *   nexus init     <dir>  — 从模板初始化新合约项目
 *   nexus compile  <dir>  — 编译合约为 WASM
 *   nexus deploy   <dir>  — 部署合约到 NexusChain 链
 *   nexus test     <dir>  — 运行合约测试
 */

import { Command } from 'commander';
import { resolve } from 'node:path';
import { mkdirSync, existsSync, cpSync } from 'node:fs';
import { RustCompiler } from './compiler/RustCompiler.js';
import { AssemblyScriptCompiler } from './compiler/AssemblyScriptCompiler.js';
import { ContractDeployer } from './deployer/ContractDeployer.js';
import { ContractTester } from './test/ContractTester.js';

const program = new Command();

program
  .name('nexus')
  .description('NexusChain 开发者工具链 — WASM 合约编译、部署、调试、测试')
  .version('0.1.0');

// ---- init 命令：初始化合约项目 ----
program
  .command('init')
  .description('从模板初始化新的 NexusChain 合约项目')
  .argument('<dir>', '项目目录')
  .option('-l, --lang <language>', '合约语言 (rust | assemblyscript)', 'rust')
  .action((dir: string, opts: { lang: string }) => {
    const lang = opts.lang.toLowerCase();
    const supportedLangs = ['rust', 'assemblyscript'];

    if (!supportedLangs.includes(lang)) {
      console.error(`不支持的语言: ${lang}。支持的语言: ${supportedLangs.join(', ')}`);
      process.exit(1);
    }

    const targetPath = resolve(process.cwd(), dir);
    if (existsSync(targetPath)) {
      console.error(`目录已存在: ${targetPath}`);
      process.exit(1);
    }

    // 从内置模板复制
    const templateDir = resolve(
      import.meta.dirname ?? __dirname,
      '..',
      'templates',
      lang
    );

    if (!existsSync(templateDir)) {
      console.error(`模板不存在: ${templateDir}`);
      process.exit(1);
    }

    mkdirSync(targetPath, { recursive: true });
    cpSync(templateDir, targetPath, { recursive: true });

    console.log(`NexusChain 合约项目已创建: ${targetPath}`);
    console.log(`语言: ${lang}`);
    console.log(`\n下一步: cd ${dir} && nexus compile .`);
  });

// ---- compile 命令：编译合约 ----
program
  .command('compile')
  .description('编译合约为 WASM 字节码')
  .argument('<dir>', '合约项目目录')
  .option('-o, --output <path>', '输出目录', './build')
  .option('-l, --lang <language>', '覆盖语言检测')
  .action(async (dir: string, opts: { output: string; lang?: string }) => {
    const projectPath = resolve(process.cwd(), dir);
    const outputPath = resolve(projectPath, opts.output);

    // 选择编译器
    const lang = opts.lang ?? detectLanguage(projectPath);
    const compiler =
      lang === 'rust'
        ? new RustCompiler()
        : lang === 'assemblyscript'
          ? new AssemblyScriptCompiler()
          : null;

    if (!compiler) {
      console.error(`无法确定合约语言，请用 --lang 指定`);
      process.exit(1);
    }

    console.log(`正在编译 (${lang})...`);
    const result = await compiler.compile(projectPath, outputPath);

    if (result.success) {
      console.log(`编译成功!`);
      console.log(`  WASM: ${result.wasmPath}`);
      console.log(`  大小: ${result.wasmSize ?? '未知'} bytes`);
    } else {
      console.error(`编译失败:`);
      console.error(result.stderr || result.error?.message || '未知错误');
      process.exit(1);
    }
  });

// ---- deploy 命令：部署合约 ----
program
  .command('deploy')
  .description('部署 WASM 合约到 NexusChain 链')
  .argument('<dir>', '合约项目目录')
  .requiredOption('-n, --node <url>', 'NexusChain 节点 RPC 地址')
  .option('-k, --key <path>', '签名密钥文件路径')
  .option('-w, --wasm <path>', 'WASM 文件路径（默认使用 build 目录）')
  .action(async (dir: string, opts: { node: string; key?: string; wasm?: string }) => {
    const projectPath = resolve(process.cwd(), dir);
    const wasmPath = opts.wasm
      ? resolve(process.cwd(), opts.wasm)
      : resolve(projectPath, 'build', 'contract.wasm');

    if (!existsSync(wasmPath)) {
      console.error(`WASM 文件不存在: ${wasmPath}`);
      console.error(`请先运行 nexus compile ${dir}`);
      process.exit(1);
    }

    const deployer = new ContractDeployer(opts.node, opts.key);
    console.log(`正在部署合约到 ${opts.node}...`);

    const result = await deployer.deploy(wasmPath);
    if (result.success) {
      console.log(`部署成功!`);
      console.log(`  合约地址: ${result.contractAddress}`);
      console.log(`  交易哈希: ${result.txHash}`);
    } else {
      console.error(`部署失败: ${result.error?.message ?? '未知错误'}`);
      process.exit(1);
    }
  });

// ---- test 命令：运行测试 ----
program
  .command('test')
  .description('运行合约测试')
  .argument('<dir>', '合约项目目录')
  .option('-f, --file <pattern>', '测试文件 glob 模式', '**/*.test.ts')
  .action(async (dir: string, opts: { file: string }) => {
    const projectPath = resolve(process.cwd(), dir);
    const tester = new ContractTester(projectPath);

    console.log(`正在运行测试 (${opts.file})...`);
    const results = await tester.runTests(opts.file);

    console.log(`\n测试结果:`);
    console.log(`  通过: ${results.passed}`);
    console.log(`  失败: ${results.failed}`);
    console.log(`  跳过: ${results.skipped}`);

    if (results.failed > 0) {
      process.exit(1);
    }
  });

// ---- 辅助函数 ----

/**
 * 检测合约语言
 * 根据项目目录中存在的配置文件判断
 */
function detectLanguage(projectPath: string): string | undefined {
  if (existsSync(resolve(projectPath, 'Cargo.toml'))) return 'rust';
  if (existsSync(resolve(projectPath, 'package.json'))) {
    return 'assemblyscript';
  }
  return undefined;
}

// ---- 启动 CLI ----

program.parse(process.argv);
