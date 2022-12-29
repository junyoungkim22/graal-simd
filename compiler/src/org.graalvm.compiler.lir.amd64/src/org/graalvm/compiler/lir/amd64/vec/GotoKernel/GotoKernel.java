package org.graalvm.compiler.lir.amd64.vec.GotoKernel;

import static jdk.vm.ci.amd64.AMD64.k2;
import static jdk.vm.ci.amd64.AMD64.r15;
import static jdk.vm.ci.amd64.AMD64.rsp;
import static jdk.vm.ci.amd64.AMD64.xmmRegistersAVX512;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.JavaKind;
import org.graalvm.compiler.asm.Label;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Address.Scale;
import org.graalvm.compiler.asm.amd64.AMD64Assembler;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.asm.amd64.AVXKind.AVXSize;
import org.graalvm.compiler.lir.amd64.vec.GotoOpCode;
import org.graalvm.compiler.lir.amd64.vec.dag.ExprDag;
import org.graalvm.compiler.lir.amd64.vec.util.ChangeableString;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;

public abstract class GotoKernel {
  protected int opLength = 5;

  protected final int INT_ARRAY_BASE_OFFSET;
  protected final Scale INT_ARRAY_INDEX_SCALE;

  protected final int DOUBLE_ARRAY_BASE_OFFSET;
  protected final Scale DOUBLE_ARRAY_INDEX_SCALE;

  protected final int OBJECT_ARRAY_BASE_OFFSET;
  protected final Scale OBJECT_ARRAY_INDEX_SCALE;

  protected final String opStringRaw;
  protected final double[] constArgs;
  protected final int[] varArgProperties;

  protected int stackOffsetToConstArgs;
  protected int constArgsStackSize;
  protected int aTempArrayAddressNumLimit;
  protected final int constArgStackSlotSize;

  protected final int kernelType;
  protected final int mLength, kLength, nLength;
  protected final int initialALength;
  protected final int initialBLength;
  protected final int remainingRegisterNum;

  Register arrsPtr, kPanelSize, iPos, kPos, jPos, loopIndex, tempArrayAddressReg;

  protected int kPanelSizeIndexFromBehind;
  Register useAsAddressRegs[];
  Register[] tempRegs;
  int[] tempRegNums;
  Map<String, Integer> availableValues;
  Map<Integer, Integer> variableArgsStackOffsets;
  Set<String> toLoad;
  protected int varArgsStackSize;
  protected ExprDag exprDag;

  protected GotoKernelOp kernelOp;

  public PrintWriter debugLog;

  public GotoKernel(
      LIRGeneratorTool tool,
      int kernelType,
      int aLength,
      int bLength,
      int mLength,
      int kLength,
      int nLength,
      long[] calc,
      double[] constArgs,
      int[] varArgProperties,
      GotoKernelOp kernelOp) {
    INT_ARRAY_BASE_OFFSET = tool.getProviders().getMetaAccess().getArrayBaseOffset(JavaKind.Int);
    INT_ARRAY_INDEX_SCALE =
        Objects.requireNonNull(
            Scale.fromInt(tool.getProviders().getMetaAccess().getArrayIndexScale(JavaKind.Int)));

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

    String opStringBuild = "";
    for (int index = 0; index < calc.length; index++) {
      opStringBuild +=
          Long.toBinaryString(calc[index]).substring(1, Long.toBinaryString(calc[index]).length());
    }
    this.opStringRaw = opStringBuild;

    this.constArgs = constArgs;
    this.varArgProperties = varArgProperties;

    // Check loads
    this.toLoad = new HashSet<String>();
    for (int loadIndex = 0; loadIndex < opStringRaw.length(); loadIndex += GotoOpCode.INDEXLENGTH) {
      if (opStringRaw
          .substring(loadIndex, loadIndex + GotoOpCode.INDEXLENGTH)
          .equals(GotoOpCode.LOAD)) {
        String loadTarget =
            opStringRaw.substring(
                loadIndex + (GotoOpCode.INDEXLENGTH * 3), loadIndex + (GotoOpCode.INDEXLENGTH * 4));
        if (loadTarget.equals(GotoOpCode.CONSTARG) || loadTarget.equals(GotoOpCode.VARIABLEARG)) {
          loadTarget +=
              opStringRaw.substring(
                  loadIndex + (GotoOpCode.INDEXLENGTH * 4),
                  loadIndex + (GotoOpCode.INDEXLENGTH * 5));
        }
        toLoad.add(loadTarget);
      }
    }

    this.kernelType = kernelType;
    this.mLength = mLength;
    this.kLength = kLength;
    this.nLength = nLength;
    this.initialALength = aLength;
    this.initialBLength = bLength / 8;

    constArgStackSlotSize = 32; // Causes an error if value if 8 (do not know reason why)

    remainingRegisterNum = 7;

    variableArgsStackOffsets = new HashMap<Integer, Integer>();
    availableValues = new HashMap<String, Integer>();
    varArgsStackSize = 0;

    this.kernelOp = kernelOp;
  }

