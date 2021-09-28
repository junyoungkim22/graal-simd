package org.graalvm.compiler.lir.amd64.vec.dag;

import java.io.PrintWriter;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

import org.graalvm.compiler.lir.amd64.vec.util.ChangeableString;
import org.graalvm.compiler.lir.amd64.vec.GotoOpCode;
import static org.graalvm.compiler.lir.amd64.vec.GotoOpCode.*;
import org.graalvm.compiler.lir.amd64.vec.dag.ExprNode;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;

import static jdk.vm.ci.amd64.AMD64.cpuRegisters;
import static jdk.vm.ci.amd64.AMD64.xmmRegistersAVX512;

import static jdk.vm.ci.amd64.AMD64.k0;
import static jdk.vm.ci.amd64.AMD64.k1;
import static jdk.vm.ci.amd64.AMD64.k2;

public final class ExprDag {
    private ExprNode rootNode;

    private final int opLength = 5;

    private int idCount;

    private HashMap<String, ExprNode> subExprMap;
    private HashMap<Integer, String> locationTracker;  // id -> location (register or address)
    private HashMap<String, Integer> leafToId;
    private HashMap<Integer, Integer> referenceCount;
    private HashMap<String, Integer> nameToRegNum;
    private Queue<Integer> usedRegisters;
    private Queue<Integer> availableRegisters;

    private PrintWriter debugLog;

    private ArrayList<Inst> instructions;

    public ExprDag(ChangeableString opString) {
        this.subExprMap = new HashMap<>();
        idCount = 0;
        this.rootNode = createDAG(opString);
    }

    public ExprDag(ChangeableString opString, /*HashMap<String, Integer> availableValues, int[] tempRegs,*/ PrintWriter debugLog) {
        this.debugLog = debugLog;
        this.subExprMap = new HashMap<>();
        this.leafToId = new HashMap<>();
        this.idCount = 0;
        this.referenceCount = new HashMap<>();
        //this.nameToRegNum = availableValues;
        this.rootNode = createDAG(opString);

        createSethiUllmanLabels(this.rootNode);

        /*
        this.locationTracker = new HashMap<>();
        for(String key : leafToId.keySet()) {
            locationTracker.put(leafToId.get(key), "R" + String.valueOf(availableValues.get(key)));
        }
        debugLog.write("-------\n");

        for(int key : locationTracker.keySet()) {
            debugLog.write(key + " " + locationTracker.get(key) + "\n");
        }
        this.usedRegisters = new LinkedList<>();
        this.availableRegisters = new LinkedList<>();
        for(int regNum : tempRegs) {
            this.availableRegisters.add(regNum);
        }

        this.instructions = new ArrayList<>();
        generateCode(this.rootNode);
        for(Inst inst : instructions) {
            debugLog.println(inst.toDebugString());
        }
        */
    }

    public void createCode(HashMap<String, Integer> availableValues, int[] tempRegs, AMD64MacroAssembler masm) {
        this.nameToRegNum = availableValues;
        this.locationTracker = new HashMap<>();
        for(String key : leafToId.keySet()) {
            locationTracker.put(leafToId.get(key), "R" + String.valueOf(availableValues.get(key)));
        }
        //debugLog.write("-------\n");

        /*
        for(int key : locationTracker.keySet()) {
            debugLog.write(key + " " + locationTracker.get(key) + "\n");
        }
        */
        this.usedRegisters = new LinkedList<>();
        this.availableRegisters = new LinkedList<>();
        for(int regNum : tempRegs) {
            this.availableRegisters.add(regNum);
        }
        this.instructions = new ArrayList<>();
        generateCode(this.rootNode);
        for(Inst inst : instructions) {
            debugLog.println(inst.toDebugString());
        }
        emitAsmCode(masm);
    }

    private class Inst {
        public String op;
        public String dst;
        public String mask;
        public String src0;
        public String src1;

        public Inst() {
            op = "";
            dst = "";
            mask = "";
            src0 = "";
            src1 = "";
        }

        public String toString() {
            if(mask.equals("")) {
                return op + " " + dst + " " + src0 + " " + src1;
            }
            else {
                return op + " " + mask + " " + dst + " " + src0 + " " + src1;
            }
        }

