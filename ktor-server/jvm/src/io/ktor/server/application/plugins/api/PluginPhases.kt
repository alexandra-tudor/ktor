/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.application.plugins.api

import io.ktor.application.*
import io.ktor.request.*

public interface OnRequestBase {
    /**
     * Define how processing an HTTP call should be modified by the current [ApplicationPlugin].
     * */
    public operator fun invoke(block: suspend RequestContext.(ApplicationCall) -> Unit): Unit

    /**
     * Defines actions to perform before the call was processed by any feature (including [Routing]).
     * It is useful for monitoring and logging (see [CallLogging] feature) to be executed before any "real stuff"
     * was performed with the call because other features can change it's content as well as add more resource usage etc.
     * while for logging and monitoring it is important to observe the pure (untouched) data.
     * */
    public fun beforeHandle(block: suspend RequestContext.(ApplicationCall) -> Unit): Unit
}

public interface OnCall: OnRequestBase {
    /**
     * Defines actions to perform after the call was processed by all features.
     * Useful for metrics and logging.
     * */
    public fun afterHandle(block: suspend RequestContext.(ApplicationCall) -> Unit): Unit
}

public interface OnCallReceive {
    /**
     * Define how current [ApplicationPlugin] should transform data received from a client.
     * */
    public operator fun invoke(block: suspend CallReceiveContext.(ApplicationCall) -> Unit): Unit
}

public interface OnCallRespond {
    /**
     * Do transformations of the data. Example: you can write a custom serializer using this method.
     * */
    public operator fun invoke(block: suspend CallRespondContext.(ApplicationCall) -> Unit): Unit

    /**
     * Allows to calculate some statistics on the data that was already sent to a client, or to handle errors.
     * (See [Metrics], [CachingHeaders], [StatusPages] features as examples).
     * */
    public fun afterTransform(block: suspend CallRespondAfterTransformContext.(ApplicationCall, Any) -> Unit): Unit
}

public interface PluginContext {
    public val onCall: OnRequestBase
    public val onCallReceive: OnCallReceive
    public val onCallRespond: OnCallRespond

    /**
     * Sets a shutdown hook. This method is useful for closing resources allocated by the feature.
     * */
    public fun applicationShutdownHook(hook: (Application) -> Unit)
}
