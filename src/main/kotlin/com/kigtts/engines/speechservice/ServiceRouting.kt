// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A service routes standard lifecycle signals; the provider alone owns resident native state. */
internal class ServiceRouting(private val settings: ServiceSettings, private val broker: SpeechBroker,
    private val system: TtsEngine) {
    private data class Route(val id: String?, val voice: String?, val generation: String = "")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var route: Route? = null
    private var used = false
    private var resident: Boolean? = null
    private var active = false
    @Volatile private var automatic = false
    init { scope.launch { for (ignored in changes) {
        try { mutex.withLock { if (route != null) reconcile(false) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { }
    } } }
    fun changed(automaticRead: Boolean? = null) {
        if (automaticRead != null) automatic = automaticRead
        changes.trySend(Unit)
    }
    suspend fun begin(): TtsProvider? = mutex.withLock { reconcile(false).also { used = true } }
    suspend fun control(command: TtsControl) = mutex.withLock {
        when (command.operation) {
            TtsControlOperation.PREPARE -> { reconcile(true); used = true }
            TtsControlOperation.RELEASE -> { active = false; release(); system.control(TtsControl(TtsControlOperation.RELEASE)) }
            TtsControlOperation.SET_RESIDENT -> { settings.resident = command.resident; reconcile(false) }
            TtsControlOperation.ACTIVITY -> {
                active = command.active
                system.control(command)
                route?.takeIf { it.id != null }?.let { send(it, command) }
            }
        }
    }
    private suspend fun reconcile(warm: Boolean): TtsProvider? {
        val provider = try { broker.providers().firstOrNull {
            it.pluginId == settings.provider && it.pluginId != LEGACY_SYSTEM_PROVIDER && it.pluginId != SYSTEM_PROVIDER &&
                it.enabled && it.info.availability == EngineAvailability.READY
        } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        val next = Route(provider?.pluginId, if (provider == null) settings.systemVoice else settings.voice, provider?.generation.orEmpty())
        val previous = route
        val switched = previous != next
        val preload = warm || switched && (automatic || previous?.id == next.id && used)
        val pinActivated = settings.resident && resident != true && used
        if (switched) { release(); system.control(TtsControl(TtsControlOperation.RELEASE)); route = next }
        if (switched || resident != settings.resident) {
            send(next, TtsControl(TtsControlOperation.SET_RESIDENT, resident = settings.resident))
            resident = settings.resident
        }
        if (switched && active) send(next, TtsControl(TtsControlOperation.ACTIVITY, active = true))
        if (preload || pinActivated) { send(next, TtsControl(TtsControlOperation.PREPARE, next.voice)); used = true }
        return provider
    }
    private suspend fun send(target: Route, command: TtsControl) {
        if (target.id == null) system.control(command)
        else if (broker.providers().firstOrNull { it.pluginId == target.id }?.info?.supportsControl == true)
            broker.control(target.id, command)
    }
    suspend fun fallback() = mutex.withLock {
        system.control(TtsControl(TtsControlOperation.SET_RESIDENT, resident = settings.resident))
        system.control(TtsControl(TtsControlOperation.ACTIVITY, active = active))
    }
    private suspend fun release() {
        val previous = route
        route = null; used = false; resident = null
        if (previous != null) {
            try { send(previous, TtsControl(TtsControlOperation.RELEASE)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
        }
    }
    suspend fun close() { scope.cancel(); mutex.withLock { release(); system.close() } }
}
