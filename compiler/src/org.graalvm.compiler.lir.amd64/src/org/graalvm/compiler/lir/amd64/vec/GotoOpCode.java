package org.graalvm.compiler.lir.amd64.vec;

public enum GotoOpCode {
    MUL("0000"),
    ADD("0002342341"),
    A("1000"),
    B("1001"),
    CONSTARG("1010")
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