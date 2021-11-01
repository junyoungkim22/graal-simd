package org.graalvm.compiler.lir.amd64.vec;

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

@Opcode("GOTOKERNEL8X8")
public final class GotoKernelOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<GotoKernelOp> TYPE = LIRInstructionClass.create(GotoKernelOp.class);

    private static int opLength = 5;

    private final int DOUBLE_ARRAY_BASE_OFFSET;
    private final Scale DOUBLE_ARRAY_INDEX_SCALE;

    private final int OBJECT_ARRAY_BASE_OFFSET;
    private final Scale OBJECT_ARRAY_INDEX_SCALE;

    private final String opStringRaw;
    private final double[] constArgs;
    private final int[] varArgProperties;

    @Alive({REG}) private Value arrsValue;

    @Temp({REG}) private Value kPanelSizeValue;
    @Temp({REG}) private Value iValue;
    @Temp({REG}) private Value kValue;
    @Temp({REG}) private Value jValue;

    int stackOffsetToConstArgs;
    int constArgsStackSize;
    int aTempArrayAddressNumLimit;
    final int constArgStackSlotSize;

    final int kernelType;
    final int mLength, kLength, nLength;
    final int initialALength;
    final int initialBLength;
    final int remainingRegisterNum;

    @Temp({REG}) private Value loopIndexValue;
    @Temp({REG}) private Value tempArrayAddressRegValue;
    @Temp({REG}) private Value[] remainingRegValues;

    Register arrsPtr, kPanelSize, iPos, kPos, jPos, loopIndex, tempArrayAddressReg;

    int kPanelSizeIndexFromBehind;
    Register useAsAddressRegs[];
    Register[] tempRegs;
    int[] tempRegNums;
    Map<Integer, Integer> variableArgsStackOffsets;
    int varArgsStackSize;
    private ExprDag exprDag;

    public static PrintWriter debugLog;

    public GotoKernelOp(LIRGeneratorTool tool, Value arrs, Value kPanelSize,
                                    Value i, Value k, Value j, int kernelType, int aLength, int bLength, int mLength, int kLength, int nLength, long[] calc, double[] constArgs, int[] varArgProperties) {
        super(TYPE);

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

        arrsValue = arrs;
        kPanelSizeValue = kPanelSize;
        iValue = i;
        kValue = k;
        jValue = j;

        this.kernelType = kernelType;
        this.mLength = mLength;
        this.kLength = kLength;
        this.nLength = nLength;
        this.initialALength = aLength;
        this.initialBLength = bLength/8;

        constArgStackSlotSize = 32;  // Causes an error if value if 8 (do not know reason why)

        remainingRegisterNum = 7;

        loopIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        tempArrayAddressRegValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        remainingRegValues = new Value[remainingRegisterNum];
        for(int index = 0; index < remainingRegisterNum; index++) {
            remainingRegValues[index] = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        }

        variableArgsStackOffsets = new HashMap<Integer, Integer>();
        varArgsStackSize = 0;
    }

    private static Boolean registerEquals(Register a, Register b) {
        return a.name.equals(b.name);
    }

    private static Register findRegister(Register toFind, Register[] registerArray) {
        for(int i = 0; i < registerArray.length; i++) {
            if(registerEquals(toFind, registerArray[i])) {
                return registerArray[i];
            }
        }
        return null;
    }

    public static Boolean interleave = true;

    private void emitAtBKernelCode(AMD64MacroAssembler masm, int aLength, int bLength) {
        // Declare SIMD registers
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

        Register tempGenReg = asRegister(remainingRegValues[remainingRegisterNum-1]);

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

        Register aPtr = asRegister(remainingRegValues[remainingRegisterNum-2]);
        masm.movq(aPtr, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
        Register bPtr = asRegister(remainingRegValues[remainingRegisterNum-3]);
        masm.movq(bPtr, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+8));

        int prefetchDistance = 0;
        int unrollFactor = 1;
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
            atbsubIter(aLength, bLength, i, prefetchDistance, masm, simdRegisters, aPtr, bPtr, tempGenReg);
        }
        masm.addq(loopIndex, unrollFactor);
        masm.cmpq(loopIndex, kPanelSize);
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        masm.addq(kPanelSize, Math.max(prefetchDistance, unrollFactor));

        loopLabel = new Label();
        masm.bind(loopLabel);
        atbsubIter(aLength, bLength, 0, 0, masm, simdRegisters, aPtr, bPtr, tempGenReg);
        masm.addq(loopIndex, 1);
        masm.cmpq(loopIndex, kPanelSize);
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        // Store partial results in result array
        masm.movq(loopIndex, 0);
        masm.movq(tempGenReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+16));
        if(interleave) {
            Register resultPtr0 = asRegister(remainingRegValues[remainingRegisterNum-2]);
            Register resultPtr1 = asRegister(remainingRegValues[remainingRegisterNum-3]);
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

    public void atbsubIter(int aLength, int bLength, int offset, int prefetchDistance, AMD64MacroAssembler masm, Map<String, Integer> simdRegisters, Register aPtr, Register bPtr, Register tempGenReg) {
        AMD64Address aAddress, bAddress;
        if(prefetchDistance > 0) {
            bAddress = new AMD64Address(bPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(offset*8)+(prefetchDistance*8));
            masm.movq(tempArrayAddressReg, bAddress);
            for(int j = 0; j < bLength; j++) {
                bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.prefetcht0(bAddress);
            }

            aAddress = new AMD64Address(aPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(offset*8)+(prefetchDistance*8));
            masm.movq(tempGenReg, aAddress);
            for(int j = 0; j < (int) Math.ceil((double) aLength/8); j++) {
                aAddress = new AMD64Address(tempGenReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
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
        masm.movq(tempGenReg, aAddress);

        if(interleave) {
            for(int i = 0; i < aLength; i+=2) {
                aAddress = new AMD64Address(tempGenReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(i*8));
                masm.vbroadcastf32x4(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);
                for(int j = 0; j < bLength*2; j++) {
                    Register aRegister = xmmRegistersAVX512[simdRegisters.get("A")];
                    Register bRegister = xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))];
                    debugLog.println("C" + String.valueOf(i+(j%2)) + String.valueOf(j/2));
                    Register cRegister = xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i+(j%2)) + String.valueOf(j/2))];
                    masm.vfmadd231pd(cRegister, aRegister, bRegister);
                }
            }
        }
        else {
            for(int i = 0; i < aLength; i++) {
                aAddress = new AMD64Address(tempGenReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(i*8));
                //masm.vbroadcastsd(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);
                masm.movq(tempArrayAddressReg, aAddress);
                masm.vpbroadcastq(xmmRegistersAVX512[simdRegisters.get("A")], tempArrayAddressReg);
                for(int j = 0; j < bLength; j++) {
                    masm.vfmadd231pd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                }
            }
        }
    }

    public void subIter(int aLength, int bLength, int offset, int prefetchDistance, AMD64MacroAssembler masm, Map<String, Integer> simdRegisters, Register[] aTempArrayAddressRegs) {
        AMD64Address aAddress, bAddress;

        if(prefetchDistance > 0) {
            bAddress = new AMD64Address(loopIndex, OBJECT_ARRAY_BASE_OFFSET+(offset*8)+(prefetchDistance*8));
            masm.movq(tempArrayAddressReg, bAddress);

            for(int j = 0; j < bLength; j++) {
                bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.prefetcht0(bAddress);
            }
        }

        bAddress = new AMD64Address(loopIndex, OBJECT_ARRAY_BASE_OFFSET+(offset*8));
        masm.movq(tempArrayAddressReg, bAddress);

        for(int j = 0; j < bLength; j++) {
            bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
            masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], bAddress);
        }

        HashMap<String, Integer> availableValues = new HashMap<String, Integer>();
        availableValues.put(GotoOpCode.A, simdRegisters.get("A"));

        for(int i = 0; i < aLength; i++) {
            if(i < aTempArrayAddressNumLimit) {
                aAddress = new AMD64Address(aTempArrayAddressRegs[i], loopIndex, AMD64Address.Scale.Times1, DOUBLE_ARRAY_BASE_OFFSET+(offset*8));
            }
            else {
                // Todo: read from stack
                masm.movq(tempArrayAddressReg, new AMD64Address(rsp, 8*(i-aTempArrayAddressNumLimit)));
                aAddress = new AMD64Address(tempArrayAddressReg, loopIndex, AMD64Address.Scale.Times1, DOUBLE_ARRAY_BASE_OFFSET+(offset*8));
            }
            masm.vbroadcastsd(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);
            for(int j = 0; j < bLength; j++) {
                availableValues.put(GotoOpCode.B, simdRegisters.get("B" + String.valueOf(j)));
                availableValues.put(GotoOpCode.VARIABLEARG + "00000", tempRegNums[0]+j);
                availableValues.put(GotoOpCode.C, simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j)));
                exprDag.createCode(availableValues, new int[]{29, 30, 31}, masm);
            }
        }
    }

    private void pushArguments(AMD64MacroAssembler masm) {
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

        Register tempGenReg = asRegister(remainingRegValues[remainingRegisterNum-1]);

        // Push Constant arguments in reverse order
        constArgsStackSize = 0;
        for(int i = constArgs.length-1; i >=0; i--) {
            masm.movq(tempGenReg, Double.doubleToLongBits(constArgs[i]));
            masm.subq(rsp, constArgStackSlotSize);
            masm.movq(new AMD64Address(rsp), tempGenReg);
            constArgsStackSize += constArgStackSlotSize;
        }
    }

    private void emitABKernelCode(AMD64MacroAssembler masm, int aLength, int bLength) {
        aTempArrayAddressNumLimit = aLength < remainingRegisterNum+useAsAddressRegs.length ? aLength : remainingRegisterNum+useAsAddressRegs.length;
        Register aTempArrayAddressRegs[] = new Register[aTempArrayAddressNumLimit];
        for(int i = 0; i < aTempArrayAddressNumLimit; i++) {
            if(i < useAsAddressRegs.length) {
                aTempArrayAddressRegs[i] = findRegister(useAsAddressRegs[i], cpuRegisters);
            }
            else {
                aTempArrayAddressRegs[i] = asRegister(remainingRegValues[i-useAsAddressRegs.length]);
            }
        }

        // Declare SIMD registers
        int registerIndex = 0;
        HashMap<String, Integer> simdRegisters= new HashMap<String, Integer>();
        for(int i = 0; i < aLength; i++) {
            for(int j = 0; j < bLength; j++) {
                simdRegisters.put("C" + String.valueOf(i) + String.valueOf(j), registerIndex++);
            }
        }

        simdRegisters.put("A", registerIndex++);
        for(int i = 0; i < bLength; i++) {
            simdRegisters.put("B" + String.valueOf(i), registerIndex++);
        }

        tempRegNums = new int[xmmRegistersAVX512.length - registerIndex];
        for(int i = 0; i < tempRegNums.length; i++) {
            tempRegNums[i] = registerIndex++;
        }

        AMD64Address resultAddress, aAddress, bAddress;

        Register tempGenReg = asRegister(remainingRegValues[remainingRegisterNum-1]);

        // Store previous values of the result array into SIMD registers
        masm.movq(loopIndex, 0);
        masm.movq(tempGenReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+16));
        for(int i = 0; i < aLength; i++) {
            resultAddress = new AMD64Address(tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(tempArrayAddressReg, resultAddress);
            for(int j = 0; j < bLength; j++) {
                resultAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], resultAddress);
            }
        }

        // Store &B[0] in tempArrPtr
        masm.movq(tempGenReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+8));

        // Store (&B[0] + k*8) in loop index
        masm.leaq(loopIndex, new AMD64Address(tempGenReg, kPos, OBJECT_ARRAY_INDEX_SCALE, 0));
        masm.push(loopIndex);

        // Store (&B[k] + kPanelSize*8) in kPanelSize register
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

        if(varArgProperties.length > 0) {
            int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(0);
            masm.vmovupd(xmmRegistersAVX512[tempRegNums[0]], new AMD64Address(rsp, varArgOffset));
            masm.vmovupd(xmmRegistersAVX512[tempRegNums[1]], new AMD64Address(rsp, varArgOffset+64));
            //varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(1);
            //masm.vmovupd(xmmRegistersAVX512[tempRegNums[2]], new AMD64Address(rsp, varArgOffset));
            //masm.vmovupd(xmmRegistersAVX512[tempRegNums[3]], new AMD64Address(rsp, varArgOffset+64));
        }

        // Subtract prefetchDistance*8 from kPanelSize
        masm.subq(new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)), prefetchDistance*mult);

        Label loopLabel = new Label();

        int unrollFactor = 2;

        // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
        masm.bind(loopLabel);
        for(int i = 0; i < unrollFactor; i++) {
            subIter(aLength, bLength, i, prefetchDistance, masm, simdRegisters, aTempArrayAddressRegs);
        }
        masm.addq(loopIndex, unrollFactor*mult);
        masm.cmpq(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)));
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        masm.addq(new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)), prefetchDistance*mult);

        loopLabel = new Label();
        masm.bind(loopLabel);
        subIter(aLength, bLength, 0, 0, masm, simdRegisters, aTempArrayAddressRegs);
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
        for(int i = 0; i < aLength; i++) {
            resultAddress = new AMD64Address(tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(tempArrayAddressReg, resultAddress);
            for(int j = 0; j < bLength; j++) {
                resultAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(resultAddress, xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))]);
            }
        }
    }

    private void emitKernelCode(AMD64MacroAssembler masm, int aLength, int bLength) {
        switch(kernelType) {
            case 0:
                emitABKernelCode(masm, aLength, bLength);
                break;
            case 1:
                // Todo: Fix A^TB Kernel when A is odd
                if(aLength == initialALength) {
                    emitAtBKernelCode(masm, aLength, bLength);
                }
                break;
        }
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        // Make sure to not use debugLog for testing, it increases compile time
        try{
            debugLog = new PrintWriter("/home/junyoung2/project/adaptive-code-generation/log.txt", "UTF-8");
        } catch (Exception e) {
            System.out.println(e);
        }

        exprDag = new ExprDag(new ChangeableString(opStringRaw), debugLog);
        //ExprDag.printDAG(debugLog, exprDag.getRootNode());
        //debugLog.write("\n");

        //debugLog.write(mLength + "\n");
        //debugLog.write(kLength + "\n");
        //debugLog.write(nLength + "\n");
        /*
        if(debugLog != null) {
            debugLog.close();
            return;
        }
        */

        arrsPtr = asRegister(arrsValue);
        kPanelSize = asRegister(kPanelSizeValue);
        iPos = asRegister(iValue);
        kPos = asRegister(kValue);
        jPos = asRegister(jValue);
        loopIndex = asRegister(loopIndexValue);
        tempArrayAddressReg = asRegister(tempArrayAddressRegValue);

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

            emitKernelCode(masm, tempALength, initialBLength);
            //emitAtBKernelCode(masm, tempALength, initialBLength);

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
