package io.github.todotxt.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import io.github.todotxt.app.R
import io.github.todotxt.app.model.Note
import io.github.todotxt.app.storage.NoteStorage

/**
 * Editor for a task note (multi-line text + gallery images).
 *
 * Extras in:
 *   EXTRA_NOTE_ID   — existing note ID, or null to create a new one
 *
 * Extras out (RESULT_OK):
 *   EXTRA_NOTE_ID   — the note ID (new or existing); absent if note was deleted
 *   EXTRA_DELETED   — true if the note was deleted
 */
class NoteActivity : Activity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_DELETED = "note_deleted"
        private const val REQ_PICK_IMAGE = 1001
        private const val THUMB_SIZE_PX  = 200
    }

    private lateinit var noteEditText: EditText
    private lateinit var imagesScrollView: HorizontalScrollView
    private lateinit var imagesRow: LinearLayout
    private lateinit var noteDeleteButton: Button

    private var noteId: String? = null
    private val imageUris = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note)

        noteEditText      = findViewById(R.id.noteEditText)
        imagesScrollView  = findViewById(R.id.imagesScrollView)
        imagesRow         = findViewById(R.id.imagesRow)
        noteDeleteButton  = findViewById(R.id.noteDeleteButton)

        noteId = intent.getStringExtra(EXTRA_NOTE_ID)

        if (noteId != null) {
            setTitle(R.string.edit_note)
            noteDeleteButton.visibility = View.VISIBLE
            val note = NoteStorage.load(this, noteId!!)
            if (note != null) {
                noteEditText.setText(note.text)
                imageUris.addAll(note.images)
                rebuildImageStrip()
            }
        } else {
            setTitle(R.string.add_note)
        }

        findViewById<Button>(R.id.addImageButton).setOnClickListener { pickImage() }
        findViewById<Button>(R.id.noteSaveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.noteCancelButton).setOnClickListener { finish() }
        noteDeleteButton.setOnClickListener { deleteNote() }
    }

    // ── Image picking ─────────────────────────────────────────────────────

    private fun pickImage() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            REQ_PICK_IMAGE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            imageUris.add(uri.toString())
            rebuildImageStrip()
        }
    }

    // ── Image strip ───────────────────────────────────────────────────────

    private fun rebuildImageStrip() {
        imagesRow.removeAllViews()
        imageUris.forEachIndexed { index, uriString ->
            val frame = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).also { it.setMargins(0, 0, 8, 0) }
            }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(THUMB_SIZE_PX, THUMB_SIZE_PX)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            loadThumbnail(iv, uriString)

            // Long-press to remove
            iv.setOnLongClickListener {
                android.app.AlertDialog.Builder(this)
                    .setMessage(R.string.note_remove_image_confirm)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        imageUris.removeAt(index)
                        rebuildImageStrip()
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
                true
            }
            frame.addView(iv)
            imagesRow.addView(frame)
        }
        imagesScrollView.visibility = if (imageUris.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadThumbnail(iv: ImageView, uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bmp = BitmapFactory.decodeStream(stream, null, opts)
                iv.setImageBitmap(bmp)
            }
        } catch (_: Exception) {
            iv.setImageResource(android.R.drawable.ic_menu_report_image)
        }
    }

    // ── Save / delete ─────────────────────────────────────────────────────

    private fun save() {
        val text   = noteEditText.text.toString()
        val images = imageUris.toList()

        if (text.isBlank() && images.isEmpty()) {
            // Nothing to save — treat as delete if editing existing note
            if (noteId != null) deleteNote() else finish()
            return
        }

        val id = noteId ?: NoteStorage.newId().also { noteId = it }
        NoteStorage.save(this, id, Note(text, images))

        setResult(RESULT_OK, Intent().putExtra(EXTRA_NOTE_ID, id))
        finish()
    }

    private fun deleteNote() {
        val id = noteId
        if (id != null) {
            android.app.AlertDialog.Builder(this)
                .setMessage(R.string.note_delete_confirm)
                .setPositiveButton(R.string.yes) { _, _ ->
                    NoteStorage.delete(this, id)
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_DELETED, true))
                    finish()
                }
                .setNegativeButton(R.string.no, null)
                .show()
        } else {
            finish()
        }
    }
}
