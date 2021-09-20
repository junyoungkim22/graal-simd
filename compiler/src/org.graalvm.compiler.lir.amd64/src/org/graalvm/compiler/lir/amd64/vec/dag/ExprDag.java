package org.graalvm.compiler.lir.amd64.vec.dag;

import java.io.PrintWriter;

import java.util.HashMap;

import org.graalvm.compiler.lir.amd64.vec.util.ChangeableString;
import org.graalvm.compiler.lir.amd64.vec.GotoOpCode;
import static org.graalvm.compiler.lir.amd64.vec.GotoOpCode.*;
import org.graalvm.compiler.lir.amd64.vec.dag.ExprNode;


public final class ExprDag {
    private ExprNode rootNode;

    private final int opLength = 5;

    private int idCount;

    private HashMap<String, ExprNode> subExprMap;

    private PrintWriter debugLog;

    public ExprDag(ChangeableString opString) {
        this.subExprMap = new HashMap<>();
        idCount = 0;
        this.rootNode = createDAG(opString);
    }

    public ExprDag(ChangeableString opString, PrintWriter debugLog) {
        this.debugLog = debugLog;
        this.subExprMap = new HashMap<>();
        idCount = 0;
        this.rootNode = createDAG(opString);
    }

    public ExprNode getRootNode() {
        return this.rootNode;
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
        writer.print("(" + rootNode.getId() + ") ");
        if(rootNode.getChildren() != null) {
            for(ExprNode child : rootNode.getChildren()) {
                printDAG(writer, child);
            }
        }
    }
}