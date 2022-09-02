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
import java.util.Stack;
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
import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rdx;
import static jdk.vm.ci.amd64.AMD64.cpuRegisters;
import static jdk.vm.ci.amd64.AMD64.xmmRegistersAVX512;
import static org.graalvm.compiler.asm.amd64.AMD64BaseAssembler.OperandSize.QWORD;
import static org.graalvm.compiler.asm.amd64.AMD64BaseAssembler.OperandSize.DWORD;

import org.graalvm.compiler.lir.amd64.vec.util.ChangeableString;
import org.graalvm.compiler.lir.amd64.vec.GotoOpCode;
import org.graalvm.compiler.lir.amd64.vec.dag.ExprDag;
import org.graalvm.compiler.lir.amd64.vec.GotoKernel.GotoKernelOp;
import org.graalvm.compiler.lir.amd64.vec.GotoKernel.GotoKernel;

public final class GotoPackedKernel extends GotoKernel {
    private boolean interleave;
    private int kPack;
    private int aAddressOffset;
    private int aAlignmentOffset;
    private int bAlignmentOffset;

    public GotoPackedKernel(LIRGeneratorTool tool, int kernelType, int aLength, int bLength, int mLength, int kLength, int nLength,
                        long[] calc, double[] constArgs, int[] varArgProperties, GotoKernelOp kernelOp, int[] miscArgs) {
        super(tool, kernelType, aLength, bLength, mLength, kLength, nLength, calc, constArgs, varArgProperties, kernelOp);
        this.interleave = true;
        this.kPack = miscArgs[0];
        this.aAlignmentOffset = miscArgs[1];
        this.bAlignmentOffset = miscArgs[2];

        // There is a bug where an AMD64Address with offset 0x40 or -0x40 will be generated as offset 0x8 or -0x8
        // for vbroadcastsd. Use the following variable to control the offset of AMD64Address so that 0x40 or 0x-40
        // is not reached.
        this.aAddressOffset = 256;   
    }

