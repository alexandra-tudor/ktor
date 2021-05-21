/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.application.plugins.api

import io.ktor.application.*
import io.ktor.application.plugins.api.*
import io.ktor.server.config.*
import io.ktor.server.http.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.util.*
import java.io.*
import java.util.logging.*
import io.ktor.util.pipeline.*

/**
 * Gets plugin instance for this pipeline, or fails with [MissingApplicationFeatureException] if the feature is not installed
 * @throws MissingApplicationFeatureException
 * @param plugin plugin to lookup
 * @return an instance of plugin
 */
public fun <A : Pipeline<*, ApplicationCall>, ConfigurationT : Any> A.plugin(
    plugin: ApplicationInstallablePlugin<ConfigurationT>
): ApplicationPlugin<ConfigurationT> {
    return attributes[featureRegistryKey].getOrNull(plugin.key)
        ?: throw MissingApplicationFeatureException(plugin.key)
}

internal fun <A : Pipeline<*, ApplicationCall>> A.findInterceptionsHolder(
    plugin: ApplicationInstallablePlugin<*>
): InterceptionsHolder {
    return attributes[featureRegistryKey].getOrNull(plugin.key)
        ?: throw MissingApplicationFeatureException(plugin.key)
}

public abstract class ApplicationInstallablePlugin<Configuration : Any>(public val name: String) :
    ApplicationFeature<ApplicationCallPipeline, Configuration, ApplicationPlugin<Configuration>>

internal typealias PipelineHandler = (Pipeline<*, ApplicationCall>) -> Unit

/**
 * A plugin for Ktor that embeds into the HTTP pipeline and extends functionality of Ktor framework.
 * */
