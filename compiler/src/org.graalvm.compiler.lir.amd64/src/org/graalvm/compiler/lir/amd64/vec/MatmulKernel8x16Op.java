package org.graalvm.compiler.lir.amd64.vec;

import static jdk.vm.ci.amd64.AMD64.k0;
import static jdk.vm.ci.amd64.AMD64.k1;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

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
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;

@Opcode("MATMULKERNEL8X16")
public final class MatmulKernel8x16Op extends AMD64LIRInstruction {
    public static final LIRInstructionClass<MatmulKernel8x16Op> TYPE = LIRInstructionClass.create(MatmulKernel8x16Op.class);

    private final int DOUBLE_ARRAY_BASE_OFFSET;
    private final Scale DOUBLE_ARRAY_INDEX_SCALE;

    private final int INT_ARRAY_BASE_OFFSET;
    private final Scale INT_ARRAY_INDEX_SCALE;

    //private final int OBJECT_ARRAY_BASE_OFFSET;
    //private final Scale OBJECT_ARRAY_INDEX_SCALE;

    @Alive({REG}) private Value aValue;
    @Alive({REG}) private Value bValue;
    @Alive({REG}) private Value resultValue;
    @Alive({REG}) private Value constantsValue;
    @Temp({REG}) private Value iValue;
    @Temp({REG}) private Value kValue;
    @Temp({REG}) private Value jValue;

    @Temp({REG}) private Value kPanelSizeValue;
    @Temp({REG}) private Value lenValue;

    @Temp({REG}) private Value aTempValue;
    @Temp({REG}) private Value aTempBroadcastValue;
    @Temp({REG}) private Value b0Value;
    @Temp({REG}) private Value b1Value;
    
    @Temp({REG}) private Value c00Val;
    @Temp({REG}) private Value c10Val;
    @Temp({REG}) private Value c20Val;
    @Temp({REG}) private Value c30Val;
    @Temp({REG}) private Value c40Val;
    @Temp({REG}) private Value c50Val;
    @Temp({REG}) private Value c60Val;
    @Temp({REG}) private Value c70Val;
    /*
    @Temp({REG}) private Value c00Val;
    @Temp({REG}) private Value c01Val;
    @Temp({REG}) private Value c10Val;
    @Temp({REG}) private Value c11Val;
    @Temp({REG}) private Value c20Val;
    @Temp({REG}) private Value c21Val;
    @Temp({REG}) private Value c30Val;
    @Temp({REG}) private Value c31Val;
    @Temp({REG}) private Value c40Val;
    @Temp({REG}) private Value c41Val;
    @Temp({REG}) private Value c50Val;
    @Temp({REG}) private Value c51Val;
    @Temp({REG}) private Value c60Val;
    @Temp({REG}) private Value c61Val;
    @Temp({REG}) private Value c70Val;
    @Temp({REG}) private Value c71Val;
    */

    @Temp({REG}) private Value loopIndexValue;
    @Temp({REG}) private Value loopEndValue;
    @Temp({REG}) private Value indexValue;
    //@Temp({REG}) private Value bColIndexValue;
    //@Temp({REG}) private Value tempArrayAddressRegValue;



