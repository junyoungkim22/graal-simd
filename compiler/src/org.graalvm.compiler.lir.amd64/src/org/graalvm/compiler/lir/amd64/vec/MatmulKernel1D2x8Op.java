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

@Opcode("MATMULKERNEL1D2X8")
public final class MatmulKernel1D2x8Op extends AMD64LIRInstruction {
  public static final LIRInstructionClass<MatmulKernel1D2x8Op> TYPE =
      LIRInstructionClass.create(MatmulKernel1D2x8Op.class);

  private final int DOUBLE_ARRAY_BASE_OFFSET;
  private final Scale DOUBLE_ARRAY_INDEX_SCALE;

  private final int INT_ARRAY_BASE_OFFSET;
  private final Scale INT_ARRAY_INDEX_SCALE;

  // private final int OBJECT_ARRAY_BASE_OFFSET;
  // private final Scale OBJECT_ARRAY_INDEX_SCALE;

  @Alive({REG})
  private Value aValue;

  @Alive({REG})
  private Value bValue;

  @Alive({REG})
  private Value resultValue;

  @Alive({REG})
  private Value constantsValue;

  @Temp({REG})
  private Value iValue;

  @Temp({REG})
  private Value kValue;

  @Temp({REG})
  private Value jValue;

  @Temp({REG})
  private Value kPanelSizeValue;

  @Temp({REG})
  private Value lenValue;

  @Temp({REG})
  private Value aTempValue;

  @Temp({REG})
  private Value aTempBroadcastValue;

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
  /*
  @Temp({REG}) private Value c00Val;
  @Temp({REG}) private Value c10Val;
  @Temp({REG}) private Value c20Val;
  @Temp({REG}) private Value c30Val;
  @Temp({REG}) private Value c40Val;
  @Temp({REG}) private Value c50Val;
  @Temp({REG}) private Value c60Val;
  @Temp({REG}) private Value c70Val;
  */
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

  @Temp({REG})
  private Value loopIndexValue;

  @Temp({REG})
  private Value loopEndValue;

  @Temp({REG})
  private Value indexValue;
  // @Temp({REG}) private Value bColIndexValue;
  // @Temp({REG}) private Value tempArrayAddressRegValue;

