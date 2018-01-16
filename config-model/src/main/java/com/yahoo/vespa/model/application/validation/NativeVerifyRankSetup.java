package com.yahoo.vespa.model.application.validation;

public class NativeVerifyRankSetup {
    static {
        System.loadLibrary("searchcore_verify_ranksetup");
    }
    public static native boolean verify(String configid);
}