  protected Boolean registerEquals(Register a, Register b) {
    return a.name.equals(b.name);
  }

  protected Register findRegister(Register toFind, Register[] registerArray) {
    for (int i = 0; i < registerArray.length; i++) {
      if (registerEquals(toFind, registerArray[i])) {
        return registerArray[i];
      }
    }
    return null;
  }

  public void pushArguments(AMD64MacroAssembler masm) {
    Register tempReg = xmmRegistersAVX512[31];

    Register tempGenReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum - 1]);

    // Push Variable arguments in reverse order
    masm.movl(loopIndex, 0);
    for (int i = varArgProperties.length - 1; i >= 0; i--) {
      if (varArgProperties[i] == 2) { // index is j
        for (int varArgIndex : variableArgsStackOffsets.keySet()) {
          variableArgsStackOffsets.put(
              varArgIndex, variableArgsStackOffsets.get(varArgIndex) + 128);
        }
        variableArgsStackOffsets.put(i, 0);
        masm.movq(
            tempArrayAddressReg,
            new AMD64Address(
                arrsPtr,
                loopIndex,
                OBJECT_ARRAY_INDEX_SCALE,
                OBJECT_ARRAY_BASE_OFFSET + 24 + 8 * i));
        masm.vmovupd(
            tempReg,
            new AMD64Address(
                tempArrayAddressReg,
                jPos,
                DOUBLE_ARRAY_INDEX_SCALE,
                DOUBLE_ARRAY_BASE_OFFSET + 64));
        masm.subq(rsp, 64);
        masm.vmovupd(new AMD64Address(rsp), tempReg);
        varArgsStackSize += 64;

        masm.vmovupd(
            tempReg,
            new AMD64Address(
                tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET));
        masm.subq(rsp, 64);
        masm.vmovupd(new AMD64Address(rsp), tempReg);
        varArgsStackSize += 64;
      } else if (varArgProperties[i] == 1) {
        for (int varArgIndex : variableArgsStackOffsets.keySet()) {
          variableArgsStackOffsets.put(
              varArgIndex, variableArgsStackOffsets.get(varArgIndex) + 128);
        }
        variableArgsStackOffsets.put(i, 0);

        masm.movq(
            tempArrayAddressReg,
            new AMD64Address(
                arrsPtr,
                loopIndex,
                OBJECT_ARRAY_INDEX_SCALE,
                OBJECT_ARRAY_BASE_OFFSET + 24 + 8 * i));
        masm.vmovupd(
            tempReg,
            new AMD64Address(
                tempArrayAddressReg,
                iPos,
                DOUBLE_ARRAY_INDEX_SCALE,
                DOUBLE_ARRAY_BASE_OFFSET + 64));
        masm.subq(rsp, 64);
        masm.vmovupd(new AMD64Address(rsp), tempReg);
        varArgsStackSize += 64;

        masm.vmovupd(
            tempReg,
            new AMD64Address(
                tempArrayAddressReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET));
        masm.subq(rsp, 64);
        masm.vmovupd(new AMD64Address(rsp), tempReg);
        varArgsStackSize += 64;
      } else if (varArgProperties[i] == 3) { // varArg[i][j]
        for (int varArgIndex : variableArgsStackOffsets.keySet()) {
          variableArgsStackOffsets.put(
              varArgIndex, variableArgsStackOffsets.get(varArgIndex) + 128 * 12);
        }
        variableArgsStackOffsets.put(i, 0);

        Register genReg1 = asRegister(kernelOp.remainingRegValues[remainingRegisterNum - 2]);
        Register genReg2 = asRegister(kernelOp.remainingRegValues[remainingRegisterNum - 3]);
        Register genReg3 = asRegister(kernelOp.remainingRegValues[remainingRegisterNum - 4]);

        masm.movq(genReg1, mLength);
        masm.subq(genReg1, iPos);

        Label cmpLabel = new Label();
        masm.cmpl(genReg1, 12);
        masm.jcc(AMD64Assembler.ConditionFlag.LessEqual, cmpLabel);
        masm.movl(genReg1, 12);
        masm.bind(cmpLabel);

        Label endLabel = new Label();
        Label startLabel = new Label();

        masm.subq(rsp, 128 * 12);
        varArgsStackSize += 128 * 12;
        masm.imull(genReg1, genReg1, 128);
        masm.movq(genReg2, 0);
        masm.movq(genReg3, iPos);

        masm.movq(
            tempArrayAddressReg,
            new AMD64Address(
                arrsPtr,
                loopIndex,
                OBJECT_ARRAY_INDEX_SCALE,
                OBJECT_ARRAY_BASE_OFFSET + 24 + 8 * i));

        masm.bind(startLabel);
        masm.cmpl(genReg2, genReg1);
        masm.jcc(AMD64Assembler.ConditionFlag.GreaterEqual, endLabel);

        masm.movq(
            tempGenReg,
            new AMD64Address(
                tempArrayAddressReg, genReg3, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));

        masm.vmovupd(
            tempReg,
            new AMD64Address(tempGenReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET));
        masm.vmovupd(new AMD64Address(rsp, genReg2, AMD64Address.Scale.Times1, 0), tempReg);
        masm.vmovupd(
            tempReg,
            new AMD64Address(
                tempGenReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + 64));
        masm.vmovupd(new AMD64Address(rsp, genReg2, AMD64Address.Scale.Times1, 64), tempReg);

        masm.incrementl(genReg3, 1);
        masm.incrementl(genReg2, 128);
        masm.jmp(startLabel);
        masm.bind(endLabel);

        /*
        for(int k = 11; k >= 0; k--) {
            masm.movq(tempGenReg, new AMD64Address(tempArrayAddressReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+8*k));
            masm.vmovupd(tempReg, new AMD64Address(tempGenReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+64));
            masm.subq(rsp, 64);
            masm.vmovupd(new AMD64Address(rsp), tempReg);
            varArgsStackSize += 64;

            masm.vmovupd(tempReg, new AMD64Address(tempGenReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET));
            masm.subq(rsp, 64);
            masm.vmovupd(new AMD64Address(rsp), tempReg);
            varArgsStackSize += 64;
        }
        masm.movl(loopIndex, 0);
        */
      }
    }

    // Push Constant arguments in reverse order
    constArgsStackSize = 0;
    for (int i = constArgs.length - 1; i >= 0; i--) {
      masm.movq(tempGenReg, Double.doubleToLongBits(constArgs[i]));
      masm.subq(rsp, constArgStackSlotSize);
      masm.movq(new AMD64Address(rsp), tempGenReg);
      constArgsStackSize += constArgStackSlotSize;
    }
  }

  protected abstract void loadA(AMD64MacroAssembler masm, int iIndex, int offset, int dstRegNum);

  protected abstract void loadB(AMD64MacroAssembler masm, int jIndex, int offset, int dstRegNum);

  protected abstract void loadVarArg(
      AMD64MacroAssembler masm, int argIndex, int iIndex, int jIndex, int dstRegNum);

  protected void emitSubiterCode(AMD64MacroAssembler masm, int iIndex, int jIndex, int offset) {
    ChangeableString codeString = new ChangeableString(opStringRaw);
    final int opLength = GotoOpCode.INDEXLENGTH;
    while (codeString.toString().length() > 0) {
      String op = codeString.cutOff(opLength);
      String opType = op.substring(0, 2);
      if (op.equals(GotoOpCode.LOAD)) {
        int dstRegNum = availableValues.get(getRegisterString(codeString));
        String loadType = getRegisterString(codeString);
        switch (loadType.substring(0, opLength)) {
          case GotoOpCode.A:
            loadA(masm, iIndex, offset, dstRegNum);
            break;
          case GotoOpCode.B:
            loadB(masm, jIndex, offset, dstRegNum);
            break;
          case GotoOpCode.VARIABLEARG:
            int argIndex = Integer.parseInt(loadType.substring(opLength, opLength + opLength), 2);
            loadVarArg(masm, argIndex, iIndex, jIndex, dstRegNum);
            break;
        }
      } else if (opType.equals(GotoOpCode.OP)) {
        int dstRegNum = availableValues.get(getRegisterString(codeString));
        int src0RegNum = availableValues.get(getRegisterString(codeString));
        int src1RegNum = availableValues.get(getRegisterString(codeString));
        switch (op) {
          case GotoOpCode.ADD:
            masm.vaddpd(
                xmmRegistersAVX512[dstRegNum],
                xmmRegistersAVX512[src0RegNum],
                xmmRegistersAVX512[src1RegNum]);
            break;
          case GotoOpCode.SUB:
            AMD64Assembler.VexRVMOp.VSUBPD.emit(
                masm,
                AVXSize.ZMM,
                xmmRegistersAVX512[dstRegNum],
                xmmRegistersAVX512[src0RegNum],
                xmmRegistersAVX512[src1RegNum]);
            break;
          case GotoOpCode.MUL:
            masm.vmulpd(
                xmmRegistersAVX512[dstRegNum],
                xmmRegistersAVX512[src0RegNum],
                xmmRegistersAVX512[src1RegNum]);
            break;
          case GotoOpCode.FMADD:
            masm.vfmadd231pd(
                xmmRegistersAVX512[dstRegNum],
                xmmRegistersAVX512[src0RegNum],
                xmmRegistersAVX512[src1RegNum]);
            break;
        }
      } else if (opType.equals(GotoOpCode.CMPOP)) {
        // int dstRegNum = availableValues.get(getRegisterString(codeString));
        getRegisterString(codeString);
        int src0RegNum = availableValues.get(getRegisterString(codeString));
        int src1RegNum = availableValues.get(getRegisterString(codeString));

        int cmpOperation = 0;
        switch (op) {
          case GotoOpCode.LT:
            cmpOperation = 1;
            break;
          case GotoOpCode.GT:
            cmpOperation = 0x0e;
            break;
        }
        masm.vcmppd(
            k2, xmmRegistersAVX512[src0RegNum], xmmRegistersAVX512[src1RegNum], cmpOperation);
      } else if (opType.equals(GotoOpCode.MASKOP)) {
        // int maskRegNum = availableValues.get(getRegisterString(codeString));
        getRegisterString(codeString);
        int dstRegNum = availableValues.get(getRegisterString(codeString));
        int src0RegNum = availableValues.get(getRegisterString(codeString));
        int src1RegNum = availableValues.get(getRegisterString(codeString));
        // Todo: Parse mask register
        switch (op) {
          case GotoOpCode.MASKADD:
            masm.vaddpd(
                xmmRegistersAVX512[dstRegNum],
                xmmRegistersAVX512[src0RegNum],
                xmmRegistersAVX512[src1RegNum],
                k2);
            break;
          case GotoOpCode.MASKSUB:
            AMD64Assembler.VexRVMOp.VSUBPD.emit(
                masm,
                AVXSize.ZMM,
                xmmRegistersAVX512[dstRegNum],
                xmmRegistersAVX512[src0RegNum],
                xmmRegistersAVX512[src1RegNum],
                k2);
            break;
        }
      }
    }
  }

  private String getRegisterString(ChangeableString dst) {
    String op = dst.cutOff(GotoOpCode.INDEXLENGTH);
    switch (op) {
      case GotoOpCode.A:
      case GotoOpCode.B:
      case GotoOpCode.C:
        return op;
      case GotoOpCode.REG:
      case GotoOpCode.MASKREG:
      case GotoOpCode.CONSTARG:
      case GotoOpCode.VARIABLEARG:
        return op + dst.cutOff(GotoOpCode.INDEXLENGTH);
    }
    return "";
  }

  protected abstract void emitKernelCode(AMD64MacroAssembler masm, int aLength, int bLength);

  public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
    arrsPtr = asRegister(kernelOp.arrsValue);
    kPanelSize = asRegister(kernelOp.kPanelSizeValue);
    iPos = asRegister(kernelOp.iValue);
    kPos = asRegister(kernelOp.kValue);
    jPos = asRegister(kernelOp.jValue);
    loopIndex = asRegister(kernelOp.loopIndexValue);
    tempArrayAddressReg = asRegister(kernelOp.tempArrayAddressRegValue);

    // Make sure that iPos is first!
    useAsAddressRegs = new Register[] {iPos, arrsPtr, kPos, r15, kPanelSize};
    kPanelSizeIndexFromBehind = 0;
    for (int i = 0; i < useAsAddressRegs.length; i++) {
      if (useAsAddressRegs[i] == kPanelSize) {
        kPanelSizeIndexFromBehind = i;
      }
    }
    kPanelSizeIndexFromBehind = useAsAddressRegs.length - kPanelSizeIndexFromBehind - 1;

    masm.push(kPanelSize);

    // Check if kPanelSize overflows bounds.
    masm.addq(kPanelSize, kPos);
    masm.cmpl(kPanelSize, kLength);
    Label kPanelSizeCheckLabel = new Label();
    masm.jcc(AMD64MacroAssembler.ConditionFlag.LessEqual, kPanelSizeCheckLabel);
    masm.movq(kPanelSize, kLength);
    masm.bind(kPanelSizeCheckLabel);
    masm.subq(kPanelSize, kPos);

    // Push arguments in reverse order
    pushArguments(masm);

    masm.movq(tempArrayAddressReg, iPos);
    masm.addq(tempArrayAddressReg, initialALength);

    Label endLabel = new Label();
    Label loopLabel = new Label();
    masm.cmpl(tempArrayAddressReg, mLength);
    masm.jcc(AMD64Assembler.ConditionFlag.Greater, loopLabel);
    emitKernelCode(masm, initialALength, initialBLength);
    masm.jmp(endLabel);

    masm.bind(loopLabel);

    int tempALength = initialALength - 1;

    while (tempALength > 0) {
      masm.movq(tempArrayAddressReg, iPos);
      masm.addq(tempArrayAddressReg, tempALength);

      Label loopLabel2 = new Label();
      // Check if iPos + tempALength > mLength
      masm.cmpl(tempArrayAddressReg, mLength);
      masm.jcc(AMD64Assembler.ConditionFlag.Greater, loopLabel2);
      emitKernelCode(masm, tempALength, initialBLength);
      masm.jmp(endLabel);
      masm.bind(loopLabel2);

      tempALength -= 1;
    }

    masm.bind(endLabel);

    // Pop arguments + B
    masm.addq(rsp, constArgsStackSize + varArgsStackSize);

    // Restore original value of kPanelSize
    masm.pop(kPanelSize);
  }

  protected static void debugPrint(String msg) {
    PrintWriter debugLog = null;
    try {
      debugLog =
          new PrintWriter(
              new FileWriter("/home/junyoung2/project/adaptive-code-generation/log.txt", true));
    } catch (Exception e) {
      System.out.println(e);
    }
    debugLog.println(msg);
    debugLog.close();
  }
}
