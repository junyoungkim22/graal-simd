package org.graalvm.compiler.lir.amd64.vec.ConvKernel;

import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rdx;
import static jdk.vm.ci.amd64.AMD64.rsp;
import static jdk.vm.ci.amd64.AMD64.xmmRegistersAVX512;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static org.graalvm.compiler.asm.amd64.AMD64BaseAssembler.OperandSize.QWORD;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Objects;
import java.util.Stack;
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

@Opcode("CONVKERNEL")
public final class ConvKernelOp extends AMD64LIRInstruction {
  public static final LIRInstructionClass<ConvKernelOp> TYPE =
      LIRInstructionClass.create(ConvKernelOp.class);

  protected final int DOUBLE_ARRAY_BASE_OFFSET;
  protected final Scale DOUBLE_ARRAY_INDEX_SCALE;

  protected final int OBJECT_ARRAY_BASE_OFFSET;
  protected final Scale OBJECT_ARRAY_INDEX_SCALE;

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
  Value[] remainingRegValues;

  protected final int remainingRegisterNum;

  protected final int kernelType;
  protected final int aLength, bLength;
  protected final int outChannels, inChannels, imgLength, kernelLength;

  public ConvKernelOp(
      LIRGeneratorTool tool,
      Value arrs,
      Value kPanelSize,
      Value i,
      Value k,
      Value j,
      int kernelType,
      int aLength,
      int bLength,
      int outChannels,
      int inChannels,
      int imgLength,
      int kernelLength,
      long[] calc,
      double[] constArgs,
      int[] varArgProperties) {
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

    arrsValue = arrs;
    kPanelSizeValue = kPanelSize;
    iValue = i;
    kValue = k;
    jValue = j;

    loopIndexValue = tool.newVariable(LIRKind.value(AMD64Kind.DWORD));

    remainingRegisterNum = 8;

    remainingRegValues = new Value[remainingRegisterNum];
    for (int index = 0; index < remainingRegisterNum; index++) {
      remainingRegValues[index] = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
    }

    this.kernelType = kernelType;
    this.aLength = aLength;
    this.bLength = bLength / 8; // bLength in term of SIMD registers
    this.outChannels = outChannels;
    this.inChannels = inChannels;
    this.imgLength = imgLength;
    this.kernelLength = kernelLength;
  }

  private void addToNoDivStack(Stack<Register> stack, Register toPush) {
    if (!toPush.equals(rax) && !toPush.equals(rdx)) {
      stack.push(toPush);
    }
  }

  @Override
  public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
    Register arrsPtr = asRegister(arrsValue);
    Register kPanelSize = asRegister(kPanelSizeValue);
    Register iPos = asRegister(iValue);
    Register kPos = asRegister(kValue);
    Register jPos = asRegister(jValue);
    Register loopIndex = asRegister(loopIndexValue);

    Register[] genRegs = new Register[remainingRegisterNum];
    for (int i = 0; i < remainingRegisterNum; i++) {
      genRegs[i] = asRegister(remainingRegValues[i]);
    }

    Stack<Register> notRaxRdxRegs = new Stack<>();
    for (int i = 0; i < genRegs.length; i++) {
      addToNoDivStack(notRaxRdxRegs, genRegs[i]);
    }

    // Declare SIMD registers
    int registerIndex = 0;
    HashMap<String, Integer> simdRegisters = new HashMap<String, Integer>();

    simdRegisters.put("A", registerIndex++);

    for (int i = 0; i < bLength; i++) {
      simdRegisters.put("B" + String.valueOf(i), registerIndex++);
    }

    for (int i = 0; i < aLength; i++) {
      for (int j = 0; j < bLength; j++) {
        simdRegisters.put("C" + String.valueOf(i) + String.valueOf(j), registerIndex++);
      }
    }

    AMD64Address resultAddress, aAddress, bAddress;

