package com.teleteh.xplayer2.ui.recent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.RecentStore
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.util.DisplayUtils
import android.view.ViewGroup

class RecentFragment : Fragment(R.layout.fragment_recent) {
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: RecentAdapter

    private fun canPersistReadPermission(uri: android.net.Uri): Boolean {
        if (uri.scheme != "content") return true
        return try {
            requireContext().contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (_: Exception) {
            false
        }
    }

    private fun canReadRecentUri(uri: android.net.Uri): Boolean {
        if (uri.scheme != "content") return true
        return try {
            requireContext().contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                true
            } ?: false
        } catch (_: SecurityException) {
            false
        } catch (_: java.io.FileNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.rvRecent)
        empty = view.findViewById(R.id.tvEmpty)
        adapter = RecentAdapter(onClick = { entry ->
            val ctx = requireContext()
            val candidates = listOfNotNull(entry.uriObj(), entry.fallbackUriObj())
            val uri = candidates.firstOrNull { candidate ->
                if (!canPersistReadPermission(candidate) && candidate.scheme == "content") {
                    try {
                        ctx.contentResolver.takePersistableUriPermission(candidate, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) {
                    }
                }
                canReadRecentUri(candidate)
            }

            if (uri == null) {
                android.widget.Toast.makeText(
                    ctx,
                    "Recent item is no longer accessible. Open it again from Files to restore access.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@RecentAdapter
            }
            
            val intent = Intent(ctx, PlayerActivity::class.java)
            intent.data = uri
            intent.putExtra(PlayerActivity.EXTRA_START_POSITION_MS, entry.lastPositionMs)
            // Pass the stored title so PlayerActivity starts with the correct display title
            intent.putExtra(PlayerActivity.EXTRA_TITLE, entry.title)
            intent.putExtra(PlayerActivity.EXTRA_RECENT_URI, entry.uri)
            entry.fallbackUri?.let {
                intent.putExtra(PlayerActivity.EXTRA_RECENT_FALLBACK_URI, it)
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DisplayUtils.startOnBestDisplay(requireActivity(), intent)
        }, onDelete = { entry ->
            RecentStore(requireContext()).delete(entry.uri)
            loadData()
        })

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        // Configure RecyclerView for D-pad navigation
        recycler.isFocusable = true
        recycler.isFocusableInTouchMode = true
        recycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        val items = RecentStore(requireContext()).getAll()
        adapter.submitList(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}
