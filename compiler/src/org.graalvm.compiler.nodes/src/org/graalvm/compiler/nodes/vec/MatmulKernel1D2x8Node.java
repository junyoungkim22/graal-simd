package org.graalvm.compiler.nodes.vec;

import static org.graalvm.compiler.nodeinfo.NodeCycles.CYCLES_1;
import static org.graalvm.compiler.nodeinfo.NodeSize.SIZE_1;

import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;

@NodeInfo(cycles = CYCLES_1, size = SIZE_1)
public final class MatmulKernel1D2x8Node extends FixedWithNextNode implements LIRLowerable {
  public static final NodeClass<MatmulKernel1D2x8Node> TYPE =
      NodeClass.create(MatmulKernel1D2x8Node.class);

  @Input ValueNode a;
  @Input ValueNode b;
  @Input ValueNode result;
  @Input ValueNode kPanelSize;
  @Input ValueNode i;
  @Input ValueNode k;
  @Input ValueNode j;

  public MatmulKernel1D2x8Node(
      ValueNode a,
      ValueNode b,
      ValueNode result,
      ValueNode kPanelSize,
      ValueNode i,
      ValueNode k,
      ValueNode j) {
    super(TYPE, StampFactory.forVoid());
    this.a = a;
    this.b = b;
    this.result = result;
    this.kPanelSize = kPanelSize;
    this.i = i;
    this.k = k;
    this.j = j;
  }

  @Override
  public void generate(NodeLIRBuilderTool gen) {
    gen.getLIRGeneratorTool()
        .emitMatmulKernel1D2x8(
            gen.operand(a),
            gen.operand(b),
            gen.operand(result),
            gen.operand(kPanelSize),
            gen.operand(i),
            gen.operand(k),
            gen.operand(j));
  }
}
