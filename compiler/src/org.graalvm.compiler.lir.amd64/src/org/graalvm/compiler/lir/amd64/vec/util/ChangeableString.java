package org.graalvm.compiler.lir.amd64.vec.util;

public final class ChangeableString {
    private String str;

    public ChangeableString(String str) {
        this.str = str;
    }

    public String cutOff(int length) {
        String ret = str.substring(0, length);
        this.str = str.substring(length, str.length());
        return ret;
    }

    public String toString() {
        return str;
    }
}