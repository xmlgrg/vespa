#include "com_yahoo_vespa_model_application_validation_NativeVerifyRankSetup.h"
#include "verify_ranksetup.h"
#include <stdexcept>

JNIEXPORT jboolean
JNICALL Java_com_yahoo_vespa_model_application_validation_NativeVerifyRankSetup_verify(JNIEnv * env, jclass, jstring jconfigId)
{
    const char *configId = env->GetStringUTFChars(jconfigId, NULL);
    if (NULL == configId) return false;

    std::string messages;
    bool ok = false;
    try {
        ok = verifyRankSetup(configId, messages);
    } catch (std::exception &) {

    }
    env->ReleaseStringUTFChars(jconfigId, configId);
    return static_cast<jboolean>(ok);
}

