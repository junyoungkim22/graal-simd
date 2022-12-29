package org.graalvm.compiler.lir.amd64.vec.GotoKernel;

import static jdk.vm.ci.amd64.AMD64.cpuRegisters;
import static jdk.vm.ci.amd64.AMD64.k2;
import static jdk.vm.ci.amd64.AMD64.rsp;
import static jdk.vm.ci.amd64.AMD64.xmmRegistersAVX512;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import java.util.HashMap;
import java.util.Map;
import jdk.vm.ci.code.Register;
import org.graalvm.compiler.asm.Label;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Assembler;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.lir.amd64.vec.GotoOpCode;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;

public final class GotoABTKernel extends GotoKernel {
  public GotoABTKernel(
      LIRGeneratorTool tool,
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
      GotoKernelOp kernelOp) {
    super(
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
        kernelOp);
  }

  public void subIter(
      int aLength,
      int bLength,
      int offset,
      int prefetchDistance,
      AMD64MacroAssembler masm,
      Map<String, Integer> simdRegisters,
      Register[] aTempArrayAddressRegs) {
    AMD64Address aAddress, bAddress;
    /*
    if(prefetchDistance > 0) {
        bAddress = new AMD64Address(loopIndex, OBJECT_ARRAY_BASE_OFFSET+(offset*8)+(prefetchDistance*8));
        masm.movq(tempArrayAddressReg, bAddress);

        for(int j = 0; j < bLength; j++) {
            bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
            masm.prefetcht0(bAddress);
        }
    }

    bAddress = new AMD64Address(loopIndex, OBJECT_ARRAY_BASE_OFFSET+(offset*8));
    masm.movq(tempArrayAddressReg, bAddress);

    for(int j = 0; j < bLength; j++) {
        bAddress = new AMD64Address(tempArrayAddressReg, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(j*64));
        masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], bAddress);
    }
    */
    for (int j = 0; j < bLength; j++) {
      bAddress =
          new AMD64Address(
              loopIndex,
              xmmRegistersAVX512[simdRegisters.get("BAddress" + String.valueOf(j))],
              AMD64Address.Scale.Times1,
              12 + (offset * 4));
      masm.kxnorw(k2, k2, k2);
      masm.vpgatherqq(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], k2, bAddress);
    }

    // HashMap<String, Integer> availableValues = new HashMap<String, Integer>();
    availableValues.put(GotoOpCode.A, simdRegisters.get("A"));

