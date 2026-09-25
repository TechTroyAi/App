package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.media.MediaAccess
import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Owns the "pick a photo / video / take a photo" round-trip for the editor and
 * the wallpaper setup, so those screens only see a final `(uri, isVideo)` pair.
 *
 * Selection always goes through the system picker - never through
 * `READ_EXTERNAL_STORAGE`, which we do not request - and the returned URI is
 * handed straight to [MediaAccess.persist] so it survives a reboot.
 */
class BackgroundPicker(
    private val activity: Activity,
    private val onPicked: (uri: String, isVideo: Boolean) -> Unit,
) {

    private var cameraTarget: Uri? = null

    fun pickImage() {
        activity.startActivityForResult(MediaAccess.pickImages(), MediaAccess.REQ_IMAGE)
    }

    fun pickVideo() {
        activity.startActivityForResult(MediaAccess.pickVideos(), MediaAccess.REQ_VIDEO)
    }

    fun takePhoto() {
        val name = "IMG_" + System.currentTimeMillis() + ".jpg"
        val target = MediaAccess.prepareCameraTarget(activity, name)
        if (target == null) {
            pickImage()
            return
        }
        cameraTarget = target
        try {
            activity.startActivityForResult(MediaAccess.cameraIntent(target), MediaAccess.REQ_CAMERA)
        } catch (e: Throwable) {
            activity.toastOrIgnore("No camera app available.")
            cameraTarget = null
        }
    }

    /** @return true when [requestCode] was one of ours and has been handled. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK && !(requestCode == MediaAccess.REQ_CAMERA && cameraTarget != null)) return false
        when (requestCode) {
            MediaAccess.REQ_IMAGE -> {
                val uri = MediaAccess.pickedIntentData(data)?.uri ?: return true
                finishWith(uri, false)
                return true
            }
            MediaAccess.REQ_VIDEO -> {
                val uri = MediaAccess.pickedIntentData(data)?.uri ?: return true
                finishWith(uri, true)
                return true
            }
            MediaAccess.REQ_CAMERA -> {
                val target = cameraTarget ?: return false
                cameraTarget = null
                if (target.path?.let { File(it).exists() } == true || "content" == target.scheme) {
                    finishWith(target, false)
                }
                return true
            }
        }
        return false
    }

    private fun finishWith(uri: Uri, isVideo: Boolean) {
        val described = MediaAccess.describe(activity, uri)
        MediaAccess.persist(activity, uri)
        onPicked(uri.toString(), isVideo || described.isVideo)
    }
}

private fun Activity.toastOrIgnore(message: String) {
    try {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Throwable) {
    }
}
