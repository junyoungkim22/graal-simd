package org.graalvm.compiler.lir.amd64.vec.dag;

import java.io.PrintWriter;

import java.util.HashMap;

import org.graalvm.compiler.lir.amd64.vec.util.ChangeableString;
import org.graalvm.compiler.lir.amd64.vec.GotoOpCode;
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
        createDAG(opString);
    }

    public void setDebugLog(PrintWriter writer) {
        debugLog = writer;
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
        return ret;
    }

    public void printDAG(PrintWriter writer, ExprNode rootNode) {
        writer.print(rootNode.getOp());
        if(rootNode.getChildren() != null) {
            for(ExprNode child : rootNode.getChildren()) {
                printDAG(writer, child);
            }
        }
    }
}