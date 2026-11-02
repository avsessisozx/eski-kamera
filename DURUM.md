# Eski Kamera - DURUM (gizli durum dosyası, durum dalında)

Sürüm: 0.2.0 — Tarih: 2026-11-03 — Repo: avsessisozx/eski-kamera (public) — Sadece Android (Windows istenmedi).

## Yapıldı
- 23 cihazlık liste: profiles/devices.json (tuşlu telefonlar, kompaktlar, VHS/Hi8/MiniDV + ikonikler: Casio QV-10, Game Boy Camera, Sony Mavica, Sharp J-SH04, Nokia 7650, RAZR V3, Flip Ultra, iPhone 3GS). Dönem grupları kodda (Device.era).
- Canlı kayıt (LiveRecorder.kt): kareler ImageAnalysis ile işlenip kayıt sırasında kodlanır, ses AudioRecord ile canlı işlenir; durdurunca dosya ~0.1-2 sn'de hazır. Dikey videolar yönlendirme bilgisiyle kaydedilir (kırpma yok).
- Yeni arayüz: tam ekran canlı işlenmiş önizleme, cihaz/mod çipleri, dönem gruplu cihaz seçici, FOTO/VİDEO seçici, deklanşör, ⋮ menü.
- Foto: kırp, boyutlandır, bayer, bulanıklık, ton/renk, bloom, keskinleştirme, vinyet, gürültü, JPEG kalite (LookProcessor.kt).
- Video: MediaCodec ile çöz → cihaz çözünürlüğü/fps/bitrate + görünüm → kodla (H.263/MPEG-4 varsa, yoksa H.264); ses → telefon bandı + örnekleme (AMR-NB varsa, yoksa AAC); 3GP/MP4 birleştirme (VideoTranscoder.kt).
- Ekran: cihaz/mod seçimi, kamerayla foto/video, galeriden dönüştürme, ilerleme, "Hata raporunu kopyala".
- CI (GitHub Actions): birim testleri, APK, emülatör testleri, main'e push'ta otomatik release (versionName'e göre).
- Sabit imza: android/eski-kamera.keystore (repo public olduğu için anahtar açık; kişisel kullanım).

## Doğrulandı (emülatör, API 30, CI)
- Profil okuma, görüntü/ses motoru (JVM birim testleri)
- Video dönüştürme: nokia_6230 (H.263 + AMR-NB, 3GP), n95 (MPEG-4 yoksa H.264 yedeği), vhs, döndürülmüş kaynak; boyut/süre/ses/siyah olmayan kare kontrolü
- Foto dönüştürme 640x480
- Arayüz: aç, emülatör kamerasıyla foto çek + canlı video çek → Galeri'ye kayıt (durdurma ~0.1 sn)
- LiveRecorder: 4 cihaz (nokia_6230 H.263+AMR, genel_cif, vhs, iphone_3gs) sentetik karelerle; boyut/süre/yön/durdurma süresi

## Doğrulanmadı (gerçek telefonda test edilmedi)
- Mi 10T Pro'da canlı kayıt/önizleme hızı, kodlayıcı davranışı (0.1.1'de galeri/kayıt sonrası dönüştürme telefonda çalıştı: H.263+AMR 3GP, Hi8 H.264), performans (özellikle Kodak 10 MP foto), ısınma
- Gerçek kameradan 4K/yüksek çözünürlüklü video girişi, HDR/10-bit kaynaklar
- Görünüm ayarlarının gerçek cihaz örneklerine benzerliği

## Eksik / sıradaki
- 640x480@30fps modlarda gerçek telefonda kare atlama olup olmadığı (emülatörde doğrulanmadı)
- Cihaz görünüm ayarlarını gerçek örnek dosyalarla ince ayar; ayar ekranı (kullanıcı kaydırıcıları)
- MJPEG/AVI çıktısı Android'de yok (dijital kamera video modları H.264 MP4 olur)
- Daha fazla cihaz (Nokia 6230i, RAZR, Cyber-shot vb.), kamkorder özellikleri araştırılmadı (tahmini)
- Tarih damgası seçeneği

## Bilinen hatalar
- Yok (bilinen). Hata olursa uygulamadan rapor kopyalanır.
