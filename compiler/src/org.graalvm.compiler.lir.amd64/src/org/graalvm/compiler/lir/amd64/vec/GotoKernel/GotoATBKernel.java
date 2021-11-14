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
import org.graalvm.compiler.lir.amd64.vec.GotoKernel.GotoKernel;

public final class GotoATBKernel extends GotoKernel {
    private Boolean interleave;
    public GotoATBKernel(LIRGeneratorTool tool, int kernelType, int aLength, int bLength, int mLength, int kLength, int nLength,
                        long[] calc, double[] constArgs, int[] varArgProperties, GotoKernelOp kernelOp) {
        super(tool, kernelType, aLength, bLength, mLength, kLength, nLength, calc, constArgs, varArgProperties, kernelOp);
        interleave = true;
    }

    public void subIter(int aLength, int bLength, int offset, int prefetchDistance, AMD64MacroAssembler masm, Map<String, Integer> simdRegisters, Register aPtr, Register bPtr) {
        AMD64Address aAddress, bAddress;
        if(prefetchDistance > 0) {
            bAddress = new AMD64Address(bPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(offset*8)+(prefetchDistance*8));
            masm.movq(tempArrayAddressReg, bAddress);
            for(int j = 0; j < bLength; j++) {
                bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.prefetcht0(bAddress);
            }

            aAddress = new AMD64Address(aPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(offset*8)+(prefetchDistance*8));
            masm.movq(tempArrayAddressReg, aAddress);
            for(int j = 0; j < (int) Math.ceil((double) aLength/8); j++) {
                aAddress = new AMD64Address(tempArrayAddressReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.prefetcht0(aAddress);
            }
        }

        bAddress = new AMD64Address(bPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(offset*8));
        masm.movq(tempArrayAddressReg, bAddress);
        if(interleave) {
            for(int j = 0; j < bLength*2; j++) {
                bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+((j/2)*64)+((j%2)*8));
                masm.vmovddup(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], bAddress);
            }
        }
        else {
            for(int j = 0; j < bLength; j++) {
                bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], bAddress);
            }
        }

        aAddress = new AMD64Address(aPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(offset*8));
        masm.movq(tempArrayAddressReg, aAddress);

        if(interleave) {
            for(int i = 0; i < aLength; i+=2) {
                aAddress = new AMD64Address(tempArrayAddressReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(i*8));
                masm.vbroadcastf32x4(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);
                for(int j = 0; j < bLength*2; j++) {
                    Register aRegister = xmmRegistersAVX512[simdRegisters.get("A")];
                    Register bRegister = xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))];
                    //debugLog.println("C" + String.valueOf(i+(j%2)) + String.valueOf(j/2));
                    Register cRegister = xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i+(j%2)) + String.valueOf(j/2))];
                    masm.vfmadd231pd(cRegister, aRegister, bRegister);
                }
            }
        }
        else {
            Register tempGenReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-1]);
            for(int i = 0; i < aLength; i++) {
                aAddress = new AMD64Address(tempArrayAddressReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(i*8));
                //masm.vbroadcastsd(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);
                masm.movq(tempGenReg, aAddress);
                masm.vpbroadcastq(xmmRegistersAVX512[simdRegisters.get("A")], tempGenReg);
                for(int j = 0; j < bLength; j++) {
                    masm.vfmadd231pd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                }
            }
        }
    }

    protected void emitKernelCode(AMD64MacroAssembler masm, int aLength, int bLength) {
        // Declare SIMD registers
        if(aLength % 2 == 1) {
            interleave = false;
        }
        int registerIndex = 0;
        HashMap<String, Integer> simdRegisters= new HashMap<String, Integer>();
        for(int i = 0; i < aLength; i++) {
            for(int j = 0; j < bLength; j++) {
                simdRegisters.put("C" + String.valueOf(i) + String.valueOf(j), registerIndex++);
            }
        }

        simdRegisters.put("A", registerIndex++);
        if(interleave) {
            for(int i = 0; i < bLength*2; i++) {
                simdRegisters.put("B" + String.valueOf(i), registerIndex++);
            }
        }
        else {
            for(int i = 0; i < bLength; i++) {
                simdRegisters.put("B" + String.valueOf(i), registerIndex++);
            }
        }

        tempRegNums = new int[xmmRegistersAVX512.length - registerIndex];
        for(int i = 0; i < tempRegNums.length; i++) {
            tempRegNums[i] = registerIndex++;
        }

        Register tempGenReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-1]);

        AMD64Address resultAddress, aAddress, bAddress;

        // Set subresult registers to zero
        masm.vpxorq(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(0) + String.valueOf(0))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(0) + String.valueOf(0))],
                    xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(0) + String.valueOf(0))]);
        for(int i = 0; i < aLength; i++) {
            for(int j = 0; j < bLength; j++) {
                if(i != 0 || j != 0) {
                    masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(0) + String.valueOf(0))]);
                }
            }
        }

        masm.movq(loopIndex, 0);

        Register aPtr = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-2]);
        masm.movq(aPtr, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
        Register bPtr = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-3]);
        masm.movq(bPtr, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+8));

        int prefetchDistance = 8;
        int unrollFactor = 16;
        if(interleave) {
            prefetchDistance = 8;
            unrollFactor = 16;
        }

        masm.movq(loopIndex, kPos);
        masm.addq(kPanelSize, kPos);
        masm.subq(kPanelSize, Math.max(prefetchDistance, unrollFactor));

        Label loopLabel = new Label();

        masm.bind(loopLabel);

        for(int i = 0; i < unrollFactor; i++) {
            subIter(aLength, bLength, i, prefetchDistance, masm, simdRegisters, aPtr, bPtr);
        }
        masm.addq(loopIndex, unrollFactor);
        masm.cmpq(loopIndex, kPanelSize);
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        masm.addq(kPanelSize, Math.max(prefetchDistance, unrollFactor));

        loopLabel = new Label();
        masm.bind(loopLabel);
        subIter(aLength, bLength, 0, 0, masm, simdRegisters, aPtr, bPtr);
        masm.addq(loopIndex, 1);
        masm.cmpq(loopIndex, kPanelSize);
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        // Store partial results in result array
        masm.movq(loopIndex, 0);
        masm.movq(tempGenReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+16));
        if(interleave) {
            Register resultPtr0 = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-2]);
            Register resultPtr1 = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-3]);
            for(int i = 0; i < aLength; i+=2) {
                masm.movq(resultPtr0, new AMD64Address(tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8)));
                masm.movq(resultPtr1, new AMD64Address(tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+((i+1)*8)));
                for(int j = 0; j < bLength*2; j++) {
                    if(j % 2 == 0) {
                        masm.vunpcklpd(xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j/2))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i+1) + String.valueOf(j/2))]);
                        resultAddress = new AMD64Address(resultPtr0, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+((j/2)*64));
                    }
                    else {
                        masm.vunpckhpd(xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j/2))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i+1) + String.valueOf(j/2))]);
                        resultAddress = new AMD64Address(resultPtr1, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+((j/2)*64));
                    }
                    masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("A")], resultAddress);
                    masm.vmovupd(resultAddress, xmmRegistersAVX512[simdRegisters.get("A")]);
                }
            }
        }
        else {
            for(int i = 0; i < aLength; i++) {
                resultAddress = new AMD64Address(tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
                masm.movq(tempArrayAddressReg, resultAddress);
                for(int j = 0; j < bLength; j++) {
                    resultAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                    masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], resultAddress);
                    masm.vmovupd(resultAddress, xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))]);
                }
            }
        }
    }
}