    // Set subresult regs to zero
    masm.vpxorq(
        xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(0) + String.valueOf(0))],
        xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(0) + String.valueOf(0))],
        xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(0) + String.valueOf(0))]);
    for (int i = 0; i < aLength; i++) {
      for (int j = 0; j < bLength; j++) {
        if (i != 0 || j != 0) {
          masm.vmovupd(
              xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))],
              xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(0) + String.valueOf(0))]);
        }
      }
    }

    Register jTempReg = notRaxRdxRegs.pop(); // temp
    masm.movq(jTempReg, jPos);

    masm.push(rax);
    masm.push(rdx);

    Register outLengthReg = notRaxRdxRegs.pop(); // temp
    masm.movq(rdx, 0);
    masm.movq(rax, jTempReg);
    masm.movq(outLengthReg, imgLength - kernelLength + 1);
    AMD64Assembler.AMD64MOp.IDIV.emit(masm, QWORD, outLengthReg);
    Register iptlcoReg = notRaxRdxRegs.pop();
    Register iptlroReg = notRaxRdxRegs.pop();
    masm.movq(iptlcoReg, rax);
    masm.movq(iptlroReg, rdx);

    addToNoDivStack(notRaxRdxRegs, outLengthReg);
    addToNoDivStack(notRaxRdxRegs, jTempReg);

    masm.pop(rdx);
    masm.pop(rax);

    // jPos not needed during inner loop
    masm.push(jPos);
    addToNoDivStack(notRaxRdxRegs, jPos);

    masm.push(rax);
    masm.push(rdx);

    Register kernelSizeLengthReg = notRaxRdxRegs.pop(); // temp
    masm.movq(rdx, 0);
    masm.movq(rax, kPos);
    masm.movq(kernelSizeLengthReg, kernelLength * kernelLength);
    AMD64Assembler.AMD64MOp.IDIV.emit(masm, QWORD, kernelSizeLengthReg);
    Register kernelOffsetReg = notRaxRdxRegs.pop();
    Register kernelOutChannelReg = notRaxRdxRegs.pop();
    masm.movq(kernelOffsetReg, rdx);
    masm.movq(kernelOutChannelReg, rax);

    Register colOffsetReg = notRaxRdxRegs.pop();
    Register rowOffsetReg = notRaxRdxRegs.pop();

    masm.movq(rdx, 0);
    masm.movq(rax, kernelOffsetReg);
    masm.movq(kernelSizeLengthReg, kernelLength);
    AMD64Assembler.AMD64MOp.IDIV.emit(masm, QWORD, kernelSizeLengthReg);
    masm.movq(colOffsetReg, rax);
    masm.movq(rowOffsetReg, rdx);

    addToNoDivStack(notRaxRdxRegs, kernelSizeLengthReg);

    masm.pop(rdx);
    masm.pop(rax);

    // rax and rdx not needed in inner loop
    masm.push(rax);
    masm.push(rdx);

    masm.addq(kPanelSize, kPos);

    // iteration test
    // masm.movq(kPanelSize, kPos);
    // masm.addq(kPanelSize, 1);

    masm.push(kPanelSize);
    addToNoDivStack(notRaxRdxRegs, kPanelSize);

    Register imgReg = notRaxRdxRegs.pop();
    Register kernelReg = notRaxRdxRegs.pop();
    masm.movq(loopIndex, 0);
    masm.movq(
        imgReg,
        new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
    masm.movq(
        kernelReg,
        new AMD64Address(
            arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 8));

    // arrsPtr not needed during inner loop
    masm.push(arrsPtr);
    addToNoDivStack(notRaxRdxRegs, arrsPtr);

    // kPos not needed during inner loop
    masm.push(kPos);
    addToNoDivStack(notRaxRdxRegs, kPos);

    masm.movq(loopIndex, kPos);

    Label loopLabel = new Label();
    Label compStartLabel = new Label();

    masm.bind(loopLabel);

    masm.cmpq(rowOffsetReg, kernelLength);
    masm.jcc(AMD64Assembler.ConditionFlag.NotEqual, compStartLabel);
    masm.addq(colOffsetReg, 1);
    masm.movq(rowOffsetReg, 0);

    masm.cmpq(kernelOffsetReg, kernelLength * kernelLength);
    masm.jcc(AMD64Assembler.ConditionFlag.NotEqual, compStartLabel);
    masm.movq(kernelOffsetReg, 0);
    masm.addq(kernelOutChannelReg, 1);

    masm.bind(compStartLabel);

    masm.movq(
        rax,
        new AMD64Address(
            imgReg, kernelOutChannelReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
    masm.movq(rdx, iptlcoReg);
    masm.addq(rdx, colOffsetReg);
    masm.movq(rax, new AMD64Address(rax, rdx, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
    masm.movq(rdx, iptlroReg);
    masm.addq(rdx, rowOffsetReg);
    for (int j = 0; j < bLength; j++) {
      masm.vmovupd(
          xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))],
          new AMD64Address(
              rax, rdx, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + (j * 64)));
    }

    for (int i = 0; i < aLength; i++) {
      masm.movq(
          rax,
          new AMD64Address(
              kernelReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + (i * 8)));
      masm.movq(
          rax,
          new AMD64Address(
              rax, kernelOutChannelReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
      masm.movq(
          rax,
          new AMD64Address(rax, colOffsetReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
      masm.vbroadcastsd(
          xmmRegistersAVX512[simdRegisters.get("A")],
          new AMD64Address(rax, rowOffsetReg, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET));
      for (int j = 0; j < bLength; j++) {
        masm.vfmadd231pd(
            xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))],
            xmmRegistersAVX512[simdRegisters.get("A")],
            xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
        // masm.vfmadd231pd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) +
        // String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("A")],
        // xmmRegistersAVX512[simdRegisters.get("A")]);
        // masm.vfmadd231pd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) +
        // String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))],
        // xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
        // masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) +
        // String.valueOf(j))], xmmRegistersAVX512[simdRegisters.get("A")]);
      }
    }
    masm.addq(rowOffsetReg, 1);
    masm.addq(kernelOffsetReg, 1);
    masm.addq(loopIndex, 1);
    masm.cmpq(loopIndex, new AMD64Address(rsp, (8 * 2)));
    masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

    /*
    masm.push(rax);
    masm.push(rdx);
    masm.movq(rdx, 0);
    masm.movq(rax, 12);
    masm.movq(genRegs[3], 7);
    AMD64Assembler.AMD64MOp.IDIV.emit(masm, QWORD, genRegs[3]);
    masm.movq(genRegs[2], rdx);
    debugPrint(genRegs[2].toString());
    masm.pop(rdx);
    masm.pop(rax);

    masm.movq(loopIndex, 0);
    masm.movq(genRegs[0], new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+16));
    masm.movq(genRegs[0], new AMD64Address(genRegs[0], loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
    masm.movq(genRegs[0], new AMD64Address(genRegs[0], loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
    masm.movq(genRegs[1], Double.doubleToRawLongBits(67));
    masm.movq(new AMD64Address(genRegs[0], genRegs[2], DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET), genRegs[1]);
    */

    masm.pop(kPos);
    masm.pop(arrsPtr);
    masm.pop(kPanelSize);
    masm.pop(rdx);
    masm.pop(rax);
    masm.subq(kPanelSize, kPos);
    masm.pop(jPos);

    notRaxRdxRegs = new Stack<>();
    for (int i = 0; i < genRegs.length; i++) {
      addToNoDivStack(notRaxRdxRegs, genRegs[i]);
    }
    masm.push(iPos);
    addToNoDivStack(notRaxRdxRegs, iPos);
    Register iPosTemp = notRaxRdxRegs.pop();
    masm.movq(iPosTemp, iPos);

    masm.push(jPos);
    addToNoDivStack(notRaxRdxRegs, jPos);
    Register jPosTemp = notRaxRdxRegs.pop();
    masm.movq(jPosTemp, jPos);

    masm.push(arrsPtr);
    addToNoDivStack(notRaxRdxRegs, arrsPtr);
    Register resultPtr = notRaxRdxRegs.pop();
    masm.movq(loopIndex, 0);
    masm.movq(
        resultPtr,
        new AMD64Address(
            arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 16));

    Register resultIindexed = notRaxRdxRegs.pop();
    Register jIndex = notRaxRdxRegs.pop();

    outLengthReg = notRaxRdxRegs.pop(); // temp
    masm.movq(outLengthReg, imgLength - kernelLength + 1);

    Register addressTempReg = notRaxRdxRegs.pop();

    masm.push(rax);
    masm.push(rdx);

    for (int i = 0; i < aLength; i++) {
      masm.movq(
          resultIindexed,
          new AMD64Address(
              resultPtr, iPosTemp, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + (i * 8)));
      for (int j = 0; j < bLength; j++) {
        masm.movq(rdx, 0);
        masm.movq(rax, 0);
        masm.movq(rax, jPosTemp);
        masm.addq(rax, 8 * j);
        AMD64Assembler.AMD64MOp.IDIV.emit(masm, QWORD, outLengthReg);
        masm.movq(
            addressTempReg,
            new AMD64Address(
                resultIindexed, rax, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
        resultAddress =
            new AMD64Address(
                addressTempReg, rdx, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.vaddpd(
            xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))],
            xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))],
            resultAddress);
        masm.vmovupd(
            resultAddress,
            xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))]);
        /*
        resultAddress = new AMD64Address(addressTempReg, jPosTemp, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET);
        masm.movq(rax, Double.doubleToRawLongBits(67));
        masm.movq(resultAddress, rax);
        */
      }
    }

    masm.pop(rdx);
    masm.pop(rax);
    masm.pop(arrsPtr);
    masm.pop(jPos);
    masm.pop(iPos);

    /*

    debugPrint("^^^^^^");
    debugPrint(arrsPtr.toString());
    debugPrint(kPanelSize.toString());
    debugPrint(iPos.toString());
    debugPrint(kPos.toString());
    debugPrint(jPos.toString());
    debugPrint(loopIndex.toString());
    debugPrint("$$$$$");
    for(int i = 0; i < genRegs.length; i++) {
        debugPrint(genRegs[i].toString());
    }

    */

    return;
  }

  protected static void debugPrint(String msg) {
    PrintWriter debugLog = null;
    try {
      debugLog =
          new PrintWriter(
              new FileWriter(
                  "/home/junyoung2/project/project2/adaptive-code-generation/log.txt", true));
    } catch (Exception e) {
      System.out.println(e);
    }
    debugLog.println(msg);
    debugLog.close();
  }
}
