package org.nexus.core.contract;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal embedded EVM interpreter (subset).
 *
 * <p>Executes a stack-based EVM bytecode subset sufficient for simple
 * storage / arithmetic contracts. This is deliberately self-contained with
 * no external EVM dependency. Supported opcodes:</p>
 *
 * <ul>
 *   <li>Arithmetic: ADD, SUB, MUL, DIV, MOD, EXP</li>
 *   <li>Comparison: LT, GT, EQ, ISZERO</li>
 *   <li>Bitwise: AND, OR, XOR, NOT, SHL, SHR</li>
 *   <li>Stack: POP, DUP1, SWAP1, PUSH1..PUSH32</li>
 *   <li>Memory: MLOAD, MSTORE, MSTORE8</li>
 *   <li>Storage: SLOAD, SSTORE</li>
 *   <li>Control: JUMP, JUMPI, JUMPDEST, STOP, RETURN, REVERT</li>
 *   <li>Env: ADDRESS, CALLER, TIMESTAMP, CHAINID</li>
 * </ul>
 *
 * <p>All values are unsigned 256-bit words ({@link BigInteger}).
 * Gas is charged per opcode via a supplied {@link GasMeter}.</p>
 *
 * @since 1.2
 */
public class EvmInterpreter {

    /** Word size in bits (EVM = 256). */
    private static final int WORD_BITS = 256;
    private static final BigInteger WORD_MOD = BigInteger.ONE.shiftLeft(WORD_BITS);
    private static final BigInteger MAX_STACK = BigInteger.valueOf(1024);

    private final byte[] code;
    private final GasMeter gasMeter;
    private final Map<BigInteger, byte[]> storage;
    private final byte[] memory = new byte[1024 * 64]; // 64 KiB scratch memory

    private final Deque<BigInteger> stack = new ArrayDeque<>();
    private BigInteger returnValue = BigInteger.ZERO;
    private boolean reverted = false;
    private long gasUsed = 0;

    /** Env context exposed to opcodes. */
    private String contractAddress = "";
    private String callerAddress = "";
    private long timestamp = 0L;
    private int chainId = 0;

    public EvmInterpreter(byte[] code, GasMeter gasMeter, Map<BigInteger, byte[]> storage) {
        this.code = code == null ? new byte[0] : code;
        this.gasMeter = gasMeter != null ? gasMeter : new GasMeter();
        this.storage = storage != null ? storage : new HashMap<>();
    }

    public void setEnv(String contractAddress, String callerAddress, long timestamp, int chainId) {
        this.contractAddress = contractAddress == null ? "" : contractAddress;
        this.callerAddress = callerAddress == null ? "" : callerAddress;
        this.timestamp = timestamp;
        this.chainId = chainId;
    }

    /**
     * Push invocation arguments onto the stack in reverse order so the first
     * argument ends up on top (EVM stack convention).
     *
     * @param args argument values; Number / Boolean / numeric String accepted
     */
    public void pushArgs(java.util.List<Object> args) {
        if (args == null || args.isEmpty()) {
            return;
        }
        for (int i = args.size() - 1; i >= 0; i--) {
            push(toWordValue(args.get(i)));
        }
    }

