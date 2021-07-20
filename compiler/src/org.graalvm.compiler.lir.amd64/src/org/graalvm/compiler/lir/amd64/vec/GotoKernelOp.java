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

import static jdk.vm.ci.amd64.AMD64.xmm0;
import static jdk.vm.ci.amd64.AMD64.xmm1;
import static jdk.vm.ci.amd64.AMD64.xmm2;
import static jdk.vm.ci.amd64.AMD64.xmm3;
import static jdk.vm.ci.amd64.AMD64.xmm4;
import static jdk.vm.ci.amd64.AMD64.xmm5;
import static jdk.vm.ci.amd64.AMD64.xmm6;
import static jdk.vm.ci.amd64.AMD64.xmm7;
import static jdk.vm.ci.amd64.AMD64.xmm8;
import static jdk.vm.ci.amd64.AMD64.xmm9;
import static jdk.vm.ci.amd64.AMD64.xmm10;
import static jdk.vm.ci.amd64.AMD64.xmm11;
import static jdk.vm.ci.amd64.AMD64.xmm12;
import static jdk.vm.ci.amd64.AMD64.xmm13;
import static jdk.vm.ci.amd64.AMD64.xmm14;
import static jdk.vm.ci.amd64.AMD64.xmm15;
import static jdk.vm.ci.amd64.AMD64.xmm16;
import static jdk.vm.ci.amd64.AMD64.xmm17;
import static jdk.vm.ci.amd64.AMD64.xmm18;
import static jdk.vm.ci.amd64.AMD64.xmm19;
import static jdk.vm.ci.amd64.AMD64.xmm20;
import static jdk.vm.ci.amd64.AMD64.xmm21;
import static jdk.vm.ci.amd64.AMD64.xmm22;
import static jdk.vm.ci.amd64.AMD64.xmm23;
import static jdk.vm.ci.amd64.AMD64.xmm24;
import static jdk.vm.ci.amd64.AMD64.xmm25;
import static jdk.vm.ci.amd64.AMD64.xmm26;
import static jdk.vm.ci.amd64.AMD64.xmm27;
import static jdk.vm.ci.amd64.AMD64.xmm28;
import static jdk.vm.ci.amd64.AMD64.xmm29;
import static jdk.vm.ci.amd64.AMD64.xmm30;
import static jdk.vm.ci.amd64.AMD64.xmm31;
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

    class ChangeableString {
        private String str;

        public ChangeableString(String str) {
            this.str = str;
        }

        public void changeTo(String newStr) {
            String s = newStr;
            this.str = s;
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

    final int aLength;
    final int bLength;
    final int remainingRegisterNum;
    final int aTempArrayAddressNumLimit;

    @Temp({REG}) private Value loopIndexValue;
    @Temp({REG}) private Value tempArrayAddressRegValue;
    @Temp({REG}) private Value[] remainingRegValues;

    public GotoKernelOp(LIRGeneratorTool tool, Value arrs, Value kPanelSize,
                                    Value i, Value k, Value j, int aLength, int bLength, long[] calc) {
        super(TYPE);

        DOUBLE_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Double);
        DOUBLE_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Double)));

        OBJECT_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Object);
        OBJECT_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Object)));

        this.calcArr = calc;

        arrsValue = arrs;
        tempArrPtrValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        kPanelSizeValue = kPanelSize;
        iValue = i;
        kValue = k;
        jValue = j;

        this.aLength = aLength;
        this.bLength = bLength/8;

        remainingRegisterNum = 6;
        aTempArrayAddressNumLimit = aLength < remainingRegisterNum ? aLength : remainingRegisterNum+5;

        loopIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        tempArrayAddressRegValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        remainingRegValues = new Value[remainingRegisterNum];
        for(int index = 0; index < remainingRegisterNum; index++) {
            remainingRegValues[index] = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        }
    }

    public void emitOperation(Register cReg, Register aBroadcast, Register bReg, ChangeableString opString, AMD64MacroAssembler masm, Register[] tempRegs) {
        String op = opString.toString().substring(0, 4);
        // Assume that results are added to cReg (for the moment)
        //opString = opString.substring(3, opString.length());
        opString.changeTo(opString.toString().substring(4, opString.toString().length()));
        if(op.equals(GotoOpCode.MUL.toString())) {  //Multiplication
            Register lhs = getOperationRegister(cReg, aBroadcast, bReg, opString, masm, tempRegs[0]);
            Register rhs = getOperationRegister(cReg, aBroadcast, bReg, opString, masm, tempRegs[1]);
            masm.vfmadd231pd(cReg, lhs, rhs);
        }

    }

    public Register getOperationRegister(Register cReg, Register aBroadcast, Register bReg, ChangeableString opString, AMD64MacroAssembler masm, Register resultRegister) {
        String op = opString.toString().substring(0, 4);
        opString.changeTo(opString.toString().substring(4, opString.toString().length()));
        if(op.equals(GotoOpCode.A.toString())) {
            return aBroadcast;
        }
        else if(op.equals(GotoOpCode.B.toString())) {
            return bReg;
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

        Register aBroadcast = xmmRegistersAVX512[registerIndex++];
        Register aTempArrayAddressRegs[] = new Register[aTempArrayAddressNumLimit];
        for(int i = 0; i < aTempArrayAddressNumLimit; i++) {
            if(i < remainingRegisterNum) {
                aTempArrayAddressRegs[i] = asRegister(remainingRegValues[i]);
            }
            else if(i == remainingRegisterNum) {
                aTempArrayAddressRegs[i] = arrsPtr;
            }
            else if(i == remainingRegisterNum+1) {
                for(int j = 0; j < cpuRegisters.length; j++) {
                    if(kPanelSize.name.equals(cpuRegisters[j].name)) {
                        aTempArrayAddressRegs[i] = cpuRegisters[j];
                        break;
                    }
                }
            }
            else if(i == remainingRegisterNum+2) {
                aTempArrayAddressRegs[i] = r15;
            }
            else if(i == remainingRegisterNum+3) {
                for(int j = 0; j < cpuRegisters.length; j++) {
                    if(kPos.name.equals(cpuRegisters[j].name)) {
                        aTempArrayAddressRegs[i] = cpuRegisters[j];
                        break;
                    }
                }
            }
            else if(i == remainingRegisterNum+4) {
                for(int j = 0; j < cpuRegisters.length; j++) {
                    if(iPos.name.equals(cpuRegisters[j].name)) {
                        aTempArrayAddressRegs[i] = cpuRegisters[j];
                        break;
                    }
                }
            }
        }

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
        Register tempArrayAddressGeneralReg = null;
        for(int i = 0; i < cpuRegisters.length; i++) {
            if(tempArrayAddressReg.name.equals(cpuRegisters[i].name)) {
                tempArrayAddressGeneralReg = cpuRegisters[i];
                break;
            }
        }

        AMD64Address resultAddress, aAddress, bAddress;

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

        // Push value of kpos to stack
        masm.subq(rsp, 4);
        masm.movl(new AMD64Address(rsp), kPos);

        // Push value of r15 to stack
        masm.push(r15);

        // Push value of iPos to stack
        masm.subq(rsp, 4);
        masm.movl(new AMD64Address(rsp), iPos);

        // Push pointer to B to stack
        masm.movq(tempArrPtr, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+8));
        masm.push(tempArrPtr);

        // Initialize loop index + push kPanelSize to stack
        masm.movl(loopIndex, kPos);
        masm.addl(kPanelSize, kPos);
        masm.subq(rsp, 4);
        masm.movl(new AMD64Address(rsp), kPanelSize);

        // push arrsPtr to stack
        masm.push(arrsPtr);

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

        //Load B
        masm.movq(tempArrPtr, new AMD64Address(rsp, (numOfAAddressOnStack*8)+8+4));

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
                emitOperation(cRegs[i][j], aBroadcast, bRegs[j], opString, masm, tempRegs);
                /*
                if(opString.equals("001101110")) {
                    //masm.vfmadd231pd(cRegs[i][j], bRegs[j], aBroadcast);
                }
                */
            }
        }

        masm.addl(loopIndex, 1);
        masm.cmpl(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+8));
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        for(int i = 0; i < numOfAAddressOnStack; i++) {
            masm.pop(tempArrayAddressReg);
        }

        // Restore arrsPtr
        masm.pop(arrsPtr);

        // Reset kPanelSize register to (kPanelSize + kPos)
        masm.movl(kPanelSize, new AMD64Address(rsp));
        masm.addq(rsp, 4);

        // Pop B
        masm.pop(tempArrPtr);

        // Restore iPos
        masm.movl(iPos, new AMD64Address(rsp));
        masm.addq(rsp, 4);

        // Restore r15
        masm.pop(r15);

        // Restore kPos
        masm.movl(kPos, new AMD64Address(rsp));
        masm.addq(rsp, 4);

        // Restore original value of kPanelSize
        masm.subl(kPanelSize, kPos);

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
