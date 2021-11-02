package org.graalvm.compiler.lir.amd64.vec.GotoKernel;

import static jdk.vm.ci.amd64.AMD64.k0;
import static jdk.vm.ci.amd64.AMD64.k1;
import static jdk.vm.ci.amd64.AMD64.k2;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.asStackSlot;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.STACK;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.UNINITIALIZED;

import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.PrintWriter;

import org.graalvm.compiler.asm.Label;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Address.Scale;
import org.graalvm.compiler.asm.amd64.AMD64Assembler;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.asm.amd64.AVXKind.AVXSize;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.amd64.AMD64LIRInstruction;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;

import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;

import static jdk.vm.ci.amd64.AMD64.rsp;
import static jdk.vm.ci.amd64.AMD64.r15;
import static jdk.vm.ci.amd64.AMD64.rsi;
import static jdk.vm.ci.amd64.AMD64.cpuRegisters;
import static jdk.vm.ci.amd64.AMD64.xmmRegistersAVX512;

import org.graalvm.compiler.lir.amd64.vec.util.ChangeableString;
import org.graalvm.compiler.lir.amd64.vec.GotoOpCode;
import org.graalvm.compiler.lir.amd64.vec.dag.ExprDag;
import org.graalvm.compiler.lir.amd64.vec.GotoKernel.GotoKernelOp;

public abstract class GotoKernel {
    protected int opLength = 5;

    protected final int DOUBLE_ARRAY_BASE_OFFSET;
    protected final Scale DOUBLE_ARRAY_INDEX_SCALE;

    protected final int OBJECT_ARRAY_BASE_OFFSET;
    protected final Scale OBJECT_ARRAY_INDEX_SCALE;

    protected final String opStringRaw;
    protected final double[] constArgs;
    protected final int[] varArgProperties;

    protected int stackOffsetToConstArgs;
    protected int constArgsStackSize;
    protected int aTempArrayAddressNumLimit;
    protected final int constArgStackSlotSize;

    protected final int kernelType;
    protected final int mLength, kLength, nLength;
    protected final int initialALength;
    protected final int initialBLength;
    protected final int remainingRegisterNum;

    Register arrsPtr, kPanelSize, iPos, kPos, jPos, loopIndex, tempArrayAddressReg;

    protected int kPanelSizeIndexFromBehind;
    Register useAsAddressRegs[];
    Register[] tempRegs;
    int[] tempRegNums;
    Map<Integer, Integer> variableArgsStackOffsets;
    protected int varArgsStackSize;
    protected ExprDag exprDag;

    protected GotoKernelOp kernelOp;

    public PrintWriter debugLog;

