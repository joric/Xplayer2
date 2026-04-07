package com.teleteh.xplayer2.ui.files

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.util.DisplayUtils

class FilesFragment : Fragment(R.layout.fragment_files) {

    private lateinit var openDocLauncher: ActivityResultLauncher<Intent>

    private fun resolveMediaStoreUri(uri: Uri): Uri? {
        if (uri.authority == MediaStore.AUTHORITY) return uri
        if (uri.scheme != "content") return null
        val ctx = requireContext()
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val fileName: String
        val fileSize: Long
        try {
            ctx.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (!cursor.moveToFirst() || nameIndex < 0) return null
                fileName = cursor.getString(nameIndex) ?: return null
                fileSize = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
            } ?: return null
        } catch (_: Throwable) {
            return null
        }

        val mediaProjection = arrayOf(MediaStore.Video.Media._ID)
        val selection = if (fileSize >= 0L) {
            "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.SIZE}=?"
        } else {
            "${MediaStore.Video.Media.DISPLAY_NAME}=?"
        }
        val selectionArgs = if (fileSize >= 0L) {
            arrayOf(fileName, fileSize.toString())
        } else {
            arrayOf(fileName)
        }

        return try {
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                mediaProjection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                if (cursor.moveToFirst() && idIndex >= 0) {
                    Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idIndex).toString()
                    )
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            uri ?: return@registerForActivityResult
            val ctx = requireContext()
            // Persist read permission so we can reopen from Recent later
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Ignore if not persistable; we still have transient read permission
            }
            val recentUri = resolveMediaStoreUri(uri) ?: uri
            val intent = Intent(ctx, PlayerActivity::class.java)
            intent.data = uri
            intent.putExtra(PlayerActivity.EXTRA_RECENT_URI, recentUri.toString())
            if (recentUri != uri) {
                intent.putExtra(PlayerActivity.EXTRA_RECENT_FALLBACK_URI, uri.toString())
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DisplayUtils.startOnBestDisplay(requireActivity(), intent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val button = view.findViewById<Button>(R.id.btnOpen)
        button.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            }
            openDocLauncher.launch(intent)
        }
        // Configure button for D-pad navigation
        button.isFocusable = true
        button.isFocusableInTouchMode = true
        button.isLongClickable = false
        
        // Single-tap activation without double-click requirement
        button.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                v.requestFocus()
                v.performClick()
                true
            } else {
                false
            }
        }

        // Files screen has a single visible control; block downward focus escape
        // so D-pad does not jump to hidden/off-screen targets.
        button.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val activity = requireActivity()
                    val tabLayout = activity.findViewById<TabLayout>(R.id.tabLayout)
                    val viewPager = activity.findViewById<ViewPager2>(R.id.viewPager)
                    val activeTabView = tabLayout?.getTabAt(viewPager?.currentItem ?: 0)?.view
                    (activeTabView ?: tabLayout)?.requestFocus()
                    true
                }
                else -> false
            }
        }
    }
}
