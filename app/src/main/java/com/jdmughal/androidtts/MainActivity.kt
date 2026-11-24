package com.jdmughal.androidtts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * JD Android TTS — offline text-to-speech on sherpa-onnx.
 *
 * Engine: Supertonic 3 (int8), 31 languages, 10 speakers, 24 kHz.
 * Default voice: M5 (sid 9) — warm, storytelling male.
 * The model (~123 MB) is downloaded once from the sherpa-onnx
 * tts-models release and extracted to external files; after that
 * everything runs offline, no API key, no account.
 *
 * Model files (all required):
 *   duration_predictor.int8.onnx, text_encoder.int8.onnx,
 *   vector_estimator.int8.onnx, vocoder.int8.onnx,
 *   tts.json, unicode_indexer.bin, voice.bin
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
        private const val MODEL_DIR_NAME = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        private const val DEFAULT_SID = 9 // M5 — warm, storytelling male
    }

    // (label, sid) — Supertonic voice order is F1-F5 then M1-M5.
    private val voices = listOf(
        "F1 — Calm, composed female" to 0,
        "F2 — Bright, cheerful female" to 1,
        "F3 — Professional announcer female" to 2,
        "F4 — Crisp, confident female" to 3,
        "F5 — Gentle, soothing female" to 4,
        "M1 — Lively, upbeat male" to 5,
        "M2 — Deep, calm male" to 6,
        "M3 — Authoritative male" to 7,
        "M4 — Soft, friendly male" to 8,
        "M5 — Warm, storytelling male (default)" to 9,
    )

    private lateinit var etText: EditText
    private lateinit var spinnerVoice: Spinner
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnSpeak: Button
    private lateinit var btnSave: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var stopRequested = false
    @Volatile private var busy = false

    private var tts: OfflineTts? = null

    private fun modelDir(): File =
        File(getExternalFilesDir("models"), MODEL_DIR_NAME)

    private fun isModelReady(): Boolean =
        File(modelDir(), "tts.json").exists() &&
            File(modelDir(), "voice.bin").exists() &&
            File(modelDir(), "vocoder.int8.onnx").exists()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etText = findViewById(R.id.etText)
        spinnerVoice = findViewById(R.id.spinnerVoice)
        seekSpeed = findViewById(R.id.seekSpeed)
        tvSpeed = findViewById(R.id.tvSpeed)
        btnDownload = findViewById(R.id.btnDownload)
        btnSpeak = findViewById(R.id.btnSpeak)
        btnSave = findViewById(R.id.btnSave)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            voices.map { it.first },
        )
        spinnerVoice.adapter = adapter
        spinnerVoice.setSelection(DEFAULT_SID)

        seekSpeed.max = 20
        seekSpeed.min = 5
        seekSpeed.progress = 10
        updateSpeedLabel()
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateSpeedLabel()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        btnDownload.setOnClickListener { downloadModel() }
        btnSpeak.setOnClickListener { speak(saveToFile = false) }
        btnSave.setOnClickListener { speak(saveToFile = true) }
        btnStop.setOnClickListener { stopPlayback() }

        refreshState()
    }

    private fun updateSpeedLabel() {
        tvSpeed.text = "Speed: %.1fx".format(seekSpeed.progress / 10f)
    }

    private fun speed(): Float = seekSpeed.progress / 10f

    private fun selectedSid(): Int =
        voices[spinnerVoice.selectedItemPosition].second

    private fun refreshState() {
        val ready = isModelReady()
        btnSpeak.isEnabled = ready && !busy
        btnSave.isEnabled = ready && !busy
        btnDownload.isEnabled = !busy
        btnDownload.text =
            if (ready) "Model downloaded — tap to re-download" else "Download model (~123 MB)"
        tvStatus.text = if (ready) {
            "Model ready. Voice: ${voices[spinnerVoice.selectedItemPosition].first}"
        } else {
            "Model not downloaded yet. Tap 'Download model' once (needs internet). " +
                "After that, speech works fully offline."
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        runOnUiThread {
            progressBar.visibility = if (b) View.VISIBLE else View.GONE
            refreshState()
        }
    }

    /** Download + extract the Supertonic model tarball on a worker thread. */
    private fun downloadModel() {
        if (busy) return
        setBusy(true)
        tvStatus.text = "Downloading model (~123 MB)..."
        Thread {
            try {
                val modelsRoot = getExternalFilesDir("models")!!
                modelsRoot.mkdirs()
                val tmpFile = File(modelsRoot, "supertonic-3.tar.bz2")
                URL(MODEL_URL).openStream().use { input ->
                    FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
                }
                runOnUiThread { tvStatus.text = "Extracting model..." }
                extractTarBz2(tmpFile, modelsRoot)
                tmpFile.delete()
                if (!isModelReady()) throw IllegalStateException("Extracted model is incomplete")
                runOnUiThread {
                    setBusy(false)
                    Toast.makeText(this, "Model ready", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    tvStatus.text = "Download failed: ${e.message}"
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bzIn ->
            TarArchiveInputStream(bzIn).use { tarIn ->
                var entry = tarIn.nextTarEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    // Guard against path traversal inside the archive.
                    require(outFile.canonicalPath.startsWith(destDir.canonicalPath))
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> tarIn.copyTo(out) }
                    }
                    entry = tarIn.nextTarEntry
                }
            }
        }
    }

    private fun getOrCreateTts(): OfflineTts {
        tts?.let { return it }
        val dir = modelDir().absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = "$dir/duration_predictor.int8.onnx",
                    textEncoder = "$dir/text_encoder.int8.onnx",
                    vectorEstimator = "$dir/vector_estimator.int8.onnx",
                    vocoder = "$dir/vocoder.int8.onnx",
                    ttsJson = "$dir/tts.json",
                    unicodeIndexer = "$dir/unicode_indexer.bin",
                    voiceStyle = "$dir/voice.bin",
                ),
                numThreads = 2,
                debug = false,
            ),
        )
        return OfflineTts(config = config).also { tts = it }
    }

    /** Generate speech for the text box and either play it or save it as WAV. */
    private fun speak(saveToFile: Boolean) {
        if (busy) return
        val text = etText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Type some text first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isModelReady()) {
            Toast.makeText(this, "Download the model first", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        stopRequested = false
        tvStatus.text = "Generating speech..."
        Thread {
            try {
                val engine = getOrCreateTts()
                val genConfig = GenerationConfig(
                    sid = selectedSid(),
                    numSteps = 8,
                    speed = speed(),
                    extra = mapOf("lang" to "en"),
                )
                // generateWithConfig is the documented Kotlin path for
                // GenerationConfig (sid / numSteps / speed / lang).
                val audio = engine.generateWithConfig(text, genConfig)
                if (saveToFile) {
                    val outFile = File(
                        getExternalFilesDir(null),
                        "jd-tts-${System.currentTimeMillis()}.wav",
                    )
                    audio.save(outFile.absolutePath)
                    runOnUiThread {
                        setBusy(false)
                        tvStatus.text = "Saved: ${outFile.absolutePath}"
                        Toast.makeText(this, "Saved WAV", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread { tvStatus.text = "Playing..." }
                    playSamples(audio.samples, audio.sampleRate)
                    runOnUiThread {
                        setBusy(false)
                        tvStatus.text = "Done."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    tvStatus.text = "Failed: ${e.message}"
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun playSamples(samples: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(4096),
            )
            .build()
        audioTrack = track
        track.play()
        var offset = 0
        while (offset < pcm.size && !stopRequested) {
            val chunk = minOf(4096, pcm.size - offset)
            track.write(pcm, offset, chunk)
            offset += chunk
        }
        track.stop()
        track.release()
        audioTrack = null
    }

    private fun stopPlayback() {
        stopRequested = true
        audioTrack?.stop()
    }

    override fun onDestroy() {
        stopPlayback()
        tts?.release()
        tts = null
        super.onDestroy()
    }
}
