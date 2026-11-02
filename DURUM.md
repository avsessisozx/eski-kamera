# Eski Kamera - DURUM (gizli durum dosyası, durum dalında)

Sürüm: 0.3.0 — Tarih: 2026-11-03 — Repo: avsessisozx/eski-kamera (public) — Sadece Android. Release: v0.3.0 (CI yeşil: build, 11 emülatör testi, release).

## Yapıldı (0.3.0)
- 33 cihaz (10 yeni: PXL-2000, Connectix QuickCam, QuickTake 100, DSi, 3DS, PS Eye (30 fps), Kidizoom x2, l'Espion, FLIR One termal). Yerel çözünürlük + piksel büyütme, seviye/palet/çerçeve (FramePipeline, LookProcessor).
- Arayüz: cihaz simgeleri (kodla çizilmiş), cihaz ve kalite seçicide önizleme, son çekim küçük resmi, ViewerActivity (yakınlaştırma, oynatma, kaydırma, paylaş, sil, galeride aç, bilgi, ızgara, orijinal/işlenmiş).
- Kamera: ön/arka, fener, pinch zoom, dokunarak odak, lens seçimi.
- Ekran bilgisi: tarih damgası, kamkorder OSD, GPS, pusula + eğim, ses çubuğu; CSV sensör kaydı; orijinal fotoğrafı kaydetme.
- Ekstra modlar: zaman atlamalı, yavaş çekim efekti, döngülü kayıt (parça döndürme + eski silme), hareketle çekim, sonar (deneysel, radar göstergesi, ses testi). Her modun uygulama içi açıklaması var (Help.kt, SettingsDialog.kt).

## Doğrulandı
- JVM birim testleri: profiller (33), Game Boy 4 renk, termal palet, paralel işleme, MotionDetector/MotionTrigger, sonar DSP simülasyonu (yerel).
- Emülatör (API 30): derleme, foto/video, PXL-2000 zaman atlamalı kayıt (120x90 → 480x360), görüntüleyici açılışı, tüm 11 test geçti.

## Doğrulanmadı (gerçek telefonda / emülatörde yok)
- Sonar (ses çıkışı/mikrofon), GPS, pusula/eğim, fener, ön kamera, lens seçimi, dokunarak odak, pinch zoom, hareketle çekim gerçek sahnede, döngülü kayıt parça geçişi (ses çakışması), CSV içeriği, Mi 10T Pro performansı/ısınma.
- Cihaz simgelerinin ve önizlemelerin görsel kalitesi (sadece derleme/çalışma doğrulandı).

## Yapılmadı (bilerek)
- Çift kamera (PIP), flaş efekti, PS Eye 120 fps, AVI/MJPEG (MP4 H.264 kullanılır).

## Sıradaki
- Kullanıcı geri bildirimi (hata raporlarıyla) → telefona özgü düzeltmeler; görünüm ince ayarı.

## Bilinen hatalar
- Yok (bilinen). Hata olursa uygulamadan rapor kopyalanır.
