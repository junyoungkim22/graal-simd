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

    final int aLength;
    final int bLength;
    final int remainingRegisterNum;

    @Temp({REG}) private Value loopIndexValue;
    @Temp({REG}) private Value tempArrayAddressRegValue;
    @Temp({REG}) private Value[] remainingRegValues;

    Register[] tempRegs;
    int[] tempRegNums;
    Map<Integer, Integer> variableArgsStackOffsets;
    int computeIIndex;
    int computeJIndex;
    private ExprDag exprDag;

    public static PrintWriter debugLog;

    public GotoKernelOp(LIRGeneratorTool tool, Value arrs, Value kPanelSize,
                                    Value i, Value k, Value j, int aLength, int bLength, long[] calc, double[] constArgs, int[] varArgProperties) {
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

        this.aLength = aLength;
        this.bLength = bLength/8;

        constArgStackSlotSize = 32;  // Causes an error if value if 8 (do not know reason why)

        remainingRegisterNum = 7;

        loopIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        tempArrayAddressRegValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        remainingRegValues = new Value[remainingRegisterNum];
        for(int index = 0; index < remainingRegisterNum; index++) {
            remainingRegValues[index] = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        }

        variableArgsStackOffsets = new HashMap<Integer, Integer>();
        int computeIIndex = 0;
        int computeJIndex = 0;
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

    public void subIter(int offset, int prefetchDistance, AMD64MacroAssembler masm, Map<String, Integer> simdRegisters, Register[] aTempArrayAddressRegs, Map<String, Register> generalPurposeRegisters) {
        Register loopIndex = generalPurposeRegisters.get("loopIndex");
        Register tempArrayAddressReg = generalPurposeRegisters.get("tempArrayAddressReg");
        Register jPos = generalPurposeRegisters.get("jPos");

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
                //exprDag.createCode(availableValues, tempRegNums, masm);
                /*
                masm.vmulpd(xmmRegistersAVX512[29], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                masm.vcmppd(k1, xmmRegistersAVX512[29], xmmRegistersAVX512[tempRegNums[0]+j], 0x0e);
                masm.vaddpd(xmmRegistersAVX512[30], xmmRegistersAVX512[29], xmmRegistersAVX512[tempRegNums[0]+j], k1);
                masm.vaddpd(xmmRegistersAVX512[30], xmmRegistersAVX512[29], xmmRegistersAVX512[30]);
                masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[30]);
                */
                /*
                masm.vbroadcastsd(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);
                masm.vmulpd(xmmRegistersAVX512[31], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                masm.vcmppd(k1, xmmRegistersAVX512[31], xmmRegistersAVX512[tempRegNums[2]+j], 0x0e);
                masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[31], xmmRegistersAVX512[tempRegNums[2]+j], k1);
                masm.vaddpd(xmmRegistersAVX512[31], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[31]);
                masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[31]);
                */
                /*
                bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], bAddress);
                masm.vmulpd(xmmRegistersAVX512[31], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                masm.vcmppd(k1, xmmRegistersAVX512[31], xmmRegistersAVX512[tempRegNums[2]+j], 0x0e);
                masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], xmmRegistersAVX512[31], xmmRegistersAVX512[tempRegNums[2]+j], k1);
                masm.vaddpd(xmmRegistersAVX512[31], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], xmmRegistersAVX512[31]);
                masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[31]);
                */
                /*
                masm.vmulpd(xmmRegistersAVX512[31], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(0);
                masm.vmovupd(xmmRegistersAVX512[tempRegNums[0]], new AMD64Address(rsp, varArgOffset + 64*j));
                masm.vcmppd(k1, xmmRegistersAVX512[31], xmmRegistersAVX512[tempRegNums[0]], 0x0e);
                masm.vaddpd(xmmRegistersAVX512[tempRegNums[0]], xmmRegistersAVX512[31], xmmRegistersAVX512[tempRegNums[2]+j], k1);
                masm.vaddpd(xmmRegistersAVX512[31], xmmRegistersAVX512[tempRegNums[0]], xmmRegistersAVX512[31]);
                masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[31]);
                */
                /*
                masm.vmulpd(xmmRegistersAVX512[31], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                masm.vcmppd(k1, xmmRegistersAVX512[31], xmmRegistersAVX512[tempRegNums[0]+j], 0x0e);
                masm.subq(rsp, 64);
                masm.vmovupd(new AMD64Address(rsp), xmmRegistersAVX512[tempRegNums[0]+j]);
                masm.vaddpd(xmmRegistersAVX512[tempRegNums[0]+j], xmmRegistersAVX512[31], xmmRegistersAVX512[tempRegNums[2]+j], k1);
                masm.vaddpd(xmmRegistersAVX512[31], xmmRegistersAVX512[tempRegNums[0]+j], xmmRegistersAVX512[31]);
                masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[31]);
                masm.vmovupd(xmmRegistersAVX512[tempRegNums[0]+j], new AMD64Address(rsp));
                masm.addq(rsp, 64);
                */
                //masm.vfmadd231pd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                /*
                masm.vmulpd(xmmRegistersAVX512[27], xmmRegistersAVX512[simdRegisters.get("A")], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
                masm.vcmppd(k1, xmmRegistersAVX512[27], xmmRegistersAVX512[tempRegNums[0]+j], 0x0e);
                masm.vaddpd(xmmRegistersAVX512[28], xmmRegistersAVX512[27], xmmRegistersAVX512[tempRegNums[2]+j], k1);
                masm.vaddpd(xmmRegistersAVX512[27], xmmRegistersAVX512[27], xmmRegistersAVX512[28]);
                masm.vaddpd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], xmmRegistersAVX512[27]);
                */
            }
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
        /*
        HashMap<String, Integer> availableValues = new HashMap<>();
        availableValues.put(GotoOpCode.A, 0);
        availableValues.put(GotoOpCode.B, 1);
        availableValues.put(GotoOpCode.C, 8);
        if(varArgProperties.length > 0) {
            availableValues.put(GotoOpCode.VARIABLEARG + "00000", 3);
        }
        */
        //tempRegNums = new int[]{29, 30, 31};
        //ExprDag exprDag = new ExprDag(new ChangeableString(opStringRaw), availableValues, tempRegNums, debugLog);
        exprDag = new ExprDag(new ChangeableString(opStringRaw), debugLog);
        ExprDag.printDAG(debugLog, exprDag.getRootNode());
        debugLog.write("\n");
        /*
        if(debugLog != null) {
            debugLog.close();
            return;
        }
        */

        Register arrsPtr = asRegister(arrsValue);

        Register kPanelSize = asRegister(kPanelSizeValue);
        Register iPos = asRegister(iValue);
        Register kPos = asRegister(kValue);
        Register jPos = asRegister(jValue);

        // Make sure that iPos is first!
        Register useAsAddressRegs[] = new Register[]{iPos, arrsPtr, kPos, r15, kPanelSize};
        int kPanelSizeIndexFromBehind = 0;
        for(int i = 0; i < useAsAddressRegs.length; i++) {
            if(useAsAddressRegs[i] == kPanelSize) {
                kPanelSizeIndexFromBehind = i;
            }
        }
        kPanelSizeIndexFromBehind = useAsAddressRegs.length - kPanelSizeIndexFromBehind - 1;

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

        Register tempReg = xmmRegistersAVX512[tempRegNums[0]];

        Register loopIndex = asRegister(loopIndexValue);
        Register tempArrayAddressReg = asRegister(tempArrayAddressRegValue);
        Register tempArrayAddressGeneralReg = findRegister(tempArrayAddressReg, cpuRegisters);

        AMD64Address resultAddress, aAddress, bAddress;

        masm.push(kPanelSize);

        // Push Variable arguments in reverse order
        masm.movl(loopIndex, 0);
        int varArgsStackSize = 0;
        for(int i = varArgProperties.length-1; i>=0; i--) {
            if(varArgProperties[i] == 2) {   // index is j
                //variableArgsStackOffsets.put(i, varArgsStackSize);
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

        masm.movq(tempGenReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+16));

        for(int i = 0; i < aLength; i++) {
            resultAddress = new AMD64Address(tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(tempArrayAddressReg, resultAddress);
            for(int j = 0; j < bLength; j++) {
                resultAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))], resultAddress);
            }
        }

        // Store B[0]'s address in tempArrPtr
        masm.movq(tempGenReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+8));

        // Store B[k]'s address in loop index
        masm.leaq(loopIndex, new AMD64Address(tempGenReg, kPos, OBJECT_ARRAY_INDEX_SCALE, 0));

        // Store (&B[k] + kPanelSize) in kPanelSize register
        masm.leaq(kPanelSize, new AMD64Address(loopIndex, kPanelSize, OBJECT_ARRAY_INDEX_SCALE, 0));

        masm.push(loopIndex);

        // Store &B[0] in loopIndex
        masm.movq(loopIndex, tempGenReg);

        // Push registers to be used for storing addresses of A on stack
        for(int i = 0; i < useAsAddressRegs.length; i++) {
            masm.push(useAsAddressRegs[i]);
        }

        // Load A
        masm.movl(tempArrayAddressGeneralReg, 0);
        masm.movq(tempGenReg, new AMD64Address(arrsPtr, tempArrayAddressGeneralReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));

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
            // Todo: Push A addresses to stack
        }

        if(aTempArrayAddressNumLimit >= 12) {
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
            varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(1);
            masm.vmovupd(xmmRegistersAVX512[tempRegNums[2]], new AMD64Address(rsp, varArgOffset));
            masm.vmovupd(xmmRegistersAVX512[tempRegNums[3]], new AMD64Address(rsp, varArgOffset+64));
        }

        masm.subq(new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)), prefetchDistance*mult);

        Label loopLabel = new Label();

        Map<String, Register> generalPurposeRegisters = new HashMap<String, Register>();
        generalPurposeRegisters.put("loopIndex", loopIndex);
        generalPurposeRegisters.put("jPos", jPos);
        generalPurposeRegisters.put("tempArrayAddressReg", tempArrayAddressReg);

        int unrollFactor = 2;

        // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
        masm.bind(loopLabel);
        for(int i = 0; i < unrollFactor; i++) {
            subIter(i, prefetchDistance, masm, simdRegisters, aTempArrayAddressRegs, generalPurposeRegisters);
        }
        masm.addq(loopIndex, unrollFactor*mult);
        masm.cmpq(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)));
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        masm.addq(new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)), prefetchDistance*mult);

        loopLabel = new Label();
        // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
        masm.bind(loopLabel);
        subIter(0, 0, masm, simdRegisters, aTempArrayAddressRegs, generalPurposeRegisters);
        masm.addq(loopIndex, mult);
        masm.cmpq(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)));
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        for(int i = 0; i < numOfAAddressOnStack; i++) {
            masm.pop(tempArrayAddressReg);
        }

        // Restore registers pushed to stack
        for(int i = useAsAddressRegs.length-1; i >=0; i--) {
            masm.pop(useAsAddressRegs[i]);
        }

        // Pop B
        masm.pop(tempGenReg);

        // Pop const arguments
        masm.addq(rsp, constArgsStackSize);

        // Pop variable arguments
        masm.addq(rsp, varArgsStackSize);

        // Restore original value of kPanelSize
        masm.pop(kPanelSize);

        // Get pointer to result array
        masm.movl(loopIndex, 0);
        masm.movq(tempGenReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+16));

        // Store partial results in result array
        for(int i = 0; i < aLength; i++) {
            resultAddress = new AMD64Address(tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(tempArrayAddressReg, resultAddress);
            for(int j = 0; j < bLength; j++) {
                resultAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(resultAddress, xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))]);
            }
        }
        debugLog.close();
    }
}
