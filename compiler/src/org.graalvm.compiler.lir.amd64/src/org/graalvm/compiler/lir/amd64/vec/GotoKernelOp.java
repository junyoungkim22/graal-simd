package org.graalvm.compiler.lir.amd64.vec;

import static jdk.vm.ci.amd64.AMD64.k0;
import static jdk.vm.ci.amd64.AMD64.k1;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.asStackSlot;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.STACK;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.UNINITIALIZED;

import java.util.Objects;

import org.graalvm.compiler.asm.Label;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Address.Scale;
import org.graalvm.compiler.asm.amd64.AMD64Assembler;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
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

import org.graalvm.compiler.lir.amd64.vec.GotoOpCode;

@Opcode("GOTOKERNEL8X8")
public final class GotoKernelOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<GotoKernelOp> TYPE = LIRInstructionClass.create(GotoKernelOp.class);



    private final int DOUBLE_ARRAY_BASE_OFFSET;
    private final Scale DOUBLE_ARRAY_INDEX_SCALE;

    private final int OBJECT_ARRAY_BASE_OFFSET;
    private final Scale OBJECT_ARRAY_INDEX_SCALE;

    private final long[] calcArr;
    private final double[] constArgs;

    class ChangeableString {
        private String str;

        public ChangeableString(String str) {
            this.str = str;
        }

        public String cutOff(int length) {
            String ret = str.substring(0, length);
            this.str = str.substring(length, str.length());
            return ret;
        }

        public String toString() {
            return str;
        }
    }

    @Alive({REG}) private Value arrsValue;
    @Temp({REG}) private Value tempArrPtrValue;

    @Temp({REG}) private Value kPanelSizeValue;
    @Temp({REG}) private Value iValue;
    @Temp({REG}) private Value kValue;
    @Temp({REG}) private Value jValue;

    int stackOffsetToConstArgs;

    final int aLength;
    final int bLength;
    final int remainingRegisterNum;

    @Temp({REG}) private Value loopIndexValue;
    @Temp({REG}) private Value tempArrayAddressRegValue;
    @Temp({REG}) private Value[] remainingRegValues;

    public GotoKernelOp(LIRGeneratorTool tool, Value arrs, Value kPanelSize,
                                    Value i, Value k, Value j, int aLength, int bLength, long[] calc, double[] constArgs) {
        super(TYPE);

        DOUBLE_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Double);
        DOUBLE_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Double)));

        OBJECT_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Object);
        OBJECT_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Object)));

        this.calcArr = calc;
        this.constArgs = constArgs;

        arrsValue = arrs;
        tempArrPtrValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        kPanelSizeValue = kPanelSize;
        iValue = i;
        kValue = k;
        jValue = j;

        this.aLength = aLength;
        this.bLength = bLength/8;

        remainingRegisterNum = 6;

        loopIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        tempArrayAddressRegValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        remainingRegValues = new Value[remainingRegisterNum];
        for(int index = 0; index < remainingRegisterNum; index++) {
            remainingRegValues[index] = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        }
    }

    private static Register findRegister(Register toFind, Register[] registerArray) {
        for(int i = 0; i < registerArray.length; i++) {
            if(toFind.name.equals(registerArray[i].name)) {
                return registerArray[i];
            }
        }
        return null;
    }

    // Emit operation corresponding to opString, store in resultRegister (if applicable), and return SIMD register containing result
    public Register emitOperation(Register cReg, Register aBroadcast, Register bReg, ChangeableString opString,
                        AMD64MacroAssembler masm, Register[] tempRegs, Register resultRegister) {
        String op = opString.cutOff(4);
        if(op.equals(GotoOpCode.FMADD.toString())) {
            Register fmaddResultReg = emitOperation(cReg, aBroadcast, bReg, opString, masm, tempRegs, resultRegister);
            Register mulLhs = emitOperation(cReg, aBroadcast, bReg, opString, masm, tempRegs, tempRegs[0]);
            Register mulRhs = emitOperation(cReg, aBroadcast, bReg, opString, masm, tempRegs, tempRegs[1]);
            masm.vfmadd231pd(fmaddResultReg, mulLhs, mulRhs);
            return fmaddResultReg;
        }
        else if(op.equals(GotoOpCode.A.toString())) {
            return aBroadcast;
        }
        else if(op.equals(GotoOpCode.B.toString())) {
            return bReg;
        }
        else if(op.equals(GotoOpCode.C.toString())) {
            return cReg;
        }
        return resultRegister;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
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

        int aTempArrayAddressNumLimit = aLength < remainingRegisterNum ? aLength : remainingRegisterNum+useAsAddressRegs.length;
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

        Register tempRegs[] = new Register[2];
        tempRegs[0] = xmmRegistersAVX512[registerIndex++];
        tempRegs[1] = xmmRegistersAVX512[registerIndex++];

        Register loopIndex = asRegister(loopIndexValue);
        Register tempArrayAddressReg = asRegister(tempArrayAddressRegValue);
        Register tempArrayAddressGeneralReg = findRegister(tempArrayAddressReg, cpuRegisters);

        AMD64Address resultAddress, aAddress, bAddress;

        // Push Constant arguments in reverse order
        for(int i = constArgs.length-1; i >=0; i--) {
            masm.movq(tempArrPtr, Double.doubleToLongBits(constArgs[i]));
            masm.push(tempArrPtr);
        }

        masm.movl(loopIndex, 0);
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
        masm.push(tempArrPtr);

        // Initialize loop index and kPanelSize to loop end
        masm.movl(loopIndex, kPos);
        masm.addl(kPanelSize, kPos);

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
            masm.push(tempArrayAddressReg);
            numOfAAddressOnStack++;
        }
        for(int i = 0; i < aTempArrayAddressNumLimit; i++) {
            aAddress = new AMD64Address(tempArrPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(aTempArrayAddressRegs[i], aAddress);
        }

        // Calculate offset to constant arguments
        stackOffsetToConstArgs = numOfAAddressOnStack*8 + useAsAddressRegs.length*8 + 8;

        //Load B
        masm.movq(tempArrPtr, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(useAsAddressRegs.length*8)));

        Label loopLabel = new Label();

        // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
        masm.bind(loopLabel);

        bAddress = new AMD64Address(tempArrPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET);
        masm.movq(tempArrayAddressReg, bAddress);

        for(int j = 0; j < bLength; j++) {
            bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
            masm.vmovupd(bRegs[j], bAddress);
        }

        for(int i = 0; i < aLength; i++) {
            if(i < aTempArrayAddressNumLimit) {
                aAddress = new AMD64Address(aTempArrayAddressRegs[i], loopIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
            }
            else {
                aAddress = new AMD64Address(rsp, (aLength-i-1)*8);
                masm.movq(tempArrayAddressReg, aAddress);
                aAddress = new AMD64Address(tempArrayAddressReg, loopIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
            }
            masm.vbroadcastsd(aBroadcast, aAddress);
            for(int j = 0; j < bLength; j++) {
                String opStringRaw = Long.toBinaryString(calcArr[0]);
                opStringRaw = opStringRaw.substring(1, opStringRaw.length());  //Remove first digit (which is 1)
                ChangeableString opString = new ChangeableString(opStringRaw);
                //emitOperation(cRegs[i][j], aBroadcast, bRegs[j], opString, masm, tempRegs);
                emitOperation(cRegs[i][j], aBroadcast, bRegs[j], opString, masm, tempRegs, cRegs[i][j]);
            }
        }

        masm.addl(loopIndex, 1);
        masm.cmpl(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)));
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
        for(int i = 0; i < constArgs.length; i++) {
            masm.pop(tempArrPtr);
        }

        // Restore original value of kPanelSize
        masm.subl(kPanelSize, kPos);

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
    }
}
