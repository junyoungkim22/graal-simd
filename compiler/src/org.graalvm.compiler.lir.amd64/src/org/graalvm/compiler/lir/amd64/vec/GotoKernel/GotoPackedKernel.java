package org.graalvm.compiler.lir.amd64.vec.GotoKernel;

import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rdx;
import static jdk.vm.ci.amd64.AMD64.rsp;
import static jdk.vm.ci.amd64.AMD64.xmmRegistersAVX512;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static org.graalvm.compiler.asm.amd64.AMD64BaseAssembler.OperandSize.DWORD;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Stack;
import jdk.vm.ci.code.Register;
import org.graalvm.compiler.asm.Label;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Assembler;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.lir.amd64.vec.GotoOpCode;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;

public final class GotoPackedKernel extends GotoKernel {
  private boolean interleave;
  private Map<Integer, Register> varArgAddresses;
  private Queue<Register> availableGenRegs;
  private int kPack;
  private int aAddressOffset;
  private int aAlignmentOffset;
  private int bAlignmentOffset;

  public GotoPackedKernel(
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
      GotoKernelOp kernelOp,
      int[] miscArgs) {
    super(
        tool,
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
    this.interleave = true;
    this.kPack = miscArgs[0];
    this.aAlignmentOffset = miscArgs[1];
    this.bAlignmentOffset = miscArgs[2];

    // There is a bug where an AMD64Address with offset 0x40 or -0x40 will be generated as offset
    // 0x8 or -0x8
    // for vbroadcastsd. Use the following variable to control the offset of AMD64Address so that
    // 0x40 or 0x-40
    // is not reached.
    this.aAddressOffset = 256;
  }

  public void subIter(
      int aLength,
      int bLength,
      int offset,
      int prefetchDistance,
      AMD64MacroAssembler masm,
      Map<String, Integer> simdRegisters,
      Register aPtr,
      Register bPtr,
      Register aIndex,
      Register bIndex) {
    AMD64Address aAddress, bAddress;

    if (prefetchDistance > 0) {
      for (int j = 0; j < bLength; j++) {
        bAddress =
            new AMD64Address(
                bPtr,
                bIndex,
                DOUBLE_ARRAY_INDEX_SCALE,
                DOUBLE_ARRAY_BASE_OFFSET
                    + (j * 64)
                    + (offset * 8 * bLength * 8)
                    + (prefetchDistance * 8 * bLength * 8));
        masm.prefetcht0(bAddress);
      }
    }

    if (interleave) {
      for (int j = 0; j < bLength * 2; j++) {
        bAddress =
            new AMD64Address(
                bPtr,
                bIndex,
                DOUBLE_ARRAY_INDEX_SCALE,
                DOUBLE_ARRAY_BASE_OFFSET
                    + ((j / 2) * 64)
                    + ((j % 2) * 8)
                    + (offset * 8 * bLength * 8));
        masm.vmovddup(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], bAddress);
      }
    } else {
      for (int j = 0; j < bLength; j++) {
        bAddress =
            new AMD64Address(
                bPtr,
                bIndex,
                DOUBLE_ARRAY_INDEX_SCALE,
                DOUBLE_ARRAY_BASE_OFFSET + (j * 64) + (offset * 8 * bLength * 8));
        masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))], bAddress);
      }
    }

    availableValues.put(GotoOpCode.A, simdRegisters.get("A"));

    if (interleave) {
      for (int i = 0; i < aLength; i += 2) {
        aAddress =
            new AMD64Address(
                aPtr,
                aIndex,
                DOUBLE_ARRAY_INDEX_SCALE,
                DOUBLE_ARRAY_BASE_OFFSET + (i * 8) - aAddressOffset + (offset * 8 * aLength));
        masm.vbroadcastf32x4(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);

        for (int k = 0; k < varArgProperties.length; k++) {
          if (varArgProperties[k] == 1) {
            loadVarArg(masm, k, i, -1, simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
            availableValues.put(
                GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k),
                simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
          }
        }

        for (int j = 0; j < bLength * 2; j++) {
          availableValues.put(GotoOpCode.B, simdRegisters.get("B" + String.valueOf(j)));
          availableValues.put(
              GotoOpCode.C,
              simdRegisters.get("C" + String.valueOf(i + (j % 2)) + String.valueOf(j / 2)));
          for (int k = 0; k < varArgProperties.length; k++) {
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
          emitSubiterCode(masm, i, j, offset);

          // masm.vfmadd231pd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i+(j%2)) +
          // String.valueOf(j/2))],
          //               xmmRegistersAVX512[simdRegisters.get("A")],
          // xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
        }
      }
    } else {
      for (int i = 0; i < aLength; i++) {
        aAddress =
            new AMD64Address(
                aPtr,
                aIndex,
                DOUBLE_ARRAY_INDEX_SCALE,
                DOUBLE_ARRAY_BASE_OFFSET + (i * 8) - aAddressOffset + (offset * 8 * aLength));

        // There is a bug in Graal where for vbroadcastsd offset 0xc8 becomes 0x18. Fix later.
        masm.vbroadcastsd(xmmRegistersAVX512[simdRegisters.get("A")], aAddress);

        /*
        Register temp = availableGenRegs.poll();
        masm.movq(temp, aAddress);
        masm.vpbroadcastq(xmmRegistersAVX512[simdRegisters.get("A")], temp);
        availableGenRegs.offer(temp);
        */

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
            } else if (varArgProperties[k] == 3) {
              availableValues.put(
                  GotoOpCode.VARIABLEARG + GotoOpCode.toOpLengthBinaryString(k),
                  simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
              loadVarArg(masm, k, i, j, simdRegisters.get("VARIABLEARG" + String.valueOf(k)));
            }
          }
          emitSubiterCode(masm, i, j, offset);

          // masm.vfmadd231pd(xmmRegistersAVX512[simdRegisters.get("C" + String.valueOf(i) +
          // String.valueOf(j))],
          //                xmmRegistersAVX512[simdRegisters.get("A")],
          // xmmRegistersAVX512[simdRegisters.get("B" + String.valueOf(j))]);
        }
      }
    }
  }

  protected void loadA(AMD64MacroAssembler masm, int iIndex, int offset, int dstRegNum) {}

  protected void loadB(AMD64MacroAssembler masm, int jIndex, int offset, int dstRegNum) {}

  protected void loadVarArg(
      AMD64MacroAssembler masm, int argIndex, int iIndex, int jIndex, int dstRegNum) {
    if (interleave) {
      if (varArgProperties[argIndex] == 2) {
        int varArgOffset =
            stackOffsetToConstArgs + constArgsStackSize + variableArgsStackOffsets.get(argIndex);
        masm.vmovupd(
            xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset + 64 * jIndex));
      } else if (varArgProperties[argIndex] == 1) {
        /*
        int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);

        masm.leaq(tempArrayAddressReg, new AMD64Address(rsp, varArgOffset+8*iIndex));
        masm.vbroadcastf32x4(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempArrayAddressReg));
        */

        /*
        Register zeroReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-4]);
        masm.movq(zeroReg, 0);
        masm.movq(tempArrayAddressReg, new AMD64Address(arrsPtr, zeroReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*argIndex));
        masm.vbroadcastf32x4(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempArrayAddressReg, iPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+(iIndex*8)));
        */

        masm.vbroadcastf32x4(
            xmmRegistersAVX512[dstRegNum],
            new AMD64Address(
                varArgAddresses.get(argIndex),
                iPos,
                DOUBLE_ARRAY_INDEX_SCALE,
                DOUBLE_ARRAY_BASE_OFFSET + (iIndex * 8)));

        // Todo: optimize into the below code! (vbroadcastsd does not work for some addresses)
        // masm.vbroadcastf32x4(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp,
        // varArgOffset+8*iIndex));
      } else if (varArgProperties[argIndex] == 3) {
        // int varArgOffset =
        // stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(argIndex);
        // masm.vmovddup(xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp,
        // varArgOffset+(128*iIndex)+64*jIndex));

        /*
        Register zeroReg = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-5]);
        masm.movq(zeroReg, 0);
        masm.movq(tempArrayAddressReg, new AMD64Address(arrsPtr, zeroReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*argIndex));
        Register tempRegAh = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-6]);
        masm.movq(tempRegAh, new AMD64Address(tempArrayAddressReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+(iIndex*8)));
        int j = jIndex;
        masm.vmovddup(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempRegAh, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET+DOUBLE_ARRAY_BASE_OFFSET+((j/2)*64)+((j%2)*8)));
        */
        Register zeroReg = availableGenRegs.poll();
        masm.movq(zeroReg, 0);
        masm.movq(
            zeroReg,
            new AMD64Address(
                arrsPtr,
                zeroReg,
                OBJECT_ARRAY_INDEX_SCALE,
                OBJECT_ARRAY_BASE_OFFSET + 24 + 8 * argIndex));
        masm.movq(
            zeroReg,
            new AMD64Address(
                zeroReg, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + (iIndex * 8)));
        int j = jIndex;
        masm.vmovddup(
            xmmRegistersAVX512[dstRegNum],
            new AMD64Address(
                zeroReg,
                jPos,
                DOUBLE_ARRAY_INDEX_SCALE,
                DOUBLE_ARRAY_BASE_OFFSET + ((j / 2) * 64) + ((j % 2) * 8)));
        availableGenRegs.offer(zeroReg);
      }
    } else {
      if (varArgProperties[argIndex] == 2) {
        int varArgOffset =
            stackOffsetToConstArgs + constArgsStackSize + variableArgsStackOffsets.get(argIndex);
        masm.vmovupd(
            xmmRegistersAVX512[dstRegNum], new AMD64Address(rsp, varArgOffset + 64 * jIndex));
      } else if (varArgProperties[argIndex] == 1) {

        int varArgOffset =
            stackOffsetToConstArgs + constArgsStackSize + variableArgsStackOffsets.get(argIndex);
        /*
        masm.leaq(tempArrayAddressReg, new AMD64Address(rsp, varArgOffset+8*iIndex));
        masm.vbroadcastsd(xmmRegistersAVX512[dstRegNum], new AMD64Address(tempArrayAddressReg));
        */

        Register tempRegAAAA = asRegister(kernelOp.remainingRegValues[remainingRegisterNum - 5]);
        masm.movq(tempRegAAAA, new AMD64Address(rsp, varArgOffset + 8 * iIndex));
        masm.vpbroadcastq(xmmRegistersAVX512[dstRegNum], tempRegAAAA);

        // masm.vbroadcastsd(xmmRegistersAVX512[dstRegNum], new
        // AMD64Address(varArgAddresses.get(argIndex), iPos, DOUBLE_ARRAY_INDEX_SCALE,
        // DOUBLE_ARRAY_BASE_OFFSET+(iIndex*8)));

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
  }

  protected void emitKernelCode(AMD64MacroAssembler masm, int aLength, int bLength) {
    if (aLength % 2 == 1) {
      interleave = false;
      aAddressOffset = 4;
    } else {
      interleave = true;
      aAddressOffset = 256;
    }

    for (int property : varArgProperties) {
      if (property == 3) {
        interleave = false;
        aAddressOffset = 4;
      }
    }

    // Declare SIMD registers
    int registerIndex = 0;
    HashMap<String, Integer> simdRegisters = new HashMap<String, Integer>();

    Register[] genRegs = new Register[remainingRegisterNum];
    for (int i = 0; i < remainingRegisterNum; i++) {
      genRegs[i] = asRegister(kernelOp.remainingRegValues[i]);
    }

    // Queue<Register> availableGenRegs = new LinkedList<>();
    availableGenRegs = new LinkedList<>();
    for (int i = 0; i < genRegs.length; i++) {
      availableGenRegs.offer(genRegs[i]);
    }

    /*
    Stack<Register> notRaxRdxRegs = new Stack<>();
    for(int i = 0; i < genRegs.length; i++) {
        addToNoDivStack(notRaxRdxRegs, genRegs[i]);
    }
    */

    simdRegisters.put("A", registerIndex++);
    if (interleave) {
      for (int i = 0; i < bLength * 2; i++) {
        simdRegisters.put("B" + String.valueOf(i), registerIndex++);
      }
    } else {
      for (int i = 0; i < bLength; i++) {
        simdRegisters.put("B" + String.valueOf(i), registerIndex++);
      }
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
        if (interleave) {
          for (int j = 0; j < bLength * 2; j++) {
            simdRegisters.put(
                "VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j), registerIndex++);
          }
        } else {
          for (int j = 0; j < bLength; j++) {
            simdRegisters.put(
                "VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j), registerIndex++);
          }
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
    varArgAddresses = new HashMap<Integer, Register>();
    int genRegCountdown = 4;

    for(int i = 0; i < varArgProperties.length; i++) {
        if(varArgProperties[i] == 2) {
            int varArgOffset = stackOffsetToConstArgs+constArgsStackSize+variableArgsStackOffsets.get(i);
            if(interleave) {
                for(int j = 0; j < bLength*2; j++) {
                    masm.vmovddup(xmmRegistersAVX512[simdRegisters.get("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j))], new AMD64Address(rsp, varArgOffset+((j/2)*64)+((j%2)*8)));
                }
            }
            else {
                for(int j = 0; j < bLength; j++) {
                    masm.vmovupd(xmmRegistersAVX512[simdRegisters.get("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j))], new AMD64Address(rsp, varArgOffset+64*j));
                }
            }
        }
        else if(varArgProperties[i] == 1) {
            Register varArgRegister = asRegister(kernelOp.remainingRegValues[remainingRegisterNum-genRegCountdown]);
            genRegCountdown++; // Todo: Do something when genRegCountdown == remainingRegisterNum
            masm.movq(varArgRegister, new AMD64Address(arrsPtr, loopIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET+24+8*i));
            varArgAddresses.put(i, varArgRegister);
        }
    }
    */

    for (int i = 0; i < constArgs.length; i++) {
      masm.vbroadcastsd(
          xmmRegistersAVX512[
              availableValues.get(GotoOpCode.CONSTARG + GotoOpCode.toOpLengthBinaryString(i))],
          new AMD64Address(rsp, stackOffsetToConstArgs + constArgStackSlotSize * i));
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

    int kernelWidth = bLength * 8;

    // Register kPosTemp = notRaxRdxRegs.pop();
    Register kPosTemp = getNotRaxRdxReg(availableGenRegs);
    masm.movq(kPosTemp, kPos);
    // Register kPackTemp = notRaxRdxRegs.pop();
    Register kPackTemp = getNotRaxRdxReg(availableGenRegs);
    masm.movq(kPackTemp, kPack);

    masm.push(rax);
    masm.push(rdx);

    // Register kPosDivKpack = notRaxRdxRegs.pop();
    // Register kStart = notRaxRdxRegs.pop();
    Register kPosDivKpack = getNotRaxRdxReg(availableGenRegs);
    Register kStart = getNotRaxRdxReg(availableGenRegs);

    masm.movq(rdx, 0);
    masm.movq(rax, kPosTemp);
    AMD64Assembler.AMD64MOp.IDIV.emit(masm, DWORD, kPackTemp);
    masm.movq(kPosDivKpack, rax);
    masm.movq(kStart, rdx);

    // addToNoDivStack(notRaxRdxRegs, kPackTemp);
    // addToNoDivStack(notRaxRdxRegs, kPosTemp);
    availableGenRegs.offer(kPackTemp);
    availableGenRegs.offer(kPosTemp);

    masm.pop(rdx);
    masm.pop(rax);

    // Register bIndex = notRaxRdxRegs.pop();
    // Register aIndex = notRaxRdxRegs.pop();
    Register bIndex = getNotRaxRdxReg(availableGenRegs);
    Register aIndex = getNotRaxRdxReg(availableGenRegs);

    masm.movq(bIndex, kPosDivKpack);
    masm.imull(bIndex, bIndex, nLength);
    masm.imull(bIndex, bIndex, kPack);

    masm.movq(aIndex, kPosDivKpack);
    masm.imull(aIndex, aIndex, mLength);
    masm.imull(aIndex, aIndex, kPack);

    // addToNoDivStack(notRaxRdxRegs, kPosDivKpack);
    availableGenRegs.offer(kPosDivKpack);

    // Register temp = notRaxRdxRegs.pop();
    Register temp = getNotRaxRdxReg(availableGenRegs);

    masm.movq(temp, jPos);
    masm.imull(temp, temp, kPack);
    masm.addq(bIndex, temp);

    masm.movq(temp, kStart);
    masm.imull(temp, temp, kernelWidth);
    masm.addq(bIndex, temp);

    masm.movq(temp, iPos);
    masm.imull(temp, temp, kPack);
    masm.addq(aIndex, temp);

    masm.movq(temp, kStart);
    masm.imull(temp, temp, aLength);
    masm.addq(aIndex, temp);

    // addToNoDivStack(notRaxRdxRegs, kStart);
    availableGenRegs.offer(kStart);

    // Register loopEndReg = notRaxRdxRegs.pop();
    Register loopEndReg = getNotRaxRdxReg(availableGenRegs);
    masm.movq(loopEndReg, bIndex);
    masm.movq(temp, kPanelSize);
    masm.imull(temp, temp, kernelWidth);
    masm.addq(loopEndReg, temp);

    // masm.push(iPos);
    // stackOffsetToConstArgs += 8;
    // masm.push(jPos);
    // stackOffsetToConstArgs += 8;

    // stackOffsetToConstArgs += 16;

    masm.movq(temp, 0);
    // Register aPtr = notRaxRdxRegs.pop();
    Register aPtr = availableGenRegs.poll();
    // Register aPtr = iPos;
    masm.movq(
        aPtr, new AMD64Address(arrsPtr, temp, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET));
    // Register bPtr = notRaxRdxRegs.pop();
    // Register bPtr = jPos;
    Register bPtr = availableGenRegs.poll();
    masm.movq(
        bPtr,
        new AMD64Address(arrsPtr, temp, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 8));

    Label loopLabel = new Label();

    masm.addq(aPtr, aAddressOffset);
    masm.addq(aPtr, aAlignmentOffset);

    masm.addq(bPtr, bAlignmentOffset);

    int unrollFactor = 4;
    int prefetchDistance = 4;
    masm.movq(temp, kernelWidth);
    masm.imull(temp, temp, unrollFactor);
    masm.subq(loopEndReg, temp);

    // Code to write stuff to the debug array.
    /*
    masm.push(aIndex);
    masm.push(bIndex);
    masm.movq(bIndex, 0);
    masm.movq(aIndex, new AMD64Address(arrsPtr, bIndex, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 24));
    masm.movl(new AMD64Address(aIndex, bIndex, INT_ARRAY_INDEX_SCALE, INT_ARRAY_BASE_OFFSET), 3450);
    masm.pop(bIndex);
    masm.pop(aIndex);
    */

    varArgAddresses = new HashMap<Integer, Register>();
    int genRegCountdown = 4;
    masm.movq(temp, 0);
    for (int i = 0; i < varArgProperties.length; i++) {
      if (varArgProperties[i] == 2) {
        int varArgOffset =
            stackOffsetToConstArgs + constArgsStackSize + variableArgsStackOffsets.get(i);
        if (interleave) {
          for (int j = 0; j < bLength * 2; j++) {
            masm.vmovddup(
                xmmRegistersAVX512[
                    simdRegisters.get("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j))],
                new AMD64Address(rsp, varArgOffset + ((j / 2) * 64) + ((j % 2) * 8)));
          }
        } else {
          for (int j = 0; j < bLength; j++) {
            masm.vmovupd(
                xmmRegistersAVX512[
                    simdRegisters.get("VARIABLEARG" + String.valueOf(i) + "_" + String.valueOf(j))],
                new AMD64Address(rsp, varArgOffset + 64 * j));
          }
        }
      } else if (varArgProperties[i] == 1) {
        // Register varArgRegister =
        // asRegister(kernelOp.remainingRegValues[remainingRegisterNum-genRegCountdown]);
        // Register varArgRegister = notRaxRdxRegs.pop();
        Register varArgRegister = availableGenRegs.poll();
        genRegCountdown++; // Todo: Do something when genRegCountdown == remainingRegisterNum
        masm.movq(
            varArgRegister,
            new AMD64Address(
                arrsPtr, temp, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 24 + 8 * i));
        varArgAddresses.put(i, varArgRegister);
      }
    }
    availableGenRegs.offer(temp);

    Label endLabel = new Label();
    masm.cmpq(bIndex, loopEndReg);
    masm.jcc(AMD64Assembler.ConditionFlag.GreaterEqual, endLabel);

    masm.bind(loopLabel);
    for (int i = 0; i < unrollFactor; i++) {
      subIter(
          aLength, bLength, i, prefetchDistance, masm, simdRegisters, aPtr, bPtr, aIndex, bIndex);
    }
    masm.addq(bIndex, kernelWidth * unrollFactor);
    masm.addq(aIndex, aLength * unrollFactor);
    masm.cmpq(bIndex, loopEndReg);
    masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

    masm.bind(endLabel);

    temp = availableGenRegs.poll();
    masm.movq(temp, kernelWidth);
    masm.imull(temp, temp, unrollFactor);

    masm.addq(loopEndReg, temp);
    availableGenRegs.offer(temp);

    loopLabel = new Label();
    masm.bind(loopLabel);
    subIter(aLength, bLength, 0, 0, masm, simdRegisters, aPtr, bPtr, aIndex, bIndex);
    masm.addq(bIndex, kernelWidth * 1);
    masm.addq(aIndex, aLength * 1);
    masm.cmpq(bIndex, loopEndReg);
    masm.jcc(AMD64Assembler.ConditionFlag.Less, loopLabel);

    // addToNoDivStack(notRaxRdxRegs, temp);
    // availableGenRegs.offer(temp);

    // Comment these two lines to cause a segfault and dump asm code to a log file.
    // masm.pop(jPos);
    // stackOffsetToConstArgs -= 8;
    // masm.pop(iPos);
    // stackOffsetToConstArgs -= 8;

    // stackOffsetToConstArgs -= 16;

    // masm.pop(jPos);

    /*
    addToNoDivStack(notRaxRdxRegs, bIndex);
    addToNoDivStack(notRaxRdxRegs, aIndex);
    addToNoDivStack(notRaxRdxRegs, loopEndReg);
    */
    availableGenRegs.offer(bIndex);
    availableGenRegs.offer(aIndex);
    availableGenRegs.offer(loopEndReg);

    /*
    temp = notRaxRdxRegs.pop();
    Register temp2 = notRaxRdxRegs.pop();
    Register zeroReg = notRaxRdxRegs.pop();
    */
    temp = availableGenRegs.poll();
    Register temp2 = availableGenRegs.poll();
    Register zeroReg = availableGenRegs.poll();
    masm.movq(zeroReg, 0);
    masm.movq(
        temp,
        new AMD64Address(
            arrsPtr, zeroReg, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + 16));
    // addToNoDivStack(notRaxRdxRegs, zeroReg);
    availableGenRegs.offer(zeroReg);

    if (interleave) {
      // Register resultPtr0 = notRaxRdxRegs.pop();
      // Register resultPtr1 = notRaxRdxRegs.pop();
      Register resultPtr0 = availableGenRegs.poll();
      Register resultPtr1 = availableGenRegs.poll();
      for (int i = 0; i < aLength; i += 2) {
        masm.movq(
            resultPtr0,
            new AMD64Address(
                temp, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + (i * 8)));
        masm.movq(
            resultPtr1,
            new AMD64Address(
                temp, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + ((i + 1) * 8)));
        for (int j = 0; j < bLength * 2; j++) {
          if (j % 2 == 0) {
            masm.vunpcklpd(
                xmmRegistersAVX512[simdRegisters.get("A")],
                xmmRegistersAVX512[
                    simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j / 2))],
                xmmRegistersAVX512[
                    simdRegisters.get("C" + String.valueOf(i + 1) + String.valueOf(j / 2))]);
            resultAddress =
                new AMD64Address(
                    resultPtr0,
                    jPos,
                    DOUBLE_ARRAY_INDEX_SCALE,
                    DOUBLE_ARRAY_BASE_OFFSET + ((j / 2) * 64));
          } else {
            masm.vunpckhpd(
                xmmRegistersAVX512[simdRegisters.get("A")],
                xmmRegistersAVX512[
                    simdRegisters.get("C" + String.valueOf(i) + String.valueOf(j / 2))],
                xmmRegistersAVX512[
                    simdRegisters.get("C" + String.valueOf(i + 1) + String.valueOf(j / 2))]);
            resultAddress =
                new AMD64Address(
                    resultPtr1,
                    jPos,
                    DOUBLE_ARRAY_INDEX_SCALE,
                    DOUBLE_ARRAY_BASE_OFFSET + ((j / 2) * 64));
          }
          masm.vaddpd(
              xmmRegistersAVX512[simdRegisters.get("A")],
              xmmRegistersAVX512[simdRegisters.get("A")],
              resultAddress);
          masm.vmovupd(resultAddress, xmmRegistersAVX512[simdRegisters.get("A")]);
        }
      }
    } else {
      for (int i = 0; i < aLength; i++) {
        resultAddress =
            new AMD64Address(
                temp, iPos, OBJECT_ARRAY_INDEX_SCALE, OBJECT_ARRAY_BASE_OFFSET + (i * 8));
        masm.movq(temp2, resultAddress);
        for (int j = 0; j < bLength; j++) {
          resultAddress =
              new AMD64Address(
                  temp2, jPos, DOUBLE_ARRAY_INDEX_SCALE, DOUBLE_ARRAY_BASE_OFFSET + (j * 64));
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

  private void addToNoDivStack(Stack<Register> stack, Register toPush) {
    if (!toPush.equals(rax) && !toPush.equals(rdx)) {
      stack.push(toPush);
    }
  }

  private Register getNotRaxRdxReg(Queue<Register> queue) {
    for (int i = 0; i < queue.size(); i++) {
      Register candidate = queue.poll();
      if (!candidate.equals(rax) && !candidate.equals(rdx)) {
        return candidate;
      } else {
        queue.offer(candidate);
      }
    }
    return null;
  }
}
