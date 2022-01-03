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
    private Map<Integer, Register> varArgAddresses;
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

        availableValues.put(GotoOpCode.A, simdRegisters.get("A"));

        if(interleave) {
            for(int i = 0; i < aLength; i+=2) {
                aAddress = new AMD64Address(tempArrayAddressReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(i*8));
                masm.vbroadcastf32x4(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);

                for(int k = 0; k < varArgProperties.length; k++) {
                    if(varArgProperties[k] == 1) {
                        loadVarArg(masm, k, i, -1, simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                        /*
                        Register zeroReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-4]);
                        masm.movq(zeroReg, 0);
                        masm.movq(tempArrayAddressReg, new AMD64Address(arrsPtr, zeroReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*k));
                        masm.vbroadcastf32x4(xmmRegistersAVX512[simdRegisters.get("VARIABLEARG" + String.valueOf(k))], new AMD64Address(tempArrayAddressReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(i*8)));
                        */
                        availableValues.put(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k), simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                    }
                }

                for(int j = 0; j < bLength*2; j++) {
                    //Register aRegister = xmmRegistersAVX512[simdRegisters.get("A")];
                    //Register bRegister = xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))];
                    availableValues.put(GotoOpCode.B, simdRegisters.get("B" + String.valueOf(j)));
                    availableValues.put(GotoOpCode.C, simdRegisters.get("C" + String.valueOf(i+(j%2)) + String.valueOf(j/2)));
                    //debugLog.println("C" + String.valueOf(i+(j%2)) + String.valueOf(j/2));
                    //Register cRegister = xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i+(j%2)) + String.valueOf(j/2))];
                    //masm.vfmadd231pd(cRegister, aRegister, bRegister);
                    for(int k = 0; k < varArgProperties.length; k++) {
                        if(varArgProperties[k] == 2) {
                            availableValues.put(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k), simdRegisters.get("VARIABLEARG" + String.valueOf(k) + "_" + String.valueOf(j)));
                        }
                        else if(varArgProperties[k] == 3) {
                            availableValues.put(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k), simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                            loadVarArg(masm, k, i, j, simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                        }
                    }
                    emitSubiterCode(masm, i, j, offset);
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

                for(int k = 0; k < varArgProperties.length; k++) {
                    if(varArgProperties[k] == 1) {
                        loadVarArg(masm, k, i, -1, simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                        availableValues.put(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k), simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                    }
                }

                for(int j = 0; j < bLength; j++) {
                    availableValues.put(GotoOpCode.B, simdRegisters.get("B" + String.valueOf(j)));
                    availableValues.put(GotoOpCode.C, simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j)));
                    for(int k = 0; k < varArgProperties.length; k++) {
                        if(varArgProperties[k] == 2) {
                            availableValues.put(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k), simdRegisters.get("VARIABLEARG" + String.valueOf(k) + "_" + String.valueOf(j)));
                        }
                        else if(varArgProperties[k] == 3) {
                            availableValues.put(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k), simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                            loadVarArg(masm, k, i, j, simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
                        }
                    }
                    emitSubiterCode(masm, i, j, offset);
                    //masm.vfmadd231pd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                }
            }
        }
    }

    protected void loadA(AMD64MacroAssembler masm, int iIndex, int offset, int dstRegNum) {
        return;
    }

    protected void loadB(AMD64MacroAssembler masm, int jIndex, int offset, int dstRegNum) {
        return;
    }

    protected void loadVarArg(AMD64MacroAssembler masm, int argIndex, int iIndex, int jIndex, int dstRegNum) {
        if(interleave) {
            if(varArgProperties[argIndex] == 2) {
                int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);
                masm.vmovupd(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset+64*jIndex));
            }
            else if(varArgProperties[argIndex] == 1) {
                /*
                int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);

                masm.leaq(tempArrayAddressReg, new AMD64Address(rsp, varArgOffset+8*iIndex));
                masm.vbroadcastf32x4(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempArrayAddressReg));
                */

                /*
                Register zeroReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-4]);
                masm.movq(zeroReg, 0);
                masm.movq(tempArrayAddressReg, new AMD64Address(arrsPtr, zeroReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*argIndex));
                masm.vbroadcastf32x4(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempArrayAddressReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(iIndex*8)));
                */

                masm.vbroadcastf32x4(xmmRegistersAVX512[dstRegNum], new AMD64Address(varArgAddresses.get(argIndex), iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(iIndex*8)));


                // Todo: optimize into the below code! (vbroadcastsd does not work for some addresses)
                //masm.vbroadcastf32x4(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset+8*iIndex));
            }
            else if(varArgProperties[argIndex] == 3) {
                //int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);
                //masm.vmovddup(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset+(128*iIndex)+64*jIndex));


                Register zeroReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-5]);
                masm.movq(zeroReg, 0);
                masm.movq(tempArrayAddressReg, new AMD64Address(arrsPtr, zeroReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*argIndex));
                Register tempRegAh = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-6]);
                masm.movq(tempRegAh, new AMD64Address(tempArrayAddressReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(iIndex*8)));
                int j = jIndex;
                masm.vmovddup(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempRegAh, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+DOUBLE_ARRAY_BASE_OFFSET+((j/2)*64)+((j%2)*8)));
            }
        } else {
            if(varArgProperties[argIndex] == 2) {
                int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);
                masm.vmovupd(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset+64*jIndex));
            }
            else if(varArgProperties[argIndex] == 1) {

                int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);
                /*
                masm.leaq(tempArrayAddressReg, new AMD64Address(rsp, varArgOffset+8*iIndex));
                masm.vbroadcastsd(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempArrayAddressReg));
                */

                Register tempRegAAAA = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-5]);
                masm.movq(tempRegAAAA, new AMD64Address(rsp, varArgOffset+8*iIndex));
                masm.vpbroadcastq(xmmRegistersAVX512[dstRegNum], tempRegAAAA);

                //masm.vbroadcastsd(xmmRegistersAVX512[dstRegNum], new AMD64Address(varArgAddresses.get(argIndex), iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(iIndex*8)));

                // Todo: optimize into the below code! (vbroadcastsd does not work for some addresses)
                //masm.vbroadcastsd(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset+8*iIndex));
            }
            else if(varArgProperties[argIndex] == 3) {
                int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);
                masm.vmovupd(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset+(128*iIndex)+64*jIndex));
                /*
                Register zeroReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-5]);
                masm.movq(zeroReg, 0);
                masm.movq(tempArrayAddressReg, new AMD64Address(arrsPtr, zeroReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*argIndex));
                Register tempRegAh = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-6]);
                masm.movq(tempRegAh, new AMD64Address(tempArrayAddressReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(iIndex*8)));
                masm.vmovupd(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempRegAh, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(jIndex*8)));
                */
            }
        }
    }

    protected void emitKernelCode(AMD64MacroAssembler masm, int aLength, int bLength) {
        // Declare SIMD registers
        if(aLength % 2 == 1) {
            interleave = false;
        }
        for(int property : varArgProperties) {
            if(property == 3) {
                interleave = false;
                break;
            }
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

        for(int i = 0; i < constArgs.length; i++) {
            if(!toLoad.contains(GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i))) {
                availableValues.put(GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i), registerIndex++);
            }
        }
        for(int i = 0; i < varArgProperties.length; i++) {
            if(varArgProperties[i] == 2) {
                if(interleave) {
                    for(int j = 0; j < bLength*2; j++) {
                        simdRegisters.put("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j), registerIndex++);
                    }
                }
                else {
                    for(int j = 0; j < bLength; j++) {
                        simdRegisters.put("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j), registerIndex++);
                    }
                }
            }
            else if(varArgProperties[i] == 1 || varArgProperties[i] == 3) {
                simdRegisters.put("VARIABLEARG" + String.valueOf(i), registerIndex++);
            }
        }

        int remainingSimdRegisterNum = xmmRegistersAVX512.length - registerIndex;
        for(int i = 0; i < remainingSimdRegisterNum; i++) {
            availableValues.put(GotoOpCode.REG + GotoOpCode.toOpLengthBinaryString(i), registerIndex++);
        }

        /*
        for(int i = 0; i < xmmRegistersAVX512.length - registerIndex; i++) {
            availableValues.put(GotoOpCode.REG + GotoOpCode.toOpLengthBinaryString(i), registerIndex++);
        }

        tempRegNums = new int[xmmRegistersAVX512.length - registerIndex];
        for(int i = 0; i < tempRegNums.length; i++) {
            tempRegNums[i] = registerIndex++;
        }
        */

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

        varArgAddresses = new HashMap<Integer, Register>();
        int genRegCountdown = 4;

        for(int i = 0; i < varArgProperties.length; i++) {
            if(varArgProperties[i] == 2) {
                int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(i);
                if(interleave) {
                    for(int j = 0; j < bLength*2; j++) {
                        masm.vmovddup(xmmRegistersAVX512[simdRegisters.get("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j))], new AMD64Address(rsp, varArgOffset+((j/2)*64)+((j%2)*8)));
                    }
                }
                else {
                    for(int j = 0; j < bLength; j++) {
                        masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j))], new AMD64Address(rsp, varArgOffset+64*j));
                    }
                }
            }
            else if(varArgProperties[i] == 1) {
                Register varArgRegister = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-genRegCountdown]);
                genRegCountdown++; // Todo: Do something when genRegCountdown == remainingRegisterNum
                masm.movq(varArgRegister, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*i));
                varArgAddresses.put(i, varArgRegister);
            }
        }

        for(int i = 0; i < constArgs.length; i++) {
            if(!toLoad.contains(GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i))) {
                masm.vbroadcastsd(xmmRegistersAVX512[availableValues.get(GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i))], new AMD64Address(rsp, stackOffsetToConstArgs + constArgStackSlotSize*i));
            }
        }

        int prefetchDistance = 8;
        int unrollFactor = 8;
        if(interleave) {
            prefetchDistance = 8;
            unrollFactor = 8;
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