  public MatmulKernel1D2x8Op(
      LIRGeneratorTool tool,
      Value a,
      Value b,
      Value result,
      Value constants,
      Value i,
      Value k,
      Value j) {
    super(TYPE);

    DOUBLE_ARRAY_BASE_OFFSET =
        tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Double);
    DOUBLE_ARRAY_INDEX_SCALE =
        Objects.requireNonNull(
            Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Double)));

    INT_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Int);
    INT_ARRAY_INDEX_SCALE =
        Objects.requireNonNull(
            Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Int)));

    // OBJECT_ARRAY_BASE_OFFSET =
    // tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Object);
    // OBJECT_ARRAY_INDEX_SCALE =
    // Objects.requireNonNull(Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Object)));

    aValue = a;
    bValue = b;
    resultValue = result;
    constantsValue = constants;
    // kPanelSizeValue = k;
    iValue = i;
    kValue = k;
    jValue = j;

    kPanelSizeValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
    lenValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));

    aTempValue = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
    aTempBroadcastValue = tool.newVariable(LIRKind.value(AMD64Kind.V128_QWORD));
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
    /*
    c00Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    c10Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    c20Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    c30Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    c40Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    c50Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    c60Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    c70Val = tool.newVariable(LIRKind.value(AMD64Kind.V512_QWORD));
    */
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
    // bColIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
    // tempArrayAddressRegValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));

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

    masm.xorpd(c00, c00);
    masm.xorpd(c01, c01);
    masm.xorpd(c02, c02);
    masm.xorpd(c03, c03);
    masm.xorpd(c10, c10);
    masm.xorpd(c11, c11);
    masm.xorpd(c12, c12);
    masm.xorpd(c13, c13);

    Register loopIndex = asRegister(loopIndexValue);
    Register loopEnd = asRegister(loopEndValue);
    Register index = asRegister(indexValue);
    // Register bColIndex = asRegister(bColIndexValue);
    // Register tempArrayAddressReg = asRegister(tempArrayAddressRegValue);

    masm.movl(index, 0);
    AMD64Address resultAddress =
        new AMD64Address(constantsPtr, index, INT_ARRAY_INDEX_SCALE, INT_ARRAY_BASE_OFFSET);
    masm.movl(kPanelSize, resultAddress);

    masm.movl(index, 1);
    resultAddress =
        new AMD64Address(constantsPtr, index, INT_ARRAY_INDEX_SCALE, INT_ARRAY_BASE_OFFSET);
    masm.movl(len, resultAddress);

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
    AMD64Address bAddress =
        new AMD64Address(bPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(b0, bAddress);

    masm.addl(index, 2);
    bAddress = new AMD64Address(bPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(b1, bAddress);

    masm.addl(index, 2);
    bAddress = new AMD64Address(bPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(b2, bAddress);

    masm.addl(index, 2);
    bAddress = new AMD64Address(bPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(b3, bAddress);

    masm.movl(index, iPos);
    masm.imulq(index, len);
    masm.addl(index, loopIndex);
    AMD64Address aAddress =
        new AMD64Address(aPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movq(aTemp, aAddress);
    masm.movddup(aTempBroadcast, aTemp);

    masm.movdqu(temp, aTempBroadcast);
    masm.mulpd(temp, b0);
    masm.addpd(c00, temp);

    masm.movdqu(temp, aTempBroadcast);
    masm.mulpd(temp, b1);
    masm.addpd(c01, temp);

    masm.movdqu(temp, aTempBroadcast);
    masm.mulpd(temp, b2);
    masm.addpd(c02, temp);

    masm.movdqu(temp, aTempBroadcast);
    masm.mulpd(temp, b3);
    masm.addpd(c03, temp);

    masm.addl(index, len);
    aAddress = new AMD64Address(aPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movq(aTemp, aAddress);
    masm.movddup(aTempBroadcast, aTemp);

    masm.movdqu(temp, aTempBroadcast);
    masm.mulpd(temp, b0);
    masm.addpd(c10, temp);

    masm.movdqu(temp, aTempBroadcast);
    masm.mulpd(temp, b1);
    masm.addpd(c11, temp);

    masm.movdqu(temp, aTempBroadcast);
    masm.mulpd(temp, b2);
    masm.addpd(c12, temp);

    masm.movdqu(temp, aTempBroadcast);
    masm.mulpd(temp, b3);
    masm.addpd(c13, temp);

    masm.addl(loopIndex, 1);
    masm.cmpl(loopIndex, loopEnd);
    masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

    // Store partial results in result array

    masm.movl(index, iPos);
    masm.imulq(index, len);
    masm.addl(index, jPos);

    resultAddress =
        new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(temp, resultAddress);
    masm.addpd(c00, temp);
    masm.movdqu(resultAddress, c00);

    masm.addl(index, 2);
    resultAddress =
        new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(temp, resultAddress);
    masm.addpd(c01, temp);
    masm.movdqu(resultAddress, c01);

    masm.addl(index, 2);
    resultAddress =
        new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(temp, resultAddress);
    masm.addpd(c02, temp);
    masm.movdqu(resultAddress, c02);

    masm.addl(index, 2);
    resultAddress =
        new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(temp, resultAddress);
    masm.addpd(c03, temp);
    masm.movdqu(resultAddress, c03);

    masm.movl(index, iPos);
    masm.imulq(index, len);
    masm.addl(index, jPos);
    masm.addl(index, len);

    resultAddress =
        new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(temp, resultAddress);
    masm.addpd(c10, temp);
    masm.movdqu(resultAddress, c10);

    masm.addl(index, 2);
    resultAddress =
        new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(temp, resultAddress);
    masm.addpd(c11, temp);
    masm.movdqu(resultAddress, c11);

    masm.addl(index, 2);
    resultAddress =
        new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(temp, resultAddress);
    masm.addpd(c12, temp);
    masm.movdqu(resultAddress, c12);

    masm.addl(index, 2);
    resultAddress =
        new AMD64Address(resultPtr, index, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
    masm.movdqu(temp, resultAddress);
    masm.addpd(c13, temp);
    masm.movdqu(resultAddress, c13);
  }
}
