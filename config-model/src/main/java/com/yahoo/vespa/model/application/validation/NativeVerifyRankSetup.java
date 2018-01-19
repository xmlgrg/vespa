package com.yahoo.vespa.model.application.validation;

public class NativeVerifyRankSetup {
    static {
        System.loadLibrary("searchcore_verify_ranksetup");
    }

    private String messages;

    public String getMessages() { return messages; }
    public native boolean verify(String configid);
}
