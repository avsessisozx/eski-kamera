# Eski Kamera - DURUM (gizli durum dosyası, durum dalında)

Sürüm: 0.3.1 — Tarih: 2026-11-17 — Repo: avsessisozx/eski-kamera (public) — Sadece Android.
Devam noktası: 0.3.1 yayınlandıktan sonra kullanıcı telefonda test edip hata raporu yapıştıracak; ondan sonra 0.4.0 (cihaz ailesine özel arayüz) yapılacak.

## 0.3.1'de yapılanlar
- Izgara sorunu: önizleme küçük kareleri filtresiz büyütüyordu ve çıktı dosyaları 128x96 gibi gerçek cihaz boyutundaydı (telefonda ~20 kat büyüyüp kare kare görünüyordu). Artık varsayılan "izleme için yumuşak büyüt" (Ayarlar > Video çıktısı): cihaz çözünürlüğünde işlenir, Mitchell bicubic ile >=480 yüksekliğe büyütülür, H.264/MP4 olarak kodlanır. Kapatılırsa eski davranış (gerçek boyut, 3GP). Piksel sanatı cihazları (levels/palette) ve yerel çözünürlüklü cihazlar (PXL-2000) etkilenmez.
- Renk kanalı ayrıca yumuşatılıyor (chromaSoft = blur*0.9); VHS gibi renk taşması olan cihazlarda atlanır.
- Prescale'li cihazlar (VHS, Hi8...) artık prescale çözünürlüğünde (320x240) işlenip yumuşak büyütülüyor (4 kat az piksel).
- Hız: ses ve görüntü paralel; kodek döngüsündeki 10 ms bekleme kaldırıldı; gürültü tablo tabanlı; keskinleştirme tek (parlaklık) bulanıklığıyla; renk taşması kayan toplam; 2 kanallı renk yumuşatma. Emülatörde VHS 3 sn'lik örnek: 7085 ms -> 4837 ms. İşlem aşama süreleri artık hata raporunda (Görünüm aşamaları, Süre:).
- Cihaz listesi açılışta seçili cihaza kayıyor.
- Menüler/ayarlar/pencereler tek koyu stile (Ui.kt) geçti; menü, ekstra modlar, ayarlar, yardım, silme ve bilgi pencereleri.
- Hata raporu: kaynak video süresi ve ses akışı, silme ve lens/kamera değişimi, aşama süreleri.
- Yanlış alarm (düzeltme gerekmedi): rapordaki "ses yok" kaynak videonun sessiz olmasındandı; kısa çıktılar kısa kaynaktan; "Kamera hazır" iki kez lens düğmesinden; görüntüleyici sayısı silmeden.

## Doğrulandı (CI)
- Birim testleri (smoothExport, resizeSmooth, FramePipeline boyutları, chroma testleri) ve emülatör (API 30) testleri geçti: dönüştürme, yumuşak dışa aktarma (MP4, ses var, boyut doğru), cihaz listesi seçiliye kayıyor.

## Doğrulanmadı (gerçek telefonda test lazım)
- Yeni görünümün gerçek cihaz videolarına ne kadar benzediği (gözle karar verilecek; referans video yok).
- Gerçek telefonda hız (emülatör yavaş ve göreli), 704x576 gibi büyük izleme boyutunda canlı kayıtta kare düşmesi / ısınma.
- Yeni menülerin görünümü (dar ekranda taşma vb.).
- Önceki sürümden kalanlar: sonar, GPS, pusula, fener, ön kamera, lens seçimi, odak, hareketle çekim, döngülü kayıt geçişi, CSV.

## Sıradaki (0.4.0)
- Cihaz ailesine özel arayüz: tuşlu telefon, flip, dijital kamera, VHS/kamkorder, oyun cihazları, termal; ortak şablon + aileye özel çerçeve/renk/ekran göstergesi.
- Görünüm ince ayarı (kullanıcı geri bildirimine göre), gerekirse zamansal gürültü/ghosting.
- rgbToYuv420 ve resizeSmooth'un daha da hızlandırılması (tam sayı aritmetik).

## Bilinen hatalar
- Yok (bilinen). test/baseline-0.3.0 dalı CI ölçümü için kaldı, silinebilir.
