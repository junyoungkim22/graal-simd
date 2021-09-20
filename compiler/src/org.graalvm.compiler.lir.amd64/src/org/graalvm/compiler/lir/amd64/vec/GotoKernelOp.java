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
    @Temp({REG}) private Value tempArrPtrValue;

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
    Map<Integer, Integer> variableArgsStackOffsets;
    int computeIIndex;
    int computeJIndex;

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
        tempArrPtrValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        kPanelSizeValue = kPanelSize;
        iValue = i;
        kValue = k;
        jValue = j;

        this.aLength = aLength;
        this.bLength = bLength/8;

        constArgStackSlotSize = 32;  // Causes an error if value if 8 (do not know reason why)

        remainingRegisterNum = 6;

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

    private static Register findAvailableTempRegister(Register[] tempRegs, Register ...usedRegs) {
        for(Register reg : tempRegs) {
            Boolean found = true;
            for(Register used : usedRegs) {
                if(registerEquals(reg, used)) {
                    found = false;
                    break;
                }
            }
            if(found) {
                return reg;
            }
        }
        return null;
    }

    private void pushIfNotAvailable(Register toPush, Map<String, Register> availableValues, AMD64MacroAssembler masm) {
        if(availableValues.containsValue(toPush)) {
            return;
        }
        masm.subq(rsp, 64);
        masm.vmovupd(new AMD64Address(rsp), toPush);
        this.stackOffsetToConstArgs += 64;
    }

    private void popIfNotAvailable(Register toPush, Map<String, Register> availableValues, AMD64MacroAssembler masm) {
        if(availableValues.containsValue(toPush)) {
            return;
        }
        masm.vmovupd(toPush, new AMD64Address(rsp));
        masm.addq(rsp, 64);
        this.stackOffsetToConstArgs -= 64;
    }

    // Emit operation corresponding to opString, store in resultRegister (if applicable), and return SIMD register containing result
    public Register emitOperation(Map<String, Register> availableValues, ChangeableString opString,
                        AMD64MacroAssembler masm, Register resultRegister) {
        String op = opString.cutOff(opLength);
        String opType = op.substring(0, 2);
        if(opType.equals(GotoOpCode.OP)) {
            if(!op.equals(GotoOpCode.FMADD)) {
                Register lhs = emitOperation(availableValues, opString, masm, tempRegs[0]);
                pushIfNotAvailable(lhs, availableValues, masm);
                Register rhs = emitOperation(availableValues, opString, masm, tempRegs[1]);
                popIfNotAvailable(lhs, availableValues, masm);
                switch(op) {
                    case GotoOpCode.ADD:
                        masm.vaddpd(resultRegister, lhs, rhs);
                        break;
                    case GotoOpCode.MUL:
                        masm.vmulpd(resultRegister, lhs, rhs);
                        break;
                }
                return resultRegister;
            }
            else {
                Register fmaddResultReg = emitOperation(availableValues, opString, masm, resultRegister);
                pushIfNotAvailable(fmaddResultReg, availableValues, masm);

                Register tempReg0 = findAvailableTempRegister(tempRegs, fmaddResultReg);
                Register mulLhs = emitOperation(availableValues, opString, masm, tempReg0);
                pushIfNotAvailable(mulLhs, availableValues, masm);

                Register tempReg1 = findAvailableTempRegister(tempRegs, fmaddResultReg, tempReg0);
                Register mulRhs = emitOperation(availableValues, opString, masm, tempReg1);

                popIfNotAvailable(mulLhs, availableValues, masm);
                popIfNotAvailable(fmaddResultReg, availableValues, masm);

                masm.vfmadd231pd(fmaddResultReg, mulLhs, mulRhs);
                return fmaddResultReg;
            }
        }
        else if(opType.equals(GotoOpCode.MASKOP)) {
            Register lhs = emitOperation(availableValues, opString, masm, resultRegister);
            if(!registerEquals(lhs, resultRegister)) {
                masm.vmovupd(resultRegister, lhs);
            }
            pushIfNotAvailable(resultRegister, availableValues, masm);

            // Evaluate compare expression
            String cmpOp = opString.cutOff(opLength);
            Register cmpLhs = emitOperation(availableValues, opString, masm, tempRegs[0]);
            pushIfNotAvailable(cmpLhs, availableValues, masm);
            Register cmpRhs = emitOperation(availableValues, opString, masm, tempRegs[1]);
            popIfNotAvailable(cmpLhs, availableValues, masm);
            int cmpOperation = 0;
            switch(cmpOp) {
                case GotoOpCode.LT:
                    cmpOperation = 1;
                    break;
                case GotoOpCode.GT:
                    cmpOperation = 0x0e;
                    break;
            }
            masm.vcmppd(k1, cmpLhs, cmpRhs, cmpOperation);
            // Evaluate rhs
            Register rhsTempReg = findAvailableTempRegister(tempRegs, resultRegister);
            Register rhs = emitOperation(availableValues, opString, masm, rhsTempReg);

            // Do mask operation
            popIfNotAvailable(resultRegister, availableValues, masm);
            switch(op) {
                case GotoOpCode.MASKADD:
                    masm.vaddpd(resultRegister, resultRegister, rhs, k1);
                    break;
            }
            return resultRegister;
        }
        else if(opType.equals(GotoOpCode.CMPOP)) {
            Register lhs = emitOperation(availableValues, opString, masm, tempRegs[0]);
            pushIfNotAvailable(lhs, availableValues, masm);
            Register rhs = emitOperation(availableValues, opString, masm, tempRegs[1]);
            popIfNotAvailable(lhs, availableValues, masm);
            int cmpOperation = 0;
            switch(op) {
                case GotoOpCode.LT:
                    cmpOperation = 1;
                    break;
                case GotoOpCode.GT:
                    cmpOperation = 0x0e;
                    break;
            }
            masm.vcmppd(resultRegister, lhs, rhs, cmpOperation);
            return resultRegister;
        }
        else if(opType.equals(GotoOpCode.ARGOP)) {
            int argIndex;
            switch(op) {
                case GotoOpCode.A:
                    return availableValues.get("aBroadcast");
                case GotoOpCode.B:
                    return availableValues.get("bReg");
                case GotoOpCode.C:
                    return availableValues.get("cReg");
                case GotoOpCode.CONSTARG:
                    argIndex = Integer.parseInt(opString.cutOff(opLength), 2);
                    masm.vbroadcastsd(resultRegister, new AMD64Address(rsp, stackOffsetToConstArgs+(constArgStackSlotSize*argIndex)));
                    return resultRegister;
                case GotoOpCode.VARIABLEARG:
                    argIndex = Integer.parseInt(opString.cutOff(opLength), 2);
                    int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);
                    if(varArgProperties[argIndex] == 2) {
                        varArgOffset += 64*computeJIndex;
                    }
                    else if(varArgProperties[argIndex] == 1) {
                        // Todo find correct broadcast vector when index is i
                        varArgOffset += 0;
                    }
                    masm.vmovupd(resultRegister, new AMD64Address(rsp, varArgOffset));
                    return resultRegister;
            }
        }
        return resultRegister;
    }

    public void subIter(int offset, int prefetchDistance, AMD64MacroAssembler masm, Register aBroadcast, Register[] bRegs, Register[][] cRegs, Register[] aTempArrayAddressRegs, Map<String, Register> generalPurposeRegisters) {
        Register loopIndex = generalPurposeRegisters.get("loopIndex");
        Register tempArrayAddressReg = generalPurposeRegisters.get("tempArrayAddressReg");
        Register jPos = generalPurposeRegisters.get("jPos");
        Register tempArrPtr = generalPurposeRegisters.get("tempArrPtr");

        AMD64Address aAddress, bAddress;

        bAddress = new AMD64Address(loopIndex, OBJECT_ARRAY_BASE_OFFSET+(offset*8));
        masm.movq(tempArrayAddressReg, bAddress);

        for(int j = 0; j < bLength; j++) {
            bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
            masm.vmovupd(bRegs[j], bAddress);
        }

        if(prefetchDistance > 0) {
            bAddress = new AMD64Address(loopIndex, OBJECT_ARRAY_BASE_OFFSET+(offset*8)+(prefetchDistance*8));
            masm.movq(tempArrayAddressReg, bAddress);

            for(int j = 0; j < bLength; j++) {
                bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.prefetcht0(bAddress);
            }
        }

        Map<String, Register> availableValues = new HashMap<String, Register>();

        for(int i = 0; i < aLength; i++) {
            if(i < aTempArrayAddressNumLimit) {
                //aAddress = new AMD64Address(aTempArrayAddressRegs[i], loopIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(offset*8));
                aAddress = new AMD64Address(aTempArrayAddressRegs[i], loopIndex, AMD64Address.Scale.Times1, DOUBLE_ARRAY_BASE_OFFSET+(offset*8));
            }
            else {
                aAddress = new AMD64Address(tempArrPtr, loopIndex, AMD64Address.Scale.Times1, DOUBLE_ARRAY_BASE_OFFSET+(offset*8));
            }
            masm.vbroadcastsd(aBroadcast, aAddress);
            for(int j = 0; j < bLength; j++) {
                computeIIndex = i;
                computeJIndex = j;
                ChangeableString opString = new ChangeableString(opStringRaw);
                availableValues.put("cReg", cRegs[i][j]);
                availableValues.put("aBroadcast", aBroadcast);
                availableValues.put("bReg", bRegs[j]);
                emitOperation(availableValues, opString, masm, cRegs[i][j]);
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
        ExprDag exprDag = new ExprDag(new ChangeableString(opStringRaw), debugLog);
        ExprDag.printDAG(debugLog, exprDag.getRootNode());

        int registerIndex = 0;

        Register arrsPtr = asRegister(arrsValue);
        Register tempArrPtr = asRegister(tempArrPtrValue);

        Register kPanelSize = asRegister(kPanelSizeValue);
        Register iPos = asRegister(iValue);
        Register kPos = asRegister(kValue);
        Register jPos = asRegister(jValue);

        // Make sure that iPos is last!
        Register useAsAddressRegs[] = new Register[]{arrsPtr, kPos, r15, kPanelSize, iPos};
        int kPanelSizeIndexFromBehind = 0;
        for(int i = 0; i < useAsAddressRegs.length; i++) {
            if(useAsAddressRegs[i] == kPanelSize) {
                kPanelSizeIndexFromBehind = i;
            }
        }
        kPanelSizeIndexFromBehind = useAsAddressRegs.length - kPanelSizeIndexFromBehind - 1;

        aTempArrayAddressNumLimit = aLength < remainingRegisterNum ? aLength : remainingRegisterNum+useAsAddressRegs.length;
        Register aTempArrayAddressRegs[] = new Register[aTempArrayAddressNumLimit];
        for(int i = 0; i < aTempArrayAddressNumLimit; i++) {
            if(i < remainingRegisterNum) {
                aTempArrayAddressRegs[i] = asRegister(remainingRegValues[i]);
            }
            else {
                aTempArrayAddressRegs[i] = findRegister(useAsAddressRegs[i-remainingRegisterNum], cpuRegisters);
            }
        }

        // Declare SIMD registers
        Register aBroadcast = xmmRegistersAVX512[registerIndex++];
        Register bRegs[] = new Register[bLength];
        for(int i = 0; i < bLength; i++) {
            bRegs[i] = xmmRegistersAVX512[registerIndex++];
        }
        Register cRegs[][] = new Register[aLength][bLength];
        for(int i = 0; i < aLength; i++) {
            for(int j = 0; j < bLength; j++) {
                cRegs[i][j] = xmmRegistersAVX512[registerIndex++];
            }
        }

        tempRegs = new Register[3];
        tempRegs[0] = xmmRegistersAVX512[registerIndex++];
        tempRegs[1] = xmmRegistersAVX512[registerIndex++];
        tempRegs[2] = xmmRegistersAVX512[registerIndex++];

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
                variableArgsStackOffsets.put(i, varArgsStackSize);
                masm.movq(tempArrayAddressReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*i));
                masm.vmovupd(tempRegs[0], new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+64));
                masm.subq(rsp, 64);
                masm.vmovupd(new AMD64Address(rsp), tempRegs[0]);
                varArgsStackSize += 64;

                masm.vmovupd(tempRegs[0], new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET));
                masm.subq(rsp, 64);
                masm.vmovupd(new AMD64Address(rsp), tempRegs[0]);
                varArgsStackSize += 64;
            }
            else if(varArgProperties[i] == 1) {
                // Todo: push 12 broadcasts to stack
                variableArgsStackOffsets.put(i, varArgsStackSize);
                masm.movq(tempArrayAddressReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*i));
                masm.vbroadcastsd(tempRegs[0], new AMD64Address(tempArrayAddressReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET));
                masm.subq(rsp, 64);
                masm.vmovupd(new AMD64Address(rsp), tempRegs[0]);
                varArgsStackSize += 64;
            }
        }

        // Push Constant arguments in reverse order
        constArgsStackSize = 0;
        for(int i = constArgs.length-1; i >=0; i--) {
            masm.movq(tempArrPtr, Double.doubleToLongBits(constArgs[i]));
            masm.subq(rsp, constArgStackSlotSize);
            masm.movq(new AMD64Address(rsp), tempArrPtr);
            constArgsStackSize += constArgStackSlotSize;
        }

        masm.movq(tempArrPtr, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+16));

        for(int i = 0; i < aLength; i++) {
            resultAddress = new AMD64Address(tempArrPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(tempArrayAddressReg, resultAddress);
            for(int j = 0; j < bLength; j++) {
                resultAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(cRegs[i][j], resultAddress);
            }
        }

        // Store pointer to B in temporary register and push to stack
        masm.movq(tempArrPtr, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+8));

        masm.leaq(loopIndex, new AMD64Address(tempArrPtr, kPos, OBJECT_ARRAY_INDEX_SCALE, 0));
        masm.leaq(kPanelSize, new AMD64Address(loopIndex, kPanelSize, OBJECT_ARRAY_INDEX_SCALE, 0));

        masm.push(loopIndex);
        masm.movq(loopIndex, tempArrPtr);

        // Push registers to be used for storing addresses of A on stack
        for(int i = 0; i < useAsAddressRegs.length; i++) {
            masm.push(useAsAddressRegs[i]);
        }

        // Load A
        masm.movl(tempArrayAddressGeneralReg, 0);
        masm.movq(tempArrPtr, new AMD64Address(arrsPtr, tempArrayAddressGeneralReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));

        // Push Addresses of A that are not savable on a register on stack first, so register iPos can be pushed on stack last
        int numOfAAddressOnStack = 0;
        for(int i = aTempArrayAddressNumLimit; i < aLength; i++) {
            aAddress = new AMD64Address(tempArrPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(tempArrayAddressReg, aAddress);
            //AMD64Assembler.VexMoveOp.VMOVQ.emit(masm, AVXSize.XMM, tempRegs[0], tempArrayAddressReg);
            masm.subq(tempArrayAddressReg, loopIndex);
            masm.push(tempArrayAddressReg);
            //numOfAAddressOnStack++;

        }
        for(int i = 0; i < aTempArrayAddressNumLimit; i++) {
            aAddress = new AMD64Address(tempArrPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(aTempArrayAddressRegs[i], aAddress);
            masm.subq(aTempArrayAddressRegs[i], loopIndex);
        }

        // Calculate offset to constant arguments
        stackOffsetToConstArgs = /*numOfAAddressOnStack*8 +*/useAsAddressRegs.length*8 + 8;

        //Load B
        //masm.movq(tempArrPtr, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(useAsAddressRegs.length*8)));
        masm.pop(tempArrPtr);
        masm.movq(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(useAsAddressRegs.length*8)));

        int prefetchDistance = 4;

        int mult = 8;

        masm.subq(new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)), prefetchDistance*mult);

        Label loopLabel = new Label();

        Map<String, Register> generalPurposeRegisters = new HashMap<String, Register>();
        generalPurposeRegisters.put("loopIndex", loopIndex);
        generalPurposeRegisters.put("jPos", jPos);
        generalPurposeRegisters.put("tempArrPtr", tempArrPtr);
        generalPurposeRegisters.put("tempArrayAddressReg", tempArrayAddressReg);

        int unrollFactor = 2;

        // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
        masm.bind(loopLabel);
        for(int i = 0; i < unrollFactor; i++) {
            subIter(i, prefetchDistance, masm, aBroadcast, bRegs, cRegs, aTempArrayAddressRegs, generalPurposeRegisters);
        }
        //subIter(0, prefetchDistance, masm, aBroadcast, bRegs, cRegs, aTempArrayAddressRegs, generalPurposeRegisters);
        masm.addq(loopIndex, unrollFactor*mult);
        masm.cmpq(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)));
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        masm.addq(new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)), prefetchDistance*mult);

        loopLabel = new Label();
        // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
        masm.bind(loopLabel);
        subIter(0, 0, masm, aBroadcast, bRegs, cRegs, aTempArrayAddressRegs, generalPurposeRegisters);
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
        masm.pop(tempArrPtr);

        // Pop const arguments
        masm.addq(rsp, constArgsStackSize);

        // Pop variable arguments
        masm.addq(rsp, varArgsStackSize);

        // Restore original value of kPanelSize
        masm.pop(kPanelSize);

        // Get pointer to result array
        masm.movl(loopIndex, 0);
        masm.movq(tempArrPtr, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+16));

        // Store partial results in result array
        for(int i = 0; i < aLength; i++) {
            resultAddress = new AMD64Address(tempArrPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(tempArrayAddressReg, resultAddress);
            for(int j = 0; j < bLength; j++) {
                resultAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(resultAddress, cRegs[i][j]);
            }
        }
        debugLog.close();
    }
}