        public String toDebugString() {
            HashMap<String, String> debugMap = new HashMap<String, String>();
            debugMap.put(MUL, "MUL");
            debugMap.put(ADD, "ADD");
            debugMap.put(FMADD, "FMADD");
            debugMap.put(SUB, "SUB");
            debugMap.put(DIV, "DIV");
            debugMap.put(MASKMUL, "MASKMUL");
            debugMap.put(MASKADD, "MASKADD");
            debugMap.put(MASKFMADD, "MASKFMADD");
            debugMap.put(MASKSUB, "MASKSUB");
            debugMap.put(MASKDIV, "MASKDIV");
            debugMap.put(GT, "GT");
            debugMap.put(GE, "GE");
            debugMap.put(LT, "LT");
            debugMap.put(LE, "LE");
            debugMap.put(EQ, "EQ");
            debugMap.put(NEQ, "NEQ");
            debugMap.put(A, "A");
            debugMap.put(B, "B");
            debugMap.put(C, "C");
            debugMap.put(CONSTARG, "CONSTARG");
            debugMap.put(VARIABLEARG, "VARIABLEARG");
            if(mask.equals("")) {
                return debugMap.get(op) + " " + dst + " " + src0 + " " + src1;
            }
            else {
                return debugMap.get(op) + " " + mask + " " + dst + " " + src0 + " " + src1;
            }
        }
    }

    public ExprNode getRootNode() {
        return this.rootNode;
    }

    public void generateCode(ExprNode currNode) {
        referenceCount.put(currNode.getId(), referenceCount.get(currNode.getId())+1);
        if(locationTracker.containsKey(currNode.getId())) {
            return;
        }
        String op = currNode.getOp().substring(0, opLength);
        String opType = op.substring(0, 2);
        int argIndex = 0;
        if(opType.equals(GotoOpCode.ARGOP)) {
            Inst newInst = new Inst();
            if(locationTracker.containsKey(currNode.getId())) {
                return;
            }
            // Todo : evaluate expression from 'source'
            switch(op) {
                case GotoOpCode.A:
                    newInst.op = "A";
                    break;
                case GotoOpCode.B:
                    newInst.op = "B";
                    break;
                case GotoOpCode.C:
                    newInst.op = "C";
                    break;
                case GotoOpCode.CONSTARG:
                    argIndex = Integer.parseInt(currNode.getOp().substring(opLength, opLength+opLength));
                    newInst.op = "CONSTARG " + argIndex;
                    break;
                case GotoOpCode.VARIABLEARG:
                    argIndex = Integer.parseInt(currNode.getOp().substring(opLength, opLength+opLength));
                    newInst.op = "VARIABLEARG " + argIndex;
                    break;
            }
            instructions.add(newInst);
        }
        else if(opType.equals(GotoOpCode.OP) || opType.equals(GotoOpCode.CMPOP)) {
            if(!op.equals(GotoOpCode.FMADD)) {
                if(currNode.getChildren()[0].getLabel() >= currNode.getChildren()[1].getLabel()) {
                    generateCode(currNode.getChildren()[0]);
                    generateCode(currNode.getChildren()[1]);
                }
                else {
                    generateCode(currNode.getChildren()[1]);
                    generateCode(currNode.getChildren()[0]);
                }

                // Free registers
                for(ExprNode child : currNode.getChildren()) {
                    freeRegister(child);
                }

                Inst newInst = new Inst();
                if(currNode.getId() == rootNode.getId()) {
                    newInst.dst = "R" + nameToRegNum.get(GotoOpCode.C);
                }
                else if(opType.equals(GotoOpCode.CMPOP)) {
                    newInst.dst = "K2";
                }
                else {
                    int newRegister = availableRegisters.remove();
                    newInst.dst = "R" + newRegister;
                }
                newInst.src0 = locationTracker.get(currNode.getChildren()[0].getId());
                newInst.src1 = locationTracker.get(currNode.getChildren()[1].getId());
                newInst.op = op;
                /*
                switch(op) {
                    case GotoOpCode.ADD:
                        newInst.op = GotoOpCode;
                        break;
                    case GotoOpCode.MUL:
                        newInst.op = "MUL";
                        break;
                    case GotoOpCode.LT:
                        newInst.op = "LT";
                        break;
                    case GotoOpCode.GT:
                        newInst.op = "GT";
                        break;

                }
                */
                locationTracker.put(currNode.getId(), newInst.dst);
                usedRegisters.add(Integer.valueOf(newInst.dst.substring(1,newInst.dst.length())));
                instructions.add(newInst);
            }
            else {
                // Todo: Add FMADD
                ExprNode[] evalList = sortNodesByLabel(currNode.getChildren());
                for(int i = 0; i < evalList.length; i++) {
                    generateCode(evalList[i]);
                }

                // Free registers
                for(ExprNode child : currNode.getChildren()) {
                    freeRegister(child);
                }

                Inst newInst = new Inst();
                newInst.dst = locationTracker.get(currNode.getChildren()[0].getId());
                newInst.src0 = locationTracker.get(currNode.getChildren()[1].getId());
                newInst.src1 = locationTracker.get(currNode.getChildren()[2].getId());
                newInst.op = op;
                instructions.add(newInst);
                return;
            }
        }
        else if(opType.equals(GotoOpCode.MASKOP)) {
            ExprNode[] evalList = sortNodesByLabel(currNode.getChildren());
            for(int i = 0; i < evalList.length; i++) {
                generateCode(evalList[i]);
            }

            // Free registers
            for(ExprNode child : currNode.getChildren()) {
                freeRegister(child);
            }

            int newRegister = availableRegisters.remove();

            Inst newInst = new Inst();
            newInst.dst = "R" + newRegister;
            newInst.mask = locationTracker.get(currNode.getChildren()[0].getId()); // MASK
            newInst.src0 = locationTracker.get(currNode.getChildren()[1].getId());
            newInst.src1 = locationTracker.get(currNode.getChildren()[2].getId());
            newInst.op = op;
            /*
            switch(op) {
                case GotoOpCode.MASKADD:
                    newInst.op = "MASKADD";
                    break;
                case GotoOpCode.MASKSUB:
                    newInst.op = "MASKSUB";
                    break;
            }
            */
            locationTracker.put(currNode.getId(), newInst.dst);
            usedRegisters.add(Integer.valueOf(newInst.dst.substring(1,newInst.dst.length())));
            instructions.add(newInst);
        }
    }

