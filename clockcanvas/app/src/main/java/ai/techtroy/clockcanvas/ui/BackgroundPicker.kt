package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.R
import ai.techtroy.clockcanvas.media.MediaAccess
import ai.techtroy.clockcanvas.media.MediaVault
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Owns the "pick a photo / video / take a photo" round-trip for the editor and
 * the wallpaper setup, so those screens only see a final `(uri, isVideo)` pair.
 *
 * Selection always goes through the system picker - never through
 * `READ_EXTERNAL_STORAGE`, which we do not request. A successful pick is then
 * *imported* into [MediaVault] on a worker thread, because that is the part that
 * decides whether the picture is still there tomorrow: a picker URI is readable
 * only while we hold its (often non-persistable) grant, and a widget update can
 * arrive long after the launcher killed our process. Copying the file is also why
 * the copy is decoded off the main thread - a 12 MP photo is not cheap, and
 * `onActivityResult` is a bad place to spend a frame.
 */
class BackgroundPicker(
    private val activity: Activity,
    private val onPicked: (uri: String, isVideo: Boolean) -> Unit,
) {

    private var cameraTarget: Uri? = null
    private var importing = false
    private val main = Handler(Looper.getMainLooper())

    fun pickImage() {
        launch(MediaAccess.pickImages(), MediaAccess.REQ_IMAGE, "image/*")
    }

    fun pickVideo() {
        launch(MediaAccess.pickVideos(), MediaAccess.REQ_VIDEO, "video/*")
    }

    /**
     * The Photo Picker is a system *app* that a user can disable, so an unresolvable
     * intent is a supported state, not a bug: fall through to the document picker,
     * which is part of the framework and accepts the same persistable flags.
     */
    private fun launch(intent: Intent, request: Int, mime: String) {
        try {
            if (MediaAccess.canLaunch(activity, intent)) {
                activity.startActivityForResult(intent, request)
                return
            }
        } catch (e: Throwable) {
            // fall through
        }
        try {
            activity.startActivityForResult(MediaAccess.openDocument(mime), request)
        } catch (e: Throwable) {
            toast(activity.getString(R.string.bg_pick_failed))
        }
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
            cameraTarget = null
            toast(activity.getString(R.string.bg_no_camera))
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

    /** Copy the picked item into our own storage, then hand the design a file that cannot vanish. */
    private fun finishWith(uri: Uri, isVideo: Boolean) {
        if (importing) return
        importing = true
        try {
            Thread {
                val described = try {
                    MediaAccess.describe(activity, uri)
                } catch (e: Throwable) {
                    null
                }
                val video = isVideo || described?.isVideo == true
                // Still worth asking: when the provider allows it, the original stays
                // readable too, which lets a reel preview a video we chose not to copy.
                MediaAccess.persist(activity, uri)
                val imported = MediaVault.import(activity, uri, video, described?.displayName)
                val finalUri = imported ?: uri.toString()
                val readable = imported != null || MediaAccess.canRead(activity, finalUri)
                main.post {
                    importing = false
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (!readable) {
                        toast(activity.getString(R.string.bg_pick_failed))
                        return@post
                    }
                    onPicked(finalUri, video)
                }
            }.start()
        } catch (e: Throwable) {
            importing = false
            toast(activity.getString(R.string.bg_pick_failed))
        }
    }

    private fun toast(message: String) {
        try {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
        }
    }
}
