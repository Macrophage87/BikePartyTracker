package io.github.macrophage87.bikeparty

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import io.github.macrophage87.bikeparty.model.RiderRole
import io.github.macrophage87.bikeparty.ride.GpxParser
import io.github.macrophage87.bikeparty.ride.RideSession
import io.github.macrophage87.bikeparty.service.LocationSharingService
import java.security.SecureRandom
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var pickedRoute: GpxParser.Result? = null

    private lateinit var etName: TextInputEditText
    private lateinit var actRole: MaterialAutoCompleteTextView
    private lateinit var etCode: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var switchLeader: SwitchMaterial
    private lateinit var rowGpx: View
    private lateinit var tvGpx: TextView
    private lateinit var etBrokerHost: TextInputEditText
    private lateinit var etBrokerPort: TextInputEditText
    private lateinit var switchTls: SwitchMaterial
    private lateinit var btnStart: Button
    private lateinit var btnReturn: Button

    private val gpxPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { loadGpx(it) }
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startRide()
        } else {
            Snackbar.make(btnStart, R.string.location_permission_needed, Snackbar.LENGTH_LONG)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("bikeparty", MODE_PRIVATE)

        etName = findViewById(R.id.et_name)
        actRole = findViewById(R.id.act_role)
        etCode = findViewById(R.id.et_code)
        etPassword = findViewById(R.id.et_password)
        switchLeader = findViewById(R.id.switch_leader)
        rowGpx = findViewById(R.id.row_gpx)
        tvGpx = findViewById(R.id.tv_gpx)
        etBrokerHost = findViewById(R.id.et_broker_host)
        etBrokerPort = findViewById(R.id.et_broker_port)
        switchTls = findViewById(R.id.switch_tls)
        btnStart = findViewById(R.id.btn_start)
        btnReturn = findViewById(R.id.btn_return)

        actRole.setSimpleItems(RiderRole.entries.map { it.label }.toTypedArray())

        etName.setText(prefs.getString(PREF_NAME, ""))
        actRole.setText(RiderRole.fromId(prefs.getString(PREF_ROLE, null)).label, false)
        etCode.setText(prefs.getString(PREF_CODE, ""))
        etBrokerHost.setText(prefs.getString(PREF_BROKER_HOST, DEFAULT_BROKER_HOST))
        etBrokerPort.setText(prefs.getInt(PREF_BROKER_PORT, DEFAULT_BROKER_PORT).toString())
        switchTls.isChecked = prefs.getBoolean(PREF_BROKER_TLS, true)

        switchLeader.setOnCheckedChangeListener { _, checked ->
            rowGpx.visibility = if (checked) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btn_generate).setOnClickListener {
            etCode.setText(generateRideCode())
        }
        findViewById<Button>(R.id.btn_gpx).setOnClickListener {
            gpxPicker.launch(arrayOf("*/*"))
        }
        btnStart.setOnClickListener { onStartClicked() }
        btnReturn.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        btnReturn.visibility = if (RideSession.active) View.VISIBLE else View.GONE
    }

    private fun onStartClicked() {
        val name = etName.text?.toString()?.trim().orEmpty()
        val code = etCode.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()
        when {
            name.isEmpty() -> etName.error = getString(R.string.field_required)
            code.isEmpty() -> etCode.error = getString(R.string.field_required)
            password.isEmpty() -> etPassword.error = getString(R.string.field_required)
            else -> {
                val needed = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= 33) {
                    needed.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                val fineGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (fineGranted) startRide() else permissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    private fun startRide() {
        val role = RiderRole.fromLabel(actRole.text?.toString())
        val name = etName.text?.toString()?.trim().orEmpty()
        val code = etCode.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()
        val host = etBrokerHost.text?.toString()?.trim()?.ifEmpty { null } ?: DEFAULT_BROKER_HOST
        val port = etBrokerPort.text?.toString()?.trim()?.toIntOrNull() ?: DEFAULT_BROKER_PORT

        prefs.edit()
            .putString(PREF_NAME, name)
            .putString(PREF_ROLE, role.id)
            .putString(PREF_CODE, code)
            .putString(PREF_BROKER_HOST, host)
            .putInt(PREF_BROKER_PORT, port)
            .putBoolean(PREF_BROKER_TLS, switchTls.isChecked)
            .apply()

        val config = RideSession.Config(
            riderId = riderId(),
            riderName = name,
            role = role,
            rideCode = code,
            password = password,
            isLeader = switchLeader.isChecked,
            brokerHost = host,
            brokerPort = port,
            useTls = switchTls.isChecked
        )
        RideSession.start(config, pickedRoute?.let { it.name to it.points })
        LocationSharingService.start(this)
        startActivity(Intent(this, MapActivity::class.java))
    }

    private fun loadGpx(uri: Uri) {
        try {
            val result = contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }
            if (result == null || result.points.isEmpty()) {
                pickedRoute = null
                tvGpx.text = getString(R.string.gpx_empty)
            } else {
                pickedRoute = result
                tvGpx.text = getString(
                    R.string.gpx_loaded,
                    result.name ?: getString(R.string.gpx_unnamed),
                    result.points.size
                )
            }
        } catch (_: Exception) {
            pickedRoute = null
            tvGpx.text = getString(R.string.gpx_error)
        }
    }

    private fun riderId(): String {
        prefs.getString(PREF_RIDER_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_RIDER_ID, id).apply()
        return id
    }

    private fun generateRideCode(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    companion object {
        private const val PREF_NAME = "rider_name"
        private const val PREF_ROLE = "rider_role"
        private const val PREF_CODE = "ride_code"
        private const val PREF_RIDER_ID = "rider_id"
        private const val PREF_BROKER_HOST = "broker_host"
        private const val PREF_BROKER_PORT = "broker_port"
        private const val PREF_BROKER_TLS = "broker_tls"

        private const val DEFAULT_BROKER_HOST = "broker.hivemq.com"
        private const val DEFAULT_BROKER_PORT = 8883
    }
}