    public void subIter(int aLength, int bLength, int offset, int prefetchDistance, AMD64MacroAssembler masm, Map<String, Integer> simdRegisters, 
                        Register aPtr, Register bPtr, Register aIndex, Register bIndex, Register temp) {
        AMD64Address aAddress, bAddress;

        if(prefetchDistance > 0) {
            for(int j = 0; j < bLength; j++) {
                bAddress = new AMD64Address(bPtr, bIndex, DOUBLE_ARRAY_INDEX_SCALE, 
                               DOUBLE_ARRAY_BASE_OFFSET+(j*64)+(offset*8*bLength*8)+(prefetchDistance*8*bLength*8));
                masm.prefetcht0(bAddress);
            }
        }

        if(interleave) {
            for(int j = 0; j < bLength*2; j++) {
                bAddress = new AMD64Address(bPtr, bIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+((j/2)*64)+((j%2)*8)+(offset*8*bLength*8));
                masm.vmovddup(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], bAddress);
            }
        }
        else {
            for(int j = 0; j < bLength; j++) {
                bAddress = new AMD64Address(bPtr, bIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64)+(offset*8*bLength*8));
                masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], bAddress);
            }
        }


        if(interleave) {
            for(int i = 0; i < aLength; i+=2) {
                aAddress = new AMD64Address(aPtr, aIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(i*8) - aAddressOffset + (offset*8*aLength));
                masm.vbroadcastf32x4(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);

                for(int j = 0; j < bLength*2; j++) {
                    masm.vfmadd231pd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i+(j%2)) + String.valueOf(j/2))], 
                                    xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                }
            }
        } else {
            for(int i = 0; i < aLength; i++) {
                aAddress = new AMD64Address(aPtr, aIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(i*8) - aAddressOffset + (offset*8*aLength));
                // There is a bug in Graal where for vbroadcastsd offset 0xc8 becomes 0x18. Fix later.
                masm.vbroadcastsd(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);
                for(int j = 0; j < bLength; j++) {
                    masm.vfmadd231pd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], 
                                    xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                }
            }
        }
    }

    protected void loadA(AMD64MacroAssembler masm, int iIndex, int offset, int dstRegNum) {
    }

    protected void loadB(AMD64MacroAssembler masm, int jIndex, int offset, int dstRegNum) {
    }

    protected void loadVarArg(AMD64MacroAssembler masm, int argIndex, int iIndex, int jIndex, int dstRegNum) {
    }

    protected void emitKernelCode(AMD64MacroAssembler masm, int aLength, int bLength) {
        if(aLength % 2 == 1) {
            interleave = false;
        } else {
            interleave = true;
        }
        // Declare SIMD registers
        int registerIndex = 0;
        HashMap<String, Integer> simdRegisters= new HashMap<String, Integer>();

        Register[] genRegs = new Register[remainingRegisterNum];
        for(int i = 0; i < remainingRegisterNum; i++) {
            genRegs[i] = asRegister(kernelOp.remainingRegValues[i]);
        }

        Stack<Register> notRaxRdxRegs = new Stack<>();
        for(int i = 0; i < genRegs.length; i++) {
            addToNoDivStack(notRaxRdxRegs, genRegs[i]);
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
        for(int i = 0; i < aLength; i++) {
            for(int j = 0; j < bLength; j++) {
                simdRegisters.put("C" + String.valueOf(i) + String.valueOf(j), registerIndex++);
            }
        }

        AMD64Address resultAddress, aAddress, bAddress;

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

        int kernelWidth = bLength*8;

        Register kPosTemp = notRaxRdxRegs.pop();
        masm.movq(kPosTemp, kPos);
        Register kPackTemp = notRaxRdxRegs.pop();
        masm.movq(kPackTemp, kPack);

        masm.push(rax);
        masm.push(rdx);

        Register kPosDivKpack = notRaxRdxRegs.pop();
        Register kStart = notRaxRdxRegs.pop();

        masm.movq(rdx, 0);
        masm.movq(rax, kPosTemp);
        AMD64Assembler.AMD64MOp.IDIV.emit(masm, DWORD, kPackTemp);
        masm.movq(kPosDivKpack, rax);
        masm.movq(kStart, rdx);

        addToNoDivStack(notRaxRdxRegs, kPackTemp);
        addToNoDivStack(notRaxRdxRegs, kPosTemp);

        masm.pop(rdx);
        masm.pop(rax);

        Register bIndex = notRaxRdxRegs.pop();
        Register aIndex = notRaxRdxRegs.pop();

        masm.movq(bIndex, kPosDivKpack);
        masm.imull(bIndex, bIndex, nLength);
        masm.imull(bIndex, bIndex, kPack);

        masm.movq(aIndex, kPosDivKpack);
        masm.imull(aIndex, aIndex, mLength);
        masm.imull(aIndex, aIndex, kPack);

        addToNoDivStack(notRaxRdxRegs, kPosDivKpack);

        Register temp = notRaxRdxRegs.pop();

        masm.movq(temp, jPos);
        masm.imull(temp, temp, kPack);
        masm.addq(bIndex, temp);

        masm.movq(temp, kStart);
        masm.imull(temp, temp, kernelWidth);
        masm.addq(bIndex, temp);

        masm.movq(temp, iPos);
        masm.imull(temp, temp, kPack);
        masm.addq(aIndex, temp);

        masm.movq(temp, kStart);
        masm.imull(temp, temp, aLength);
        masm.addq(aIndex, temp);

        addToNoDivStack(notRaxRdxRegs, kStart);

        Register loopEndReg = notRaxRdxRegs.pop();
        masm.movq(loopEndReg, bIndex);
        masm.movq(temp, kPanelSize);
        masm.imull(temp, temp, kernelWidth);
        masm.addq(loopEndReg, temp);

        masm.push(iPos);
        masm.push(jPos);

        masm.movq(temp, 0);
        //Register aPtr = notRaxRdxRegs.pop();
        Register aPtr = iPos;
        masm.movq(aPtr, new AMD64Address(arrsPtr, temp, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
        //Register bPtr = notRaxRdxRegs.pop();
        Register bPtr = jPos;
        masm.movq(bPtr, new AMD64Address(arrsPtr, temp, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+8));

        Label loopLabel = new Label();

        masm.addq(aPtr, aAddressOffset);
        masm.addq(aPtr, aAlignmentOffset);

        masm.addq(bPtr, bAlignmentOffset);

        int unrollFactor = 4;
        int prefetchDistance = 4;
        masm.movq(temp, kernelWidth);
        masm.imull(temp, temp, unrollFactor);
        masm.subq(loopEndReg, temp);
        
        // Code to write stuff to the debug array.
        /*
        masm.push(aIndex);
        masm.push(bIndex);
        masm.movq(bIndex, 0);
        masm.movq(aIndex, new AMD64Address(arrsPtr, bIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 24));
        masm.movl(new AMD64Address(aIndex, bIndex, INT_ARRAY_INDEX_SCALE, INT_ARRAY_BASE_OFFSET), 3450);
        masm.pop(bIndex);
        masm.pop(aIndex);
        */

        Label endLabel = new Label();
        masm.cmpq(bIndex, loopEndReg);
        masm.jcc(AMD64Assembler.ConditionFlag.GreaterEqual, endLabel);

        masm.bind(loopLabel);
        for(int i = 0; i < unrollFactor; i++) {
            subIter(aLength, bLength, i, prefetchDistance, masm, simdRegisters, aPtr, bPtr, aIndex, bIndex, temp);
        }
        masm.addq(bIndex, kernelWidth*unrollFactor);
        masm.addq(aIndex, aLength*unrollFactor);
        masm.cmpq(bIndex, loopEndReg);
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        masm.bind(endLabel);

        masm.addq(loopEndReg, temp);

        loopLabel = new Label();
        masm.bind(loopLabel);
        subIter(aLength, bLength, 0, 0, masm, simdRegisters, aPtr, bPtr, aIndex, bIndex, temp);
        masm.addq(bIndex, kernelWidth*1);
        masm.addq(aIndex, aLength*1);
        masm.cmpq(bIndex, loopEndReg);
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        addToNoDivStack(notRaxRdxRegs, temp);

        // Comment these two lines to cause a segfault and dump asm code to a log file.
        masm.pop(jPos);
        masm.pop(iPos);

        addToNoDivStack(notRaxRdxRegs, bIndex);
        addToNoDivStack(notRaxRdxRegs, aIndex);
        addToNoDivStack(notRaxRdxRegs, loopEndReg);

        temp = notRaxRdxRegs.pop();
        Register temp2 = notRaxRdxRegs.pop();
        Register zeroReg = notRaxRdxRegs.pop();
        masm.movq(zeroReg, 0);
        masm.movq(temp, new AMD64Address(arrsPtr, zeroReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+16));
        addToNoDivStack(notRaxRdxRegs, zeroReg);

        if(interleave) {
            Register resultPtr0 = notRaxRdxRegs.pop();
            Register resultPtr1 = notRaxRdxRegs.pop();
            for(int i = 0; i < aLength; i+=2) {
                masm.movq(resultPtr0, new AMD64Address(temp, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8)));
                masm.movq(resultPtr1, new AMD64Address(temp, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+((i+1)*8)));
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
                resultAddress = new AMD64Address(temp, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
                masm.movq(temp2, resultAddress);
                for(int j = 0; j < bLength; j++) {
                    resultAddress = new AMD64Address(temp2, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                    masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], resultAddress);
                    masm.vmovupd(resultAddress, xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))]);
                }
            }
        }
    }

    private void addToNoDivStack(Stack<Register> stack, Register toPush) {
        if(!toPush.equals(rax) && !toPush.equals(rdx)) {
            stack.push(toPush);
        }
    }
}
