package coil.decode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build.VERSION.SDK_INT
import androidx.test.core.app.ApplicationProvider
import coil.bitmappool.BitmapPool
import coil.size.OriginalSize
import coil.size.PixelSize
import coil.size.Scale
import coil.size.Size
import coil.util.createOptions
import coil.util.isSimilarTo
import coil.util.size
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BitmapFactoryDecoderTest {

    private lateinit var context: Context
    private lateinit var pool: BitmapPool
    private lateinit var service: BitmapFactoryDecoder

    @Before
    fun before() {
        context = ApplicationProvider.getApplicationContext()
        pool = BitmapPool(Long.MAX_VALUE)
        service = BitmapFactoryDecoder(context)
    }

    @Test
    fun basic() {
        val source = context.assets.open("normal.jpg").source().buffer()
        val (drawable, isSampled) = runBlocking {
            service.decode(
                pool = pool,
                source = source,
                size = PixelSize(100, 100),
                options = createOptions()
            )
        }

        val exception = assertFailsWith<IllegalStateException> { source.exhausted() }
        assertEquals("closed", exception.message)
        assertTrue(isSampled)
        assertTrue(drawable is BitmapDrawable)
        assertEquals(PixelSize(100, 125), drawable.bitmap.size)
        assertEquals(drawable.bitmap.config, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun malformedImageThrows() {
        assertFailsWith<IllegalStateException> {
            runBlocking {
                service.decode(
                    pool = pool,
                    source = context.assets.open("malformed.jpg").source().buffer(),
                    size = PixelSize(100, 100),
                    options = createOptions()
                )
            }
        }
    }

    @Test
    fun resultIsSampledIfGreaterThanHalfSize() {
        val (drawable, isSampled) = runBlocking {
            service.decode(
                pool = pool,
                source = context.assets.open("normal.jpg").source().buffer(),
                size = PixelSize(600, 600),
                options = createOptions()
            )
        }

        assertTrue(isSampled)
        assertTrue(drawable is BitmapDrawable)
    }

    @Test
    fun originalSizeDimensionsAreResolvedCorrectly() {
        val size = OriginalSize
        val normal = decode("normal.jpg", size)
        assertEquals(PixelSize(1080, 1350), normal.size)
    }

    @Test
    fun exifTransformationsAreAppliedCorrectly() {
        val size = PixelSize(500, 500)
        val normal = decode("normal.jpg", size)

        for (index in 1..8) {
            val other = decode("exif/$index.jpg", size)
            assertTrue(normal.isSimilarTo(other), "Image with index $index is incorrect.")
        }
    }

    @Test
    fun largeExifMetadata() {
        val size = PixelSize(500, 500)
        val normal = decode("exif/large_metadata_normalized.jpg", size)
        val actual = decode("exif/large_metadata_normalized.jpg", size)
        assertTrue(normal.isSimilarTo(actual))
    }

    @Test
    fun allowInexactSize_True() {
        val result = decode(
            fileName = "normal.jpg",
            size = PixelSize(1500, 1500),
            options = { createOptions(scale = Scale.FIT, allowInexactSize = true) }
        )
        assertEquals(PixelSize(1080, 1350), result.size)
    }

    @Test
    fun allowInexactSize_False() {
        val result = decode(
            fileName = "normal.jpg",
            size = PixelSize(1500, 1500),
            options = { createOptions(scale = Scale.FIT, allowInexactSize = false) }
        )
        assertEquals(PixelSize(1200, 1500), result.size)
    }

    @Test
    fun pooledBitmap_exactSize() {
        val pooledBitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        pool.put(pooledBitmap)

        val result = decode(
            fileName = "normal.jpg",
            size = PixelSize(1080, 1350),
            options = {
                createOptions(
                    config = Bitmap.Config.ARGB_8888,
                    scale = Scale.FIT,
                    allowInexactSize = false
                )
            }
        )
        assertEquals(PixelSize(1080, 1350), result.size)
        assertEquals(pooledBitmap, result)
    }

    @Test
    fun pooledBitmap_inexactSize() {
        val pooledBitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        pool.put(pooledBitmap)

        val result = decode(
            fileName = "normal.jpg",
            size = PixelSize(500, 500),
            options = {
                createOptions(
                    config = Bitmap.Config.ARGB_8888,
                    scale = Scale.FILL,
                    allowInexactSize = false
                )
            }
        )
        assertEquals(PixelSize(500, 625), result.size)
        // The bitmap should not be re-used on pre-API 19.
        assertEquals(pooledBitmap === result, SDK_INT >= 19)
    }

    private fun decode(
        fileName: String,
        size: Size,
        options: () -> Options = { createOptions() }
    ): Bitmap = runBlocking {
        val result = service.decode(
            pool = pool,
            source = context.assets.open(fileName).source().buffer(),
            size = size,
            options = options()
        )
        return@runBlocking (result.drawable as BitmapDrawable).bitmap
    }
}
