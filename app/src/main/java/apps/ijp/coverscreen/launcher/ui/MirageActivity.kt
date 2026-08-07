package apps.ijp.coverscreen.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.tv.coverscreen.R

/**
 * External screen permission helper. noHistory, one tap, sends the user to the
 * overlay permission page which is what Samsung gates cover output behind.
 */
class MirageActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.mirage_title)
            .setMessage(R.string.mirage_body)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + packageName)
                    )
                )
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
