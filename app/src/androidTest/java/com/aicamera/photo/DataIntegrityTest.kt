package com.aicamera.photo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicamera.photo.core.data.CameraMode
import com.aicamera.photo.core.data.CaptureRecord
import com.aicamera.photo.core.data.EditRepository
import com.aicamera.photo.core.data.MediaStorage
import com.aicamera.photo.core.data.PhotoRepository
import com.aicamera.photo.core.data.db.LumenDatabase
import com.aicamera.photo.core.image.Developer
import com.aicamera.photo.domain.CaptureMetadata
import com.aicamera.photo.domain.FilmRecipe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Acceptance criterion: Film edits never overwrite originals, and links survive restarts. */
@RunWith(AndroidJUnit4::class)
class DataIntegrityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())

    @Test
    fun developingNeverModifiesTheOriginal() = runBlocking {
        val original = File(context.cacheDir, "test_original.jpg")
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120, 140, 160)) }
        original.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val before = sha(original)
        val out = File(context.cacheDir, "test_developed.jpg")
        Developer().develop(original, FilmRecipe(profileId = "mono_400", exposure = 0.5f, grain = 0.4f), out)
        assertArrayEquals(before, sha(original))
        assertTrue(out.exists() && out.length() > 0)
        assertFalse(before.contentEquals(sha(out)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun developerRefusesToWriteOverOriginal() = runBlocking {
        val original = File(context.cacheDir, "test_same.jpg")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).let { b -> original.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        Developer().develop(original, FilmRecipe(), original)
        Unit
    }

    @Test
    fun originalEditAndLinksSurviveDatabaseReopen() = runBlocking {
        val name = "integrity_test.db"
        context.deleteDatabase(name)
        val storage = MediaStorage(context)
        var db = Room.databaseBuilder(context, LumenDatabase::class.java, name).build()
        val photos = PhotoRepository(db, storage)
        val id = storage.newPhotoId()
        val file = File(context.cacheDir, "link_test.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val session = photos.startSession(CameraMode.GUIDED, null)
        photos.saveCapture(CaptureRecord(id, session, file, null, System.currentTimeMillis(), CameraMode.GUIDED, CaptureMetadata(width = 4, height = 3), null, null, null, null))
        EditRepository(db, storage).save(null, id, file.absolutePath, FilmRecipe(profileId = "cine_teal"), File(context.cacheDir, "dev.jpg").absolutePath)
        db.close()

        db = Room.databaseBuilder(context, LumenDatabase::class.java, name).build()
        val photo = db.photoDao().get(id)
        assertNotNull(photo)
        assertEquals(file.absolutePath, photo!!.originalPath)
        val edits = db.editDao().forPhoto(id)
        assertEquals(1, edits.size)
        assertEquals(file.absolutePath, edits[0].originalPath)
        assertEquals("cine_teal", EditRepository.decodeRecipe(edits[0].recipeJson).profileId)
        db.close()
        context.deleteDatabase(name)
    }
}
