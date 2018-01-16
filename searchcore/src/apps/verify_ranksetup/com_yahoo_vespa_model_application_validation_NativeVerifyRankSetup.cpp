#include "com_yahoo_vespa_model_application_validation_NativeVerifyRankSetup.h"
#include "verify_ranksetup.h"

JNIEXPORT jboolean
JNICALL Java_com_yahoo_vespa_model_application_validation_NativeVerifyRankSetup_verify(JNIEnv *, jclass, jstring)
{
    const char * configId = "";
    bool ok = verifyRankSetup(configId);
}

