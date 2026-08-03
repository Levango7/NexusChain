/**
 * NexusChain DevTools — WASM 编译器接口
 *
 * 定义所有语言编译器（Rust / AssemblyScript / C）需实现的统一接口。
 * 各具体编译器实现此接口，将不同语言的源码编译为 WASM 字节码。
 */

/** 编译输入参数 */
export interface CompileInput {
  /** 合约项目根目录 */
  projectPath: string;
  /** 编译产物输出目录 */
  outputPath: string;
  /** 额外编译选项 */
  extraArgs?: string[];
}

/** 编译结果 */
export interface CompileResult {
  /** 是否编译成功 */
  success: boolean;
  /** 生成的 WASM 文件路径 */
  wasmPath?: string;
  /** WASM 文件大小（字节） */
  wasmSize?: number;
  /** 编译器标准输出 */
  stdout?: string;
  /** 编译器标准错误 */
  stderr?: string;
  /** 错误信息（若失败） */
  error?: Error;
}

/**
 * WasmCompiler — WASM 编译器统一接口
 *
 * 所有语言编译器（RustCompiler / AssemblyScriptCompiler）均实现此接口。
 */
export interface WasmCompiler {
  /**
   * 编译合约源码为 WASM
   * @param projectPath 合约项目根目录
   * @param outputPath 编译产物输出目录
   * @returns 编译结果
   */
  compile(projectPath: string, outputPath: string): Promise<CompileResult>;

  /**
   * 检查编译工具链是否已安装
   * @returns 是否可用
   */
  checkToolchain(): Promise<boolean>;
}
