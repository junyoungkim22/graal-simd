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

@Opcode("VEC_AGGREGATE")
public final class AggregateOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AggregateOp> TYPE = LIRInstructionClass.create(AggregateOp.class);

    private final int INT_ARRAY_BASE_OFFSET;
    private final Scale INT_ARRAY_INDEX_SCALE;

    @Use({REG}) private Value inputOffsetValue;
    @Alive({REG}) private Value inputPtr;
    @Alive({REG}) private Value outputPtr;
    @Temp({REG}) private Value indexValue;
    @Temp({REG}) private Value resultValue;

    // TODO: Optimize this to be alive in the loop.
    @Temp({REG}) private Value constantValue;
    @Temp({REG}) private Value allPositiveOneValue;
    @Temp({REG}) private Value allNegativeOneValue;
    @Temp({REG}) private Value allThirtyOneValue;
    @Temp({REG}) private Value conflictValue;
    @Temp({REG}) private Value mergedValue;
    @Temp({REG}) private Value tempValue;

    public AggregateOp(LIRGeneratorTool tool, Value inputOffset, Value input, Value output) {
        super(TYPE);
        INT_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Int);
        INT_ARRAY_INDEX_SCALE = Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Int)));

        inputOffsetValue = inputOffset;
        inputPtr = input;
        outputPtr = output;

        indexValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_DWORD));
        resultValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_DWORD));

        // TODO: Make the constants alive outside the loop. Reuse registers if possible.
        constantValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
        allPositiveOneValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_DWORD));
        allNegativeOneValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_DWORD));
        allThirtyOneValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_DWORD));
        conflictValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_DWORD));
        mergedValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_DWORD));
        tempValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_DWORD));
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        Register inputOffset = asRegister(inputOffsetValue);
        Register input = asRegister(inputPtr);
        Register output = asRegister(outputPtr);
        Register index = asRegister(indexValue);
        Register result = asRegister(resultValue);

        Label conflictLoopLabel = new Label();
        Label updateLabel = new Label();

        // Load index.
        AMD64Address in = new AMD64Address(input, inputOffset, INT_ARRAY_INDEX_SCALE, INT_ARRAY_BASE_OFFSET);
        masm.vmovdqu32(index, in);

        // Detect conflicts.
        Register conflict = asRegister(conflictValue);
        masm.vpconflictd(conflict, index);

        // Gather current counts.
        AMD64Address vsib = new AMD64Address(output, index, INT_ARRAY_INDEX_SCALE, INT_ARRAY_BASE_OFFSET);
        masm.kxnorw(k1, k0, k0);
        masm.vpgatherdd(result, k1, vsib);

        // Prepare the partial result.
        Register constant = asRegister(constantValue);
        masm.movl(constant, 1);
        Register allPositiveOne = asRegister(allPositiveOneValue);
        masm.vpbroadcastd(allPositiveOne, constant);
        Register merged = asRegister(mergedValue);
        masm.vmovdqa32(merged, allPositiveOne);

        // Compute mask from conflicts.
        masm.vptestmd(k1, conflict, conflict);
        masm.kortestw(k1, k1);
        masm.jcc(AMD64Assembler.ConditionFlag.Zero, updateLabel);

        // Compute the permute control.
        masm.movl(constant, 31);
        Register allThirtyOne = asRegister(allThirtyOneValue);
        masm.vpbroadcastd(allThirtyOne, constant);
        Register control = conflict;
        masm.vplzcntd(control, conflict);
        masm.vpsubd(control, allThirtyOne, control);

        // Resolve conflicts in a loop.
        masm.bind(conflictLoopLabel);
        Register temp = asRegister(tempValue);
        masm.vpermd(temp, k1, /* z */1, control, merged);
        masm.vpaddd(merged, merged, temp);
        masm.vpermd(control, k1, /* z */0, control, control);
        masm.movl(constant, -1);
        Register allNegativeOne = asRegister(allNegativeOneValue);
        masm.vpbroadcastd(allNegativeOne, constant);
        masm.vpcmpd(k1, control, allNegativeOne, /* NEQ */4);
        masm.kortestw(k1, k1);
        masm.jcc(AMD64Assembler.ConditionFlag.NotZero, conflictLoopLabel);

        // Update the counts.
        masm.bind(updateLabel);
        masm.vpaddd(result, result, merged);
        masm.kxnorw(k1, k0, k0);
        masm.vpscatterdd(vsib, k1, result);
    }
}
