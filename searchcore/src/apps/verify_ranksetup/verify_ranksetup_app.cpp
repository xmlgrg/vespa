// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "verify_ranksetup.h"
#include <vespa/fastos/app.h>

#include <vespa/log/log.h>
LOG_SETUP("vespa-verify-ranksetup");

class App : public FastOS_Application
{
public:
    int usage();
    int Main() override;
};

int
App::usage()
{
    fprintf(stderr, "Usage: vespa-verify-ranksetup <config-id>\n");
    return 1;
}

int
App::Main()
{
    if (_argc != 2) {
        return usage();
    }

    bool ok = verifyRankSetup(_argv[1]);

    if (!ok) {
        return 1;
    }
    return 0;
}

int main(int argc, char **argv) {
    App app;
    return app.Entry(argc, argv);
}
