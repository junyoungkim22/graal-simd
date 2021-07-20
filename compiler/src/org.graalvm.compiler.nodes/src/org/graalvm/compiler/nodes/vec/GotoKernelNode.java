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
public final class GotoKernelNode extends FixedWithNextNode implements LIRLowerable {
    public static final NodeClass<GotoKernelNode> TYPE = NodeClass.create(GotoKernelNode.class);

    /*
    @Input ValueNode a;
    @Input ValueNode b;
    @Input ValueNode result;
    */
    @Input ValueNode arrs;
    @Input ValueNode kPanelSize;
    @Input ValueNode i;
    @Input ValueNode k;
    @Input ValueNode j;
    @Input ValueNode calc;

    public GotoKernelNode(ValueNode arrs, ValueNode kPanelSize, ValueNode i, ValueNode k, ValueNode j, ValueNode calc) {
        super(TYPE, StampFactory.forVoid());
        /*
        this.a = a;
        this.b = b;
        this.result = result;
        */
        this.arrs = arrs;
        this.kPanelSize = kPanelSize;
        this.i = i;
        this.k = k;
        this.j = j;
        this.calc = calc;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
	    //int calcJavaConstant = calc.asJavaConstant().asInt();
	    int arrLen = gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayLength(calc.asJavaConstant());
	    long[] argLong = new long[arrLen];
	    for(int i = 0; i < arrLen; i++) {
	        argLong[i] = gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(calc.asJavaConstant(), i).asLong();
	    }
        gen.getLIRGeneratorTool().emitGotoKernel(gen.operand(arrs), gen.operand(kPanelSize),
                                                        gen.operand(i), gen.operand(k), gen.operand(j), argLong);
    }
}
