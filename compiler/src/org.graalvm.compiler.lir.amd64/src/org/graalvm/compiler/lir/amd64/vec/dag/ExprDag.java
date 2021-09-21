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


public final class ExprDag {
    private ExprNode rootNode;

    private final int opLength = 5;

    private int idCount;

    private HashMap<String, ExprNode> subExprMap;
    private HashMap<Integer, Integer> registerTracker;
    private Queue<Integer> stack;

    private PrintWriter debugLog;

    private ArrayList<Inst> instructions;

    public ExprDag(ChangeableString opString) {
        this.subExprMap = new HashMap<>();
        idCount = 0;
        this.rootNode = createDAG(opString);
    }

    public ExprDag(ChangeableString opString, PrintWriter debugLog) {
        this.debugLog = debugLog;
        this.subExprMap = new HashMap<>();
        this.idCount = 0;
        this.rootNode = createDAG(opString);
        createSethiUllmanLabels(this.rootNode);
        this.stack = new LinkedList<>();
        this.instructions = new ArrayList<>();
        generateCode(this.rootNode);
        for(Inst inst : instructions) {
            debugLog.println(inst.toString());
        }
    }

    private class Inst {
        public String op;
        public String dst;
        public String src0;
        public String src1;

        public Inst() {
            op = "";
            dst = "";
            src0 = "";
            src1 = "";
        }

        public String toString() {
            return op + " " + dst + " " + src0 + " " + src1;
        }
    }

    public ExprNode getRootNode() {
        return this.rootNode;
    }

    public void generateCode(ExprNode currNode) {
        String op = currNode.getOp().substring(0, opLength);
        String opType = op.substring(0, 2);
        int argIndex = 0;
        if(opType.equals(GotoOpCode.ARGOP)) {
            Inst newInst = new Inst();
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
                Inst newInst = new Inst();
                switch(op) {
                    case GotoOpCode.ADD:
                        newInst.op = "ADD";
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
                instructions.add(newInst);
            }
            else {
                // Todo: Add FMADD
                return;
            }
        }
        else if(opType.equals(GotoOpCode.MASKOP)) {
            ExprNode[] evalList = sortNodesByLabel(currNode.getChildren());
            for(int i = 0; i < evalList.length; i++) {
                generateCode(evalList[i]);
            }
            Inst newInst = new Inst();
            newInst.op = "MASK";
            instructions.add(newInst);
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
        ExprNode ret = new ExprNode(op, idCount++, children);
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