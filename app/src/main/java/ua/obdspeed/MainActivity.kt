package ua.obdspeed

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.content.Context
import android.widget.ScrollView
import java.util.Locale
import kotlin.math.sqrt
import kotlin.math.abs
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ProgressBar
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity(), SensorEventListener, LocationListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var gyroscope: Sensor? = null
    private var navigationRunning = false
    private var navigationPaused = true
    private var navLat: Double? = null
    private var navLon: Double? = null
    private var navHeading = 0.0
    private var headingReady = false
    private var lastGyroNs = 0L
    private var gyroBiasZ = 0.0
    private var calibrationCount = 0
    private var calibrationSum = 0.0
    private var calibrationActive = false
    private var lastNavSpeedMs = 0L
    private lateinit var navStatus: TextView
    private lateinit var navCoordinates: TextView
    private lateinit var navButton: Button
    private var accelerometer: Sensor? = null
    private lateinit var acceleration: TextView
    private lateinit var accelerationTotal: TextView
    private lateinit var gForce: TextView
    private lateinit var gBar: ProgressBar
    private lateinit var distanceView: TextView
    private val gravity = FloatArray(3)
    private var gravityInitialized = false
    private var lastSensorNs = 0L
    private var distanceKm = 0.0
    private var previousSpeed: Int? = null
    private var previousSpeedTimeMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var worker: Thread? = null
    private lateinit var spinner: Spinner
    private lateinit var speedDial: GolfSpeedometerView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var button: Button
    private var devices: List<BluetoothDevice> = emptyList()
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val cyan = Color.rgb(56, 189, 248)
    private val gray = Color.rgb(170, 184, 200)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        distanceKm = savedInstanceState?.getDouble("distanceKm") ?: 0.0
        navLat = if (savedInstanceState?.containsKey("navLat") == true) savedInstanceState.getDouble("navLat") else null
        navLon = if (savedInstanceState?.containsKey("navLon") == true) savedInstanceState.getDouble("navLon") else null
        navHeading = savedInstanceState?.getDouble("navHeading") ?: 0.0
        headingReady = savedInstanceState?.getBoolean("headingReady") ?: false
        // Restored navigation always starts paused; phone may have moved during recreation.
        navigationRunning = savedInstanceState?.getBoolean("navigationRunning") ?: false
        navigationPaused = true
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val scroll = ScrollView(this).apply { fillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 48)
            setBackgroundColor(Color.rgb(16, 24, 32))
        }
        fun label(t: String, size: Float, color: Int = Color.WHITE): TextView = TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
        }
        root.addView(label("OBD SPEED", 26f, cyan).apply { typeface = Typeface.DEFAULT_BOLD })
        root.addView(label("Bluetooth Classic · ELM327", 14f, gray))
        root.addView(label("Спарений адаптер", 16f).apply { setPadding(0, 28, 0, 6) })
        spinner = Spinner(this)
        root.addView(spinner)
        val buttons = LinearLayout(this)
        val refresh = Button(this).apply { text = "Оновити"; setOnClickListener { ensurePermission() } }
        button = Button(this).apply { text = "Підключити"; setOnClickListener { if (active.get()) stop() else connectSelected() } }
        buttons.addView(refresh, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        root.addView(label("ШВИДКІСТЬ АВТОМОБІЛЯ", 15f, gray).apply {
            gravity = Gravity.CENTER; setPadding(0, 26, 0, 0)
        })
        speedDial = GolfSpeedometerView(this)
        root.addView(speedDial, LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels * 0.90f).toInt().coerceAtMost(950)))
        root.addView(label("ПРОЙДЕНА ВІДСТАНЬ (ЗА ПОЇЗДКУ)", 15f, gray).apply {
            gravity = Gravity.CENTER; setPadding(0, 24, 0, 4)
        })
        distanceView = label("0,000 км", 30f).apply { gravity = Gravity.CENTER }
        root.addView(distanceView)
        updateDistanceView()
        root.addView(Button(this).apply {
            text = "Скинути відстань"
            setOnClickListener {
                synchronized(this@MainActivity) {
                    distanceKm = 0.0
                    previousSpeed = null
                    previousSpeedTimeMs = 0L
                }
                updateDistanceView()
            }
        })
        status = label("Не підключено", 17f, gray).apply { setPadding(0, 30, 0, 8) }
        root.addView(status)
        detail = label("Спочатку спаруйте ELM327 у налаштуваннях Android", 14f, gray)
        root.addView(detail)
        root.addView(label("НАВІГАЦІЯ БЕЗ GPS · ЕКСПЕРИМЕНТ", 17f, cyan).apply {
            typeface = Typeface.DEFAULT_BOLD; setPadding(0, 30, 0, 10)
        })
        navStatus = label("Очікування початкової GPS-позиції", 16f, gray)
        navCoordinates = label("Координати: —", 15f, gray)
        root.addView(navStatus)
        root.addView(navCoordinates)
        navButton = Button(this).apply { text = "Почати навігацію"; setOnClickListener { toggleNavigation() } }
        root.addView(navButton)
        if (navigationRunning) {
            navButton.text = "Продовжити навігацію"
            navStatus.text = "ПАУЗА · після відновлення екрана"
            updateNavText()
        }
        root.addView(label("Перед зняттям телефона натисніть «Пауза». Повернувши його у той самий тримач, натисніть «Продовжити» та дочекайтеся калібрування на нерухомому авто. Переміщення під час паузи не враховується.", 13f, gray))
        root.addView(label("АКСЕЛЕРОМЕТР ТЕЛЕФОНА", 17f, cyan).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 36, 0, 12)
        })
        acceleration = label("X: —    Y: —    Z: —", 20f)
        root.addView(acceleration)
        accelerationTotal = label("Загальне прискорення: —", 17f, gray).apply {
            setPadding(0, 10, 0, 0)
        }
        root.addView(accelerationTotal)
        gForce = label("Лінійне прискорення: — g", 20f, cyan).apply { setPadding(0, 18, 0, 6) }
        root.addView(gForce)
        gBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 200 // 0–2 g, 100 кроків на 1 g
            progress = 0
        }
        root.addView(gBar)
        root.addView(label("Шкала 0–2 g · без гравітації (оцінка)", 13f, gray))
        root.addView(label("м/с² · включно з прискоренням вільного падіння", 13f, gray).apply {
            setPadding(0, 8, 0, 0)
        })
        if (accelerometer == null) {
            acceleration.text = "Акселерометр на пристрої відсутній"
            accelerationTotal.text = ""
            gForce.text = "Датчик недоступний"
            gBar.isEnabled = false
        }
        scroll.addView(root)
        setContentView(scroll)
        ensurePermission()
        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        startLocationUpdates()
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
        if (navigationRunning && !navigationPaused) pauseNavigation()
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val z = event.values[2].toDouble()
            if (calibrationActive) {
                calibrationSum += z
                calibrationCount++
                if (calibrationCount >= 100) {
                    gyroBiasZ = calibrationSum / calibrationCount
                    calibrationActive = false
                    lastGyroNs = event.timestamp
                    navStatus.text = "Навігація активна · оцінка положення"
                }
            } else if (navigationRunning && !navigationPaused && lastGyroNs > 0L) {
                val dt = (event.timestamp - lastGyroNs) / 1e9
                if (dt > 0 && dt < 0.25) navHeading = (navHeading + Math.toDegrees(z - gyroBiasZ) * dt + 360.0) % 360.0
            }
            lastGyroNs = event.timestamp
            return
        }
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val total = sqrt(x * x + y * y + z * z)
        acceleration.text = String.format(Locale("uk", "UA"), "X: %.2f    Y: %.2f    Z: %.2f", x, y, z)
        accelerationTotal.text = String.format(Locale("uk", "UA"), "Загальне прискорення: %.2f м/с²", total)
        // Low-pass gravity estimate; subtract it for approximate motion acceleration.
        if (!gravityInitialized || lastSensorNs == 0L ||
            event.timestamp - lastSensorNs > 1_000_000_000L) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            gravityInitialized = true
        } else {
            val dt = ((event.timestamp - lastSensorNs).coerceAtLeast(0L) / 1_000_000_000f)
            val alpha = (2.0 / (2.0 + dt)).toFloat()
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        }
        lastSensorNs = event.timestamp
        val lx = x - gravity[0]; val ly = y - gravity[1]; val lz = z - gravity[2]
        val linearG = sqrt(lx * lx + ly * ly + lz * lz) / SensorManager.GRAVITY_EARTH
        gForce.text = String.format(Locale("uk", "UA"), "Лінійне прискорення: %.2f g", linearG)
        gBar.progress = (linearG * 100f).toInt().coerceIn(0, 200)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun permitted(): Boolean = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermission() {
        if (!permitted()) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 11)
        else loadDevices()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 11 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadDevices()
        else if (code == 11) showStatus("Надайте дозвіл Bluetooth")
        if (code == 12 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startLocationUpdates()
    }

    @Suppress("MissingPermission")
    private fun loadDevices() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) { showStatus("Увімкніть Bluetooth"); return }
            devices = adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
            val names = devices.map { "${it.name ?: "Пристрій"} (${it.address})" }
                .ifEmpty { listOf("Спарених пристроїв немає") }
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        } catch (_: SecurityException) { showStatus("Немає дозволу Bluetooth") }
    }

    private fun connectSelected() {
        if (!permitted()) { ensurePermission(); return }
        val device = devices.getOrNull(spinner.selectedItemPosition)
        if (device == null) { showStatus("Спочатку спаруйте ELM327"); return }
        active.set(true)
        button.text = "Відключити"
        spinner.isEnabled = false
        worker = thread(name = "ELM327-worker") { runConnection(device) }
    }

    @Suppress("MissingPermission")
    private fun runConnection(device: BluetoothDevice) {
        var delayMs = 1000L
        while (active.get()) {
            var local: BluetoothSocket? = null
            try {
                showStatus("Підключення до ELM327…")
                local = device.createRfcommSocketToServiceRecord(uuid)
                socket = local
                local.connect()
                delayMs = 1000L
                val input = local.inputStream
                val output = local.outputStream
                showStatus("Ініціалізація OBD…")
                command(input, output, "ATZ", 4000)
                for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
                    command(input, output, cmd, 1800)
                }
                val probe = command(input, output, "0100", 8000)
                if (!probe.uppercase().replace(" ", "").contains("4100")) {
                    throw IOException("ECU не відповів на 0100")
                }
                showStatus("З'єднано · читаємо швидкість")
                var misses = 0
                while (active.get()) {
                    val reply = command(input, output, "010D", 2200)
                    val value = parseSpeed(reply)
                    if (value != null) {
                        misses = 0
                        handler.post {
                            if (active.get()) {
                                val now = SystemClock.elapsedRealtime()
                                recordSpeed(value, now)
                                advanceNavigation(value, now)
                                speedDial.setSpeed(value)
                                detail.text = "PID 010D · Дані з ECU"
                            }
                        }
                    } else {
                        misses++
                        handler.post {
                            invalidateSpeedSample()
                            speedDial.setSpeed(null); detail.text = "Немає актуальних даних швидкості"
                        }
                        if (misses >= 5) throw IOException("Немає коректних відповідей PID 010D")
                    }
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                if (active.get()) {
                    showStatus("Зв'язок втрачено · перепідключення…")
                    handler.post {
                        invalidateSpeedSample()
                        speedDial.setSpeed(null); detail.text = e.message ?: "Помилка адаптера"
                    }
                }
            } finally {
                try { local?.close() } catch (_: Exception) {}
                if (socket === local) socket = null
            }
            if (active.get()) {
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
                delayMs = (delayMs * 2).coerceAtMost(8000L)
            }
        }
        handler.post {
            invalidateSpeedSample()
            button.text = "Підключити"; spinner.isEnabled = true
            speedDial.setSpeed(null); showStatus("Відключено")
        }
    }

    // Require ELM prompt to keep request/response framing aligned.
    private fun command(input: InputStream, output: OutputStream, cmd: String, timeoutMs: Long): String {
        output.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
        val result = StringBuilder()
        val start = System.nanoTime()
        while (active.get() && (System.nanoTime() - start) / 1_000_000 < timeoutMs) {
            while (input.available() > 0) {
                val byte = input.read()
                if (byte < 0) throw IOException("Bluetooth потік закритий")
                if (byte == '>'.code) return result.toString()
                result.append(byte.toChar())
                if (result.length > 2048) throw IOException("Надто довга відповідь ELM327")
            }
            Thread.sleep(10)
        }
        throw IOException("Тайм-аут $cmd; повторне підключення")
    }

    private fun parseSpeed(raw: String): Int? {
        val cleaned = raw.uppercase().replace(" ", "").replace("\r", "").replace("\n", "")
        if (listOf("NODATA", "ERROR", "STOPPED", "UNABLE").any { cleaned.contains(it) }) return null
        val match = Regex("410D([0-9A-F]{2})").find(cleaned) ?: return null
        return match.groupValues[1].toIntOrNull(16)
    }

    private fun recordSpeed(kmh: Int, nowMs: Long) {
        // Trapezoidal integration; do not count disconnected / stale intervals.
        val prev = previousSpeed
        val dt = nowMs - previousSpeedTimeMs
        if (prev != null && dt in 1..5000) {
            distanceKm += (prev + kmh) / 2.0 * (dt / 3_600_000.0)
        }
        previousSpeed = kmh
        previousSpeedTimeMs = nowMs
        updateDistanceView()
    }

    private fun invalidateSpeedSample() {
        previousSpeed = null
        previousSpeedTimeMs = 0L
    }

    private fun updateDistanceView() {
        distanceView.text = String.format(Locale("uk", "UA"), "%.3f км", distanceKm)
    }

    private fun toggleNavigation() {
        if (!navigationRunning) {
            if (navLat == null || navLon == null || !headingReady) {
                navStatus.text = "Потрібна GPS-позиція та напрямок руху перед втратою GPS"
                ensureLocationPermission()
                return
            }
            if (gyroscope == null) { navStatus.text = "Гіроскоп недоступний"; return }
            navigationRunning = true
            resumeNavigation()
        } else if (!navigationPaused) {
            pauseNavigation()
        } else {
            resumeNavigation()
        }
    }

    private fun pauseNavigation() {
        navigationPaused = true
        calibrationActive = false
        lastNavSpeedMs = 0L
        lastGyroNs = 0L
        navButton.text = "Продовжити навігацію"
        navStatus.text = "ПАУЗА · позицію заморожено"
    }

    private fun resumeNavigation() {
        navigationPaused = false
        lastNavSpeedMs = 0L // never integrate elapsed pause time
        lastGyroNs = 0L
        calibrationSum = 0.0
        calibrationCount = 0
        calibrationActive = true
        navButton.text = "Пауза навігації"
        navStatus.text = "Калібрування гіроскопа · автомобіль має стояти"
    }

    private fun advanceNavigation(kmh: Int, nowMs: Long) {
        val previous = lastNavSpeedMs
        lastNavSpeedMs = nowMs
        if (!navigationRunning || navigationPaused || calibrationActive || !headingReady) return
        val lat = navLat ?: return
        val lon = navLon ?: return
        val dt = nowMs - previous
        if (previous == 0L || dt !in 1..3000) return
        val metres = kmh / 3.6 * dt / 1000.0
        val radians = Math.toRadians(navHeading)
        val latNext = lat + Math.cos(radians) * metres / 111_320.0
        val lonScale = 111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val lonNext = lon + Math.sin(radians) * metres / lonScale
        navLat = latNext
        navLon = lonNext
        updateNavText()
    }

    private fun updateNavText() {
        val lat = navLat ?: return
        val lon = navLon ?: return
        navCoordinates.text = String.format(Locale.US, "Оцінка: %.6f, %.6f · напрям %.0f°", lat, lon, navHeading)
    }

    private fun ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 12)
        } else startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, this)
        } catch (_: Exception) { navStatus.text = "GPS недоступний · потрібна початкова позиція" }
    }

    private fun stopLocationUpdates() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        if (location.accuracy > 35f) return
        // Don't silently teleport while navigating: GPS correction is deliberately disabled
        // in this prototype until a proper fusion/reconciliation algorithm is implemented.
        if (navigationRunning) return
        navLat = location.latitude
        navLon = location.longitude
        if (location.hasBearing() && location.speed > 2f) {
            navHeading = location.bearing.toDouble()
            headingReady = true
        }
        updateNavText()
        navStatus.text = if (headingReady) "GPS-точка та напрямок готові" else "GPS-точка є · проїдьте трохи для визначення напрямку"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("navigationRunning", navigationRunning)
        outState.putBoolean("navigationPaused", navigationPaused)
        navLat?.let { outState.putDouble("navLat", it) }
        navLon?.let { outState.putDouble("navLon", it) }
        outState.putDouble("navHeading", navHeading)
        outState.putBoolean("headingReady", headingReady)
        outState.putDouble("distanceKm", distanceKm)
        super.onSaveInstanceState(outState)
    }

    private fun showStatus(text: String) { handler.post { status.text = text } }
    private fun stop() {
        active.set(false)
        invalidateSpeedSample()
        try { socket?.close() } catch (_: Exception) {}
        worker?.interrupt()
    }
    override fun onDestroy() { stop(); super.onDestroy() }
}

