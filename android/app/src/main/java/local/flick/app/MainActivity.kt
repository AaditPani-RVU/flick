package local.flick.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import kotlin.math.max
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("flick", Context.MODE_PRIVATE) }

    private lateinit var detector: FlickDetector
    private lateinit var discovery: Discovery

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var card: FrameLayout
    private lateinit var thumbnail: ImageView
    private lateinit var cardText: TextView
    private lateinit var caption: TextView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressBar

    private var mac: Mac? = null
    private var items: List<Item> = emptyList()
    private var text: String? = null
    private var sending = false
    private var resumed = false

    private val previewLoader = Executors.newSingleThreadExecutor()
    private var preview: Bitmap? = null
    private lateinit var label: TextView
    private lateinit var glow: GradientDrawable
    private lateinit var stackBack: View
    private lateinit var stackMiddle: View
    private var glowPulse: ValueAnimator? = null
    private var textLabel = "Text"
    /** The clipboard text already offered or sent, so it is not loaded twice. */
    private var lastClipboard: String? = null

    private val hasPayload: Boolean get() = items.isNotEmpty() || text != null

    // region Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        detector = FlickDetector(this) { power -> runOnUiThread { flick(power) } }
        detector.threshold = prefs.getFloat(KEY_THRESHOLD, 5.0f)
        discovery = Discovery(this) { found -> useMac(found, remember = true) }

        restoreMac()
        handleIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        detector.start()
        discovery.start()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        detector.stop()
        discovery.stop()
        setArmed(false)
    }

    /** Android only lets the focused app read the clipboard, so this is the moment to look. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sending) return
        if (!hasPayload) {
            val copied = readClipboard()
            if (!copied.isNullOrBlank() && copied != lastClipboard) {
                lastClipboard = copied
                loadText(copied, label = "From your clipboard")
            }
        }
        render()
    }

    private fun readClipboard(): String? = runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }.getOrNull()

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        previewLoader.shutdownNow()
    }

    // endregion
    // region Choosing what to send

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (stream != null) load(listOf(stream)) else if (!shared.isNullOrBlank()) loadText(shared)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { load(it) }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) data.data?.let(uris::add)
        if (uris.isNotEmpty()) load(uris)
        render()
    }

    private fun load(uris: List<Uri>) {
        text = null
        items = uris.map { describe(it) }
        preview = null
        val first = items.firstOrNull() ?: return
        // Decoding can take a moment for large photos and videos, so do it off the main thread.
        previewLoader.execute {
            val bitmap = decodePreview(first)
            runOnUiThread {
                if (items.firstOrNull()?.uri == first.uri) {
                    preview = bitmap
                    render()
                }
            }
        }
    }

    private fun loadText(value: String, label: String = "Text") {
        items = emptyList()
        preview = null
        text = value
        textLabel = label
    }

    /** The system thumbnail is fastest, but it is missing for some items, so fall back to decoding. */
    private fun decodePreview(item: Item): Bitmap? {
        runCatching { contentResolver.loadThumbnail(item.uri, Size(900, 900), null) }.getOrNull()?.let { return it }
        if (item.mime.startsWith("video/")) {
            return runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, item.uri)
                    retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 900, 900)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, item.uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / 900))
            }
        }.getOrNull()
    }

    private fun describe(uri: Uri): Item {
        var name: String? = null
        var size = -1L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0) name = cursor.getString(nameColumn)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
        }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        return Item(uri, name ?: uri.lastPathSegment ?: "Flicked file", size, mime)
    }

    private fun pickPhotos() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
            .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
        runCatching { startActivityForResult(intent, 1) }
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        runCatching { startActivityForResult(intent, 2) }
    }

    private fun pasteClipboard() {
        val pasted = readClipboard()
        if (pasted.isNullOrBlank()) {
            say("NOTHING ON THE CLIPBOARD")
        } else {
            lastClipboard = pasted
            loadText(pasted, label = "From your clipboard")
            render()
        }
    }

    // endregion
    // region Finding the Mac

    private fun restoreMac() {
        val host = prefs.getString(KEY_HOST, null) ?: return
        val saved = Mac(prefs.getString(KEY_NAME, "Mac") ?: "Mac", host, prefs.getInt(KEY_PORT, Transport.DEFAULT_PORT))
        // Use the remembered Mac only once it actually answers; its address may have changed.
        worker.execute {
            val name = Transport.hello(saved)
            if (name != null) runOnUiThread { if (mac == null) useMac(saved.copy(name = name), remember = false) }
        }
    }

    private fun useMac(found: Mac, remember: Boolean) {
        mac = found
        if (remember) {
            prefs.edit().putString(KEY_HOST, found.host).putInt(KEY_PORT, found.port)
                .putString(KEY_NAME, found.name).apply()
        }
        render()
    }

    private fun token(mac: Mac): String? = prefs.getString("token:${mac.name}", null)

    private fun showSettings() {
        val options = arrayOf("Enter Mac address…", "Flick sensitivity…")
        AlertDialog.Builder(this)
            .setTitle(mac?.let { "${it.name} · ${it.host}:${it.port}" } ?: "No Mac found yet")
            .setItems(options) { _, which -> if (which == 0) askAddress() else askSensitivity() }
            .show()
    }

    private fun askAddress() {
        val field = EditText(this).apply {
            hint = "192.168.1.20:${Transport.DEFAULT_PORT}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(mac?.let { "${it.host}:${it.port}" } ?: "")
        }
        val holder = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        AlertDialog.Builder(this)
            .setTitle("Mac address")
            .setMessage("Shown in the Flick menu on your Mac, next to “Listening at”.")
            .setView(holder)
            .setPositiveButton("Connect") { _, _ ->
                val typed = field.text.toString().trim()
                val host = typed.substringBeforeLast(':', typed).ifEmpty { return@setPositiveButton }
                val port = typed.substringAfterLast(':', "").toIntOrNull() ?: Transport.DEFAULT_PORT
                val candidate = Mac("Mac", host, port)
                say("Looking for $host…")
                worker.execute {
                    val name = Transport.hello(candidate)
                    runOnUiThread {
                        if (name != null) useMac(candidate.copy(name = name), remember = true)
                        else say("No Flick Receiver answered at $host:$port")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askSensitivity() {
        val labels = arrayOf("Gentle flick", "Normal", "Firm flick")
        val values = floatArrayOf(3.5f, 5.0f, 7.0f)
        val current = values.indexOfFirst { it == detector.threshold }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Flick sensitivity")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                detector.threshold = values[which]
                prefs.edit().putFloat(KEY_THRESHOLD, values[which]).apply()
                dialog.dismiss()
            }
            .show()
    }

    // endregion
    // region Sending

    private fun flick(power: Float) {
        if (!resumed || sending || !hasPayload) return
        val target = mac
        if (target == null) {
            shareWithQuickShare()
            return
        }
        sending = true
        card.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        card.animate()
            .translationY(-root.height.toFloat())
            .rotation(if (Math.random() < 0.5) -14f else 14f)
            .setDuration(260)
            .start()
        progress.visibility = View.VISIBLE
        progress.progress = 0

        val batch = items
        val message = text
        val deviceName = Settings.Global.getString(contentResolver, "device_name") ?: android.os.Build.MODEL

        worker.execute {
            try {
                var key = token(target)
                var attempt = 0
                while (true) {
                    if (key == null) {
                        say("Click Allow on ${target.name}")
                        key = Transport.pair(target, deviceName)
                        prefs.edit().putString("token:${target.name}", key).apply()
                    }
                    try {
                        if (message != null) {
                            Transport.sendText(target, key, message, power)
                        } else {
                            batch.forEachIndexed { index, item ->
                                say(if (batch.size > 1) "Sending ${index + 1} of ${batch.size}…" else "Sending…")
                                Transport.sendFile(this, target, key, item, index, batch.size, power) { fraction ->
                                    val overall = ((index + fraction) / batch.size * 1000).toInt()
                                    runOnUiThread { progress.progress = overall }
                                }
                            }
                        }
                        break
                    } catch (e: NotPairedException) {
                        // The Mac forgot this phone; pair again once, then give up.
                        prefs.edit().remove("token:${target.name}").apply()
                        key = null
                        if (++attempt > 1) throw e
                    }
                }
                runOnUiThread { finishSend(null, target) }
            } catch (e: Exception) {
                runOnUiThread { finishSend(e, target) }
            }
        }
    }

    private fun finishSend(error: Exception?, target: Mac) {
        sending = false
        progress.visibility = View.GONE
        if (error == null) {
            text?.let { lastClipboard = it }
            preview = null
            items = emptyList()
            text = null
            card.translationY = 0f
            card.rotation = REST_TILT
            render()
            card.alpha = 0f
            card.animate().alpha(0.28f).setDuration(350).start()
            status.text = "LANDED ON ${target.name.uppercase()}"
        } else {
            card.performHapticFeedback(HapticFeedbackConstants.REJECT)
            card.animate().translationY(0f).rotation(REST_TILT).setDuration(320).start()
            render()
            status.text = when (error) {
                is DeniedException -> error.message
                is java.io.IOException -> "Couldn’t reach ${target.name}. Same Wi‑Fi? Is Flick Receiver running?"
                else -> error.message ?: "Send failed"
            }
        }
    }

    /** Without a Flick Receiver on the network, hand the files to the system share sheet (Quick Share). */
    private fun shareWithQuickShare() {
        val message = text
        val send = when {
            message != null -> Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
            items.size == 1 -> Intent(Intent.ACTION_SEND).setType(items[0].mime).putExtra(Intent.EXTRA_STREAM, items[0].uri)
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        }
        if (items.isNotEmpty()) {
            val clip = ClipData.newRawUri("", items[0].uri)
            items.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
            send.clipData = clip
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        card.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        runCatching { startActivity(Intent.createChooser(send, "No Flick Receiver found — send with Quick Share")) }
    }

    // endregion
    // region UI

    private fun say(message: String) = runOnUiThread { status.text = message }

    private fun render() {
        val target = mac
        status.text = if (target != null) "↑  ${target.name.uppercase()}" else "LOOKING FOR YOUR MAC…"

        val first = items.firstOrNull()
        val message = text
        thumbnail.setImageDrawable(null)
        cardText.visibility = View.GONE
        cardText.gravity = Gravity.CENTER_VERTICAL
        when {
            message != null -> {
                cardText.visibility = View.VISIBLE
                cardText.text = message
                caption.text = textLabel
            }
            first != null -> {
                val picture = preview
                if (picture != null) {
                    thumbnail.setImageBitmap(picture)
                } else {
                    cardText.visibility = View.VISIBLE
                    cardText.gravity = Gravity.CENTER
                    cardText.text = first.name.substringAfterLast('.', "file").uppercase()
                }
                caption.text = if (items.size > 1) "${items.size} items" else first.name
            }
            else -> caption.text = "Nothing loaded"
        }

        shapeCard(if (first != null) preview else null)
        label.text = when {
            first == null || preview == null -> ""
            items.size > 1 -> "${items.size} items"
            else -> first.name
        }

        card.alpha = if (hasPayload) 1f else 0.28f
        stackMiddle.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        stackBack.visibility = if (items.size > 2) View.VISIBLE else View.GONE
        setArmed(hasPayload && target != null && resumed)
        hint.text = when {
            !hasPayload -> "Copy something, pick below,\nor share to Flick from any app"
            target == null -> "Flick to send with Quick Share"
            message != null && detector.available -> "Flick it onto the Mac’s clipboard"
            detector.available -> "Flick your wrist toward the Mac"
            else -> "Swipe the card up to send"
        }
        if (hasPayload) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun buildUi() {
        // Warm light spills in from the top edge: that is where the Mac is.
        glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(Color.parseColor("#B3FF5A2A"), Color.parseColor("#33FF5A2A"), Color.TRANSPARENT)
            setGradientCenter(0.5f, 0f)
            gradientRadius = dp(460).toFloat()
            alpha = 70
        }
        root = FrameLayout(this).apply {
            background = LayerDrawable(arrayOf(ColorDrawable(INK), glow))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            column.setPadding(dp(20), bars.top + dp(12), dp(20), bars.bottom + dp(16))
            insets
        }

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#E6FFF6E9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.14f
            isAllCaps = true
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { showSettings() }
        }
        column.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val stage = FrameLayout(this)
        column.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Two more prints peek out from behind when several items are loaded.
        stackBack = paperSheet(alpha = 0.32f, tilt = -11f)
        stackMiddle = paperSheet(alpha = 0.58f, tilt = 5f)
        stage.addView(stackBack, FrameLayout.LayoutParams(dp(CARD_W), dp(CARD_H), Gravity.CENTER))
        stage.addView(stackMiddle, FrameLayout.LayoutParams(dp(CARD_W), dp(CARD_H), Gravity.CENTER))

        card = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(PAPER)
                cornerRadius = dp(10).toFloat()
            }
            elevation = dp(22).toFloat()
            outlineAmbientShadowColor = Color.parseColor("#FF5A2A")
            outlineSpotShadowColor = Color.parseColor("#7A1E00")
            rotation = REST_TILT
            clipToOutline = true
        }
        stage.addView(card, FrameLayout.LayoutParams(dp(CARD_W), dp(CARD_H), Gravity.CENTER))

        fun artParams() = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            setMargins(dp(12), dp(12), dp(12), dp(60))
        }

        thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1E7D8"))
                cornerRadius = dp(5).toFloat()
            }
            clipToOutline = true
        }
        card.addView(thumbnail, artParams())

        cardText = TextView(this).apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.SERIF
            setLineSpacing(dp(3).toFloat(), 1f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 9
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        card.addView(cardText, artParams())

        caption = TextView(this).apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        card.addView(caption, FrameLayout.LayoutParams(MATCH_PARENT, dp(60), Gravity.BOTTOM))

        card.setOnTouchListener(SwipeUp())

        label = TextView(this).apply {
            setTextColor(Color.parseColor("#99FFF6E9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            isAllCaps = true
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(32), dp(4), dp(32), dp(4))
        }
        column.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(EMBER)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#33FFF6E9"))
        }
        column.addView(progress, LinearLayout.LayoutParams(dp(220), WRAP_CONTENT))

        hint = TextView(this).apply {
            setTextColor(Color.parseColor("#B3FFF6E9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(10), dp(24), dp(22))
        }
        column.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill("Photos") { pickPhotos() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        buttons.addView(pill("Files") { pickFiles() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(10) })
        buttons.addView(pill("Paste") { pasteClipboard() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(10) })
        column.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    private fun pill(label: String, action: () -> Unit) = Button(this).apply {
        text = label.uppercase()
        isAllCaps = false
        setTextColor(PAPER)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.14f
        stateListAnimator = null
        val shape = GradientDrawable().apply {
            setColor(Color.parseColor("#14FFF6E9"))
            setStroke(dp(1), Color.parseColor("#40FFF6E9"))
            cornerRadius = dp(26).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#55FF5A2A")), shape, shape)
        setOnClickListener { action() }
    }

    /**
     * A photo is shown bare, as the card itself, in its own proportions.
     * Files and text sit on a paper card with a caption strip.
     */
    private fun shapeCard(picture: Bitmap?) {
        val bare = picture != null
        var width = dp(CARD_W)
        var height = dp(CARD_H)
        if (picture != null) {
            val maxWidth = dp(292).toFloat()
            val maxHeight = dp(400).toFloat()
            val ratio = (picture.width.toFloat() / picture.height.coerceAtLeast(1)).coerceIn(0.6f, 1.6f)
            if (ratio >= maxWidth / maxHeight) {
                width = maxWidth.toInt()
                height = (maxWidth / ratio).toInt()
            } else {
                height = maxHeight.toInt()
                width = (maxHeight * ratio).toInt()
            }
        }
        val radius = dp(if (bare) 24 else 10).toFloat()
        for (sheet in listOf(card, stackMiddle, stackBack)) {
            val params = sheet.layoutParams
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                sheet.layoutParams = params
            }
            (sheet.background as GradientDrawable).cornerRadius = radius
        }
        (card.background as GradientDrawable).setColor(if (bare) INK else PAPER)
        (thumbnail.background as GradientDrawable).cornerRadius = if (bare) 0f else dp(5).toFloat()
        val art = thumbnail.layoutParams as FrameLayout.LayoutParams
        val inset = if (bare) 0 else dp(12)
        val bottom = if (bare) 0 else dp(60)
        if (art.leftMargin != inset || art.bottomMargin != bottom) {
            art.setMargins(inset, inset, inset, bottom)
            thumbnail.layoutParams = art
        }
        caption.visibility = if (bare) View.GONE else View.VISIBLE
    }

    private fun paperSheet(alpha: Float, tilt: Float) = View(this).apply {
        background = GradientDrawable().apply {
            setColor(PAPER)
            cornerRadius = dp(10).toFloat()
        }
        this.alpha = alpha
        rotation = tilt
        visibility = View.GONE
    }

    /** The light at the top breathes while something is loaded and a Mac is listening. */
    private fun setArmed(armed: Boolean) {
        if (armed == (glowPulse != null)) return
        glowPulse?.cancel()
        glowPulse = null
        if (armed) {
            glowPulse = ValueAnimator.ofInt(130, 255).apply {
                duration = 1700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { glow.alpha = it.animatedValue as Int }
                start()
            }
        } else {
            glow.alpha = 70
        }
    }

    /** Dragging the card upward sends it, for when a wrist flick is awkward. */
    private inner class SwipeUp : View.OnTouchListener {
        private var startY = 0f
        private var startedAt = 0L

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (sending || !hasPayload) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startedAt = event.eventTime
                }
                MotionEvent.ACTION_MOVE -> view.translationY = (event.rawY - startY).coerceAtMost(0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val travelled = startY - event.rawY
                    val seconds = ((event.eventTime - startedAt).coerceAtLeast(1)) / 1000f
                    val speed = travelled / seconds
                    if (event.actionMasked == MotionEvent.ACTION_UP && (travelled > dp(110) || speed > dp(900))) {
                        flick((speed / dp(3000)).coerceIn(0.3f, 1f))
                    } else {
                        view.animate().translationY(0f).setDuration(200).start()
                    }
                }
            }
            return true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // endregion

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_NAME = "name"
        const val KEY_THRESHOLD = "threshold"

        const val CARD_W = 264
        const val CARD_H = 340
        const val REST_TILT = -3f
        val INK = Color.parseColor("#100D0B")
        val PAPER = Color.parseColor("#FFF6E9")
        val EMBER = Color.parseColor("#FF5A2A")
    }
}
