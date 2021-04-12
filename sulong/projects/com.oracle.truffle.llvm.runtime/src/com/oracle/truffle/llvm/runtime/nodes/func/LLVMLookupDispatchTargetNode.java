/*
 * Copyright (c) 2017, 2020, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.runtime.nodes.func;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.llvm.runtime.LLVMContext;
import com.oracle.truffle.llvm.runtime.LLVMFunction;
import com.oracle.truffle.llvm.runtime.LLVMFunctionDescriptor;
import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.runtime.except.LLVMIllegalSymbolIndexException;
import com.oracle.truffle.llvm.runtime.library.internal.LLVMAsForeignLibrary;
import com.oracle.truffle.llvm.runtime.memory.LLVMHandleMemoryBase;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.load.LLVMDerefHandleGetReceiverNode;
import com.oracle.truffle.llvm.runtime.nodes.others.LLVMAccessSymbolNode;
import com.oracle.truffle.llvm.runtime.nodes.others.LLVMAccessSymbolNodeGen;
import com.oracle.truffle.llvm.runtime.pointer.LLVMManagedPointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMNativePointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMPointer;

@NodeChild(value = "function", type = LLVMExpressionNode.class)
public abstract class LLVMLookupDispatchTargetNode extends LLVMExpressionNode {

    protected static final int INLINE_CACHE_SIZE = 5;

    @CompilationFinal private LanguageReference<LLVMLanguage> languageRef;

    @Specialization(limit = "INLINE_CACHE_SIZE", guards = {"isSameObject(pointer.getObject(), cachedDescriptor)", "cachedDescriptor != null",
                    "pointer.getOffset() == 0"}, assumptions = "singleContextAssumption()")
    protected static LLVMFunctionDescriptor doDirectCached(@SuppressWarnings("unused") LLVMManagedPointer pointer,
                    @Cached("asFunctionDescriptor(pointer.getObject())") LLVMFunctionDescriptor cachedDescriptor) {
        return cachedDescriptor;
    }

    @Specialization(guards = {"isFunctionDescriptor(pointer.getObject())", "pointer.getOffset() == 0"}, replaces = "doDirectCached")
    protected static LLVMFunctionDescriptor doDirect(LLVMManagedPointer pointer) {
        return (LLVMFunctionDescriptor) pointer.getObject();
    }

    @Specialization(guards = {"foreigns.isForeign(pointer.getObject())", "pointer.getOffset() == 0"})
    protected Object doForeign(LLVMManagedPointer pointer,
                    @SuppressWarnings("unused") @CachedLibrary(limit = "3") LLVMAsForeignLibrary foreigns) {
        return pointer.getObject();
    }

    @Specialization(limit = "INLINE_CACHE_SIZE", guards = {"pointer.asNative() == cachedAddress", "!isAutoDerefHandle(cachedAddress)",
                    "cachedDescriptor != null"}, assumptions = "singleContextAssumption()")
    protected static LLVMFunctionDescriptor doHandleCached(@SuppressWarnings("unused") LLVMNativePointer pointer,
                    @Cached("pointer.asNative()") @SuppressWarnings("unused") long cachedAddress,
                    @CachedContext(LLVMLanguage.class) @SuppressWarnings("unused") ContextReference<LLVMContext> ctxRef,
                    @Cached("lookupFunction(ctxRef, pointer)") LLVMFunctionDescriptor cachedDescriptor) {
        return cachedDescriptor;
    }

    @Specialization(limit = "INLINE_CACHE_SIZE", guards = {"pointer.asNative() == cachedAddress", "!isAutoDerefHandle(cachedAddress)",
                    "cachedDescriptor == null"}, assumptions = "singleContextAssumption()")
    protected static LLVMNativePointer doNativeFunctionCached(LLVMNativePointer pointer,
                    @Cached("pointer.asNative()") @SuppressWarnings("unused") long cachedAddress,
                    @CachedContext(LLVMLanguage.class) @SuppressWarnings("unused") ContextReference<LLVMContext> ctxRef,
                    @Cached("lookupFunction(ctxRef, pointer)") @SuppressWarnings("unused") LLVMFunctionDescriptor cachedDescriptor) {
        return pointer;
    }

    /*
     * Try to cache the target symbol if it's always the same one, the reverse lookup is much faster
     * and doesn't need a TruffleBoundary.
     */
    @Specialization(guards = {"!isAutoDerefHandle(pointer.asNative())", "cachedSymbol != null"}, replaces = {"doHandleCached",
                    "doNativeFunctionCached"}, rewriteOn = LLVMIllegalSymbolIndexException.class)
    protected Object doLookupNativeFunctionCachedSymbol(VirtualFrame frame, LLVMNativePointer pointer,
                    @Cached("lookupFunctionSymbol(pointer)") LLVMAccessSymbolNode cachedSymbol) {
        LLVMPointer symbolPointer;
        try {
            symbolPointer = cachedSymbol.executeGeneric(frame);
        } catch (NullPointerException e) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            // guard against uninitialized symbols in multi-context cases
            symbolPointer = null;
        }
        if (LLVMManagedPointer.isInstance(symbolPointer)) {
            LLVMManagedPointer managedPointer = LLVMManagedPointer.cast(symbolPointer);
            if (managedPointer.getOffset() == 0 && managedPointer.getObject() instanceof LLVMFunctionDescriptor) {
                LLVMFunctionDescriptor descriptor = (LLVMFunctionDescriptor) managedPointer.getObject();
                long nativePointer = descriptor.getNativePointer();
                if (nativePointer != 0 && nativePointer == pointer.asNative()) {
                    return descriptor;
                }
            }
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new LLVMIllegalSymbolIndexException("mismatching function");
    }

    @Specialization(guards = "!isAutoDerefHandle(pointer.asNative())", replaces = {"doLookupNativeFunctionCachedSymbol", "doHandleCached", "doNativeFunctionCached"})
    protected Object doLookup(LLVMNativePointer pointer,
                    @CachedContext(LLVMLanguage.class) ContextReference<LLVMContext> ctxRef) {
        LLVMFunctionDescriptor descriptor = lookupFunction(ctxRef, pointer);
        if (descriptor != null) {
            return descriptor;
        } else {
            return pointer;
        }
    }

    @Specialization(guards = "isAutoDerefHandle(pointer.asNative())")
    protected Object doDerefHandle(LLVMNativePointer pointer,
                    @Cached LLVMDerefHandleGetReceiverNode getReceiver) {
        LLVMManagedPointer foreignFunction = getReceiver.execute(pointer);
        return foreignFunction.getObject();
    }

    protected LLVMFunctionDescriptor lookupFunction(ContextReference<LLVMContext> ctxRef, LLVMNativePointer function) {
        return ctxRef.get().getFunctionDescriptor(function);
    }

    protected LLVMAccessSymbolNode lookupFunctionSymbol(LLVMNativePointer function) {
        CompilerAsserts.neverPartOfCompilation();
        LLVMContext context = LLVMLanguage.getContext();
        LLVMFunctionDescriptor descriptor = context.getFunctionDescriptor(function);
        return descriptor == null || descriptor.getLLVMFunction() == null ? null : LLVMAccessSymbolNodeGen.create(descriptor.getLLVMFunction());
    }

    protected boolean isAutoDerefHandle(long addr) {
        if (languageRef == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            languageRef = lookupLanguageReference(LLVMLanguage.class);
        }
        // checking the bit is cheaper than getting the assumption in interpreted mode
        if (CompilerDirectives.inCompiledCode() && languageRef.get().getNoDerefHandleAssumption().isValid()) {
            return false;
        }
        return LLVMHandleMemoryBase.isDerefHandleMemory(addr);
    }

    public static LLVMExpressionNode createOptimized(LLVMExpressionNode function) {
        if (function instanceof LLVMAccessSymbolNode) {
            LLVMAccessSymbolNode node = (LLVMAccessSymbolNode) function;
            if (node.getSymbol() instanceof LLVMFunction) {
                return LLVMLookupDispatchTargetSymbolNodeGen.create((LLVMFunction) node.getSymbol());
            }
        }
        return LLVMLookupDispatchTargetNodeGen.create(function);
    }
}
