package com.mrpowergamerbr.butterscotch.audio

import com.mrpowergamerbr.butterscotch.data.AudioData
import com.mrpowergamerbr.butterscotch.data.AudioFormat
import com.mrpowergamerbr.butterscotch.data.GameData
import org.lwjgl.openal.AL
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.stb.STBVorbis
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class AudioEngine(
    private val gameData: GameData,
    audioDirs: List<String> = emptyList(),
    traceOverride: Boolean = false,
    audioLogPathOverride: String? = null,
    stopCasterOnRoomChangeOverride: Boolean? = null,
) {
    private var device: Long = MemoryUtil.NULL
    private var context: Long = MemoryUtil.NULL
    private var enabled = false
    private val trace = traceOverride || System.getenv("BUTTERSCOTCH_AUDIO_TRACE") == "1"
    private val audioLogPath = audioLogPathOverride ?: System.getenv("BUTTERSCOTCH_AUDIO_LOG")
    private val audioLogWriter = audioLogPath?.let { PrintWriter(FileWriter(it, true), true) }
    private var currentFrame = 0
    private val stopCasterOnRoomChange = stopCasterOnRoomChangeOverride
        ?: (System.getenv("BUTTERSCOTCH_STOP_CASTER_ON_ROOM_CHANGE") != "0")
    private val baseDirs: List<Path> = buildBaseDirs(audioDirs)

    private val buffers = IntArray(gameData.audioData.size)
    private val sourceHandles = mutableMapOf<Int, Int>()
    private val sourceToSound = mutableMapOf<Int, Int>()
    private val soundToSources = mutableMapOf<Int, MutableSet<Int>>()
    private var nextHandle = 1
    private val streamBase = 1_000_000
    private var nextStreamId = streamBase
    private val streamToSound = mutableMapOf<Int, Int>()
    private val externalStreamBuffers = mutableMapOf<Int, Int>()
    private val externalSoundBuffers = mutableMapOf<Int, Int>()
    private val casterHandles = mutableSetOf<Int>()

    private var masterGain = 1.0f
    private val soundGainOverrides = mutableMapOf<Int, Float>()
    private val soundPitchOverrides = mutableMapOf<Int, Float>()

    init {
        enabled = initializeOpenAL()
    }

    fun dispose() {
        if (!enabled) return
        stopAll()
        for (i in buffers.indices) {
            if (buffers[i] != 0) {
                AL10.alDeleteBuffers(buffers[i])
                buffers[i] = 0
            }
        }
        for (buffer in externalStreamBuffers.values) {
            AL10.alDeleteBuffers(buffer)
        }
        externalStreamBuffers.clear()
        for (buffer in externalSoundBuffers.values) {
            AL10.alDeleteBuffers(buffer)
        }
        externalSoundBuffers.clear()
        audioLogWriter?.flush()
        audioLogWriter?.close()
        ALC10.alcMakeContextCurrent(MemoryUtil.NULL)
        if (context != MemoryUtil.NULL) {
            ALC10.alcDestroyContext(context)
            context = MemoryUtil.NULL
        }
        if (device != MemoryUtil.NULL) {
            ALC10.alcCloseDevice(device)
            device = MemoryUtil.NULL
        }
        enabled = false
    }

    fun update() {
        if (!enabled) return
        val iterator = sourceHandles.iterator()
        while (iterator.hasNext()) {
            val (handle, source) = iterator.next()
            val state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE)
            if (state == AL10.AL_STOPPED) {
                AL10.alDeleteSources(source)
                iterator.remove()
                val soundId = sourceToSound.remove(handle)
                if (soundId != null) {
                    val set = soundToSources[soundId]
                    set?.remove(handle)
                    if (set != null && set.isEmpty()) soundToSources.remove(soundId)
                }
            }
        }
    }

    fun setFrame(frame: Int) {
        currentFrame = frame
    }

    fun onRoomChange() {
        if (stopCasterOnRoomChange) {
            stopCaster(null)
        }
    }

    fun playSound(soundId: Int, loop: Boolean): Int {
        if (!enabled) return 0
        val externalStreamBuffer = externalStreamBuffers[soundId]
        if (externalStreamBuffer != null) {
            logAudio("play external handle=$soundId loop=$loop")
            logEvent(
                "play",
                "handle=$soundId external=true loop=$loop"
            )
            return playBuffer(soundId, externalStreamBuffer, loop, baseVolume = 1.0f)
        }
        val resolvedSoundId = resolveSoundId(soundId) ?: return 0
        val sound = gameData.sounds.getOrNull(resolvedSoundId) ?: return 0

        val externalSoundBuffer = externalSoundBuffers[resolvedSoundId] ?: run {
            val path = resolveExternalAudioPath(sound.fileName)
            if (path != null) {
                val bufferId = decodeExternalFileToBuffer(path)
                if (bufferId != null) {
                    externalSoundBuffers[resolvedSoundId] = bufferId
                    logAudio("external override soundId=$resolvedSoundId name=${sound.name} path=$path")
                    logEvent("external_override", "soundId=$resolvedSoundId name=${sound.name} path=$path")
                    bufferId
                } else null
            } else null
        }
        if (externalSoundBuffer != null) {
            logAudio("play external soundId=$soundId resolved=$resolvedSoundId name=${sound.name} file=${sound.fileName} loop=$loop")
            logEvent(
                "play",
                "soundId=$soundId resolved=$resolvedSoundId name=${sound.name} file=${sound.fileName} external=true loop=$loop"
            )
            return playBuffer(resolvedSoundId, externalSoundBuffer, loop, baseVolume = sound.volume)
        }

        val audioId = sound.audioId
        if (audioId !in gameData.audioData.indices) return 0
        val fmt = gameData.audioData[audioId].format
        logAudio("play soundId=$soundId resolved=$resolvedSoundId name=${sound.name} file=${sound.fileName} audioId=$audioId fmt=$fmt loop=$loop")
        logEvent(
            "play",
            "soundId=$soundId resolved=$resolvedSoundId name=${sound.name} file=${sound.fileName} audioId=$audioId loop=$loop"
        )

        val buffer = getOrCreateBuffer(audioId) ?: return 0
        return playBuffer(resolvedSoundId, buffer, loop, baseVolume = sound.volume)
    }

    fun stopSound(soundId: Int) {
        if (!enabled) return
        // If the caller provided a specific handle, stop only that instance.
        val directSource = sourceHandles[soundId]
        if (directSource != null) {
            casterHandles.remove(soundId)
            logEvent("stop", "handle=$soundId direct=true")
            stopHandle(soundId)
            return
        }
        val resolvedSoundId = resolveSoundId(soundId) ?: return
        val sound = gameData.sounds.getOrNull(resolvedSoundId)
        logAudio("stop soundId=$soundId resolved=$resolvedSoundId name=${sound?.name ?: "?"}")
        logEvent(
            "stop",
            "soundId=$soundId resolved=$resolvedSoundId name=${sound?.name ?: "?"}"
        )
        val handles = soundToSources[resolvedSoundId]?.toList() ?: return
        for (handle in handles) {
            casterHandles.remove(handle)
            stopHandle(handle)
        }
        soundToSources.remove(resolvedSoundId)
    }

    fun stopAll() {
        if (!enabled) return
        logEvent("stop_all", "")
        val handles = sourceHandles.keys.toList()
        for (handle in handles) {
            casterHandles.remove(handle)
            stopHandle(handle)
        }
        soundToSources.clear()
    }

    fun isSoundPlaying(soundId: Int): Boolean {
        if (!enabled) return false
        update()
        // If the caller provided a specific handle, check only that instance.
        val directSource = sourceHandles[soundId]
        if (directSource != null) {
            return AL10.alGetSourcei(directSource, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING
        }
        val resolvedSoundId = resolveSoundId(soundId) ?: return false
        val sound = gameData.sounds.getOrNull(resolvedSoundId)
        logAudio("isPlaying soundId=$soundId resolved=$resolvedSoundId name=${sound?.name ?: "?"}")
        val handles = soundToSources[resolvedSoundId] ?: return false
        for (handle in handles) {
            val source = sourceHandles[handle] ?: continue
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                return true
            }
        }
        return false
    }

    fun setSoundGain(soundId: Int, gain: Float) {
        if (!enabled) return
        val directSource = sourceHandles[soundId]
        if (directSource != null) {
            AL10.alSourcef(directSource, AL10.AL_GAIN, gain.coerceIn(0.0f, 1.0f))
            return
        }
        val resolvedSoundId = resolveSoundId(soundId) ?: return
        soundGainOverrides[resolvedSoundId] = gain
        logEvent("set_gain", "id=$soundId resolved=$resolvedSoundId gain=$gain")
        updateSoundSources(resolvedSoundId)
    }

    fun setSoundPitch(soundId: Int, pitch: Float) {
        if (!enabled) return
        val directSource = sourceHandles[soundId]
        if (directSource != null) {
            AL10.alSourcef(directSource, AL10.AL_PITCH, pitch)
            return
        }
        val resolvedSoundId = resolveSoundId(soundId) ?: return
        soundPitchOverrides[resolvedSoundId] = pitch
        logEvent("set_pitch", "id=$soundId resolved=$resolvedSoundId pitch=$pitch")
        val handles = soundToSources[resolvedSoundId] ?: return
        for (handle in handles) {
            val source = sourceHandles[handle] ?: continue
            AL10.alSourcef(source, AL10.AL_PITCH, pitch)
        }
    }

    fun setMasterGain(gain: Float) {
        if (!enabled) return
        masterGain = gain
        for (soundId in soundToSources.keys) {
            updateSoundSources(soundId)
        }
    }

    fun getSoundGain(soundId: Int): Float {
        if (!enabled) return 0.0f
        val directSource = sourceHandles[soundId]
        if (directSource != null) {
            return AL10.alGetSourcef(directSource, AL10.AL_GAIN)
        }
        val resolvedSoundId = resolveSoundId(soundId) ?: return 0.0f
        val sound = gameData.sounds.getOrNull(resolvedSoundId)
        val baseVolume = sound?.volume ?: 1.0f
        val gain = soundGainOverrides[resolvedSoundId] ?: baseVolume
        return (gain * masterGain).coerceIn(0.0f, 1.0f)
    }

    fun getSoundPitch(soundId: Int): Float {
        if (!enabled) return 1.0f
        val directSource = sourceHandles[soundId]
        if (directSource != null) {
            return AL10.alGetSourcef(directSource, AL10.AL_PITCH)
        }
        val resolvedSoundId = resolveSoundId(soundId) ?: return 1.0f
        return soundPitchOverrides[resolvedSoundId] ?: 1.0f
    }

    fun pauseSound(soundId: Int) {
        if (!enabled) return
        logEvent("pause", "id=$soundId")
        val sources = resolveSources(soundId)
        for (source in sources) {
            AL10.alSourcePause(source)
        }
    }

    fun resumeSound(soundId: Int) {
        if (!enabled) return
        logEvent("resume", "id=$soundId")
        val sources = resolveSources(soundId)
        for (source in sources) {
            AL10.alSourcePlay(source)
        }
    }

    fun pauseAll() {
        if (!enabled) return
        logEvent("pause_all", "")
        for (source in sourceHandles.values) {
            AL10.alSourcePause(source)
        }
    }

    fun resumeAll() {
        if (!enabled) return
        logEvent("resume_all", "")
        for (source in sourceHandles.values) {
            AL10.alSourcePlay(source)
        }
    }

    fun playCaster(soundId: Int, loop: Boolean, volume: Float?): Int {
        if (!enabled) return 0
        // Caster is treated as a single music channel: stop previous caster audio before playing new.
        stopCaster(null)
        val handle = playSound(soundId, loop)
        if (handle != 0) {
            casterHandles.add(handle)
            if (volume != null) {
                setSoundGain(handle, volume)
            }
        }
        return handle
    }

    fun stopCaster(handleOrSound: Int?) {
        if (!enabled) return
        if (handleOrSound == null) {
            val handles = casterHandles.toList()
            for (h in handles) {
                stopSound(h)
            }
            casterHandles.clear()
            return
        }
        stopSound(handleOrSound)
        casterHandles.remove(handleOrSound)
    }

    fun getTrackPosition(soundId: Int): Float {
        if (!enabled) return 0.0f
        val sources = resolveSources(soundId)
        val source = sources.firstOrNull() ?: return 0.0f
        return AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET)
    }

    fun setTrackPosition(soundId: Int, positionSeconds: Float) {
        if (!enabled) return
        logEvent("set_track_pos", "id=$soundId pos=$positionSeconds")
        val sources = resolveSources(soundId)
        for (source in sources) {
            AL10.alSourcef(source, AL11.AL_SEC_OFFSET, positionSeconds)
        }
    }

    fun setPanning(soundId: Int, pan: Float) {
        if (!enabled) return
        logEvent("set_pan", "id=$soundId pan=$pan")
        val sources = resolveSources(soundId)
        for (source in sources) {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
            AL10.alSource3f(source, AL10.AL_POSITION, pan.coerceIn(-1.0f, 1.0f), 0.0f, 0.0f)
        }
    }

    private fun updateSoundSources(soundId: Int) {
        val sound = gameData.sounds.getOrNull(soundId)
        val handles = soundToSources[soundId] ?: return
        val baseVolume = sound?.volume ?: 1.0f
        val gain = computeGain(soundId, baseVolume)
        for (handle in handles) {
            val source = sourceHandles[handle] ?: continue
            AL10.alSourcef(source, AL10.AL_GAIN, gain)
        }
    }

    fun createStream(fileName: String): Int {
        if (!enabled) return 0
        val externalPath = resolveExternalAudioPath(fileName)
        if (externalPath != null) {
            val bufferId = decodeExternalFileToBuffer(externalPath) ?: return 0
            val handle = nextStreamId++
            externalStreamBuffers[handle] = bufferId
            logAudio("create_stream external file=$fileName path=$externalPath handle=$handle")
            logEvent("create_stream", "file=$fileName path=$externalPath handle=$handle external=true")
            return handle
        }
        val resolvedSoundId = resolveSoundIdByFile(fileName) ?: return 0
        val sound = gameData.sounds.getOrNull(resolvedSoundId)
        logAudio("create_stream file=$fileName resolved=$resolvedSoundId name=${sound?.name ?: "?"} audioId=${sound?.audioId}")
        logEvent(
            "create_stream",
            "file=$fileName resolved=$resolvedSoundId name=${sound?.name ?: "?"} audioId=${sound?.audioId}"
        )
        val handle = nextStreamId++
        streamToSound[handle] = resolvedSoundId
        return handle
    }

    fun destroyStream(handle: Int) {
        if (!enabled) return
        val externalBuffer = externalStreamBuffers.remove(handle)
        if (externalBuffer != null) {
            AL10.alDeleteBuffers(externalBuffer)
            soundGainOverrides.remove(handle)
            soundPitchOverrides.remove(handle)
            stopSound(handle)
            logAudio("destroy_stream external handle=$handle")
            logEvent("destroy_stream", "handle=$handle external=true")
            return
        }
        val soundId = streamToSound.remove(handle) ?: return
        val sound = gameData.sounds.getOrNull(soundId)
        logAudio("destroy_stream handle=$handle resolved=$soundId name=${sound?.name ?: "?"}")
        logEvent("destroy_stream", "handle=$handle resolved=$soundId name=${sound?.name ?: "?"}")
        stopSound(soundId)
    }

    private fun computeGain(soundId: Int, baseVolume: Float): Float {
        val gain = soundGainOverrides[soundId] ?: baseVolume
        return (gain * masterGain).coerceIn(0.0f, 1.0f)
    }

    private fun logAudio(message: String) {
        if (trace) {
            println("  [AUDIO] $message")
        }
        audioLogWriter?.println("[AUDIO] $message")
    }

    private fun logEvent(kind: String, details: String) {
        val extra = if (details.isBlank()) "" else " $details"
        logAudio("frame=$currentFrame kind=$kind$extra")
    }

    private fun buildBaseDirs(audioDirs: List<String>): List<Path> {
        val dirs = ArrayList<Path>()
        for (dir in audioDirs) {
            if (dir.isNotBlank()) {
                dirs.add(Paths.get(dir))
            }
        }
        dirs.add(Paths.get("."))
        dirs.add(Paths.get("undertale"))
        return dirs
    }

    private fun resolveSoundId(soundId: Int): Int? {
        if (externalStreamBuffers.containsKey(soundId)) {
            return soundId
        }
        if (soundId >= streamBase) {
            return streamToSound[soundId]
        }
        val fromSource = sourceToSound[soundId]
        if (fromSource != null) return fromSource
        return if (soundId in gameData.sounds.indices) soundId else null
    }

    private fun resolveSources(id: Int): List<Int> {
        val direct = sourceHandles[id]
        if (direct != null) return listOf(direct)
        val soundId = resolveSoundId(id) ?: return emptyList()
        val handles = soundToSources[soundId] ?: return emptyList()
        return handles.mapNotNull { sourceHandles[it] }
    }

    private fun playBuffer(soundKey: Int, buffer: Int, loop: Boolean, baseVolume: Float): Int {
        val source = AL10.alGenSources()
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer)
        AL10.alSourcei(source, AL10.AL_LOOPING, if (loop) AL10.AL_TRUE else AL10.AL_FALSE)

        val gain = computeGain(soundKey, baseVolume)
        val pitch = soundPitchOverrides[soundKey] ?: 1.0f
        AL10.alSourcef(source, AL10.AL_GAIN, gain)
        AL10.alSourcef(source, AL10.AL_PITCH, pitch)

        AL10.alSourcePlay(source)

        val handle = nextHandle++
        sourceHandles[handle] = source
        sourceToSound[handle] = soundKey
        soundToSources.getOrPut(soundKey) { mutableSetOf() }.add(handle)
        return handle
    }

    private fun resolveSoundIdByFile(name: String): Int? {
        val normalizedInput = name.trim().replace('\\', '/')
        val normalized = normalizedInput.substringAfterLast('/')
        val lower = normalized.lowercase()
        if (lower.isEmpty()) return null

        val direct = gameData.sounds.indexOfFirst { it.fileName.lowercase() == lower }
        if (direct >= 0) return direct

        val noExt = lower.substringBeforeLast('.', lower)
        val byNoExtFile = gameData.sounds.indexOfFirst {
            it.fileName.substringBeforeLast('.', it.fileName).lowercase() == noExt
        }
        if (byNoExtFile >= 0) return byNoExtFile

        val byName = gameData.sounds.indexOfFirst {
            val s = it.name.lowercase()
            s == lower || s == noExt
        }
        if (byName >= 0) return byName

        val tryOgg = "$noExt.ogg"
        val byOgg = gameData.sounds.indexOfFirst { it.fileName.lowercase() == tryOgg }
        if (byOgg >= 0) return byOgg

        val tryWav = "$noExt.wav"
        val byWav = gameData.sounds.indexOfFirst { it.fileName.lowercase() == tryWav }
        if (byWav >= 0) return byWav

        // Undertale often uses folder names like music/ or sfx/ with files like story.ogg
        val hintIsMusic = normalizedInput.contains("/music/") || normalizedInput.startsWith("music/")
        val hintIsSfx = normalizedInput.contains("/sfx/") || normalizedInput.startsWith("sfx/") ||
            normalizedInput.contains("/sound/") || normalizedInput.startsWith("sound/")

        val musName = "mus_$noExt"
        val sndName = "snd_$noExt"

        if (hintIsMusic) {
            val byMus = gameData.sounds.indexOfFirst {
                val fileLower = it.fileName.lowercase()
                val nameLower = it.name.lowercase()
                fileLower == "$musName.ogg" || fileLower == "$musName.wav" ||
                    nameLower == musName
            }
            if (byMus >= 0) return byMus
        }

        if (hintIsSfx) {
            val bySnd = gameData.sounds.indexOfFirst {
                val fileLower = it.fileName.lowercase()
                val nameLower = it.name.lowercase()
                fileLower == "$sndName.ogg" || fileLower == "$sndName.wav" ||
                    nameLower == sndName
            }
            if (bySnd >= 0) return bySnd
        }

        // Generic fallback: try mus_*/snd_* without hints
        val byMusFallback = gameData.sounds.indexOfFirst {
            val fileLower = it.fileName.lowercase()
            val nameLower = it.name.lowercase()
            fileLower == "$musName.ogg" || fileLower == "$musName.wav" ||
                nameLower == musName
        }
        if (byMusFallback >= 0) return byMusFallback

        val bySndFallback = gameData.sounds.indexOfFirst {
            val fileLower = it.fileName.lowercase()
            val nameLower = it.name.lowercase()
            fileLower == "$sndName.ogg" || fileLower == "$sndName.wav" ||
                nameLower == sndName
        }
        if (bySndFallback >= 0) return bySndFallback

        logAudio("create_stream not found for file=$name (normalized=$lower)")
        return null
    }

    private fun stopHandle(handle: Int) {
        val source = sourceHandles.remove(handle) ?: return
        AL10.alSourceStop(source)
        AL10.alDeleteSources(source)
        val soundId = sourceToSound.remove(handle)
        if (soundId != null) {
            val set = soundToSources[soundId]
            set?.remove(handle)
            if (set != null && set.isEmpty()) soundToSources.remove(soundId)
        }
    }

    private fun getOrCreateBuffer(audioId: Int): Int? {
        if (audioId !in gameData.audioData.indices) return null
        val existing = buffers[audioId]
        if (existing != 0) return existing

        val bufferId = AL10.alGenBuffers()
        val audio = gameData.audioData[audioId]
        val ok = when (audio.format) {
            AudioFormat.WAV -> decodeWav(audio, bufferId)
            AudioFormat.OGG -> decodeOgg(audio, bufferId)
            AudioFormat.UNKNOWN -> false
        }
        if (!ok) {
            AL10.alDeleteBuffers(bufferId)
            logAudio("failed to decode audioId=$audioId format=${audio.format}")
            return null
        }
        buffers[audioId] = bufferId
        return bufferId
    }

    private fun decodeWav(audio: AudioData, bufferId: Int): Boolean {
        val data = sliceAudioData(audio) ?: return false
        return decodeWavBuffer(data, bufferId)
    }

    private fun decodeOgg(audio: AudioData, bufferId: Int): Boolean {
        val data = sliceAudioData(audio) ?: return false
        return decodeOggBuffer(data, bufferId)
    }

    private fun decodeWavBuffer(data: ByteBuffer, bufferId: Int): Boolean {
        if (data.remaining() < 12) return false
        if (readTag(data, 0) != "RIFF" || readTag(data, 8) != "WAVE") return false

        var offset = 12
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= data.capacity()) {
            val id = readTag(data, offset)
            val size = readIntLE(data, offset + 4)
            val chunkData = offset + 8

            if (id == "fmt ") {
                val audioFormat = readShortLE(data, chunkData)
                channels = readShortLE(data, chunkData + 2)
                sampleRate = readIntLE(data, chunkData + 4)
                bits = readShortLE(data, chunkData + 14)
                if (audioFormat != 1) return false
            } else if (id == "data") {
                dataOffset = chunkData
                dataSize = size
                break
            }

            offset = chunkData + size
            if (size % 2 == 1) offset += 1
        }

        if (dataOffset < 0 || dataSize <= 0 || channels <= 0 || sampleRate <= 0 || bits != 16) {
            logAudio("WAV unsupported: channels=$channels rate=$sampleRate bits=$bits dataOffset=$dataOffset dataSize=$dataSize")
            return false
        }
        val format = when (channels) {
            1 -> AL10.AL_FORMAT_MONO16
            2 -> AL10.AL_FORMAT_STEREO16
            else -> return false
        }

        val pcm = data.duplicate()
        pcm.position(dataOffset)
        pcm.limit(dataOffset + dataSize)
        val slice = pcm.slice()
        AL10.alBufferData(bufferId, format, slice, sampleRate)
        return true
    }

    private fun decodeOggBuffer(data: ByteBuffer, bufferId: Int): Boolean {
        MemoryStack.stackPush().use { stack ->
            val channels = stack.mallocInt(1)
            val rate = stack.mallocInt(1)
            val pcm = STBVorbis.stb_vorbis_decode_memory(data, channels, rate) ?: return false
            val format = when (channels[0]) {
                1 -> AL10.AL_FORMAT_MONO16
                2 -> AL10.AL_FORMAT_STEREO16
                else -> {
                    MemoryUtil.memFree(pcm)
                    return false
                }
            }
            AL10.alBufferData(bufferId, format, pcm, rate[0])
            MemoryUtil.memFree(pcm)
        }
        return true
    }

    private fun sliceAudioData(audio: AudioData): ByteBuffer? {
        if (audio.length <= 0) return null
        val buf = gameData.fileBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val end = audio.dataOffset + audio.length
        if (audio.dataOffset < 0 || end > buf.capacity()) return null
        buf.position(audio.dataOffset)
        buf.limit(end)
        return buf.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun resolveExternalAudioPath(name: String): Path? {
        val normalizedInput = name.trim().replace('\\', '/')
        if (normalizedInput.isEmpty()) return null

        val candidates = ArrayList<Path>()
        for (base in baseDirs) {
            candidates.add(base.resolve(normalizedInput))
        }

        val baseFileName = normalizedInput.substringAfterLast('/')
        val commonDirs = listOf("music", "audio", "sound", "sounds", "sfx")
        for (dir in commonDirs) {
            for (base in baseDirs) {
                candidates.add(base.resolve(dir).resolve(baseFileName))
            }
        }

        val fileName = normalizedInput.substringAfterLast('/')
        val dir = normalizedInput.substringBeforeLast('/', "")
        val noExt = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")

        val prefixed = listOf("mus_$noExt.$ext", "snd_$noExt.$ext")
        for (p in prefixed) {
            val rel = if (dir.isEmpty()) p else "$dir/$p"
            for (base in baseDirs) {
                candidates.add(base.resolve(rel))
                for (cdir in commonDirs) {
                    candidates.add(base.resolve(cdir).resolve(p))
                }
            }
        }

        for (path in candidates) {
            if (Files.exists(path)) return path.normalize()
        }
        return null
    }

    private fun decodeExternalFileToBuffer(path: Path): Int? {
        return try {
            val bytes = Files.readAllBytes(path)
            val data = ByteBuffer.allocateDirect(bytes.size)
            data.put(bytes)
            data.flip()
            val bufferId = AL10.alGenBuffers()
            val tag = readTag(data, 0)
            val ok = when (tag) {
                "RIFF" -> decodeWavBuffer(data, bufferId)
                "OggS" -> decodeOggBuffer(data, bufferId)
                else -> false
            }
            if (!ok) {
                AL10.alDeleteBuffers(bufferId)
                logAudio("external decode failed path=$path tag=$tag")
                null
            } else {
                bufferId
            }
        } catch (e: Exception) {
            logAudio("external decode error path=$path err=${e.message}")
            null
        }
    }

    private fun readTag(buf: ByteBuffer, offset: Int): String {
        if (offset + 4 > buf.capacity()) return ""
        val b0 = buf.get(offset).toInt().toChar()
        val b1 = buf.get(offset + 1).toInt().toChar()
        val b2 = buf.get(offset + 2).toInt().toChar()
        val b3 = buf.get(offset + 3).toInt().toChar()
        return "" + b0 + b1 + b2 + b3
    }

    private fun readIntLE(buf: ByteBuffer, offset: Int): Int {
        return (buf.get(offset).toInt() and 0xFF) or
            ((buf.get(offset + 1).toInt() and 0xFF) shl 8) or
            ((buf.get(offset + 2).toInt() and 0xFF) shl 16) or
            ((buf.get(offset + 3).toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(buf: ByteBuffer, offset: Int): Int {
        return (buf.get(offset).toInt() and 0xFF) or
            ((buf.get(offset + 1).toInt() and 0xFF) shl 8)
    }

    private fun initializeOpenAL(): Boolean {
        device = ALC10.alcOpenDevice(null as ByteBuffer?)
        if (device == MemoryUtil.NULL) {
            println("Audio disabled: failed to open OpenAL device")
            return false
        }
        val alcCaps = ALC.createCapabilities(device)
        context = ALC10.alcCreateContext(device, null as IntBuffer?)
        if (context == MemoryUtil.NULL) {
            println("Audio disabled: failed to create OpenAL context")
            ALC10.alcCloseDevice(device)
            device = MemoryUtil.NULL
            return false
        }
        ALC10.alcMakeContextCurrent(context)
        AL.createCapabilities(alcCaps)
        return true
    }
}
