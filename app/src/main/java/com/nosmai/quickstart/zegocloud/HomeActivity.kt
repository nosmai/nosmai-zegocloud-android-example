package com.nosmai.quickstart.zegocloud

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Role selection: go live as the host, or watch as the audience. */
class HomeActivity : AppCompatActivity() {
    private val config by lazy { DemoConfig(applicationContext) }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val info = findViewById<TextView>(R.id.infoText)
        val problem = config.problem(isHost = false)
        if (problem == null) {
            info.text = "Room: ${config.roomID}\nHost on one phone, watch on another."
        } else {
            info.text = problem
            info.setTextColor(Color.RED)
        }

        findViewById<MaterialButton>(R.id.hostButton).setOnClickListener { open(isHost = true) }
        findViewById<MaterialButton>(R.id.watchButton).setOnClickListener { open(isHost = false) }
    }

    private fun open(isHost: Boolean) {
        val problem = config.problem(isHost)
        if (problem != null) {
            AlertDialog.Builder(this)
                .setTitle("Setup required")
                .setMessage(problem)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        startActivity(Intent(this, LiveActivity::class.java).putExtra(LiveActivity.EXTRA_IS_HOST, isHost))
    }
}