    public void emitAsmCode(AMD64MacroAssembler masm) {
        for(Inst inst : instructions) {
            String[] asm = inst.toString().split(" ", 0);
            String op = asm[0];
            String opType = op.substring(0, 2);
            if(opType.equals(GotoOpCode.OP)) {
                int dstRegNum = Integer.valueOf(inst.dst.substring(1, inst.dst.length()));
                int src0RegNum = Integer.valueOf(inst.src0.substring(1, inst.src0.length()));
                int src1RegNum = Integer.valueOf(inst.src1.substring(1, inst.src1.length()));
                switch(op) {
                    case GotoOpCode.ADD:
                        masm.vaddpd(xmmRegistersAVX512[dstRegNum], xmmRegistersAVX512[src0RegNum], xmmRegistersAVX512[src1RegNum]);
                        break;
                    case GotoOpCode.MUL:
                        masm.vmulpd(xmmRegistersAVX512[dstRegNum], xmmRegistersAVX512[src0RegNum], xmmRegistersAVX512[src1RegNum]);
                        break;
                    case GotoOpCode.FMADD:
                        masm.vfmadd231pd(xmmRegistersAVX512[dstRegNum], xmmRegistersAVX512[src0RegNum], xmmRegistersAVX512[src1RegNum]);
                        break;
                }
                continue;
            }
            else if(opType.equals(GotoOpCode.CMPOP)) {
                int src0RegNum = Integer.valueOf(inst.src0.substring(1, inst.src0.length()));
                int src1RegNum = Integer.valueOf(inst.src1.substring(1, inst.src1.length()));
                // Todo: use different mask registers
                int cmpOperation = 0;
                switch(op) {
                    case GotoOpCode.LT:
                        cmpOperation = 1;
                        break;
                    case GotoOpCode.GT:
                        cmpOperation = 0x0e;
                        break;
                }
                masm.vcmppd(k1, xmmRegistersAVX512[src0RegNum], xmmRegistersAVX512[src1RegNum], cmpOperation);
            }
            else if(opType.equals(GotoOpCode.MASKOP)) {
                int dstRegNum = Integer.valueOf(inst.dst.substring(1, inst.dst.length()));
                int src0RegNum = Integer.valueOf(inst.src0.substring(1, inst.src0.length()));
                int src1RegNum = Integer.valueOf(inst.src1.substring(1, inst.src1.length()));
                // Todo: Parse mask register
                switch(op) {
                    case GotoOpCode.MASKADD:
                        masm.vaddpd(xmmRegistersAVX512[dstRegNum], xmmRegistersAVX512[src0RegNum], xmmRegistersAVX512[src1RegNum], k1);
                        break;
                }
            }
        }
    }

    // Free the register a node is using
    public void freeRegister(ExprNode node) {
        if(referenceCount.get(node.getId()) == node.getNumberOfParents()) { // check if child does not need to be referenced again
            String location = locationTracker.get(node.getId());
            if(location.substring(0, 1).equals("R")) {  // check if child is in register
                int registerNum = Integer.valueOf(location.substring(1, location.length()));
                if(usedRegisters.contains(registerNum)) {  // check if child is using a temporary register
                    usedRegisters.remove(registerNum);
                    availableRegisters.add(registerNum);
                }
            }
        }
    }

