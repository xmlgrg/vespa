# Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_library(searchcore_verify_ranksetup
    SOURCES
    verify_ranksetup.cpp
    com_yahoo_vespa_model_application_validation_NativeVerifyRankSetup.cpp
    INSTALL lib64
    DEPENDS
    searchcore_fconfig
    searchcore_matching
    searchcore_documentmetastore
)

vespa_add_executable(searchcore_verify_ranksetup_app
    SOURCES
    verify_ranksetup_app.cpp
    OUTPUT_NAME vespa-verify-ranksetup-bin
    INSTALL bin
    DEPENDS
    searchcore_verify_ranksetup
)
