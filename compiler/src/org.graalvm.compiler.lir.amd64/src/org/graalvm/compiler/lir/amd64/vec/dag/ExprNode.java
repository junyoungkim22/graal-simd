package org.graalvm.compiler.lir.amd64.vec.dag;

import java.io.PrintWriter;

import org.graalvm.compiler.lir.amd64.vec.util.ChangeableString;
import org.graalvm.compiler.lir.amd64.vec.GotoOpCode;

public final class ExprNode {
    private String op;
    private int id;

    private int numberOfParents;

    // To check when to free register / stack
    private int referenced;

    // for choosing paths in Sethi-Ullman algorithm
    private int label;

    private ExprNode[] children;

    public ExprNode(String op, int id, ExprNode... children) {
        this.op = op;
        this.id = id;
        this.children = children;
    }

    public void incrementNumberOfParents() {
        this.numberOfParents++;
    }

    public String getOp() {
        return op;
    }
    public int getId() {
        return id;
    }

    public ExprNode[] getChildren() {
        return children;
    }

    public String toString() {
        if(children == null) {  // is leaf
            return this.op;
        }
        else {
            String ret = op;
            for(ExprNode child : children) {
                ret += child.toString();
            }
            return ret;
        }
    }
}