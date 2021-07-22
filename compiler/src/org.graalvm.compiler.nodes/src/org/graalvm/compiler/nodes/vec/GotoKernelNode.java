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

    @Input ValueNode arrs;
    @Input ValueNode kPanelSize;
    @Input ValueNode i;
    @Input ValueNode k;
    @Input ValueNode j;
    @Input ValueNode constArgs;

    public GotoKernelNode(ValueNode arrs, ValueNode kPanelSize, ValueNode i, ValueNode k, ValueNode j, ValueNode constArgs) {
        super(TYPE, StampFactory.forVoid());
        this.arrs = arrs;
        this.kPanelSize = kPanelSize;
        this.i = i;
        this.k = k;
        this.j = j;
        this.constArgs = constArgs;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
	    //int calcJavaConstant = calc.asJavaConstant().asInt();
	    int arrLen = gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayLength(constArgs.asJavaConstant());
	    int aLength = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), 0).asLong();
	    int bLength = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), 1).asLong();
	    int numLongsInOpString = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), 2).asLong();
	    long[] argLong = new long[numLongsInOpString];
	    for(int i = 0; i < argLong.length; i++) {
	        argLong[i] = gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), i+3).asLong();
	    }
	    double[] constDoubleArgs = new double[arrLen-3-numLongsInOpString];
	    for(int i = 0; i < constDoubleArgs.length; i++) {
	        constDoubleArgs[i] = (double) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), i+3+numLongsInOpString).asLong();
	    }
        gen.getLIRGeneratorTool().emitGotoKernel(gen.operand(arrs), gen.operand(kPanelSize),
                                                        gen.operand(i), gen.operand(k), gen.operand(j), aLength, bLength, argLong, constDoubleArgs);
    }
}