public abstract class ApplicationPlugin<Configuration : Any> private constructor(
    installablePlugin: ApplicationInstallablePlugin<Configuration>
) : PluginContext,
    InterceptionsHolder {
    protected abstract val pipeline: ApplicationCallPipeline

    public abstract val pluginConfig: Configuration

    public override val name: String = installablePlugin.name

    public val environment: ApplicationEnvironment? get() = pipeline.environment
    public val configuration: ApplicationConfig? get() = environment?.config

    override val key: AttributeKey<ApplicationPlugin<Configuration>> = installablePlugin.key

    @Deprecated(level = DeprecationLevel.WARNING, message = "Please, do not use this field in your new plugin")
    override val fallbackInterceptions: MutableList<CallInterception> = mutableListOf()

    @Deprecated(level = DeprecationLevel.WARNING, message = "Please, do not use this field in your new plugin")
    override val callInterceptions: MutableList<CallInterception> = mutableListOf()

    @Deprecated(level = DeprecationLevel.WARNING, message = "Please, do not use this field in your new plugin")
    override val monitoringInterceptions: MutableList<CallInterception> = mutableListOf()

    @Deprecated(level = DeprecationLevel.WARNING, message = "Please, do not use this field in your new plugin")
    override val beforeReceiveInterceptions: MutableList<ReceiveInterception> = mutableListOf()
    @Deprecated(level = DeprecationLevel.WARNING, message = "Please, do not use this field in your new plugin")
    override val onReceiveInterceptions: MutableList<ReceiveInterception> = mutableListOf()

    @Deprecated(level = DeprecationLevel.WARNING, message = "Please, do not use this field in your new plugin")
    override val beforeResponseInterceptions: MutableList<ResponseInterception> = mutableListOf()

    @Deprecated(level = DeprecationLevel.WARNING, message = "Please, do not use this field in your new plugin")
    override val onResponseInterceptions: MutableList<ResponseInterception> = mutableListOf()

    @Deprecated(level = DeprecationLevel.WARNING, message = "Please, do not use this field in your new plugin")
    override val afterResponseInterceptions: MutableList<ResponseInterception> = mutableListOf()

    internal val pipelineHandlers: MutableList<PipelineHandler> = mutableListOf()
    internal val afterHandleCallbacks: MutableList<suspend RequestContext.(ApplicationCall) -> Unit> =
        mutableListOf()

    private fun <T : Any, ContextT : CallHandlingContext> onDefaultPhaseWithMessage(
        interceptions: MutableList<Interception<T>>,
        phase: PipelinePhase,
        contextInit: (PipelineContext<T, ApplicationCall>) -> ContextT,
        block: suspend ContextT.(ApplicationCall, Any) -> Unit
    ) {
        interceptions.add(
            Interception(
                phase,
                action = { pipeline ->
                    pipeline.intercept(phase) { contextInit(this).block(call, subject) }
                }
            )
        )
    }

    private fun <T : Any, ContextT : CallHandlingContext> onDefaultPhase(
        interceptions: MutableList<Interception<T>>,
        phase: PipelinePhase,
        contextInit: (PipelineContext<T, ApplicationCall>) -> ContextT,
        block: suspend ContextT.(ApplicationCall) -> Unit
    ) = onDefaultPhaseWithMessage(interceptions, phase, contextInit) { call, _ -> block(call) }

    /**
     * Callable object that defines how HTTP call handling should be modified by the current [ApplicationPlugin].
     * */
    public override val onCall: OnCall = object : OnCall {
        private val plugin = this@ApplicationPlugin

        override operator fun invoke(block: suspend RequestContext.(ApplicationCall) -> Unit) {
            plugin.onDefaultPhase(
                plugin.callInterceptions,
                ApplicationCallPipeline.Features,
                ::RequestContext
            ) { call ->
                block(call)
            }
        }

        override fun beforeHandle(block: suspend RequestContext.(ApplicationCall) -> Unit) {
            plugin.onDefaultPhase(
                plugin.monitoringInterceptions,
                ApplicationCallPipeline.Monitoring,
                ::RequestContext
            ) { call ->
                block(call)
            }
        }

        /**
         * Defines actions to perform after the call was processed by all features.
         * Useful for metrics and logging.
         * */
        override fun afterHandle(block: suspend RequestContext.(ApplicationCall) -> Unit) {
            plugin.afterHandleCallbacks.add(block)
        }
    }

    /**
     * Callable object that defines how receiving data from HTTP call should be modified by the current [ApplicationPlugin].
     * */
    public override val onCallReceive: OnCallReceive = object : OnCallReceive {
        private val plugin = this@ApplicationPlugin

        override fun invoke(block: suspend CallReceiveContext.(ApplicationCall) -> Unit) {
            plugin.onDefaultPhase(
                plugin.onReceiveInterceptions,
                ApplicationReceivePipeline.Transform,
                ::CallReceiveContext,
                block
            )
        }
    }

    /**
     * Callable object that defines how sending data to a client within HTTP call should be modified by the current [ApplicationPlugin].
     * */
    public override val onCallRespond: OnCallRespond = object : OnCallRespond {
        private val plugin = this@ApplicationPlugin

        override fun invoke(block: suspend CallRespondContext.(ApplicationCall) -> Unit) {
            plugin.onDefaultPhase(
                plugin.onResponseInterceptions,
                ApplicationSendPipeline.Transform,
                ::CallRespondContext,
                block
            )
        }

        override fun afterTransform(block: suspend CallRespondAfterTransformContext.(ApplicationCall, Any) -> Unit) {
            plugin.onDefaultPhaseWithMessage(
                plugin.afterResponseInterceptions,
                ApplicationSendPipeline.After,
                ::CallRespondAfterTransformContext,
                block
            )
        }
    }

    public abstract class RelativePluginContext(
        private val currentPlugin: ApplicationPlugin<*>,
        private val otherPlugins: List<InterceptionsHolder>
    ) : PluginContext {
        protected fun <T : Any> sortedPhases(
            interceptions: List<Interception<T>>,
            pipeline: Pipeline<*, ApplicationCall>,
            otherPlugin: InterceptionsHolder
        ): List<PipelinePhase> =
            interceptions
                .map { it.phase }
                .sortedBy {
                    if (!pipeline.items.contains(it)) {
                        throw PluginNotInstalledException(otherPlugin.name)
                    }

                    pipeline.items.indexOf(it)
                }

        public abstract fun selectPhase(phases: List<PipelinePhase>): PipelinePhase?

        public abstract fun insertPhase(
            pipeline: Pipeline<*, ApplicationCall>,
            relativePhase: PipelinePhase,
            newPhase: PipelinePhase
        )

        private fun <T : Any, ContextT : CallHandlingContext> insertToPhaseRelativelyWithMessage(
            currentInterceptions: MutableList<Interception<T>>,
            otherInterceptionsList: List<MutableList<Interception<T>>>,
            contextInit: (PipelineContext<T, ApplicationCall>) -> ContextT,
            block: suspend ContextT.(ApplicationCall, Any) -> Unit
        ) {
            val currentPhase = currentPlugin.newPhase()

            currentInterceptions.add(
                Interception(
                    currentPhase,
                    action = { pipeline ->
                        for (i in otherPlugins.indices) {
                            val otherPlugin = otherPlugins[i]
                            val otherInterceptions = otherInterceptionsList[i]

                            val otherPhases = sortedPhases(otherInterceptions, pipeline, otherPlugin)
                            selectPhase(otherPhases)?.let { lastDependentPhase ->
                                insertPhase(pipeline, lastDependentPhase, currentPhase)
                            }
                        }

                        pipeline.intercept(currentPhase) {
                            contextInit(this).block(call, subject)
                        }
                    }
                )
            )
        }

        private fun <T : Any, ContextT : CallHandlingContext> insertToPhaseRelatively(
            currentInterceptions: MutableList<Interception<T>>,
            otherInterceptions: List<MutableList<Interception<T>>>,
            contextInit: (PipelineContext<T, ApplicationCall>) -> ContextT,
            block: suspend ContextT.(ApplicationCall) -> Unit
        ) = insertToPhaseRelativelyWithMessage(currentInterceptions, otherInterceptions, contextInit) { call, _ ->
            block(call)
        }

        override val onCall: OnRequestBase = object : OnRequestBase {
            override operator fun invoke(block: suspend RequestContext.(ApplicationCall) -> Unit) {
                insertToPhaseRelatively(
                    currentPlugin.callInterceptions,
                    otherPlugins.map { it.callInterceptions },
                    ::RequestContext
                ) { call -> block(call) }
            }

            override fun beforeHandle(block: suspend RequestContext.(ApplicationCall) -> Unit) {
                insertToPhaseRelatively(
                    currentPlugin.monitoringInterceptions,
                    otherPlugins.map { it.monitoringInterceptions },
                    ::RequestContext
                ) { call -> block(call) }
            }
        }

        override val onCallReceive: OnCallReceive = object : OnCallReceive {
            override operator fun invoke(block: suspend CallReceiveContext.(ApplicationCall) -> Unit) {
                insertToPhaseRelatively(
                    currentPlugin.onReceiveInterceptions,
                    otherPlugins.map { it.onReceiveInterceptions },
                    ::CallReceiveContext,
                    block
                )
            }
        }

        override val onCallRespond: OnCallRespond = object : OnCallRespond {
            override operator fun invoke(block: suspend CallRespondContext.(ApplicationCall) -> Unit) {
                insertToPhaseRelatively(
                    currentPlugin.onResponseInterceptions,
                    otherPlugins.map { it.onResponseInterceptions },
                    ::CallRespondContext,
                    block
                )
            }

            override fun afterTransform(block: suspend CallRespondAfterTransformContext.(ApplicationCall, Any) -> Unit) {
                insertToPhaseRelativelyWithMessage(
                    currentPlugin.afterResponseInterceptions,
                    otherPlugins.map { it.afterResponseInterceptions },
                    ::CallRespondAfterTransformContext,
                    block
                )
            }
        }

        @Deprecated(
            level = DeprecationLevel.WARNING,
            replaceWith = ReplaceWith("this@createPlugin.applicationShutdownHook"),
            message = "Please note that applicationShutdownHook is not guaranteed to be executed before/after other plugin"
        )
        override fun applicationShutdownHook(hook: (Application) -> Unit) {
            currentPlugin.environment?.monitor?.subscribe(ApplicationStopped) { app ->
                hook(app)
            }
        }
    }

    public class AfterPluginContext(currentPlugin: ApplicationPlugin<*>, otherPlugins: List<InterceptionsHolder>) :
        RelativePluginContext(currentPlugin, otherPlugins) {
        override fun selectPhase(phases: List<PipelinePhase>): PipelinePhase? = phases.lastOrNull()

        override fun insertPhase(
            pipeline: Pipeline<*, ApplicationCall>,
            relativePhase: PipelinePhase,
            newPhase: PipelinePhase
        ) {
            pipeline.insertPhaseAfter(relativePhase, newPhase)
        }
    }

    public class BeforePluginsContext(currentPlugin: ApplicationPlugin<*>, otherPlugins: List<InterceptionsHolder>) :
        RelativePluginContext(currentPlugin, otherPlugins) {
        override fun selectPhase(phases: List<PipelinePhase>): PipelinePhase? = phases.firstOrNull()

        override fun insertPhase(
            pipeline: Pipeline<*, ApplicationCall>,
            relativePhase: PipelinePhase,
            newPhase: PipelinePhase
        ) {
            pipeline.insertPhaseBefore(relativePhase, newPhase)
        }
    }

    /**
     * Execute some actions right after all [targetPlugins] were already executed.
     *
     * Note: you can define multiple actions inside a [build] callback for multiple stages of handling an HTTP call
     * (such as [onCall], [onCallRespond], etc.) and each of these actions will be executed right after all actions defined
     * by the given [plugin] were already executed in the same stage.
     * */
    public fun afterPlugins(
        vararg targetPlugins: ApplicationInstallablePlugin<out Any>,
        build: AfterPluginContext.() -> Unit
    ) {
        pipelineHandlers.add { pipeline ->
            AfterPluginContext(this, targetPlugins.map { pipeline.findInterceptionsHolder(it) }).build()
        }
    }

    /**
     * Execute some actions right before all [targetPlugins] were already executed.
     *
     * Note: you can define multiple actions inside a [build] callback for multiple stages of handling an HTTP call
     * (such as [onCall], [onCallRespond], etc.) and each of these actions will be executed right before all actions defined
     * by the given [targetPlugins] were already executed in the same stage.
     * */

    public fun beforePlugins(
        vararg targetPlugins: ApplicationInstallablePlugin<out Any>,
        build: BeforePluginsContext.() -> Unit
    ) {
        pipelineHandlers.add { pipeline ->
            BeforePluginsContext(this, targetPlugins.map { pipeline.findInterceptionsHolder(it) }).build()
        }
    }

    public companion object {
        /**
         * A canonical way to create a [ApplicationPlugin].
         * */
        public fun <Configuration : Any> createPlugin(
            name: String,
            createConfiguration: (ApplicationCallPipeline) -> Configuration,
            body: ApplicationPlugin<Configuration>.() -> Unit
        ): ApplicationInstallablePlugin<Configuration> = object : ApplicationInstallablePlugin<Configuration>(name) {
            override val key: AttributeKey<ApplicationPlugin<Configuration>> = AttributeKey(name)

            override fun install(
                pipeline: ApplicationCallPipeline,
                configure: Configuration.() -> Unit
            ): ApplicationPlugin<Configuration> {
                val config = createConfiguration(pipeline)
                config.configure()

                val self = this
                val pluginInstance = object : ApplicationPlugin<Configuration>(self) {
                    override val pipeline: ApplicationCallPipeline
                        get() = pipeline
                    override val pluginConfig: Configuration
                        get() = config
                }

                pluginInstance.apply(body)

                pipeline.intercept(ApplicationCallPipeline.Monitoring) {
                    proceed()
                    pluginInstance.afterHandleCallbacks.forEach { handle ->
                        RequestContext(this).handle(call)
                    }
                }

                pluginInstance.pipelineHandlers.forEach { handle ->
                    handle(pipeline)
                }

                pluginInstance.fallbackInterceptions.forEach {
                    it.action(pipeline)
                }

                pluginInstance.callInterceptions.forEach {
                    it.action(pipeline)
                }

                pluginInstance.monitoringInterceptions.forEach {
                    it.action(pipeline)
                }

                pluginInstance.beforeReceiveInterceptions.forEach {
                    it.action(pipeline.receivePipeline)
                }

                pluginInstance.onReceiveInterceptions.forEach {
                    it.action(pipeline.receivePipeline)
                }

                pluginInstance.beforeResponseInterceptions.forEach {
                    it.action(pipeline.sendPipeline)
                }

                pluginInstance.onResponseInterceptions.forEach {
                    it.action(pipeline.sendPipeline)
                }

                pluginInstance.afterResponseInterceptions.forEach {
                    it.action(pipeline.sendPipeline)
                }

                return pluginInstance
            }
        }

        /**
         * A canonical way to create a [ApplicationPlugin].
         * */
        public fun createPlugin(
            name: String,
            body: ApplicationPlugin<Unit>.() -> Unit
        ): ApplicationInstallablePlugin<Unit> = createPlugin<Unit>(name, {}, body)
    }

    override fun applicationShutdownHook(hook: (Application) -> Unit) {
        environment?.monitor?.subscribe(ApplicationStopped) { app ->
            hook(app)
        }
    }
}

/**
 * Port of the current application. Same as in config.
 * */
public val ApplicationConfig.port: Int get() = propertyOrNull("ktor.deployment.port")?.getString()?.toInt() ?: 8080

/**
 * Host of the current application. Same as in config.
 * */
public val ApplicationConfig.host: String get() = propertyOrNull("ktor.deployment.host")?.getString() ?: "0.0.0.0"