    private BigInteger toWordValue(Object arg) {
        if (arg == null) {
            return BigInteger.ZERO;
        }
        if (arg instanceof BigInteger) {
            return (BigInteger) arg;
        }
        if (arg instanceof Number) {
            if (arg instanceof java.math.BigDecimal) {
                return ((java.math.BigDecimal) arg).toBigIntegerExact();
            }
            return BigInteger.valueOf(((Number) arg).longValue());
        }
        if (arg instanceof Boolean) {
            return ((Boolean) arg) ? BigInteger.ONE : BigInteger.ZERO;
        }
        String s = arg.toString().trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return new BigInteger(s.substring(2), 16);
        }
        return new BigInteger(s);
    }

    /** Run from PC 0 until STOP / RETURN / REVERT / end-of-code. */
    public void run() {
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc] & 0xff;
            charge(op);
            pc = step(op, pc);
            if (pc < 0) {
                break; // STOP / RETURN / REVERT
            }
        }
    }

    private void charge(int op) {
        // Base instruction cost; storage ops cost more (mirrors GasMeter pricing).
        if (op == 0x54) { // SLOAD
            gasMeter.consumeStorageRead();
        } else if (op == 0x55) { // SSTORE
            gasMeter.consumeStorageWrite();
        } else {
            gasMeter.consumeInstruction(1);
        }
        gasUsed = gasMeter.getGasUsed();
    }

    /** Execute one opcode, return next PC (or -1 to halt). */
    private int step(int op, int pc) {
        switch (op) {
            case 0x00: // STOP
                return -1;

            case 0x01: push(pop().add(pop())); return pc + 1;
            case 0x02: push(pop().multiply(pop())); return pc + 1;
            case 0x03: { BigInteger a = pop(); BigInteger b = pop(); push(a.subtract(b)); return pc + 1; }
            case 0x04: { BigInteger a = pop(); BigInteger b = pop(); push(b.signum() == 0 ? BigInteger.ZERO : a.divide(b)); return pc + 1; }
            case 0x06: { BigInteger a = pop(); BigInteger b = pop(); push(b.signum() == 0 ? BigInteger.ZERO : a.mod(b)); return pc + 1; }
            case 0x0a: { BigInteger b = pop(); BigInteger e = pop(); push(b.modPow(e, WORD_MOD)); return pc + 1; }

            case 0x10: { BigInteger a = pop(); BigInteger b = pop(); push(a.compareTo(b) < 0 ? BigInteger.ONE : BigInteger.ZERO); return pc + 1; }
            case 0x11: { BigInteger a = pop(); BigInteger b = pop(); push(a.compareTo(b) > 0 ? BigInteger.ONE : BigInteger.ZERO); return pc + 1; }
            case 0x14: { BigInteger a = pop(); BigInteger b = pop(); push(a.equals(b) ? BigInteger.ONE : BigInteger.ZERO); return pc + 1; }
            case 0x15: { BigInteger a = pop(); push(a.signum() == 0 ? BigInteger.ONE : BigInteger.ZERO); return pc + 1; }

            case 0x16: push(pop().and(pop())); return pc + 1;
            case 0x17: push(pop().or(pop())); return pc + 1;
            case 0x18: push(pop().xor(pop())); return pc + 1;
            case 0x19: push(pop().not().and(WORD_MOD.subtract(BigInteger.ONE))); return pc + 1;
            case 0x1b: { BigInteger shift = pop(); BigInteger v = pop(); push(v.shiftLeft(shift.intValue()).mod(WORD_MOD)); return pc + 1; }
            case 0x1c: { BigInteger shift = pop(); BigInteger v = pop(); push(v.shiftRight(shift.intValue())); return pc + 1; }

            case 0x30: push(addrToWord(contractAddress)); return pc + 1;
            case 0x33: push(addrToWord(callerAddress)); return pc + 1;
            case 0x42: push(BigInteger.valueOf(timestamp)); return pc + 1;
            case 0x46: push(BigInteger.valueOf(chainId)); return pc + 1;

            case 0x50: pop(); return pc + 1; // POP

            case 0x51: { BigInteger off = pop(); push(memLoad(off)); return pc + 1; } // MLOAD
            case 0x52: { BigInteger off = pop(); BigInteger val = pop(); memStore(off, val); return pc + 1; } // MSTORE
            case 0x53: { BigInteger off = pop(); BigInteger val = pop(); memStore8(off, val); return pc + 1; } // MSTORE8

            case 0x54: { BigInteger slot = pop(); push(storageLoad(slot)); return pc + 1; } // SLOAD
            case 0x55: { BigInteger slot = pop(); BigInteger val = pop(); storageStore(slot, val); return pc + 1; } // SSTORE

            case 0x56: { BigInteger dest = pop(); return dest.intValue(); } // JUMP
            case 0x57: { BigInteger dest = pop(); BigInteger cond = pop(); return cond.signum() != 0 ? dest.intValue() : pc + 1; } // JUMPI
            case 0x5b: return pc + 1; // JUMPDEST

            case 0x5f: push(BigInteger.ZERO); return pc + 1; // PUSH0

            case 0x60: case 0x61: case 0x62: case 0x63: case 0x64: case 0x65: case 0x66: case 0x67:
            case 0x68: case 0x69: case 0x6a: case 0x6b: case 0x6c: case 0x6d: case 0x6e: case 0x6f:
            case 0x70: case 0x71: case 0x72: case 0x73: case 0x74: case 0x75: case 0x76: case 0x77:
            case 0x78: case 0x79: case 0x7a: case 0x7b: case 0x7c: case 0x7d: case 0x7e: case 0x7f: {
                int n = op - 0x5f; // PUSH1..PUSH32
                BigInteger v = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    int b = (pc + 1 + i) < code.length ? (code[pc + 1 + i] & 0xff) : 0;
                    v = v.shiftLeft(8).or(BigInteger.valueOf(b));
                }
                push(v);
                return pc + 1 + n;
            }

            case 0x80: { // DUP1
                BigInteger top = pop(); push(top); push(top); return pc + 1;
            }
            case 0x90: { // SWAP1
                BigInteger a = pop(); BigInteger b = pop(); push(a); push(b); return pc + 1;
            }

            case 0xf3: { // RETURN — top of stack is the return value
                if (!stack.isEmpty()) returnValue = stack.pop();
                return -1;
            }
            case 0xfd: { // REVERT
                reverted = true;
                if (!stack.isEmpty()) returnValue = stack.pop();
                return -1;
            }

            default:
                throw new WasmExecutionException(String.format("unsupported EVM opcode 0x%02x at pc=%d", op, pc));
        }
    }

    private void push(BigInteger v) {
        if (stack.size() >= 1024) {
            throw new WasmExecutionException("EVM stack overflow");
        }
        stack.push(v.and(WORD_MOD.subtract(BigInteger.ONE)));
    }

    private BigInteger pop() {
        if (stack.isEmpty()) {
            throw new WasmExecutionException("EVM stack underflow");
        }
        return stack.pop();
    }

    private BigInteger memLoad(BigInteger off) {
        int o = off.intValue();
        if (o < 0 || o + 32 > memory.length) return BigInteger.ZERO;
        byte[] word = new byte[32];
        System.arraycopy(memory, o, word, 0, 32);
        return new BigInteger(1, word);
    }

    private void memStore(BigInteger off, BigInteger val) {
        int o = off.intValue();
        if (o < 0 || o + 32 > memory.length) return;
        byte[] word = toWord(val);
        System.arraycopy(word, 0, memory, o, 32);
    }

    private void memStore8(BigInteger off, BigInteger val) {
        int o = off.intValue();
        if (o < 0 || o >= memory.length) return;
        memory[o] = val.and(BigInteger.valueOf(0xff)).byteValue();
    }

    private BigInteger storageLoad(BigInteger slot) {
        byte[] word = storage.get(slot);
        return word == null ? BigInteger.ZERO : new BigInteger(1, word);
    }

    private void storageStore(BigInteger slot, BigInteger val) {
        if (val.signum() == 0) {
            storage.remove(slot);
        } else {
            storage.put(slot, toWord(val));
        }
    }

    private byte[] toWord(BigInteger v) {
        byte[] raw = v.mod(WORD_MOD).toByteArray();
        byte[] word = new byte[32];
        if (raw.length <= 32) {
            System.arraycopy(raw, 0, word, 32 - raw.length, raw.length);
        } else {
            System.arraycopy(raw, raw.length - 32, word, 0, 32);
        }
        return word;
    }

    private BigInteger addrToWord(String addr) {
        if (addr == null || addr.isEmpty()) return BigInteger.ZERO;
        String clean = addr.startsWith("0x") || addr.startsWith("0X") ? addr.substring(2) : addr;
        try {
            return new BigInteger(clean, 16);
        } catch (NumberFormatException e) {
            return BigInteger.ZERO;
        }
    }

    public BigInteger getReturnValue() { return returnValue; }
    public boolean isReverted() { return reverted; }
    public long getGasUsed() { return gasUsed; }
    public Map<BigInteger, byte[]> getStorage() { return storage; }
}
