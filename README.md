# Eski Kamera

Android uygulaması. Listeden eski bir cihaz (tuşlu telefon, eski dijital kamera, kamkorder) seçilir;
fotoğraf ve video (ses dahil) o cihazda çekilmiş gibi kaydedilir.

> ⬇️ **İndir:** [Android (APK)](https://github.com/avsessisozx/eski-kamera/releases/latest)

## Kurulum
1. Yukarıdaki bağlantıdan `EskiKamera-<sürüm>.apk` dosyasını telefonuna indir.
2. Dosyaya dokun. Telefon "bilinmeyen kaynaktan yükleme" izni isterse ver, sonra **Yükle**.

## Proje yapısı
- `profiles/devices.json`: cihaz listesi ve özellikleri (çözünürlük, kare hızı, codec, bit hızı, görünüm ayarları)
- `android/`: uygulama (Kotlin, CameraX, MediaCodec)
- Sürüm notları: [RELEASE_NOTES.md](RELEASE_NOTES.md)
