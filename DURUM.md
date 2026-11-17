# Eski Kamera - DURUM (gizli durum dosyası, durum dalında)

Sürüm: 0.4.0 — Tarih: 2026-11-17 — Repo: avsessisozx/eski-kamera (public) — Sadece Android.
Devam noktası: 0.4.0 yayınlandı (CI yeşil). Kullanıcı telefonda test edip hata raporu yapıştıracak. Sonra: çift kamera (0.4.1), görünüm ince ayarı (kullanıcı "şu cihaz sahte duruyor" deyince).

## 0.4.0'da yapılanlar
- Pencereler içeriğe göre boyutlanıyor (en çok %80 yükseklik, %88 genişlik); kısa menüler altta (Ui.dialog anchorBottom).
- Mod etiketi gerçek çıktıyı gösteriyor (VideoMode.mp4Label, smoothExport etiketi MP4).
- Video kalitesi ayarı (Ayarlar): normal x3 / yüksek x6 (varsayılan) / en yüksek x10 bit hızı.
- Temporal.kt: hareket izi (kapalı/hafif/belirgin) ve pozlama dalgalanması; önizleme, canlı kayıt ve galeri dönüştürmede (FramePipeline.process temporal parametresi).
- rgbToYuv420 tamsayı matematiğine geçti.
- Skin.kt: cihaz ailesine göre (icon) çerçeve, vurgu rengi, göstergeler (tuşlu: sinyal/pil, flip, dijital: odak köşeleri, kamkorder: REC/STBY köşeleri, oyun: LCD satırları, webcam: LED, termal: renk ölçeği). Ui.accent aileye göre değişiyor.
- Flaş: fener açıkken çekilen fotoğrafa PhotoProcessor.flashLook.
- PS Eye: 320x240 120fps modu; Camera2Interop ile AE hedef fps aralığı; telefon desteklemezse mesaj.

## Doğrulandı (CI)
- Derleme, birim testleri (kalite/etiket, Temporal, tamsayı YUV, PS Eye modu) ve emülatör testleri geçti.

## Doğrulanmadı (gerçek telefonda)
- Yeni temaların görünümü; pencerelerin dar ekranda boyutu.
- Hareket izi ve pozlama dalgalanmasının gerçekçi görünüp görünmediği (güç ayarı tahmini).
- Flaş görünümü.
- 120 fps: telefon desteği ve işlem hızı (kare düşebilir). Emülatörde denenemez.
- Gerçek telefonda kayıt hızı (büyük izleme boyutu + yeni etkiler).
- Önceki sürümden kalanlar: sonar, GPS, pusula, fener, ön kamera, lens, odak, hareketle çekim, döngülü kayıt, CSV.

## Yapılmadı (söz verilmişti)
- Çift kamera (ön+arka birlikte): 0.4.0'a girmedi; CameraX eşzamanlı kamera + iki akışın birleştirilmesi ayrıca yapılmalı, desteklemeyen telefonda net mesaj verilecek.

## Bilinen hatalar
- Yok (bilinen). test/baseline-0.3.0 dalı silinebilir.
