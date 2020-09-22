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
public final class AggregateNode extends FixedWithNextNode implements LIRLowerable {
    public static final NodeClass<AggregateNode> TYPE = NodeClass.create(AggregateNode.class);

    @Input ValueNode inputOffset;
    @Input ValueNode input;
    @Input ValueNode output;

    public AggregateNode(ValueNode inputOffset, ValueNode input, ValueNode output) {
        super(TYPE, StampFactory.forVoid());
        this.inputOffset = inputOffset;
        this.input = input;
        this.output = output;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        gen.getLIRGeneratorTool().emitVecAggregate(gen.operand(inputOffset), gen.operand(input), gen.operand(output));
    }
}
