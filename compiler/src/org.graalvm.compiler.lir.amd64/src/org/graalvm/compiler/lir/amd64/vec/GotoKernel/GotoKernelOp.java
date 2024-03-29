package org.graalvm.compiler.lir.amd64.vec.GotoKernel;

import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.meta.Value;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.amd64.AMD64LIRInstruction;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;

@Opcode("GOTOKERNEL")
public final class GotoKernelOp extends AMD64LIRInstruction {
  public static final LIRInstructionClass<GotoKernelOp> TYPE =
      LIRInstructionClass.create(GotoKernelOp.class);

  private GotoKernel gotoKernel;

  @Alive({REG})
  Value arrsValue;

  @Temp({REG})
  Value kPanelSizeValue;

  @Temp({REG})
  Value iValue;

  @Temp({REG})
  Value kValue;

  @Temp({REG})
  Value jValue;

  @Temp({REG})
  Value loopIndexValue;

  @Temp({REG})
  Value tempArrayAddressRegValue;

  @Temp({REG})
  Value[] remainingRegValues;

  public GotoKernelOp(
      LIRGeneratorTool tool,
      Value arrs,
      Value kPanelSize,
      Value i,
      Value k,
      Value j,
      int arch,
      int kernelType,
      int aLength,
      int bLength,
      int mLength,
      int kLength,
      int nLength,
      long[] calc,
      double[] constArgs,
      int[] varArgProperties,
      int[] miscArgs) {
    super(TYPE);

    switch (kernelType) {
      case 0: // AB
        this.gotoKernel =
            new GotoABKernel(
                tool,
                arch,
                kernelType,
                aLength,
                bLength,
                mLength,
                kLength,
                nLength,
                calc,
                constArgs,
                varArgProperties,
                this,
                false);
        break;
      case 1: // A^TB
        this.gotoKernel =
            new GotoATBKernel(
                tool,
                arch,
                kernelType,
                aLength,
                bLength,
                mLength,
                kLength,
                nLength,
                calc,
                constArgs,
                varArgProperties,
                this);
        break;
      case 2: // AB^T
        this.gotoKernel =
            new GotoABTKernel(
                tool,
                arch,
                kernelType,
                aLength,
                bLength,
                mLength,
                kLength,
                nLength,
                calc,
                constArgs,
                varArgProperties,
                this);
        break;
      case 3: // A^TB^T
        this.gotoKernel =
            new GotoABKernel(
                tool,
                arch,
                kernelType,
                aLength,
                bLength,
                mLength,
                kLength,
                nLength,
                calc,
                constArgs,
                varArgProperties,
                this,
                true);
        break;
      case 4: // packed
        this.gotoKernel =
            new GotoPackedKernel(
                tool,
                arch,
                kernelType,
                aLength,
                bLength,
                mLength,
                kLength,
                nLength,
                calc,
                constArgs,
                varArgProperties,
                this,
                miscArgs);
        break;
      case 5: // Interleave packed
        this.gotoKernel =
            new GotoInterleavePackedKernel(
                tool,
                arch,
                kernelType,
                aLength,
                bLength,
                mLength,
                kLength,
                nLength,
                calc,
                constArgs,
                varArgProperties,
                this,
                miscArgs);
        break;
    }

    arrsValue = arrs;
    kPanelSizeValue = kPanelSize;
    iValue = i;
    kValue = k;
    jValue = j;

    loopIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));
    tempArrayAddressRegValue = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
    remainingRegValues = new Value[gotoKernel.remainingRegisterNum];
    for (int index = 0; index < gotoKernel.remainingRegisterNum; index++) {
      remainingRegValues[index] = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
    }
  }

  @Override
  public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
    gotoKernel.emitCode(crb, masm);
  }
}
