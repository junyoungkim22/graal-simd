package org.graalvm.compiler.lir.amd64.vec;

public enum GotoOpCode {
    MUL("00000"),
    ADD("00001"),
    FMADD("00010"),
    SUB("00011"),
    DIV("00100"),
    MASKMUL("01000"),
    MASKADD("01001"),
    MASKFMADD("01010"),
    MASKSUB("01011"),
    MASKDIV("01100"),
    GT("11000"),
    GE("11001"),
    LT("11010"),
    LE("11011"),
    EQ("11100"),
    NEQ("11101"),
    A("10000"),
    B("10001"),
    C("10010"),
    CONSTARG("10011"),
    VARIABLEARG("10100"),
    VARIABLEARRARG("10101")
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