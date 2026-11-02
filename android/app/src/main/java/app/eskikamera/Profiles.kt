package app.eskikamera

/** Fotoğraf ve video için ortak görünüm parametreleri. */
class Look(
    val blur: Float,
    val sharpen: Float,
    val noiseLuma: Float,
    val noiseChroma: Float,
    val banding: Float,
    val contrast: Float,
    val saturation: Float,
    val gamma: Float,
    val cast: FloatArray,
    val vignette: Float,
    val bloom: Float,
    val bayer: Boolean,
    val hotPixels: Int,
    val chromaBleed: Int,
    val jpegQuality: Int
)

class AudioSpec(val rate: Int, val channels: Int, val kbps: Int, val pcm: Boolean)

class VideoMode(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String,
    val container: String,
    val videoKbps: Int,
    val audio: AudioSpec
)

class Device(
    val id: String,
    val name: String,
    val category: String,
    val year: Int,
    val specSource: String,
    val lookSource: String,
    val photoWidth: Int,
    val photoHeight: Int,
    val photoLook: Look,
    val modes: List<VideoMode>,
    val defaultMode: Int,
    val videoLook: Look?,
    val videoPrescale: IntArray?
) {
    val hasVideo: Boolean get() = modes.isNotEmpty()
}

@Suppress("UNCHECKED_CAST")
object Profiles {
    fun parse(text: String): List<Device> {
        val root = MiniJson.parse(text) as Map<String, Any?>
        val defaults = root["defaults"] as Map<String, Any?>
        val dPhoto = defaults["photo_look"] as Map<String, Any?>
        val dVideo = defaults["video_look"] as Map<String, Any?>
        val devices = root["devices"] as List<Any?>
        return devices.map { parseDevice(it as Map<String, Any?>, dPhoto, dVideo) }
    }

    private fun Map<String, Any?>.f(k: String): Float =
        ((this[k] ?: throw IllegalArgumentException("Profilde '$k' eksik")) as Number).toFloat()

    private fun Map<String, Any?>.i(k: String): Int =
        ((this[k] ?: throw IllegalArgumentException("Profilde '$k' eksik")) as Number).toInt()

    private fun Map<String, Any?>.s(k: String): String =
        (this[k] ?: throw IllegalArgumentException("Profilde '$k' eksik")) as String

    private fun castOf(m: Map<String, Any?>): FloatArray {
        val l = m["cast"] as List<Any?>
        return FloatArray(3) { (l[it] as Number).toFloat() }
    }

    private fun merged(defaults: Map<String, Any?>, over: Map<String, Any?>?): Map<String, Any?> {
        val m = LinkedHashMap(defaults)
        if (over != null) m.putAll(over)
        return m
    }

    private fun photoLook(m: Map<String, Any?>) = Look(
        blur = m.f("blur"), sharpen = m.f("sharpen"),
        noiseLuma = m.f("noise_luma"), noiseChroma = m.f("noise_chroma"), banding = m.f("banding"),
        contrast = m.f("contrast"), saturation = m.f("saturation"), gamma = m.f("gamma"),
        cast = castOf(m), vignette = m.f("vignette"), bloom = m.f("bloom"),
        bayer = m["bayer"] as Boolean, hotPixels = m.i("hot_pixels"), chromaBleed = 0,
        jpegQuality = m.i("jpeg_quality")
    )

    private fun videoLook(m: Map<String, Any?>): Look {
        val noise = m.f("noise")
        return Look(
            blur = m.f("blur"), sharpen = m.f("sharpen"),
            noiseLuma = noise * 0.58f, noiseChroma = noise * 0.3f, banding = 0f,
            contrast = m.f("contrast"), saturation = m.f("saturation"), gamma = m.f("gamma"),
            cast = castOf(m), vignette = m.f("vignette"), bloom = 0f,
            bayer = false, hotPixels = 0, chromaBleed = m.i("chroma_bleed"), jpegQuality = 0
        )
    }

    private fun parseDevice(d: Map<String, Any?>, dPhoto: Map<String, Any?>, dVideo: Map<String, Any?>): Device {
        val photo = d["photo"] as Map<String, Any?>
        val photoLook = photoLook(merged(dPhoto, photo["look"] as Map<String, Any?>?))
        val video = d["video"] as Map<String, Any?>?
        var modes: List<VideoMode> = emptyList()
        var defaultMode = 0
        var vLook: Look? = null
        var prescale: IntArray? = null
        if (video != null) {
            modes = (video["modes"] as List<Any?>).map { raw ->
                val m = raw as Map<String, Any?>
                val a = m["audio"] as Map<String, Any?>
                VideoMode(
                    label = m.s("label"), width = m.i("width"), height = m.i("height"), fps = m.i("fps"),
                    codec = m.s("codec"), container = m.s("container"), videoKbps = m.i("video_kbps"),
                    audio = AudioSpec(a.i("rate"), a.i("channels"), a.i("kbps"), (a["pcm"] as Boolean?) ?: false)
                )
            }
            defaultMode = video.i("default")
            val merged = merged(dVideo, video["look"] as Map<String, Any?>?)
            vLook = videoLook(merged)
            val ps = merged["prescale"] as List<Any?>?
            if (ps != null) prescale = intArrayOf((ps[0] as Number).toInt(), (ps[1] as Number).toInt())
        }
        return Device(
            id = d.s("id"), name = d.s("name"), category = d.s("category"), year = d.i("year"),
            specSource = d.s("spec_source"), lookSource = d.s("look_source"),
            photoWidth = photo.i("width"), photoHeight = photo.i("height"), photoLook = photoLook,
            modes = modes, defaultMode = defaultMode, videoLook = vLook, videoPrescale = prescale
        )
    }
}
