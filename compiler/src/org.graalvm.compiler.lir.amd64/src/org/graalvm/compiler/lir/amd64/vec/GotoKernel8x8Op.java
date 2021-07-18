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

@Opcode("GOTOKERNEL8X8")
public final class GotoKernel8x8Op extends AMD64LIRInstruction {
    public static final LIRInstructionClass<GotoKernel8x8Op> TYPE = LIRInstructionClass.create(GotoKernel8x8Op.class);

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

    @Alive({REG}) private Value aValue;
    @Alive({REG}) private Value bValue;
    @Use({REG}) private Value resultValue;

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

    public GotoKernel8x8Op(LIRGeneratorTool tool, Value a, Value b, Value result, Value kPanelSize,
                                    Value i, Value k, Value j, long[] calc) {
        super(TYPE);

        DOUBLE_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Double);
        DOUBLE_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Double)));

        OBJECT_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Object);
        OBJECT_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Object)));

        this.calcArr = calc;

        aValue = a;
        bValue = b;
        resultValue = result;
        kPanelSizeValue = kPanelSize;
        iValue = i;
        kValue = k;
        jValue = j;

        aLength = 12;
        bLength = 16/8;

        remainingRegisterNum = 5;
        aTempArrayAddressNumLimit = aLength < remainingRegisterNum ? aLength : remainingRegisterNum+5;

        loopIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        tempArrayAddressRegValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        remainingRegValues = new Value[remainingRegisterNum];
        for(int index = 0; index < remainingRegisterNum; index++) {
            remainingRegValues[index] = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        }
    }

    public void emitOperation(Register cReg, Register aBroadcast, Register bReg, ChangeableString opString, AMD64MacroAssembler masm, Register[] tempRegs) {
        String op = opString.toString().substring(0, 3);
        // Assume that results are added to cReg (for the moment)
        //opString = opString.substring(3, opString.length());
        opString.changeTo(opString.toString().substring(3, opString.toString().length()));
        if(op.equals("001")) {  //Multiplication
            Register lhs = getOperationRegister(cReg, aBroadcast, bReg, opString, masm, tempRegs[0]);
            Register rhs = getOperationRegister(cReg, aBroadcast, bReg, opString, masm, tempRegs[1]);
            masm.vfmadd231pd(cReg, lhs, rhs);
        }

    }

    public Register getOperationRegister(Register cReg, Register aBroadcast, Register bReg, ChangeableString opString, AMD64MacroAssembler masm, Register resultRegister) {
        String op = opString.toString().substring(0, 3);
        opString.changeTo(opString.toString().substring(3, opString.toString().length()));
        if(op.equals("101")) {
            return aBroadcast;
        }
        else if(op.equals("110")) {
            return bReg;
        }
        return resultRegister;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        final Register[] amd64Regs = new Register[]{xmm0, xmm1, xmm2, xmm3, xmm4, xmm5, xmm6, xmm7, xmm8, xmm9,
                                                    xmm10, xmm11, xmm12, xmm13, xmm14, xmm15, xmm16, xmm17, xmm18, xmm19,
                                                    xmm20, xmm21, xmm22, xmm23, xmm24, xmm25, xmm26, xmm27, xmm28, xmm29,
                                                    xmm30, xmm31};
        int registerIndex = 0;

        Register aPtr = asRegister(aValue);
        Register bPtr = asRegister(bValue);
        Register resultPtr = asRegister(resultValue);

        Register kPanelSize = asRegister(kPanelSizeValue);
        Register iPos = asRegister(iValue);
        Register kPos = asRegister(kValue);
        Register jPos = asRegister(jValue);

        Register aBroadcast = amd64Regs[registerIndex++];
        Register aTempArrayAddressRegs[] = new Register[aTempArrayAddressNumLimit];
        for(int i = 0; i < aTempArrayAddressNumLimit; i++) {
            if(i < remainingRegisterNum) {
                aTempArrayAddressRegs[i] = asRegister(remainingRegValues[i]);
            }
            else if(i == remainingRegisterNum) {
                aTempArrayAddressRegs[i] = resultPtr;
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
            bRegs[i] = amd64Regs[registerIndex++];
        }

        Register cRegs[][] = new Register[aLength][bLength];
        for(int i = 0; i < aLength; i++) {
            for(int j = 0; j < bLength; j++) {
                cRegs[i][j] = amd64Regs[registerIndex++];
            }
        }

        Register tempRegs[] = new Register[2];
        tempRegs[0] = amd64Regs[registerIndex++];
        tempRegs[1] = amd64Regs[registerIndex++];

        Register loopIndex = asRegister(loopIndexValue);
        Register tempArrayAddressReg = asRegister(tempArrayAddressRegValue);

        AMD64Address resultAddress, aAddress, bAddress;

        for(int i = 0; i < aLength; i++) {
            resultAddress = new AMD64Address(resultPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(tempArrayAddressReg, resultAddress);
            for(int j = 0; j < bLength; j++) {
                resultAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(cRegs[i][j], resultAddress);
            }
        }
        masm.push(resultPtr);

        // Push value of kpos to stack
        masm.subq(rsp, 4);
        masm.movl(new AMD64Address(rsp), kPos);

        // Push value of r15 to stack
        masm.push(r15);

        // Push value of iPos to stack
        masm.subq(rsp, 4);
        masm.movl(new AMD64Address(rsp), iPos);

        // Initialize loop index
        masm.movl(loopIndex, kPos);
        masm.addl(kPanelSize, kPos);
        masm.subq(rsp, 4);
        masm.movl(new AMD64Address(rsp), kPanelSize);

        // Push Addresses of A that are not savable on a register on stack first, so register iPos can be pushed on stack last
        int numOfAAddressOnStack = 0;
        for(int i = aTempArrayAddressNumLimit; i < aLength; i++) {
            aAddress = new AMD64Address(aPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(tempArrayAddressReg, aAddress);
            masm.push(tempArrayAddressReg);
            numOfAAddressOnStack++;
        }
        for(int i = 0; i < aTempArrayAddressNumLimit; i++) {
            aAddress = new AMD64Address(aPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(aTempArrayAddressRegs[i], aAddress);
        }

        Label loopLabel = new Label();

        // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
        masm.bind(loopLabel);

        bAddress = new AMD64Address(bPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET);
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
        masm.cmpl(loopIndex, new AMD64Address(rsp, numOfAAddressOnStack*8));
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        for(int i = 0; i < numOfAAddressOnStack; i++) {
            masm.pop(tempArrayAddressReg);
        }

        // Reset kPanelSize register to (kPanelSize + kPos)
        masm.movl(kPanelSize, new AMD64Address(rsp));
        masm.addq(rsp, 4);

        // Restore iPos
        masm.movl(iPos, new AMD64Address(rsp));
        masm.addq(rsp, 4);

        // Restore r15
        masm.pop(r15);

        // Restore kPos
        masm.movl(kPos, new AMD64Address(rsp));
        masm.addq(rsp, 4);
        masm.subl(kPanelSize, kPos);

        // Restore resultPtr
        masm.pop(resultPtr);
        // Store partial results in result array
        for(int i = 0; i < aLength; i++) {
            resultAddress = new AMD64Address(resultPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(i*8));
            masm.movq(tempArrayAddressReg, resultAddress);
            for(int j = 0; j < bLength; j++) {
                resultAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
                masm.vmovupd(resultAddress, cRegs[i][j]);
            }
        }
    }
}
