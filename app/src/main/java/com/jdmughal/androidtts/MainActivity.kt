package com.jdmughal.androidtts

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsAudio
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
 * The model (~129 MB) is downloaded once from the sherpa-onnx
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
    private lateinit var btnClear: ImageButton
    private lateinit var btnDownload: Button
    private lateinit var spinnerVoice: Spinner
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var btnSpeak: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvModelSub: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var playerCard: LinearLayout
    private lateinit var btnPlayPause: ImageButton
    private lateinit var seekPlay: SeekBar
    private lateinit var tvPlayTime: TextView
    private lateinit var btnDownloadAudio: ImageButton

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var busy = false
    private var downloadFailed = false

    // Last generated audio, so Play never regenerates the same
    // text + voice + speed twice. Cleared when any of them changes.
    private var cachedAudio: OfflineTtsAudio? = null
    private var cachedKey: String? = null

    // Voice-sample preview in the dropdown (bundled assets, plays
    // without the model downloaded).
    private var samplePlayer: MediaPlayer? = null
    private var samplePlayingSid = -1

    // Mini-player state.
    private var playing = false
    private var paused = false
    private var playToken = 0
    private var playBaseOffset = 0 // samples already played before this track started
    private var playTotal = 0 // total samples of the current audio
    private var playSampleRate = 24_000
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (!playing || paused) return
            val pos = currentPosition()
            seekPlay.progress = pos
            tvPlayTime.text = "${formatTime(pos)} / ${formatTime(playTotal)}"
            if (pos >= playTotal) {
                onPlaybackFinished()
            } else {
                progressHandler.postDelayed(this, 200)
            }
        }
    }

    private var tts: OfflineTts? = null

    /** Dropdown rows: the voice name, plus a play icon that previews
     *  that voice's sample (bundled asset — no model download needed).
     *  Tapping the icon only plays the sample; tapping the row text
     *  selects the voice. */
    private inner class VoiceAdapter(context: Context) :
        ArrayAdapter<Pair<String, Int>>(context, R.layout.spinner_item, voices) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.spinner_item, parent, false)
            (view as TextView).text = getItem(position)?.first
            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.spinner_dropdown_item, parent, false)
            val item = getItem(position)!!
            view.findViewById<TextView>(R.id.tvVoiceName).text = item.first
            val btnSample = view.findViewById<ImageButton>(R.id.btnSample)
            val isPlaying = samplePlayingSid == item.second
            btnSample.setImageResource(
                if (isPlaying) R.drawable.ic_stop else R.drawable.ic_play,
            )
            btnSample.contentDescription =
                if (isPlaying) "Stop sample" else "Play sample"
            btnSample.setOnClickListener { playVoiceSample(item.second) }
            return view
        }
    }

    private fun sampleFileFor(sid: Int): String {
        val names = listOf("f1", "f2", "f3", "f4", "f5", "m1", "m2", "m3", "m4", "m5")
        return "voice_samples/${names[sid]}.mp3"
    }

    /** Play one voice's bundled sample. Tapping the same voice again
     *  stops it; tapping another voice switches to that one. */
    private fun playVoiceSample(sid: Int) {
        if (samplePlayingSid == sid) {
            stopSample()
            return
        }
        stopSample()
        try {
            val afd = assets.openFd(sampleFileFor(sid))
            val player = MediaPlayer()
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            player.setOnCompletionListener { stopSample() }
            player.prepare()
            player.start()
            samplePlayer = player
            samplePlayingSid = sid
            refreshVoiceAdapter()
        } catch (e: Exception) {
            Toast.makeText(this, "Sample not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSample() {
        samplePlayer?.let { player ->
            try {
                player.stop()
            } catch (e: Exception) {
            }
            try {
                player.release()
            } catch (e: Exception) {
            }
        }
        samplePlayer = null
        if (samplePlayingSid >= 0) {
            samplePlayingSid = -1
            refreshVoiceAdapter()
        }
    }

    private fun refreshVoiceAdapter() {
        (spinnerVoice.adapter as? VoiceAdapter)?.notifyDataSetChanged()
    }

    private fun modelDir(): File =
        File(getExternalFilesDir("models"), MODEL_DIR_NAME)

    private fun isModelReady(): Boolean =
        File(modelDir(), "tts.json").exists() &&
            File(modelDir(), "voice.bin").exists() &&
            File(modelDir(), "vocoder.int8.onnx").exists()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 16 enforces edge-to-edge: keep the header below the
        // status bar and the footer above the gesture bar.
        val rootScroll = findViewById<ScrollView>(R.id.rootScroll)
        ViewCompat.setOnApplyWindowInsetsListener(rootScroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        etText = findViewById(R.id.etText)
        btnPaste = findViewById(R.id.btnPaste)
        btnClear = findViewById(R.id.btnClear)
        btnDownload = findViewById(R.id.btnDownload)
        spinnerVoice = findViewById(R.id.spinnerVoice)
        seekSpeed = findViewById(R.id.seekSpeed)
        tvSpeed = findViewById(R.id.tvSpeed)
        btnSpeak = findViewById(R.id.btnSpeak)
        tvStatus = findViewById(R.id.tvStatus)
        playerCard = findViewById(R.id.playerCard)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        seekPlay = findViewById(R.id.seekPlay)
        tvPlayTime = findViewById(R.id.tvPlayTime)
        btnDownloadAudio = findViewById(R.id.btnDownloadAudio)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvModelSub = findViewById(R.id.tvModelSub)
        tvProgress = findViewById(R.id.tvProgress)
        progressBar = findViewById(R.id.progressBar)

        // Keep the default text showing from the top, not scrolled to the end.
        etText.setSelection(0)

        // Custom adapter so the dropdown matches the Claude theme
        // (white popup, ink text, orange-tinted selection) instead of
        // the default pink-tinted Material dropdown — and so every
        // voice row has a play icon for its sample.
        spinnerVoice.adapter = VoiceAdapter(this)
        spinnerVoice.setSelection(DEFAULT_SID)

        seekSpeed.max = 20
        seekSpeed.min = 5
        seekSpeed.progress = 10
        updateSpeedLabel()
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateSpeedLabel()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) = invalidateCache()
        })

        btnPaste.setOnClickListener { pasteFromClipboard() }
        btnClear.setOnClickListener { etText.text.clear() }
        btnDownload.setOnClickListener { downloadModel() }
        btnSpeak.setOnClickListener { onPlayClicked() }
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnDownloadAudio.setOnClickListener { saveCachedAudio() }
        seekPlay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) tvPlayTime.text = "${formatTime(p)} / ${formatTime(playTotal)}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                val audio = cachedAudio ?: return
                startPlayback(audio, s?.progress ?: 0)
            }
        })

        // Any change to text, voice or speed throws the cache away —
        // the next Play generates fresh audio for the new input.
        etText.doAfterTextChanged { invalidateCache() }
        var spinnerReady = false
        spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!spinnerReady) {
                    spinnerReady = true
                    return
                }
                stopSample()
                invalidateCache()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        refreshState()
        // No auto-download: the user starts the model download manually
        // from the button in the model card.
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
        // The download button lives only in the model card and only
        // while the model is missing — never next to Play.
        btnDownload.visibility = if (ready) View.GONE else View.VISIBLE
        btnDownload.isEnabled = !busy
        btnDownload.text =
            if (downloadFailed) "Retry download" else "Download model"
        tvModelStatus.text =
            if (ready) "Model ready" else "Model not downloaded"
        tvModelSub.text = if (ready) {
            "Supertonic 3 · English · ${voices[spinnerVoice.selectedItemPosition].first}"
        } else {
            "Download once (~129 MB). After that, speech works fully offline."
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
     *  and a clean "Finalizing... %" while extracting. */
    private fun downloadModel() {
        if (busy) return
        downloadFailed = false
        setBusy(true)
        tvModelStatus.text = "Downloading model..."
        Thread {
            try {
                val modelsRoot = getExternalFilesDir("models")!!
                modelsRoot.mkdirs()
                val tmpFile = File(modelsRoot, "supertonic-3.tar.bz2")
                downloadWithProgress(tmpFile)
                runOnUiThread { tvModelStatus.text = "Finalizing model..." }
                extractTarBz2(tmpFile, modelsRoot)
                tmpFile.delete()
                if (!isModelReady()) throw IllegalStateException("Extracted model is incomplete")
                runOnUiThread {
                    hideProgress()
                    setBusy(false)
                    Toast.makeText(this, "Model ready", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // Keep the partial file: a retry resumes from where
                // the download stopped instead of starting over.
                downloadFailed = true
                runOnUiThread {
                    hideProgress()
                    setBusy(false)
                    tvStatus.text = "Download failed: ${e.message}"
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Downloads the model with resume: if a partial file is already
     *  there, the server is asked for only the missing bytes (HTTP
     *  Range), so a retry on a slow connection picks up where it
     *  stopped instead of re-downloading ~129 MB from zero. */
    private fun downloadWithProgress(destFile: File) {
        val already = if (destFile.exists()) destFile.length() else 0L
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        if (already > 0) conn.setRequestProperty("Range", "bytes=$already-")
        conn.connect()
        val code = conn.responseCode
        if (code == 416) return // already complete on disk
        if (code !in 200..299) {
            throw IllegalStateException("Download failed: HTTP $code")
        }
        val resumed = code == HttpURLConnection.HTTP_PARTIAL // 206
        if (!resumed && already > 0) destFile.delete() // server ignored Range: start fresh
        val total = if (resumed) already + conn.contentLengthLong else conn.contentLengthLong
        var downloaded = if (resumed) already else 0L
        val sessionStart = downloaded
        val startedAt = System.currentTimeMillis()
        var lastUiUpdate = 0L
        val verb = if (resumed) "Resuming" else "Downloading"
        conn.inputStream.use { input ->
            FileOutputStream(destFile, resumed).use { output ->
                val buf = ByteArray(8192)
                var n = input.read(buf)
                while (n != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= 300) {
                        lastUiUpdate = now
                        val elapsedSec = ((now - startedAt) / 1000.0).coerceAtLeast(0.5)
                        val speed = (downloaded - sessionStart) / elapsedSec
                        val detail = if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            val etaSec = if (speed > 0) ((total - downloaded) / speed).toLong() else -1
                            val eta = if (etaSec >= 0) " · ${formatEta(etaSec)}" else ""
                            showProgress(percent, "$verb... $percent% · " +
                                "${formatMb(downloaded)} / ${formatMb(total)} · " +
                                "${formatSpeed(speed)}$eta")
                            "$verb... $percent%"
                        } else {
                            showProgress(0, "$verb... ${formatMb(downloaded)} · ${formatSpeed(speed)}")
                            "$verb..."
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
        // The user never sees internal file names: just a clean
        // "Finalizing..." with the percent.
        fun report() {
            val now = System.currentTimeMillis()
            if (now - lastUiUpdate < 300) return
            lastUiUpdate = now
            val percent = (counting.count * 100 / archiveSize).toInt()
            showProgress(percent, "Finalizing... $percent%")
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
                                report()
                                n = tarIn.read(buf)
                            }
                        }
                    }
                    report()
                    entry = tarIn.nextTarEntry
                }
            }
        }
        showProgress(100, "Finalizing... 100%")
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

    private fun cacheKey(text: String): String =
        "$text|${selectedSid()}|${speed()}"

    /** The cache dies the moment the input changes, so Play never
     *  plays stale audio for new text, a new voice or a new speed. */
    private fun invalidateCache() {
        if (cachedAudio == null && cachedKey == null) return
        cachedAudio = null
        cachedKey = null
        stopTrack()
        playerCard.visibility = View.GONE
        btnDownloadAudio.visibility = View.GONE
    }

    /** Play: generate once, then replay from the cache — no
     *  regeneration while the text, voice and speed stay the same. */
    private fun onPlayClicked() {
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
        val key = cacheKey(text)
        val cached = cachedAudio
        if (cached != null && cachedKey == key) {
            playerCard.visibility = View.VISIBLE
            btnDownloadAudio.visibility = View.VISIBLE
            startPlayback(cached, 0)
            tvStatus.text = "Playing from cache..."
            return
        }
        setBusy(true)
        playerCard.visibility = View.VISIBLE
        btnPlayPause.isEnabled = false
        seekPlay.isEnabled = false
        btnDownloadAudio.visibility = View.GONE
        tvPlayTime.text = "Generating..."
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
                runOnUiThread {
                    // The input may have changed while generating:
                    // only keep audio that still matches the screen.
                    if (key != cacheKey(etText.text.toString().trim())) {
                        setBusy(false)
                        tvStatus.text = "Text changed — tap Play again."
                        return@runOnUiThread
                    }
                    cachedAudio = audio
                    cachedKey = key
                    setBusy(false)
                    btnPlayPause.isEnabled = true
                    seekPlay.isEnabled = true
                    btnDownloadAudio.visibility = View.VISIBLE
                    startPlayback(audio, 0)
                    tvStatus.text = "Playing..."
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

    /** Save the cached audio to Downloads — no regeneration. */
    private fun saveCachedAudio() {
        val audio = cachedAudio ?: return
        if (busy) return
        setBusy(true)
        Thread {
            try {
                // sherpa saves to a file path, so write a temp file first,
                // then move it into the public Downloads folder.
                val fileName = "jd-tts-${System.currentTimeMillis()}.wav"
                val tmpFile = File(cacheDir, fileName)
                audio.save(tmpFile.absolutePath)
                copyToDownloads(tmpFile, fileName)
                tmpFile.delete()
                runOnUiThread {
                    setBusy(false)
                    tvStatus.text = "Saved to Downloads · $fileName"
                    Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    tvStatus.text = "Save failed: ${e.message}"
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun formatTime(samples: Int): String {
        val totalSec = samples / playSampleRate.coerceAtLeast(1)
        return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
    }

    private fun currentPosition(): Int {
        val track = audioTrack ?: return playBaseOffset
        val head = try {
            track.playbackHeadPosition
        } catch (e: Exception) {
            0
        }
        return (playBaseOffset + head).coerceIn(0, playTotal)
    }

    private fun updatePlayPauseIcon() {
        val playingNow = playing && !paused
        btnPlayPause.setImageResource(
            if (playingNow) R.drawable.ic_pause else R.drawable.ic_play,
        )
        btnPlayPause.contentDescription = if (playingNow) "Pause" else "Play"
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        return AudioTrack.Builder()
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
    }

    /** Stream the cached audio from the given sample offset, like a
     *  media player: the progress bar and time keep moving, and the
     *  pause button and seek bar actually work. */
    private fun startPlayback(audio: OfflineTtsAudio, startOffset: Int) {
        stopSample()
        stopTrack()
        val token = playToken
        val samples = audio.samples
        playSampleRate = audio.sampleRate
        playTotal = samples.size
        playBaseOffset = startOffset.coerceIn(0, playTotal)
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val track = buildTrack(playSampleRate)
        audioTrack = track
        playing = true
        paused = false
        btnPlayPause.isEnabled = true
        seekPlay.isEnabled = true
        updatePlayPauseIcon()
        seekPlay.max = playTotal
        seekPlay.progress = playBaseOffset
        tvPlayTime.text = "${formatTime(playBaseOffset)} / ${formatTime(playTotal)}"
        track.play()
        progressHandler.post(progressRunnable)
        Thread {
            try {
                var offset = playBaseOffset
                while (offset < pcm.size && token == playToken) {
                    val chunk = minOf(4096, pcm.size - offset)
                    track.write(pcm, offset, chunk)
                    offset += chunk
                }
            } catch (e: Exception) {
                // Old writer thread after a stop/seek: ignore it.
            }
        }.start()
    }

    private fun togglePlayPause() {
        if (!playing) {
            // Finished (or never started): replay the cached audio
            // from the start, or from where the seek bar sits.
            val audio = cachedAudio ?: return
            val from = seekPlay.progress.coerceIn(0, playTotal)
            startPlayback(audio, if (from >= playTotal) 0 else from)
            return
        }
        val track = audioTrack ?: return
        if (paused) {
            track.play()
            paused = false
            progressHandler.post(progressRunnable)
        } else {
            track.pause()
            paused = true
            progressHandler.removeCallbacks(progressRunnable)
            seekPlay.progress = currentPosition()
            tvPlayTime.text = "${formatTime(seekPlay.progress)} / ${formatTime(playTotal)}"
        }
        updatePlayPauseIcon()
    }

    private fun onPlaybackFinished() {
        playing = false
        paused = false
        progressHandler.removeCallbacks(progressRunnable)
        seekPlay.progress = playTotal
        tvPlayTime.text = "${formatTime(playTotal)} / ${formatTime(playTotal)}"
        updatePlayPauseIcon()
        tvStatus.text = "Done."
    }

    /** Stop the current track and drop any writer thread still running. */
    private fun stopTrack() {
        playToken++
        progressHandler.removeCallbacks(progressRunnable)
        audioTrack?.let { track ->
            try {
                track.stop()
            } catch (e: Exception) {
            }
            try {
                track.release()
            } catch (e: Exception) {
            }
        }
        audioTrack = null
        playing = false
        paused = false
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
        stopSample()
        stopTrack()
        tts?.release()
        tts = null
        super.onDestroy()
    }
}
