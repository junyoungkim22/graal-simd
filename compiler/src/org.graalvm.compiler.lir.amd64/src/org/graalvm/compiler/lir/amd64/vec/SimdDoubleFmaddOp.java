package org.graalvm.compiler.lir.amd64.vec;

import static jdk.vm.ci.code.ValueUtil.asRegister;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

import java.util.Objects;
import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;
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

@Opcode("SIMDDOUBLEFMADD")
public final class SimdDoubleFmaddOp extends AMD64LIRInstruction {
  public static final LIRInstructionClass<SimdDoubleFmaddOp> TYPE =
      LIRInstructionClass.create(SimdDoubleFmaddOp.class);

  private final int DOUBLE_ARRAY_BASE_OFFSET;
  private final Scale DOUBLE_ARRAY_INDEX_SCALE;

  @Use({REG})
  private Value lengthValue;

  @Use({REG})
  private Value multValValue;

  @Alive({REG})
  private Value inputPtr;

  @Alive({REG})
  private Value outputPtr;

  @Temp({REG})
  private Value tempValue;

  @Temp({REG})
  private Value broadcastMultValValue;

  @Temp({REG})
  private Value loopIndexValue;

  public SimdDoubleFmaddOp(
      LIRGeneratorTool tool, Value length, Value multVal, Value input, Value output) {
    super(TYPE);
    DOUBLE_ARRAY_BASE_OFFSET =
        tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Double);
    DOUBLE_ARRAY_INDEX_SCALE =
        Objects.requireNonNull(
            Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Double)));

    lengthValue = length;
    multValValue = multVal;
    inputPtr = input;
    outputPtr = output;

    tempValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    broadcastMultValValue = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    loopIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
  }

  @Override
  public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
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
    AMD64Address inputAddress =
        new AMD64Address(input, loopIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.vmovupd(temp, inputAddress);
    AMD64Address outputAddress =
        new AMD64Address(output, loopIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    // temp = (temp * multValVector) + (values in outputAddress)
    masm.vfmadd213pd(temp, broadcastMultVal, outputAddress);
    // Store result to output
    masm.vmovupd(outputAddress, temp);
    // Increment loopIndex by 8
    masm.addl(loopIndex, 8);
    masm.cmpl(loopIndex, length);
    masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);
  }
}
