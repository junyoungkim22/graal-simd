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

@Opcode("SIMDDOUBLEMULT")
public final class SimdDoubleMultOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<SimdDoubleMultOp> TYPE = LIRInstructionClass.create(SimdDoubleMultOp.class);

    private final int DOUBLE_ARRAY_BASE_OFFSET;
    private final Scale DOUBLE_ARRAY_INDEX_SCALE;

    @Use({REG}) private Value inputOffsetValue;
    @Use({REG}) private Value multValValue;
    @Alive({REG}) private Value inputPtr;
    @Alive({REG}) private Value outputPtr;
    @Temp({REG}) private Value inputValue;
    @Temp({REG}) private Value resultValue;

    @Temp({REG}) private Value broadcastMultValValue;

    public SimdDoubleMultOp(LIRGeneratorTool tool, Value inputOffset, Value multVal, Value input, Value output) {
        super(TYPE);
        DOUBLE_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Double);
        DOUBLE_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Double)));

        inputOffsetValue = inputOffset;
        multValValue = multVal;
        inputPtr = input;
        outputPtr = output;

        inputValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
        resultValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));

        // TODO: Make the constants alive outside the loop. Reuse registers if possible.
        broadcastMultValValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        Register inputOffset = asRegister(inputOffsetValue);
        Register multVal = asRegister(multValValue);
        Register input = asRegister(inputPtr);
        Register output = asRegister(outputPtr);

        Register inValues = asRegister(inputValue);
        Register result = asRegister(resultValue);

         // Load values from input.
        AMD64Address inputAddress = new AMD64Address(input, inputOffset, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vmovupd(inValues, inputAddress);

        AMD64Address outputAddress = new AMD64Address(output, inputOffset, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);

        // Make a vector of multVal
        masm.vbroadcastsd(result, multVal);

        // Store computation results in result
        masm.vfmadd213pd(result, inValues, outputAddress);

        // Store result to output
        masm.vmovupd(outputAddress, result);
    }
}
