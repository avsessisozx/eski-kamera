package app.eskikamera

/** Uygulama içi açıklama metinleri (sade Türkçe). */
object Help {
    enum class Mode(val label: String, val title: String, val text: String) {
        TIMELAPSE("Zaman atlamalı", "Zaman atlamalı video",
            "Telefon belli aralıklarla bir kare çeker ve bunları hızlı akan bir video yapar (bulutların akışı, yol gibi).\n" +
                "• Telefonu sabit bir yere koy.\n• Aralığı Ayarlar'dan seç (1-30 sn).\n• Ses kaydedilmez.\n• Düğmeye bir kez basınca başlar, tekrar basınca biter."),
        SLOWMO("Yavaş çekim", "Yavaş çekim efekti",
            "Videoyu 2 veya 4 kat yavaşlatılmış kaydeder.\n• Gerçek yüksek kare hızı DEĞİL: kareler yavaşlayınca biraz kesik görünür.\n• Ses kaydedilmez.\n• Katı Ayarlar'dan seç."),
        LOOP("Döngülü kayıt", "Döngülü kayıt (araç/motor kamerası)",
            "Sürekli kaydeder ve kaydı parçalara böler. Sadece son birkaç parça saklanır, eskileri silinir; yerin dolmaz.\n• Parça süresi ve saklanacak parça sayısı Ayarlar'da.\n• Durdurunca son parça da kaydedilir."),
        MOTION("Hareketle çekim", "Hareketle çekim",
            "Kameranın gördüğü görüntüde hareket algılar. Hareket olunca fotoğraf çeker ya da video başlatır, hareket durunca videoyu bitirir.\n" +
                "• Telefonu sabitle, aksi halde sürekli hareket sanar.\n• Hassasiyeti ve eylemi (foto/video) Ayarlar'dan seç.\n• Düğmeye basınca 'nöbette' olur, tekrar basınca durur."),
        SONAR("Sonar (deneysel)", "Sonar (deneysel)",
            "Hoparlörden insanın duymadığı yüksek frekanslı (18-21 kHz) ses gönderir, mikrofonla yansımasını dinler. Seçtiğin mesafe aralığında hareket olunca foto çeker ya da video başlatır.\n" +
                "• Menzil kısa: yaklaşık 0.3 - 2 metre. Gerçek görüntü vermez, sadece 'bu mesafede hareket var' der.\n" +
                "• Sessiz bir odada daha iyi çalışır. Medya sesini yükselt.\n• Bazı telefonlar bu frekansı çıkaramaz. Ayarlar > Sonar ses testi ile kontrol et.\n" +
                "• Sonar videosu sessiz kaydedilir (mikrofon sonar için kullanılıyor).")
    }

    const val SETTINGS_DATE = "Fotoğraf ve videonun köşesine eski kameralardaki gibi turuncu tarih yazısı ekler."
    const val SETTINGS_OSD = "Kamkorder ekranı: videoda 'REC', pil seviyesi (telefonunun gerçek pili), kayıt sayacı ve tarih-saat görünür. Otomatik: sadece kamkorder cihazlarında açık."
    const val SETTINGS_GPS = "Görüntüde hız (km/sa), rakım ve konum yazar. Konum izni ister. Açık havada daha doğru çalışır."
    const val SETTINGS_COMPASS = "Görüntüde pusula yönü ve eğim çizgisi (su terazisi) gösterir. Telefonun pusula sensörü yoksa çalışmaz."
    const val SETTINGS_MIC = "Videoda ses seviyesi çubuğu gösterir (kayıt sırasında)."
    const val SETTINGS_ORIGINAL = "Foto çekince, işlenmemiş orijinali de ayrı klasöre kaydeder. Görüntüleyicide 'Orijinal' düğmesiyle önce/sonra karşılaştırabilirsin."
    const val SETTINGS_CSV = "Video kaydederken hız, rakım, konum, yön ve eğim verisini ayrıca bir CSV dosyasına yazar (Documents/EskiKamera)."
    const val SETTINGS_TIMELAPSE = "Zaman atlamalı: iki kare arası süre."
    const val SETTINGS_SLOW = "Yavaş çekim: videonun kaç kat yavaşlatılacağı."
    const val SETTINGS_LOOP = "Döngülü kayıt: her parçanın süresi ve saklanacak parça sayısı."
    const val SETTINGS_MOTION = "Hareketle çekim: hassasiyet yüksekse küçük hareketlerde de tetiklenir. Sessizlik süresi: hareket bitince videonun ne kadar sonra bitirileceği."
    const val SETTINGS_SONAR = "Sonar: hangi mesafe aralığındaki hareket algılansın, ne kadar hassas olsun ve hareket olunca ne yapılsın. 'Ses testi' telefonun bu özelliği destekleyip desteklemediğini söyler."
}
