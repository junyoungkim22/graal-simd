package org.graalvm.compiler.lir.amd64.vec.dag;



public final class ExprNode {
  private String op;
  private int id;

  private int numberOfParents;

  // for choosing paths in Sethi-Ullman algorithm
  private int label;

  private ExprNode[] children;

  public ExprNode(String op, int id, ExprNode... children) {
    this.op = op;
    this.id = id;
    this.children = children;
    this.label = -1;
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

  public void setLabel(int label) {
    if (this.label == -1) { // set label once
      this.label = label;
    }
  }

  public int getLabel() {
    return this.label;
  }

  public int getNumberOfParents() {
    return numberOfParents;
  }

  public ExprNode[] getChildren() {
    return children;
  }

  public String toString() {
    if (children == null) { // is leaf
      return this.op;
    } else {
      String ret = op;
      for (ExprNode child : children) {
        ret += child.toString();
      }
      return ret;
    }
  }
}
