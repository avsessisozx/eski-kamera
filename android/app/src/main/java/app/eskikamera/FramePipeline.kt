package app.eskikamera

/** Bir kamera karesini seçilen video moduna ve cihazın görünümüne dönüştürür (önizleme ve kayıt ortak kullanır). */
object FramePipeline {
    fun process(frame: YuvFrame, mode: VideoMode, look: Look, prescale: IntArray?, seed: Long): IntArray {
        val nw = mode.procW
        val nh = mode.procH
        val rgb = FrameSampler.toRgb(frame, 0, nw, nh, prescale)
        val proc = LookProcessor.processFrame(rgb, nw, nh, look, seed)
        return FrameSampler.upscaleNearest(proc, nw, nh, mode.width, mode.height)
    }
}
