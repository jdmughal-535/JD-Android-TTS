package com.jdmughal.androidtts

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
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
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
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
    private lateinit var btnPaste: ImageButton
    private lateinit var spinnerVoice: Spinner
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var btnSpeak: Button
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvModelSub: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar

    @Volatile private var audioTrack: AudioTrack? = null
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
        btnPaste = findViewById(R.id.btnPaste)
        spinnerVoice = findViewById(R.id.spinnerVoice)
        seekSpeed = findViewById(R.id.seekSpeed)
        tvSpeed = findViewById(R.id.tvSpeed)
        btnSpeak = findViewById(R.id.btnSpeak)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvModelSub = findViewById(R.id.tvModelSub)
        tvProgress = findViewById(R.id.tvProgress)
        progressBar = findViewById(R.id.progressBar)

        // Keep the default text showing from the top, not scrolled to the end.
        etText.setSelection(0)

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

        btnPaste.setOnClickListener { pasteFromClipboard() }
        btnSpeak.setOnClickListener { speak(saveToFile = false) }
        btnSave.setOnClickListener { speak(saveToFile = true) }

        refreshState()
        // No download button: the model fetches itself on first launch.
        if (!isModelReady()) downloadModel()
    }

    /** Paste the clipboard text at the cursor (or over the selection). */
    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "Nothing to paste", Toast.LENGTH_SHORT).show()
            return
        }
        val start = etText.selectionStart.coerceAtLeast(0)
        val end = etText.selectionEnd.coerceAtLeast(start)
        etText.text.replace(start, end, text)
        Toast.makeText(this, "Pasted", Toast.LENGTH_SHORT).show()
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
        tvModelStatus.text =
            if (ready) "Model ready" else "Model not downloaded"
        tvModelSub.text = if (ready) {
            "Supertonic 3 · English · ${voices[spinnerVoice.selectedItemPosition].first}"
        } else {
            "Download once (~123 MB). After that, speech works fully offline."
        }
        tvStatus.text = if (ready) {
            "Ready. Pick a voice and tap Play."
        } else {
            "Model not downloaded yet."
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        runOnUiThread { refreshState() }
    }

    /** Show the determinate progress bar with a detail line. */
    private fun showProgress(percent: Int, detail: String) {
        runOnUiThread {
            progressBar.isIndeterminate = false
            progressBar.visibility = View.VISIBLE
            progressBar.progress = percent.coerceIn(0, 100)
            tvProgress.visibility = View.VISIBLE
            tvProgress.text = detail
        }
    }

    private fun hideProgress() {
        runOnUiThread {
            progressBar.visibility = View.GONE
            tvProgress.visibility = View.GONE
        }
    }

    /** Indeterminate spinner state, used while generating speech. */
    private fun showGenerating() {
        runOnUiThread {
            progressBar.isIndeterminate = true
            progressBar.visibility = View.VISIBLE
            tvProgress.visibility = View.VISIBLE
            tvProgress.text = "Generating speech..."
        }
    }

    private fun formatMb(bytes: Long): String =
        "%.1f MB".format(bytes / 1_000_000.0)

    private fun formatSpeed(bytesPerSec: Double): String = when {
        bytesPerSec >= 1_000_000 -> "%.1f MB/s".format(bytesPerSec / 1_000_000.0)
        bytesPerSec >= 1_000 -> "%.0f KB/s".format(bytesPerSec / 1_000.0)
        else -> "%.0f B/s".format(bytesPerSec)
    }

    private fun formatEta(secondsLeft: Long): String = when {
        secondsLeft < 60 -> "$secondsLeft sec left"
        secondsLeft < 3600 -> "${secondsLeft / 60} min ${secondsLeft % 60} sec left"
        else -> "${secondsLeft / 3600} hr ${(secondsLeft % 3600) / 60} min left"
    }

    /** Download + extract the Supertonic model tarball on a worker thread,
     *  reporting live progress: % + MB + speed + ETA while downloading,
     *  and % + current file while extracting. */
    private fun downloadModel() {
        if (busy) return
        setBusy(true)
        tvModelStatus.text = "Downloading model..."
        Thread {
            try {
                val modelsRoot = getExternalFilesDir("models")!!
                modelsRoot.mkdirs()
                val tmpFile = File(modelsRoot, "supertonic-3.tar.bz2")
                downloadWithProgress(tmpFile)
                runOnUiThread { tvModelStatus.text = "Extracting model..." }
                extractTarBz2(tmpFile, modelsRoot)
                tmpFile.delete()
                if (!isModelReady()) throw IllegalStateException("Extracted model is incomplete")
                runOnUiThread {
                    hideProgress()
                    setBusy(false)
                    Toast.makeText(this, "Model ready", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideProgress()
                    setBusy(false)
                    tvStatus.text = "Download failed: ${e.message}"
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun downloadWithProgress(destFile: File) {
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("Download failed: HTTP ${conn.responseCode}")
        }
        val total = conn.contentLengthLong // -1 if the server hides it
        var downloaded: Long = 0
        val startedAt = System.currentTimeMillis()
        var lastUiUpdate = 0L
        conn.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buf = ByteArray(8192)
                var n = input.read(buf)
                while (n != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= 300) {
                        lastUiUpdate = now
                        val elapsedSec = ((now - startedAt) / 1000.0).coerceAtLeast(0.5)
                        val speed = downloaded / elapsedSec
                        val detail = if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            val etaSec = ((total - downloaded) / speed).toLong()
                            showProgress(percent, "Downloading... $percent% · " +
                                "${formatMb(downloaded)} / ${formatMb(total)} · " +
                                "${formatSpeed(speed)} · ${formatEta(etaSec)}")
                            "Downloading... $percent%"
                        } else {
                            showProgress(0, "Downloading... ${formatMb(downloaded)} · ${formatSpeed(speed)}")
                            "Downloading..."
                        }
                        runOnUiThread { tvModelStatus.text = detail }
                    }
                    n = input.read(buf)
                }
            }
        }
    }

    /** Counts bytes read so extraction can show a real %. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0
            private set

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun read(): Int {
            val n = super.read()
            if (n >= 0) count += 1
            return n
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        val archiveSize = archive.length().coerceAtLeast(1)
        val counting = CountingInputStream(BufferedInputStream(archive.inputStream()))
        var lastUiUpdate = 0L
        fun report(currentFile: String) {
            val now = System.currentTimeMillis()
            if (now - lastUiUpdate < 300) return
            lastUiUpdate = now
            val percent = (counting.count * 100 / archiveSize).toInt()
            showProgress(percent, "Extracting... $percent% · $currentFile")
        }
        BZip2CompressorInputStream(counting).use { bzIn ->
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
                        FileOutputStream(outFile).use { out ->
                            val buf = ByteArray(65536)
                            var n = tarIn.read(buf)
                            while (n != -1) {
                                out.write(buf, 0, n)
                                report(File(entry.name).name)
                                n = tarIn.read(buf)
                            }
                        }
                    }
                    report(File(entry.name).name)
                    entry = tarIn.nextTarEntry
                }
            }
        }
        showProgress(100, "Extracting... 100% · done")
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
        showGenerating()
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
                    // sherpa saves to a file path, so write a temp file first,
                    // then move it into the public Downloads folder.
                    val fileName = "jd-tts-${System.currentTimeMillis()}.wav"
                    val tmpFile = File(cacheDir, fileName)
                    audio.save(tmpFile.absolutePath)
                    copyToDownloads(tmpFile, fileName)
                    tmpFile.delete()
                    runOnUiThread {
                        hideProgress()
                        setBusy(false)
                        tvStatus.text = "Saved to Downloads · $fileName"
                        Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread { tvStatus.text = "Playing..." }
                    playSamples(audio.samples, audio.sampleRate)
                    runOnUiThread {
                        hideProgress()
                        setBusy(false)
                        tvStatus.text = "Done."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideProgress()
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
        while (offset < pcm.size) {
            val chunk = minOf(4096, pcm.size - offset)
            track.write(pcm, offset, chunk)
            offset += chunk
        }
        track.stop()
        track.release()
        audioTrack = null
    }

    /** Put a finished WAV into the public Downloads folder so the user
     *  can find it in Files / Downloads instead of buried app storage. */
    private fun copyToDownloads(file: File, displayName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
            ) ?: throw IllegalStateException("Could not create file in Downloads")
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Could not open Downloads file")
        } else {
            // Android 9 and older: write the public folder directly.
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS,
            )
            downloadsDir.mkdirs()
            file.copyTo(File(downloadsDir, displayName), overwrite = true)
        }
    }

    override fun onDestroy() {
        audioTrack?.stop()
        tts?.release()
        tts = null
        super.onDestroy()
    }
}
