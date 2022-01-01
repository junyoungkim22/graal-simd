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

public final class GotoABKernel extends GotoKernel {
    public Boolean transposeResult;
    private Register[] aTempArrayAddressRegs;

    public GotoABKernel(LIRGeneratorTool tool, int kernelType, int aLength, int bLength, int mLength, int kLength, int nLength,
                        long[] calc, double[] constArgs, int[] varArgProperties, GotoKernelOp kernelOp, Boolean transpose) {
        super(tool, kernelType, aLength, bLength, mLength, kLength, nLength, calc, constArgs, varArgProperties, kernelOp);
        this.transposeResult = transpose;
    }

    public void subIter(int aLength, int bLength, int offset, int prefetchDistance, AMD64MacroAssembler masm, Map<String, Integer> simdRegisters) {
        AMD64Address aAddress, bAddress;

        if(prefetchDistance > 0) {
            bAddress = new AMD64Address(loopIndex, OBJECT_ARRAY_BASE_OFFSET+(offset*8)+(prefetchDistance*8));
            masm.movq(tempArrayAddressReg, bAddress);

            for(int j = 0; j < bLength; j++) {
                bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.prefetcht0(bAddress);
            }
        }

        if(!toLoad.contains(GotoOpCode.B)) {
            bAddress = new AMD64Address(loopIndex, OBJECT_ARRAY_BASE_OFFSET+(offset*8));
            masm.movq(tempArrayAddressReg, bAddress);

            for(int j = 0; j < bLength; j++) {
                bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], bAddress);
            }
        }

        if(!toLoad.contains(GotoOpCode.A)) {
            availableValues.put(GotoOpCode.A, simdRegisters.get("A"));
        }

        for(int i = 0; i < aLength; i++) {
            if(!toLoad.contains(GotoOpCode.A)) {
                loadA(masm, i, offset, simdRegisters.get("A"));
            }
            for(int k = 0; k < varArgProperties.length; k++) {
                if(varArgProperties[k] == 1) {
                    loadVarArg(masm, k, i, -1, simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                    availableValues.put(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k), simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                }
            }
            for(int j = 0; j < bLength; j++) {
                if(!toLoad.contains(GotoOpCode.B)) {
                    availableValues.put(GotoOpCode.B, simdRegisters.get("B" + String.valueOf(j)));
                }
                availableValues.put(GotoOpCode.C, simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j)));
                for(int k = 0; k < varArgProperties.length; k++) {
                    if(!toLoad.contains(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k))) {
                        if(varArgProperties[k] == 2) {
                            availableValues.put(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k), simdRegisters.get("VARIABLEARG" + String.valueOf(k) + "_" + String.valueOf(j)));
                        }
                        else if(varArgProperties[k] == 3) {
                            availableValues.put(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k), simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                            loadVarArg(masm, k, i, j, simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                        }
                    }
                }
                emitSubiterCode(masm, i, j, offset);
            }
        }
    }

    protected void loadA(AMD64MacroAssembler masm, int iIndex, int offset, int dstRegNum) {
        AMD64Address aAddress;
        if(iIndex < aTempArrayAddressNumLimit) {
            aAddress = new AMD64Address(aTempArrayAddressRegs[iIndex], loopIndex, AMD64Address.Scale.Times1, DOUBLE_ARRAY_BASE_OFFSET+(offset*8));
        }
        else {
            // Todo: read from stack
            masm.movq(tempArrayAddressReg, new AMD64Address(rsp, 8*(iIndex-aTempArrayAddressNumLimit)));
            aAddress = new AMD64Address(tempArrayAddressReg, loopIndex, AMD64Address.Scale.Times1, DOUBLE_ARRAY_BASE_OFFSET+(offset*8));
        }
        masm.vbroadcastsd(xmmRegistersAVX512[dstRegNum], aAddress);
    }

    protected void loadB(AMD64MacroAssembler masm, int jIndex, int offset, int dstRegNum) {
        AMD64Address bAddress = new AMD64Address(loopIndex, OBJECT_ARRAY_BASE_OFFSET+(offset*8));
        masm.movq(tempArrayAddressReg, bAddress);

        bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(jIndex*64));
        masm.vmovupd(xmmRegistersAVX512[dstRegNum], bAddress);
    }

    protected void loadVarArg(AMD64MacroAssembler masm, int argIndex, int iIndex, int jIndex, int dstRegNum) {
        if(varArgProperties[argIndex] == 2) {
            int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);
            masm.vmovupd(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset+64*jIndex));
        }
        else if(varArgProperties[argIndex] == 1) {
            int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);

            masm.leaq(tempArrayAddressReg, new AMD64Address(rsp, varArgOffset+8*iIndex));
            masm.vbroadcastsd(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempArrayAddressReg));

            // Todo: optimize into the below code! (vbroadcastsd does not work for some addresses)
            //masm.vbroadcastsd(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset+8*iIndex));
        }
        else if(varArgProperties[argIndex] == 3) {
            int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);
            masm.vmovupd(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset+(128*iIndex)+64*jIndex));
        }
    }

    protected void emitKernelCode(AMD64MacroAssembler masm, int aLength, int bLength) {
        aTempArrayAddressNumLimit = aLength < remainingRegisterNum+useAsAddressRegs.length ? aLength : remainingRegisterNum+useAsAddressRegs.length;
        aTempArrayAddressRegs = new Register[aTempArrayAddressNumLimit];
        for(int i = 0; i < aTempArrayAddressNumLimit; i++) {
            if(i < useAsAddressRegs.length) {
                aTempArrayAddressRegs[i] = findRegister(useAsAddressRegs[i], cpuRegisters);
            }
            else {
                aTempArrayAddressRegs[i] = asRegister(kernelOp.remainingRegValues[i-useAsAddressRegs.length]);
            }
        }

        // Declare SIMD registers
        int registerIndex = 0;
        HashMap<String, Integer> simdRegisters= new HashMap<String, Integer>();

        if(!toLoad.contains(GotoOpCode.A)) {
            simdRegisters.put("A", registerIndex++);
        }
        if(!toLoad.contains(GotoOpCode.B)) {
            for(int i = 0; i < bLength; i++) {
                simdRegisters.put("B" + String.valueOf(i), registerIndex++);
            }
        }
        for(int i = 0; i < aLength; i++) {
            for(int j = 0; j < bLength; j++) {
                simdRegisters.put("C" + String.valueOf(i) + String.valueOf(j), registerIndex++);
            }
        }
        for(int i = 0; i < constArgs.length; i++) {
            if(!toLoad.contains(GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i))) {
                availableValues.put(GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i), registerIndex++);
            }
        }
        for(int i = 0; i < varArgProperties.length; i++) {
            if(!toLoad.contains(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(i))) {
                if(varArgProperties[i] == 2) {
                    for(int j = 0; j < bLength; j++) {
                        simdRegisters.put("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j), registerIndex++);
                    }
                }
                else if(varArgProperties[i] == 1) {
                    simdRegisters.put("VARIABLEARG" + String.valueOf(i), registerIndex++);
                }
                else if(varArgProperties[i] == 3) {
                    simdRegisters.put("VARIABLEARG" + String.valueOf(i), registerIndex++);
                }
            }
        }
        int remainingSimdRegisterNum = xmmRegistersAVX512.length - registerIndex;
        for(int i = 0; i < remainingSimdRegisterNum; i++) {
            availableValues.put(GotoOpCode.REG + GotoOpCode.toOpLengthBinaryString(i), registerIndex++);
        }

        AMD64Address resultAddress, aAddress, bAddress;

        Register tempGenReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-1]);

        // Set subresult regs to zero
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

        // Store &B[0] in tempArrPtr
        masm.movq(tempGenReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+8));

        // Store (&B[0] + k*8) in loop index
        masm.leaq(loopIndex, new AMD64Address(tempGenReg, kPos, OBJECT_ARRAY_INDEX_SCALE, 0));
        masm.push(loopIndex);

        // Store (&B[k] + k*8 + kPanelSize*8) in kPanelSize register
        masm.leaq(kPanelSize, new AMD64Address(loopIndex, kPanelSize, OBJECT_ARRAY_INDEX_SCALE, 0));

        // Store &B[0] in loopIndex
        masm.movq(loopIndex, tempGenReg);

        // Push registers to be used for storing addresses of A on stack
        for(int i = 0; i < useAsAddressRegs.length; i++) {
            masm.push(useAsAddressRegs[i]);
        }

        // Load A
        masm.movq(tempArrayAddressReg, 0);
        masm.movq(tempGenReg, new AMD64Address(arrsPtr, tempArrayAddressReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));

        // Push Addresses of A that are not savable on a register on stack first, so register iPos can be pushed on stack last
        int numOfAAddressOnStack = 0;
        for(int i = aLength - 1; i >= 0; i--) {
            if(i < aTempArrayAddressNumLimit) {
                if(i == 11) {
                    aAddress = new AMD64Address(tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));  // Get (i + 11)th row of A
                    masm.movq(tempArrayAddressReg, aAddress);
                    masm.subq(tempArrayAddressReg, loopIndex);
                    masm.push(tempArrayAddressReg);
                }
                else {
                    aAddress = new AMD64Address(tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
                    masm.movq(aTempArrayAddressRegs[i], aAddress);
                    masm.subq(aTempArrayAddressRegs[i], loopIndex);
                }
            }
            else {
                aAddress = new AMD64Address(tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
                masm.movq(tempArrayAddressReg, aAddress);
                masm.subq(tempArrayAddressReg, loopIndex);
                masm.push(tempArrayAddressReg);
                numOfAAddressOnStack++;
            }
        }


        if(aTempArrayAddressNumLimit >= 12) {
            // When aTempArrayAddressNumLimit >= 12, aTempArrayAddressRegs[11] == tempGenReg
            masm.pop(aTempArrayAddressRegs[11]);
        }
        // Calculate offset to constant arguments
        stackOffsetToConstArgs = numOfAAddressOnStack*8 + useAsAddressRegs.length*8 + 8;

        masm.movq(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(useAsAddressRegs.length*8)));

        int prefetchDistance = 4;
        int mult = 8;

        for(int i = 0; i < varArgProperties.length; i++) {
            if(!toLoad.contains(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(i))) {
                if(varArgProperties[i] == 2) {
                    for(int j = 0; j < bLength; j++) {
                        loadVarArg(masm, i, -1, j, simdRegisters.get("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j)));
                    }
                }
            }
        }

        for(int i = 0; i < constArgs.length; i++) {
            if(!toLoad.contains(GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i))) {
                masm.vbroadcastsd(xmmRegistersAVX512[availableValues.get(GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i))], new AMD64Address(rsp, stackOffsetToConstArgs + constArgStackSlotSize*i));
            }
        }

        // Subtract prefetchDistance*8 from kPanelSize
        masm.subq(new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)), prefetchDistance*mult);

        Label loopLabel = new Label();

        int unrollFactor = 2;

        // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
        masm.bind(loopLabel);
        for(int i = 0; i < unrollFactor; i++) {
            subIter(aLength, bLength, i, prefetchDistance, masm, simdRegisters);
        }
        masm.addq(loopIndex, unrollFactor*mult);
        masm.cmpq(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)));
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        masm.addq(new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)), prefetchDistance*mult);

        loopLabel = new Label();
        masm.bind(loopLabel);
        subIter(aLength, bLength, 0, 0, masm, simdRegisters);
        masm.addq(loopIndex, mult);
        masm.cmpl(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)));
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        masm.addq(rsp, numOfAAddressOnStack*8);

        // Restore registers pushed to stack
        for(int i = useAsAddressRegs.length-1; i >=0; i--) {
            masm.pop(useAsAddressRegs[i]);
        }

        // Pop B
        masm.pop(loopIndex);

        // Store partial results in result array
        masm.movl(loopIndex, 0);
        masm.movq(tempGenReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+16));
        if(transposeResult) {
            for(int i = 0; i < bLength; i++) {
                //Register resultAddressRegister = xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(i))];
                Register resultAddressRegister = xmmRegistersAVX512[0+i];
                for(int j = 7; j >= 0; j--) {
                    masm.movq(tempArrayAddressReg, new AMD64Address(tempGenReg, jPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*64)+(j*8)));
                    masm.push(tempArrayAddressReg);
                }
                masm.vmovupd(resultAddressRegister, new AMD64Address(rsp));
                masm.addq(rsp, 8*8);
            }
            masm.push(iPos);
            masm.imull(iPos, iPos, 8);
            for(int i = 0; i < aLength; i++) {
                for(int j = 0; j < bLength; j++) {
                    resultAddress = new AMD64Address(iPos, xmmRegistersAVX512[0+j], AMD64Address.Scale.Times1, 12+(i*4));
                    masm.kxnorw(k2, k2, k2);
                    masm.vpgatherqq(xmmRegistersAVX512[31], k2, resultAddress);
                    masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[31]);
                    masm.kxnorw(k2, k2, k2);
                    masm.vpscatterqq(resultAddress, k2, xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))]);
                }
            }
            masm.pop(iPos);
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
