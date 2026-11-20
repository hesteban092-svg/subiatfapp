package cl.atfprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.abs

/**
 * Versión SSM (protocolo propio de Subaru sobre CAN/ISO-TP).
 * Lee bloques de memoria con el servicio A0 (solo lectura) y compara capturas
 * (frío / caliente / caliente de nuevo) para encontrar el byte de la temperatura del ATF.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val elm = Elm()
    private var devices: List<BluetoothDevice> = emptyList()

    private lateinit var spinner: Spinner
    private lateinit var header: EditText
    private lateinit var startEt: EditText
    private lateinit var endEt: EditText
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    @Volatile private var stop = false
    @Volatile private var busy = false
    private val snaps = arrayOfNulls<Map<Int, Int>>(3)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        spinner = Spinner(this)
        root.addView(spinner)

        header = EditText(this).apply { setText("7E1"); setSingleLine() }
        root.addView(label("Cabecera del módulo (TCM = 7E1):"))
        root.addView(header)

        root.addView(row(
            btn("Conectar") { connect() },
            btn("Probar SSM") { probe() }
        ))

        startEt = EditText(this).apply { setText("000000"); setSingleLine() }
        endEt = EditText(this).apply { setText("0007FF"); setSingleLine() }
        root.addView(label("Rango de direcciones (hex, 6 dígitos) a leer:"))
        root.addView(row(startEt, endEt))

        root.addView(row(
            btn("A (frío)") { scan(0) },
            btn("B (caliente)") { scan(1) },
            btn("C (igual que B)") { scan(2) }
        ))
        root.addView(row(
            btn("Comparar") { compare() },
            btn("Detener") { stop = true }
        ))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(btn("Compartir log") { share() })
        setContentView(root)

        log("Versión SSM (solo lectura).\n" +
            "1) Conectar. 2) Probar SSM y revisa si responde BF / A0.\n" +
            "3) Captura A con el auto frío (encendido ON, estacionado).\n" +
            "4) Maneja hasta calentar la caja; estaciona y haz B.\n" +
            "5) Haz C enseguida, sin dejar enfriar. 6) Comparar.\n" +
            "C filtra valores que cambian rápido (rpm, contadores).")

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else {
            loadDevices()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        loadDevices()
    }

    override fun onDestroy() {
        stop = true
        elm.close()
        super.onDestroy()
    }

    private fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = adapter?.bondedDevices?.toList() ?: emptyList()
        val names = devices.map { "${it.name} (${it.address})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val i = devices.indexOfFirst { (it.name ?: "").contains("OBD", ignoreCase = true) }
        if (i >= 0) spinner.setSelection(i)
        if (devices.isEmpty()) log("No hay dispositivos emparejados.")
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 12f }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    private fun log(s: String) = runOnUiThread {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun work(block: () -> Unit) {
        if (busy) { log("Ocupado. Espera o pulsa Detener."); return }
        busy = true
        stop = false
        Thread {
            try { block() } catch (e: Exception) { log("ERROR: ${e.message}") } finally { busy = false }
        }.start()
    }

    private fun connect() = work {
        val d = devices.getOrNull(spinner.selectedItemPosition)
        if (d == null) { log("Sin dispositivo seleccionado."); return@work }
        log("Conectando a ${d.name}...")
        elm.close()
        elm.connect(d)
        log(elm.init(header.text.toString().trim().uppercase()))
        log("Listo.")
    }

    /** Pruebas de lectura: init SSM (BF), lectura de bloque (A0), lectura de dirección (A8) y UDS. */
    private fun probe() = work {
        val tests = listOf(
            "BF" to "init SSM (espera FF...)",
            "A0000000000F" to "bloque 16 bytes en 000000 (espera E0 + 16 bytes)",
            "A800000000" to "lectura de 1 dirección (espera E8 + 1 byte)",
            "22F190" to "UDS VIN (solo referencia)"
        )
        for ((req, desc) in tests) {
            val raw = elm.send(req, 1500)
            log("$req [$desc]\n   -> ${raw.replace("\n", " | ")}")
        }
    }

    private fun scan(slot: Int) = work {
        val a = startEt.text.toString().trim().toInt(16)
        val b = endEt.text.toString().trim().toInt(16)
        val n = 16
        val found = HashMap<Int, Int>()
        var fails = 0
        var addr = a
        log("Leyendo %06X a %06X ...".format(a, b))
        while (addr <= b) {
            if (stop) { log("Detenido en %06X".format(addr)); break }
            val req = "A000%06X%02X".format(addr, n - 1)
            val hex = Elm.parse(elm.send(req, 1000))
            if (hex != null && hex.startsWith("E0") && hex.length >= 2 + 2 * n) {
                for (i in 0 until n) found[addr + i] = hex.substring(2 + 2 * i, 4 + 2 * i).toInt(16)
                fails = 0
            } else {
                fails++
                log("%06X: sin lectura (%s)".format(addr, hex ?: "sin dato"))
                if (fails >= 5) { log("5 fallos seguidos, aborto."); break }
            }
            addr += n
        }
        snaps[slot] = found
        log("Captura ${"ABC"[slot]}: ${found.size} bytes leídos.")
    }

    private fun compare() {
        val a = snaps[0]
        val b = snaps[1]
        val c = snaps[2]
        if (a == null || b == null) { log("Faltan las capturas A y B."); return }
        var changed = 0
        var cands = 0
        for ((addr, x) in a.toSortedMap()) {
            val y = b[addr] ?: continue
            if (x == y) continue
            changed++
            val stable = if (c == null) true else (c[addr]?.let { abs(it - y) <= 1 } ?: false)
            if (y - x in 8..80 && x - 40 in -30..60 && y - 40 in 0..140 && stable) {
                cands++
                log("*%06X: %d -> %d  (A-40: %d -> %d C)".format(addr, x, y, x - 40, y - 40))
            }
        }
        log("$changed bytes cambiaron, $cands candidatos" +
            if (c == null) " (sin captura C, habrá falsos positivos)." else ".")
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logView.text.toString())
        }
        startActivity(Intent.createChooser(i, "Compartir log"))
    }
}
