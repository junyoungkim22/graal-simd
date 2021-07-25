package org.graalvm.compiler.lir.amd64.vec;

public enum GotoOpCode {
    MUL("0000"),
    ADD("0001"),
    FMADD("0010"),
    A("1000"),
    B("1001"),
    C("1010"),
    CONSTARG("1011")
    ;

    private final String text;

    GotoOpCode(final String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return text;
    }
}