// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/fastos/thread.h>
#include <cstdint>

namespace vespalib {

/**
 * Class to ensure that the current process finishes within a given time.
 * Construct with the number of milliseconds before triggering _exit();
 * destruct the ShutdownGuard object to dismiss the automatic process
 * termination.
 * A separate shutdown thread will perform the actual _exit() call.
 **/
class ShutdownGuard : public FastOS_Runnable
{
    FastOS_ThreadPool _pool;
    volatile uint64_t _dieAtTime;

    void Run(FastOS_ThreadInterface *, void *) override;

public:
    /**
     * Construct a shutdown guard with a given lifetime.
     * @arg millis the number of milliseconds before process automatically exits
     **/
    ShutdownGuard(uint64_t millis);

    /**
     * Destructor that dismisses the guard and collects the shutdown thread.
     **/
    ~ShutdownGuard();

};

} // namespace vespalib

