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
public final class ConvKernelNode extends FixedWithNextNode implements LIRLowerable {
    public static final NodeClass<ConvKernelNode> TYPE = NodeClass.create(ConvKernelNode.class);

    @Input ValueNode arrs;
    @Input ValueNode kPanelSize;
    @Input ValueNode i;
    @Input ValueNode k;
    @Input ValueNode j;
    @Input ValueNode constArgs;

    public ConvKernelNode(ValueNode arrs, ValueNode kPanelSize, ValueNode i, ValueNode k, ValueNode j, ValueNode constArgs) {
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
	    int curr = 0;

	    int aLength = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	    int bLength = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	    int numLongsInOpString = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	    long[] argLong = new long[numLongsInOpString];
	    for(int i = 0; i < argLong.length; i++) {
	        argLong[i] = gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	    }
	    int numConstantArgs = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	    double[] constDoubleArgs = new double[numConstantArgs];
	    for(int i = 0; i < constDoubleArgs.length; i++) {
	        //constDoubleArgs[i] = (double) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	        constDoubleArgs[i] = Double.longBitsToDouble(gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong());
	    }
	    int numVarArgs = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	    int[] varArgProperties = new int[numVarArgs];
	    for(int i = 0; i < varArgProperties.length; i++) {
	        varArgProperties[i] = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	    }

	    int outChannels = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	    int inChannels = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	    int imgLength = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();
	    int kernelLength = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();

	    int kernelType = (int) gen.getLIRGeneratorTool().getProviders().getConstantReflection().readArrayElement(constArgs.asJavaConstant(), curr++).asLong();

        gen.getLIRGeneratorTool().emitConvKernel(gen.operand(arrs), gen.operand(kPanelSize),
                                                        gen.operand(i), gen.operand(k), gen.operand(j), kernelType, aLength, bLength, outChannels, inChannels, imgLength, kernelLength, argLong, constDoubleArgs, varArgProperties);
    }
}
