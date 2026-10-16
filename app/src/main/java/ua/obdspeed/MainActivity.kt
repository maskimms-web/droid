package ua.obdspeed

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var worker: Thread? = null
    private lateinit var spinner: Spinner
    private lateinit var speed: TextView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var button: Button
    private var devices: List<BluetoothDevice> = emptyList()
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val cyan = Color.rgb(56, 189, 248)
    private val gray = Color.rgb(170, 184, 200)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
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
            gravity = Gravity.CENTER; setPadding(0, 85, 0, 0)
        })
        speed = label("—", 100f).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        root.addView(speed)
        root.addView(label("км/год", 26f, cyan).apply { gravity = Gravity.CENTER })
        status = label("Не підключено", 17f, gray).apply { setPadding(0, 95, 0, 8) }
        root.addView(status)
        detail = label("Спочатку спаруйте ELM327 у налаштуваннях Android", 14f, gray)
        root.addView(detail)
        setContentView(root)
        ensurePermission()
    }

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
                        handler.post { if (active.get()) { speed.text = value.toString(); detail.text = "PID 010D · Дані з ECU" } }
                    } else {
                        misses++
                        handler.post { speed.text = "—"; detail.text = "Немає актуальних даних швидкості" }
                        if (misses >= 5) throw IOException("Немає коректних відповідей PID 010D")
                    }
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                if (active.get()) {
                    showStatus("Зв'язок втрачено · перепідключення…")
                    handler.post { speed.text = "—"; detail.text = e.message ?: "Помилка адаптера" }
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
        handler.post { button.text = "Підключити"; spinner.isEnabled = true; speed.text = "—"; showStatus("Відключено") }
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

    private fun showStatus(text: String) { handler.post { status.text = text } }
    private fun stop() {
        active.set(false)
        try { socket?.close() } catch (_: Exception) {}
        worker?.interrupt()
    }
    override fun onDestroy() { stop(); super.onDestroy() }
}
