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

@Opcode("MATMULKERNEL2X8")
public final class MatmulKernel2x8Op extends AMD64LIRInstruction {
  public static final LIRInstructionClass<MatmulKernel2x8Op> TYPE =
      LIRInstructionClass.create(MatmulKernel2x8Op.class);

  private final int DOUBLE_ARRAY_BASE_OFFSET;
  private final Scale DOUBLE_ARRAY_INDEX_SCALE;

  private final int OBJECT_ARRAY_BASE_OFFSET;
  private final Scale OBJECT_ARRAY_INDEX_SCALE;

  @Alive({REG})
  private Value aValue;

  @Alive({REG})
  private Value bValue;

  @Alive({REG})
  private Value resultValue;

  @Temp({REG})
  private Value kPanelSizeValue;

  @Temp({REG})
  private Value iValue;

  @Temp({REG})
  private Value kValue;

  @Temp({REG})
  private Value jValue;

  @Temp({REG})
  private Value aTempBroadcastValue0;

  @Temp({REG})
  private Value aTempBroadcastValue1;

  @Temp({REG})
  private Value b0Value;

  @Temp({REG})
  private Value b1Value;

  @Temp({REG})
  private Value b2Value;

  @Temp({REG})
  private Value b3Value;

  @Temp({REG})
  private Value tempValue;

  @Temp({REG})
  private Value c00Val;

  @Temp({REG})
  private Value c01Val;

  @Temp({REG})
  private Value c02Val;

  @Temp({REG})
  private Value c03Val;

  @Temp({REG})
  private Value c10Val;

  @Temp({REG})
  private Value c11Val;

  @Temp({REG})
  private Value c12Val;

  @Temp({REG})
  private Value c13Val;

  @Temp({REG})
  private Value loopIndexValue;

  @Temp({REG})
  private Value loopEndValue;

  @Temp({REG})
  private Value tempArrayAddressRegValue;

  @Temp({REG})
  private Value aTempArrayAddressReg0Value;

  @Temp({REG})
  private Value aTempArrayAddressReg1Value;

