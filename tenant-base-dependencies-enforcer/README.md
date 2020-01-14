<!-- Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root. -->
# Dependencies enforcer for public hosted Vespa projects.

Enforces that only whitelisted dependencies are visible in
the provided classpath for tenant projects. The whitelist
must only contain artifacts that are provided runtime from 
the JDisc container.