    private ExprNode[] sortNodesByLabel(ExprNode[] arr) {
        ExprNode[] ret = new ExprNode[arr.length];
        ArrayList<ExprNode> arrList = new ArrayList<>();
        for(int i = 0; i < arr.length; i++) {
            arrList.add(arr[i]);
        }
        for(int i = 0; i < arr.length; i++) {
            int min = 9999999;
            int minIndex = 0;
            for(int j = 0; j < arrList.size(); j++) {
                if(arrList.get(j).getLabel() < min) {
                    min = arrList.get(j).getLabel();
                    minIndex = j;
                }
            }
            ret[i] = arrList.get(minIndex);
            arrList.remove(minIndex);
        }
        return ret;
    }

    public void createSethiUllmanLabels(ExprNode currNode) {
        String op = currNode.getOp();
        String opType = op.substring(0, 2);

        ExprNode[] children = currNode.getChildren();
        int[] childLabels = null;
        if(children != null) {
            childLabels = new int[children.length];
            for(int i = 0; i < children.length; i++) {
                createSethiUllmanLabels(children[i]);
                childLabels[i] = children[i].getLabel();
            }
            int max = -1;
            int prev = -1;
            Boolean isSame = true;
            for(int i = 0; i < children.length; i++) {
                int childLabel = children[i].getLabel();
                if(childLabel > max) {
                    max = childLabel;
                }
                if(prev != -1 && childLabel != prev) {
                    isSame = false;
                }
                prev = childLabel;
            }
            if(isSame) {
                currNode.setLabel(max + 1);
            }
            else {
                currNode.setLabel(max);
            }
            return;
        }
        else {  // currNode is leaf
            currNode.setLabel(1);
            return;
        }
    }

    public ExprNode createDAG(ChangeableString opString) {
        String op = opString.cutOff(opLength);
        String opType = op.substring(0, 2);
        int id = idCount++;
        referenceCount.put(id, 0);
        ExprNode[] children = null;

        if(op.equals(GotoOpCode.FMADD) || opType.equals(GotoOpCode.MASKOP)) { // Node with 3 children
            children = new ExprNode[3];
            children[0] = createDAG(opString);
            children[1] = createDAG(opString);
            children[2] = createDAG(opString);
        }
        else if(opType.equals(GotoOpCode.OP) || opType.equals(GotoOpCode.CMPOP)) { // binary node
            children = new ExprNode[2];
            children[0] = createDAG(opString);
            children[1] = createDAG(opString);
        }
        else {   // leaf node (ARGOP)
            if(op.equals(GotoOpCode.CONSTARG) || op.equals(GotoOpCode.VARIABLEARG)) {
                op += opString.cutOff(opLength);
            }
            if(!leafToId.containsKey(op)) {
                leafToId.put(op, id);
            }
        }

        if(children != null) {
            for(int i = 0; i < children.length; i++) {
                String subExprStr = children[i].toString();
                if(subExprMap.containsKey(subExprStr)) {
                    children[i] = subExprMap.get(subExprStr);
                }
                children[i].incrementNumberOfParents();
            }
        }
        ExprNode ret = new ExprNode(op, id, children);
        if(!subExprMap.containsKey(ret.toString())) {
            subExprMap.put(ret.toString(), ret);
        }
        return ret;
    }

    public static void printDAG(PrintWriter writer, ExprNode rootNode) {
        HashMap<String, String> debugMap = new HashMap<String, String>();
        debugMap.put(MUL, "MUL");
        debugMap.put(ADD, "ADD");
        debugMap.put(FMADD, "FMADD");
        debugMap.put(SUB, "SUB");
        debugMap.put(DIV, "DIV");
        debugMap.put(MASKMUL, "MASKMUL");
        debugMap.put(MASKADD, "MASKADD");
        debugMap.put(MASKFMADD, "MASKFMADD");
        debugMap.put(MASKSUB, "MASKSUB");
        debugMap.put(MASKDIV, "MASKDIV");
        debugMap.put(GT, "GT");
        debugMap.put(GE, "GE");
        debugMap.put(LT, "LT");
        debugMap.put(LE, "LE");
        debugMap.put(EQ, "EQ");
        debugMap.put(NEQ, "NEQ");
        debugMap.put(A, "A");
        debugMap.put(B, "B");
        debugMap.put(C, "C");
        debugMap.put(CONSTARG, "CONSTARG");
        debugMap.put(VARIABLEARG, "VARIABLEARG");
        String op = rootNode.getOp().substring(0, 5);
        writer.print(debugMap.get(op));
        if(op.equals(GotoOpCode.CONSTARG) || op.equals(GotoOpCode.VARIABLEARG)) {
            writer.print(" " + rootNode.getOp().substring(5, 10));
        }
        writer.print("(" + rootNode.getId() + ", " + rootNode.getNumberOfParents() + ", " + rootNode.getLabel() + ") ");
        if(rootNode.getChildren() != null) {
            for(ExprNode child : rootNode.getChildren()) {
                printDAG(writer, child);
            }
        }
    }
}