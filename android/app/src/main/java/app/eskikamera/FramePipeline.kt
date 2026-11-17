package app.eskikamera

/** Bir kamera karesini seçilen video moduna ve cihazın görünümüne dönüştürür (önizleme ve kayıt ortak kullanır). */
object FramePipeline {
    private fun scaled(look: Look, f: Float): Look =
        if (f >= 0.999f) look else look.copy(
            blur = maxOf(0.3f, look.blur * f),
            chromaBleed = if (look.chromaBleed > 0) maxOf(1, Math.round(look.chromaBleed * f)) else 0,
            chromaSoft = look.chromaSoft * f
        )

    fun process(frame: YuvFrame, mode: VideoMode, look: Look, prescale: IntArray?, seed: Long): IntArray {
        if (prescale != null) {
            // cihazın gerçek çözünürlüğünde (örn. VHS 320x240) işle, sonra yumuşak büyüt: hem daha hızlı hem eski analog görüntüye uygun kaba gürültü
            val sw = prescale[0]
            val sh = prescale[1]
            val rgb0 = FrameSampler.toRgb(frame, 0, sw, sh, null)
            val proc0 = LookProcessor.processFrame(rgb0, sw, sh, scaled(look, sw.toFloat() / mode.procW), seed)
            return FrameSampler.resizeSmooth(proc0, sw, sh, mode.width, mode.height)
        }
        val nw = mode.procW
        val nh = mode.procH
        val rgb = FrameSampler.toRgb(frame, 0, nw, nh, prescale)
        val proc = LookProcessor.processFrame(rgb, nw, nh, look, seed)
        return if (mode.smooth) FrameSampler.resizeSmooth(proc, nw, nh, mode.width, mode.height)
        else FrameSampler.upscaleNearest(proc, nw, nh, mode.width, mode.height)
    }
}
