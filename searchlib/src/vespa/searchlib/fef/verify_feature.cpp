// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "verify_feature.h"
#include "blueprintresolver.h"
#include <vespa/vespalib/util/stringfmt.h>

#include <vespa/log/log.h>
LOG_SETUP(".fef.verify_feature");

namespace search::fef {

bool verifyFeature(const BlueprintFactory &factory,
                   const IIndexEnvironment &indexEnv,
                   const std::string &featureName,
                   const std::string &desc,
                   std::vector<vespalib::string> & errors)
{
    indexEnv.hintFeatureMotivation(IIndexEnvironment::VERIFY_SETUP);
    BlueprintResolver resolver(factory, indexEnv);
    resolver.addSeed(featureName);
    bool result = resolver.compile();
    if (!result) {
        const BlueprintResolver::Errors & compileErrors(resolver.getCompileErrors());
        errors.insert(errors.end(), compileErrors.begin(), compileErrors.end());
        vespalib::string msg = vespalib::make_string("rank feature verification failed: %s (%s)",
                                                     featureName.c_str(), desc.c_str());
        LOG(error, "%s", msg.c_str());
        errors.emplace_back(msg);
    }
    return result;
}

}