/**
 * Analog gauge inspired by the Golf Mk5 instrument cluster:
 * dark face, white graduations, red needle and red high-speed region.
 * No VW logos or copyrighted artwork.
 */
class GolfSpeedometerView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var currentSpeed: Int? = null
    private val maxSpeed = 260
    private val startAngle = 135f
    private val sweepAngle = 270f
    private val face = Color.rgb(12, 16, 22)
    private val white = Color.rgb(234, 238, 243)
    private val red = Color.rgb(220, 38, 48)
    private val muted = Color.rgb(112, 125, 139)

    fun setSpeed(value: Int?) {
        currentSpeed = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val r = size * 0.46f
        canvas.drawColor(Color.rgb(16, 24, 32))
        paint.style = Paint.Style.FILL
        paint.color = face
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.013f
        paint.color = Color.rgb(59, 68, 78)
        canvas.drawCircle(cx, cy, r * 0.985f, paint)

        val arc = RectF(cx-r*0.88f, cy-r*0.88f, cx+r*0.88f, cy+r*0.88f)
        paint.strokeWidth = size * 0.014f
        paint.color = Color.rgb(225, 229, 235)
        canvas.drawArc(arc, startAngle, sweepAngle, false, paint)
        paint.color = red
        canvas.drawArc(arc, startAngle + sweepAngle*220/maxSpeed, sweepAngle*40/maxSpeed, false, paint)

        for (kmh in 0..maxSpeed step 10) {
            val angle = Math.toRadians((startAngle + sweepAngle * kmh / maxSpeed).toDouble())
            val major = kmh % 20 == 0
            val outer = r * 0.83f
            val inner = r * (if (major) 0.69f else 0.75f)
            paint.color = if (kmh >= 220) red else white
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * (if (major) 0.009f else 0.005f)
            canvas.drawLine(
                cx + cos(angle).toFloat()*inner, cy + sin(angle).toFloat()*inner,
                cx + cos(angle).toFloat()*outer, cy + sin(angle).toFloat()*outer, paint
            )
            if (major) {
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                paint.textSize = size * 0.057f
                val tx = cx + cos(angle).toFloat()*r*0.57f
                val ty = cy + sin(angle).toFloat()*r*0.57f - (paint.ascent()+paint.descent())/2f
                canvas.drawText(kmh.toString(), tx, ty, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = size*0.052f
        paint.color = muted
        canvas.drawText("km/h", cx, cy-r*0.26f, paint)

        val shown = currentSpeed
        val needleAngle = Math.toRadians((startAngle + sweepAngle*(shown ?: 0).coerceIn(0,maxSpeed)/maxSpeed).toDouble())
        paint.color = red
        paint.strokeWidth = size*0.014f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            cx - cos(needleAngle).toFloat()*r*0.12f,
            cy - sin(needleAngle).toFloat()*r*0.12f,
            cx + cos(needleAngle).toFloat()*r*0.67f,
            cy + sin(needleAngle).toFloat()*r*0.67f,
            paint
        )
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(205, 211, 218)
        canvas.drawCircle(cx, cy, size*0.028f, paint)
        paint.color = red
        canvas.drawCircle(cx, cy, size*0.015f, paint)

        paint.color = Color.rgb(8, 11, 15)
        val box = RectF(cx-r*0.30f, cy+r*0.30f, cx+r*0.30f, cy+r*0.56f)
        canvas.drawRoundRect(box, size*0.014f, size*0.014f, paint)
        paint.color = Color.rgb(235, 241, 245)
        paint.typeface = Typeface.create("monospace", Typeface.BOLD)
        paint.textSize = size*0.10f
        val digits = shown?.toString() ?: "---"
        canvas.drawText(digits, cx, cy+r*0.48f, paint)
    }
}
