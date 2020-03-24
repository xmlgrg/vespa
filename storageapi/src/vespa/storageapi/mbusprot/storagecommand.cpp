// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "storagecommand.h"
#include "storagereply.h"

namespace storage::mbusprot {

StorageCommand::StorageCommand(api::StorageCommand::SP cmd)
    : mbus::Message(),
      _cmd(std::move(cmd))
{ }

std::unique_ptr<mbus::Reply>
StorageCommand::makeReply() {
    auto reply = _cmd->makeReply();
    dynamic_cast<api::BucketInfoReply &>(*reply).setBucketInfo(api::BucketInfo(1,1,1));
    return std::make_unique<StorageReply>(std::move(reply));
}

}