  public MatmulKernel2x8Op(
      LIRGeneratorTool tool,
      Value a,
      Value b,
      Value result,
      Value kPanelSize,
      Value i,
      Value k,
      Value j) {
    super(TYPE);

    DOUBLE_ARRAY_BASE_OFFSET =
        tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Double);
    DOUBLE_ARRAY_INDEX_SCALE =
        Objects.requireNonNull(
            Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Double)));

    OBJECT_ARRAY_BASE_OFFSET =
        tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Object);
    OBJECT_ARRAY_INDEX_SCALE =
        Objects.requireNonNull(
            Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Object)));

    aValue = a;
    bValue = b;
    resultValue = result;
    kPanelSizeValue = kPanelSize;
    iValue = i;
    kValue = k;
    jValue = j;

    aTempBroadcastValue0 = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    aTempBroadcastValue1 = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    b0Value = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    b1Value = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    b2Value = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    b3Value = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));

    tempValue = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    c00Val = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    c01Val = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    c02Val = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    c03Val = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    c10Val = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    c11Val = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    c12Val = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    c13Val = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));

    loopIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
    loopEndValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
    tempArrayAddressRegValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
    aTempArrayAddressReg0Value = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
    aTempArrayAddressReg1Value = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
  }

  @Override
  public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
    Register aPtr = asRegister(aValue);
    Register bPtr = asRegister(bValue);
    Register resultPtr = asRegister(resultValue);

    Register kPanelSize = asRegister(kPanelSizeValue);
    Register iPos = asRegister(iValue);
    Register kPos = asRegister(kValue);
    Register jPos = asRegister(jValue);

    Register aTempBroadcast0 = asRegister(aTempBroadcastValue0);
    Register aTempBroadcast1 = asRegister(aTempBroadcastValue1);
    Register b0 = asRegister(b0Value);
    Register b1 = asRegister(b1Value);
    Register b2 = asRegister(b2Value);
    Register b3 = asRegister(b3Value);

    Register temp = asRegister(tempValue);

    Register c00 = asRegister(c00Val);
    Register c01 = asRegister(c01Val);
    Register c02 = asRegister(c02Val);
    Register c03 = asRegister(c03Val);
    Register c10 = asRegister(c10Val);
    Register c11 = asRegister(c11Val);
    Register c12 = asRegister(c12Val);
    Register c13 = asRegister(c13Val);

    Register loopIndex = asRegister(loopIndexValue);
    Register loopEnd = asRegister(loopEndValue);
    Register tempArrayAddressReg = asRegister(tempArrayAddressRegValue);
    Register aTempArrayAddressReg0 = asRegister(aTempArrayAddressReg0Value);
    Register aTempArrayAddressReg1 = asRegister(aTempArrayAddressReg1Value);

    AMD64Address resultAddress =
        new AMD64Address(resultPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET);
    masm.movq(tempArrayAddressReg, resultAddress);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(c00, resultAddress);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 16);
    masm.movdqu(c01, resultAddress);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 32);
    masm.movdqu(c02, resultAddress);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 48);
    masm.movdqu(c03, resultAddress);

    resultAddress =
        new AMD64Address(resultPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 8);
    masm.movq(tempArrayAddressReg, resultAddress);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(c10, resultAddress);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 16);
    masm.movdqu(c11, resultAddress);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 32);
    masm.movdqu(c12, resultAddress);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 48);
    masm.movdqu(c13, resultAddress);

    AMD64Address aAddress =
        new AMD64Address(aPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET);
    masm.movq(aTempArrayAddressReg0, aAddress);
    aAddress = new AMD64Address(aPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 8);
    masm.movq(aTempArrayAddressReg1, aAddress);

    // Initialize loop index
    masm.movl(loopIndex, kPos);
    masm.movl(loopEnd, kPos);
    masm.addl(loopEnd, kPanelSize);

    Label loopLabel = new Label();

    // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
    masm.bind(loopLabel);

    AMD64Address bAddress =
        new AMD64Address(bPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET);
    masm.movq(tempArrayAddressReg, bAddress);

    bAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(b0, bAddress);
    bAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 16);
    masm.movdqu(b1, bAddress);
    bAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 32);
    masm.movdqu(b2, bAddress);
    bAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 48);
    masm.movdqu(b3, bAddress);

    aAddress =
        new AMD64Address(
            aTempArrayAddressReg0, loopIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movddup(aTempBroadcast0, aAddress);
    aAddress =
        new AMD64Address(
            aTempArrayAddressReg1, loopIndex, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movddup(aTempBroadcast1, aAddress);

    masm.movdqu(temp, aTempBroadcast0);
    masm.mulpd(temp, b0);
    masm.addpd(c00, temp);

    masm.mulpd(b0, aTempBroadcast1);
    masm.addpd(c10, b0);

    masm.movdqu(temp, aTempBroadcast0);
    masm.mulpd(temp, b1);
    masm.addpd(c01, temp);

    masm.mulpd(b1, aTempBroadcast1);
    masm.addpd(c11, b1);

    masm.movdqu(temp, aTempBroadcast0);
    masm.mulpd(temp, b2);
    masm.addpd(c02, temp);

    masm.mulpd(b2, aTempBroadcast1);
    masm.addpd(c12, b2);

    masm.mulpd(aTempBroadcast0, b3);
    masm.addpd(c03, aTempBroadcast0);

    masm.mulpd(b3, aTempBroadcast1);
    masm.addpd(c13, b3);

    masm.addl(loopIndex, 1);
    masm.cmpl(loopIndex, loopEnd);
    masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

    // Store partial results in result array
    resultAddress =
        new AMD64Address(resultPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET);
    masm.movq(tempArrayAddressReg, resultAddress);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(resultAddress, c00);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 16);
    masm.movdqu(resultAddress, c01);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 32);
    masm.movdqu(resultAddress, c02);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 48);
    masm.movdqu(resultAddress, c03);

    resultAddress =
        new AMD64Address(resultPtr, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 8);
    masm.movq(tempArrayAddressReg, resultAddress);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(resultAddress, c10);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 16);
    masm.movdqu(resultAddress, c11);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 32);
    masm.movdqu(resultAddress, c12);
    resultAddress =
        new AMD64Address(
            tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 48);
    masm.movdqu(resultAddress, c13);
  }
}