    public GotoKernel(LIRGeneratorTool tool, int kernelType, int aLength, int bLength, int mLength, int kLength, int nLength,
                        long[] calc, double[] constArgs, int[] varArgProperties, GotoKernelOp kernelOp) {
        DOUBLE_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Double);
        DOUBLE_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Double)));

        OBJECT_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Object);
        OBJECT_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Object)));

        String opStringBuild = "";
        for(int index = 0; index < calc.length; index++) {
            opStringBuild += Long.toBinaryString(calc[index]).substring(1, Long.toBinaryString(calc[index]).length());
        }
        this.opStringRaw = opStringBuild;

        this.constArgs = constArgs;
        this.varArgProperties = varArgProperties;

        this.kernelType = kernelType;
        this.mLength = mLength;
        this.kLength = kLength;
        this.nLength = nLength;
        this.initialALength = aLength;
        this.initialBLength = bLength/8;

        constArgStackSlotSize = 32;  // Causes an error if value if 8 (do not know reason why)

        remainingRegisterNum = 7;

        variableArgsStackOffsets = new HashMap<Integer, Integer>();
        varArgsStackSize = 0;

        this.kernelOp = kernelOp;
    }

    protected Boolean registerEquals(Register a, Register b) {
        return a.name.equals(b.name);
    }

    protected Register findRegister(Register toFind, Register[] registerArray) {
        for(int i = 0; i < registerArray.length; i++) {
            if(registerEquals(toFind, registerArray[i])) {
                return registerArray[i];
            }
        }
        return null;
    }

    public void pushArguments(AMD64MacroAssembler masm) {
        Register tempReg = xmmRegistersAVX512[31];

        // Push Variable arguments in reverse order
        masm.movl(loopIndex, 0);
        for(int i = varArgProperties.length-1; i>=0; i--) {
            if(varArgProperties[i] == 2) {   // index is j
                for(int varArgIndex : variableArgsStackOffsets.keySet()) {
                    variableArgsStackOffsets.put(varArgIndex, variableArgsStackOffsets.get(varArgIndex) + 128);
                }
                variableArgsStackOffsets.put(i, 0);
                masm.movq(tempArrayAddressReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*i));
                masm.vmovupd(tempReg, new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+64));
                masm.subq(rsp, 64);
                masm.vmovupd(new AMD64Address(rsp), tempReg);
                varArgsStackSize += 64;

                masm.vmovupd(tempReg, new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET));
                masm.subq(rsp, 64);
                masm.vmovupd(new AMD64Address(rsp), tempReg);
                varArgsStackSize += 64;
            }
            else if(varArgProperties[i] == 1) {
                // Todo: push 12 broadcasts to stack
                variableArgsStackOffsets.put(i, varArgsStackSize);
                masm.movq(tempArrayAddressReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*i));
                masm.vbroadcastsd(tempReg, new AMD64Address(tempArrayAddressReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET));
                masm.subq(rsp, 64);
                masm.vmovupd(new AMD64Address(rsp), tempReg);
                varArgsStackSize += 64;
            }
        }

        Register tempGenReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-1]);

        // Push Constant arguments in reverse order
        constArgsStackSize = 0;
        for(int i = constArgs.length-1; i >=0; i--) {
            masm.movq(tempGenReg, Double.doubleToLongBits(constArgs[i]));
            masm.subq(rsp, constArgStackSlotSize);
            masm.movq(new AMD64Address(rsp), tempGenReg);
            constArgsStackSize += constArgStackSlotSize;
        }
    }

    protected abstract void emitKernelCode(AMD64MacroAssembler masm, int aLength, int bLength);

    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        // Make sure to not use debugLog for testing, it increases compile time
        try{
            debugLog = new PrintWriter("/home/junyoung2/project/adaptive-code-generation/log.txt", "UTF-8");
        } catch (Exception e) {
            System.out.println(e);
        }

        exprDag = new ExprDag(new ChangeableString(opStringRaw), debugLog);
        //ExprDag.printDAG(debugLog, exprDag.getRootNode());
        /*
        if(debugLog != null) {
            debugLog.close();
            return;
        }
        */

        arrsPtr = asRegister(kernelOp.arrsValue);
        kPanelSize = asRegister(kernelOp.kPanelSizeValue);
        iPos = asRegister(kernelOp.iValue);
        kPos = asRegister(kernelOp.kValue);
        jPos = asRegister(kernelOp.jValue);
        loopIndex = asRegister(kernelOp.loopIndexValue);
        tempArrayAddressReg = asRegister(kernelOp.tempArrayAddressRegValue);

        // Make sure that iPos is first!
        useAsAddressRegs = new Register[]{iPos, arrsPtr, kPos, r15, kPanelSize};
        kPanelSizeIndexFromBehind = 0;
        for(int i = 0; i < useAsAddressRegs.length; i++) {
            if(useAsAddressRegs[i] == kPanelSize) {
                kPanelSizeIndexFromBehind = i;
            }
        }
        kPanelSizeIndexFromBehind = useAsAddressRegs.length - kPanelSizeIndexFromBehind - 1;

        masm.push(kPanelSize);

        // Check if kPanelSize overflows bounds.
        masm.addq(kPanelSize, kPos);
        masm.cmpl(kPanelSize, kLength);
        Label kPanelSizeCheckLabel = new Label();
        masm.jcc(AMD64MacroAssembler.ConditionFlag.LessEqual, kPanelSizeCheckLabel);
        masm.movq(kPanelSize, kLength);
        masm.bind(kPanelSizeCheckLabel);
        masm.subq(kPanelSize, kPos);

        // Push arguments in reverse order
        pushArguments(masm);

        masm.movq(tempArrayAddressReg, iPos);
        masm.addq(tempArrayAddressReg, initialALength);

        Label endLabel = new Label();
        Label loopLabel = new Label();
        masm.cmpl(tempArrayAddressReg, mLength);
        masm.jcc(AMD64Assembler.ConditionFlag.Greater, loopLabel);
        emitKernelCode(masm, initialALength, initialBLength);
        //emitAtBKernelCode(masm, initialALength, initialBLength);
        masm.jmp(endLabel);

        masm.bind(loopLabel);

        int tempALength = initialALength - 1;

        while(tempALength > 0) {
            masm.movq(tempArrayAddressReg, iPos);
            masm.addq(tempArrayAddressReg, tempALength);

            Label loopLabel2 = new Label();
            // Check if iPos + tempALength > mLength
            masm.cmpl(tempArrayAddressReg, mLength);
            masm.jcc(AMD64Assembler.ConditionFlag.Greater, loopLabel2);
            if(kernelType != 1) {
                emitKernelCode(masm, tempALength, initialBLength);
            }
            masm.jmp(endLabel);
            masm.bind(loopLabel2);

            tempALength -= 1;
        }

        masm.bind(endLabel);

        // Pop arguments + B
        masm.addq(rsp, constArgsStackSize + varArgsStackSize);

        // Restore original value of kPanelSize
        masm.pop(kPanelSize);

        debugLog.close();
    }
}