    public MatmulKernel8x16Op(LIRGeneratorTool tool, Value a, Value b, Value result, Value constants,
                                    Value i, Value k, Value j) {
        super(TYPE);

        DOUBLE_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Double);
        DOUBLE_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Double)));

        INT_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Int);
        INT_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Int)));

        //OBJECT_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Object);
        //OBJECT_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Object)));

        aValue = a;
        bValue = b;
        resultValue = result;
        constantsValue = constants;
        //kPanelSizeValue = k;
        iValue = i;
        kValue = k;
        jValue = j;

        kPanelSizeValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        lenValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));

        aTempValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        aTempBroadcastValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        b0Value = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        b1Value = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));

        c00Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c10Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c20Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c30Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c40Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c50Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c60Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c70Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        /*
        c00Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c01Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c10Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c11Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c20Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c21Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c30Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c31Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c40Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c41Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c50Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c51Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c60Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c61Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c70Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        c71Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        */

        loopIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        loopEndValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        indexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        //bColIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        //tempArrayAddressRegValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));

    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        Register aPtr = asRegister(aValue);
        Register bPtr = asRegister(bValue);
        Register resultPtr = asRegister(resultValue);

        Register constantsPtr = asRegister(constantsValue);
        Register iPos = asRegister(iValue);
        Register kPos = asRegister(kValue);
        Register jPos = asRegister(jValue);

        Register kPanelSize = asRegister(kPanelSizeValue);
        Register len = asRegister(lenValue);

        Register aTemp = asRegister(aTempValue);
        Register aTempBroadcast = asRegister(aTempBroadcastValue);
        Register b0 = asRegister(b0Value);
        Register b1 = asRegister(b1Value);

        Register c00 = asRegister(c00Val);
        Register c10 = asRegister(c10Val);
        Register c20 = asRegister(c20Val);
        Register c30 = asRegister(c30Val);
        Register c40 = asRegister(c40Val);
        Register c50 = asRegister(c50Val);
        Register c60 = asRegister(c60Val);
        Register c70 = asRegister(c70Val);

        masm.vpxorq(c00, c00, c00);
        masm.vpxorq(c10, c10, c10);
        masm.vpxorq(c20, c20, c20);
        masm.vpxorq(c30, c30, c30);
        masm.vpxorq(c40, c40, c40);
        masm.vpxorq(c50, c50, c50);
        masm.vpxorq(c60, c60, c60);
        masm.vpxorq(c70, c70, c70);

        Register loopIndex = asRegister(loopIndexValue);
        Register loopEnd = asRegister(loopEndValue);
        Register index = asRegister(indexValue);
        //Register bColIndex = asRegister(bColIndexValue);
        //Register tempArrayAddressReg = asRegister(tempArrayAddressRegValue);

        masm.movl(index, 0);
        AMD64Address resultAddress = new AMD64Address(constantsPtr, index, INT_ARRAY_INDEX_SCALE, INT_ARRAY_BASE_OFFSET);
        masm.movl(kPanelSize, resultAddress);

        masm.movl(index, 1);
        resultAddress = new AMD64Address(constantsPtr, index, INT_ARRAY_INDEX_SCALE, INT_ARRAY_BASE_OFFSET);
        masm.movl(len, resultAddress);

        // Initialize c registers with initial values from result

        /*
        masm.movl(index, iPos);
        masm.imulq(index, len);
        masm.addl(index, jPos);

        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(c00, resultAddress);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(c10, resultAddress);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(c20, resultAddress);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(c30, resultAddress);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(c40, resultAddress);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(c50, resultAddress);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(c60, resultAddress);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(c70, resultAddress);
        */

        // Initialize loop index
        masm.movl(loopIndex, kPos);
        masm.movl(loopEnd, kPos);
        masm.addl(loopEnd, kPanelSize);

        Label loopLabel = new Label();

        // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
        masm.bind(loopLabel);

        masm.movl(index, loopIndex);
        masm.imulq(index, len);
        masm.addl(index, jPos);
        AMD64Address bAddress = new AMD64Address(bPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(b0, bAddress);

        masm.movl(index, iPos);
        masm.imulq(index, len);
        masm.addl(index, loopIndex);
        AMD64Address aAddress = new AMD64Address(aPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.movq(aTemp, aAddress);
        masm.vpbroadcastq(aTempBroadcast, aTemp);
        masm.vfmadd231pd(c00, b0, aTempBroadcast);

        masm.addl(index, len);
        aAddress = new AMD64Address(aPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.movq(aTemp, aAddress);
        masm.vpbroadcastq(aTempBroadcast, aTemp);
        masm.vfmadd231pd(c10, b0, aTempBroadcast);

        masm.addl(index, len);
        aAddress = new AMD64Address(aPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.movq(aTemp, aAddress);
        masm.vpbroadcastq(aTempBroadcast, aTemp);
        masm.vfmadd231pd(c20, b0, aTempBroadcast);

        masm.addl(index, len);
        aAddress = new AMD64Address(aPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.movq(aTemp, aAddress);
        masm.vpbroadcastq(aTempBroadcast, aTemp);
        masm.vfmadd231pd(c30, b0, aTempBroadcast);

        masm.addl(index, len);
        aAddress = new AMD64Address(aPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.movq(aTemp, aAddress);
        masm.vpbroadcastq(aTempBroadcast, aTemp);
        masm.vfmadd231pd(c40, b0, aTempBroadcast);

        masm.addl(index, len);
        aAddress = new AMD64Address(aPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.movq(aTemp, aAddress);
        masm.vpbroadcastq(aTempBroadcast, aTemp);
        masm.vfmadd231pd(c50, b0, aTempBroadcast);

        masm.addl(index, len);
        aAddress = new AMD64Address(aPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.movq(aTemp, aAddress);
        masm.vpbroadcastq(aTempBroadcast, aTemp);
        masm.vfmadd231pd(c60, b0, aTempBroadcast);

        masm.addl(index, len);
        aAddress = new AMD64Address(aPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.movq(aTemp, aAddress);
        masm.vpbroadcastq(aTempBroadcast, aTemp);
        masm.vfmadd231pd(c70, b0, aTempBroadcast);

        masm.addl(loopIndex, 1);
        masm.cmpl(loopIndex, loopEnd);
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

        // Store partial results in result array

        masm.movl(index, iPos);
        masm.imulq(index, len);
        masm.addl(index, jPos);

        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vaddpd(c00, c00, resultAddress);
        masm.vmovupd(resultAddress, c00);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vaddpd(c10, c10, resultAddress);
        masm.vmovupd(resultAddress, c10);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vaddpd(c20, c20, resultAddress);
        masm.vmovupd(resultAddress, c20);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vaddpd(c30, c30, resultAddress);
        masm.vmovupd(resultAddress, c30);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vaddpd(c40, c40, resultAddress);
        masm.vmovupd(resultAddress, c40);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vaddpd(c50, c50, resultAddress);
        masm.vmovupd(resultAddress, c50);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vaddpd(c60, c60, resultAddress);
        masm.vmovupd(resultAddress, c60);

        masm.addl(index, len);
        resultAddress = new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vaddpd(c70, c70, resultAddress);
        masm.vmovupd(resultAddress, c70);

        /*
        Register length = asRegister(lengthValue);
        Register multVal = asRegister(multValValue);
        Register input = asRegister(inputPtr);
        Register output = asRegister(outputPtr);

        Register temp = asRegister(tempValue);
        Register loopIndex = asRegister(loopIndexValue);
        Register broadcastMultVal = asRegister(broadcastMultValValue);

        Label loopLabel = new Label();

        // Make a vector of multVal
        masm.vbroadcastsd(broadcastMultVal, multVal);

        // Initialize loop index
        masm.movl(loopIndex, 0);

        // Start iterating through arrays and update output array
        masm.bind(loopLabel);
        AMD64Address inputAddress = new AMD64Address(input, loopIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(temp, inputAddress);
        AMD64Address outputAddress = new AMD64Address(output, loopIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        // temp = (temp * multValVector) + (values in outputAddress)
        masm.vfmadd213pd(temp, broadcastMultVal, outputAddress);
        // Store result to output
        masm.vmovupd(outputAddress, temp);
        // Increment loopIndex by 8
        masm.addl(loopIndex, 8);
        masm.cmpl(loopIndex, length);
        masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);
        */
    }
}
