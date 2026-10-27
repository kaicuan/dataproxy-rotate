package com.dataproxy.proxy

import kotlinx.coroutines.Job

/** A client session that the listener can force closed during shutdown. */
interface ManagedProxyConnection {
    fun handle(): Job
    fun abort()
}