    for (int i = 0; i < aLength; i++) {
      if (i < aTempArrayAddressNumLimit) {
        aAddress =
            new AMD64Address(
                aTempArrayAddressRegs[i],
                loopIndex,
                AMD64Address.Scale.Times1,
                DOUBLE_ARRAY_BASE_OFFSET + (offset * 8));
      } else {
        // Todo: read from stack
        masm.movq(tempArrayAddressReg, new AMD64Address(rsp, 8 * (i - aTempArrayAddressNumLimit)));
        aAddress =
            new AMD64Address(
                tempArrayAddressReg,
                loopIndex,
                AMD64Address.Scale.Times1,
                DOUBLE_ARRAY_BASE_OFFSET + (offset * 8));
      }
      masm.vbroadcastsd(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);

      for (int k = 0; k < varArgProperties.length; k++) {
        if (varArgProperties[k] == 1) {
          loadVarArg(masm, k, i, -1, simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
          availableValues.put(
              GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k),
              simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
        }
      }

      for (int j = 0; j < bLength; j++) {
        availableValues.put(GotoOpCode.B, simdRegisters.get("B" + String.valueOf(j)));
        availableValues.put(
            GotoOpCode.C, simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j)));
        for (int k = 0; k < varArgProperties.length; k++) {
          if (varArgProperties[k] == 2) {
            availableValues.put(
                GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k),
                simdRegisters.get("VARIABLEARG" + String.valueOf(k) + "_" + String.valueOf(j)));
          }
        }
        Register aRegister = xmmRegistersAVX512[simdRegisters.get("A")];
        Register bRegister = xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))];
        Register cRegister =
            xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))];

        for (int k = 0; k < varArgProperties.length; k++) {
          if (!toLoad.contains(GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k))) {
            if (varArgProperties[k] == 2) {
              availableValues.put(
                  GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k),
                  simdRegisters.get("VARIABLEARG" + String.valueOf(k) + "_" + String.valueOf(j)));
            } else if (varArgProperties[k] == 3) {
              availableValues.put(
                  GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k),
                  simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
              loadVarArg(masm, k, i, j, simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
            }
          }
        }

        emitSubiterCode(masm, i, j, offset);
        // masm.vaddpd(cRegister, aRegister, cRegister);
        // masm.vaddpd(cRegister, bRegister, cRegister);
        // masm.vmovupd(cRegister, bRegister);
        // masm.vfmadd231pd(cRegister, aRegister, bRegister);
        /*
        availableValues.put(GotoOpCode.B, simdRegisters.get("B" + String.valueOf(j)));
        availableValues.put(GotoOpCode.VARIABLEARG + "00000", tempRegNums[0]+j);
        availableValues.put(GotoOpCode.C, simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j)));
        exprDag.createCode(availableValues, new int[]{29, 30, 31}, masm);
        */
      }
    }
  }

  protected void loadA(AMD64MacroAssembler masm, int iIndex, int offset, int dstRegNum) {
    return;
  }

  protected void loadB(AMD64MacroAssembler masm, int jIndex, int offset, int dstRegNum) {
    return;
  }

  protected void loadVarArg(
      AMD64MacroAssembler masm, int argIndex, int iIndex, int jIndex, int dstRegNum) {
    if (varArgProperties[argIndex] == 2) {
      int varArgOffset =
          stackOffsetToConstArgs + constArgsStackSize + variableArgsStackOffsets.get(argIndex);
      masm.vmovupd(
          xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset + 64 * jIndex));
    } else if (varArgProperties[argIndex] == 1) {
      int varArgOffset =
          stackOffsetToConstArgs + constArgsStackSize + variableArgsStackOffsets.get(argIndex);

      masm.leaq(tempArrayAddressReg, new AMD64Address(rsp, varArgOffset + 8 * iIndex));
      masm.vbroadcastsd(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempArrayAddressReg));

      // Todo: optimize into the below code! (vbroadcastsd does not work for some addresses)
      // masm.vbroadcastsd(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp,
      // varArgOffset+8*iIndex));
    } else if (varArgProperties[argIndex] == 3) {
      int varArgOffset =
          stackOffsetToConstArgs + constArgsStackSize + variableArgsStackOffsets.get(argIndex);
      masm.vmovupd(
          xmmRegistersAVX512[dstRegNum],
          new AMD64Address(rsp, varArgOffset + (128 * iIndex) + 64 * jIndex));
    }
  }

  protected void emitKernelCode(AMD64MacroAssembler masm, int aLength, int bLength) {
    aTempArrayAddressNumLimit =
        aLength < remainingRegisterNum + useAsAddressRegs.length
            ? aLength
            : remainingRegisterNum + useAsAddressRegs.length;
    Register aTempArrayAddressRegs[] = new Register[aTempArrayAddressNumLimit];
    for (int i = 0; i < aTempArrayAddressNumLimit; i++) {
      if (i < useAsAddressRegs.length) {
        aTempArrayAddressRegs[i] = findRegister(useAsAddressRegs[i], cpuRegisters);
      } else {
        aTempArrayAddressRegs[i] =
            asRegister(kernelOp.remainingRegValues[i - useAsAddressRegs.length]);
      }
    }

    // Declare SIMD registers
    int registerIndex = 0;
    HashMap<String, Integer> simdRegisters = new HashMap<String, Integer>();

    for (int i = 0; i < bLength; i++) {
      simdRegisters.put("BAddress" + String.valueOf(i), registerIndex++);
    }

    simdRegisters.put("A", registerIndex++);
    for (int i = 0; i < bLength; i++) {
      simdRegisters.put("B" + String.valueOf(i), registerIndex++);
    }
    for (int i = 0; i < aLength; i++) {
      for (int j = 0; j < bLength; j++) {
        simdRegisters.put("C" + String.valueOf(i) + String.valueOf(j), registerIndex++);
      }
    }
    for (int i = 0; i < constArgs.length; i++) {
      availableValues.put(
          GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i), registerIndex++);
    }
    for (int i = 0; i < varArgProperties.length; i++) {
      if (varArgProperties[i] == 2) {
        for (int j = 0; j < bLength; j++) {
          simdRegisters.put(
              "VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j), registerIndex++);
        }
      } else if (varArgProperties[i] == 1 || varArgProperties[i] == 3) {
        simdRegisters.put("VARIABLEARG" + String.valueOf(i), registerIndex++);
      }
    }
    int remainingSimdRegisterNum = xmmRegistersAVX512.length - registerIndex;
    for (int i = 0; i < remainingSimdRegisterNum; i++) {
      availableValues.put(GotoOpCode.REG + GotoOpCode.toOpLengthBinaryString(i), registerIndex++);
    }

    /*
    for(int i = 0; i < xmmRegistersAVX512.length - registerIndex; i++) {
        availableValues.put(GotoOpCode.REG + GotoOpCode.toOpLengthBinaryString(i), registerIndex++);
    }

    tempRegNums = new int[xmmRegistersAVX512.length - registerIndex];
    for(int i = 0; i < tempRegNums.length; i++) {
        tempRegNums[i] = registerIndex++;
    }
    */

    AMD64Address resultAddress, aAddress, bAddress;

    Register tempGenReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum - 1]);

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

    masm.movq(loopIndex, 0);
    masm.movq(
        tempGenReg,
        new AMD64Address(
            arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 8));
    for (int i = 0; i < bLength; i++) {
      // Register resultAddressRegister = xmmRegistersAVX512[simdRegisters.get("B" +
      // String.valueOf(i))];
      Register resultAddressRegister =
          xmmRegistersAVX512[simdRegisters.get("BAddress" + String.valueOf(i))];
      for (int j = 7; j >= 0; j--) {
        masm.movq(
            tempArrayAddressReg,
            new AMD64Address(
                tempGenReg,
                jPos,
                OBJECT_ARRAY_INDEX_SCALE,
                OBJECT_ARRAY_BASE_OFFSET + (i * 64) + (j * 8)));
        masm.push(tempArrayAddressReg);
      }
      masm.vmovupd(resultAddressRegister, new AMD64Address(rsp));
      masm.addq(rsp, 8 * 8);
    }

    /*
    masm.movq(loopIndex, 0);

    // Store &B[0] in tempArrPtr
    masm.movq(tempGenReg, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+8));

    // Store (&B[0] + k*8) in loop index
    masm.leaq(loopIndex, new AMD64Address(tempGenReg, kPos, OBJECT_ARRAY_INDEX_SCALE, 0));
    masm.push(loopIndex);

    // Store (&B[k] + kPanelSize*8) in kPanelSize register
    masm.leaq(kPanelSize, new AMD64Address(loopIndex, kPanelSize, OBJECT_ARRAY_INDEX_SCALE, 0));

    // Store &B[0] in loopIndex
    masm.movq(loopIndex, tempGenReg);
    */

    // Store k*8 in loop index
    masm.imull(loopIndex, kPos, 8);

    // Remove this later
    masm.push(loopIndex);

    // Store k*8 + kPanelSize*8 in kPanelSize register
    masm.addq(kPanelSize, kPos);
    masm.imull(kPanelSize, kPanelSize, 8);

    // Push registers to be used for storing addresses of A on stack
    for (int i = 0; i < useAsAddressRegs.length; i++) {
      masm.push(useAsAddressRegs[i]);
    }

    // Load A
    masm.movq(tempArrayAddressReg, 0);
    masm.movq(
        tempGenReg,
        new AMD64Address(
            arrsPtr, tempArrayAddressReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));

    // Push Addresses of A that are not savable on a register on stack first, so register iPos can
    // be pushed on stack last
    int numOfAAddressOnStack = 0;
    for (int i = aLength - 1; i >= 0; i--) {
      if (i < aTempArrayAddressNumLimit) {
        if (i == 11) {
          aAddress =
              new AMD64Address(
                  tempGenReg,
                  iPos,
                  OBJECT_ARRAY_INDEX_SCALE,
                  OBJECT_ARRAY_BASE_OFFSET + (i * 8)); // Get (i + 11)th row of A
          masm.movq(tempArrayAddressReg, aAddress);
          // masm.subq(tempArrayAddressReg, loopIndex);
          masm.push(tempArrayAddressReg);
        } else {
          aAddress =
              new AMD64Address(
                  tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + (i * 8));
          masm.movq(aTempArrayAddressRegs[i], aAddress);
          // masm.subq(aTempArrayAddressRegs[i], loopIndex);
        }
      } else {
        aAddress =
            new AMD64Address(
                tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + (i * 8));
        masm.movq(tempArrayAddressReg, aAddress);
        // masm.subq(tempArrayAddressReg, loopIndex);
        masm.push(tempArrayAddressReg);
        numOfAAddressOnStack++;
      }
    }

    if (aTempArrayAddressNumLimit >= 12) {
      // When aTempArrayAddressNumLimit >= 12, aTempArrayAddressRegs[11] == tempGenReg
      masm.pop(aTempArrayAddressRegs[11]);
    }
    // Calculate offset to constant arguments
    stackOffsetToConstArgs = numOfAAddressOnStack * 8 + useAsAddressRegs.length * 8 + 8;

    // masm.movq(loopIndex, new AMD64Address(rsp,
    // (numOfAAddressOnStack*8)+(useAsAddressRegs.length*8)));

    int prefetchDistance = 0;
    int mult = 8;

    for (int i = 0; i < varArgProperties.length; i++) {
      if (varArgProperties[i] == 2) {
        int varArgOffset =
            stackOffsetToConstArgs + constArgsStackSize + variableArgsStackOffsets.get(i);
        for (int j = 0; j < bLength; j++) {
          masm.vmovupd(
              xmmRegistersAVX512[
                  simdRegisters.get("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j))],
              new AMD64Address(rsp, varArgOffset + 64 * j));
        }
      }
    }

    for (int i = 0; i < constArgs.length; i++) {
      masm.vbroadcastsd(
          xmmRegistersAVX512[
              availableValues.get(GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i))],
          new AMD64Address(rsp, stackOffsetToConstArgs + constArgStackSlotSize * i));
    }

    // Subtract prefetchDistance*8 from kPanelSize
    masm.subq(
        new AMD64Address(rsp, (numOfAAddressOnStack * 8) + (8 * kPanelSizeIndexFromBehind)),
        prefetchDistance * mult);

    Label loopLabel = new Label();

    int unrollFactor = 1;

    // Iterate from kPos to kPos + kPanelSize-1 and store partial results in c** registers
    masm.bind(loopLabel);
    for (int i = 0; i < unrollFactor; i++) {
      subIter(aLength, bLength, i, prefetchDistance, masm, simdRegisters, aTempArrayAddressRegs);
    }
    masm.addq(loopIndex, unrollFactor * mult);
    masm.cmpq(
        loopIndex,
        new AMD64Address(rsp, (numOfAAddressOnStack * 8) + (8 * kPanelSizeIndexFromBehind)));
    masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

    masm.addq(
        new AMD64Address(rsp, (numOfAAddressOnStack * 8) + (8 * kPanelSizeIndexFromBehind)),
        prefetchDistance * mult);

    /*
    loopLabel = new Label();
    masm.bind(loopLabel);
    //subIter(aLength, bLength, 0, 0, masm, simdRegisters, aTempArrayAddressRegs);
    masm.addq(loopIndex, mult);
    masm.cmpl(loopIndex, new AMD64Address(rsp, (numOfAAddressOnStack*8)+(8*kPanelSizeIndexFromBehind)));
    masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);
    */

    masm.addq(rsp, numOfAAddressOnStack * 8);

    // Restore registers pushed to stack
    for (int i = useAsAddressRegs.length - 1; i >= 0; i--) {
      masm.pop(useAsAddressRegs[i]);
    }

    // Pop B
    masm.pop(loopIndex);

    // Store partial results in result array
    masm.movl(loopIndex, 0);
    masm.movq(
        tempGenReg,
        new AMD64Address(
            arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 16));
    for (int i = 0; i < aLength; i++) {
      resultAddress =
          new AMD64Address(
              tempGenReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + (i * 8));
      masm.movq(tempArrayAddressReg, resultAddress);
      for (int j = 0; j < bLength; j++) {
        resultAddress =
            new AMD64Address(
                tempArrayAddressReg,
                jPos,
                DOUBLE_ARRAY_INDEX_SCALE,
                DOUBLE_ARRAY_BASE_OFFSET + (j * 64));
        masm.vaddpd(
            xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))],
            xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))],
            resultAddress);
        masm.vmovupd(
            resultAddress,
            xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j))]);
      }
    }
  }
}
