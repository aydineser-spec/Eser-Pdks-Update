# Belge Tarayıcı (Android)

Fotoğraftan belge tarayan Android uygulaması. Google ML Kit Belge Tarayıcı
motorunu kullanır:

- Belge kenarlarını **otomatik** bulur ve perspektifi düzeltir
- **Gölge, leke ve parmak izlerini temizler**, yazıları netleştirir
- Sonucu **JPEG görüntü** ve **PDF** olarak verir
- Galeriye / İndirilenler'e kaydeder ve paylaşır

## Kurulabilir APK'yı nereden indiririm?

APK, GitHub üzerinde otomatik olarak derlenir. Her `belge-tarayici/`
değişikliğinde `.github/workflows/belge-tarayici-apk.yml` iş akışı çalışır ve:

1. **Actions** sekmesindeki ilgili çalışmanın altında `BelgeTarayici-APK`
   adlı çıktıyı (artifact) oluşturur, ve
2. **Releases** bölümünde `belge-tarayici-latest` etiketiyle `BelgeTarayici.apk`
   dosyasını yayınlar.

Telefonda: APK'yı indir → aç → "bilinmeyen kaynaklardan kuruluma izin ver" →
kur.

> Not: Uygulama ilk açılışta tarayıcı modülünü Google Play Hizmetleri
> üzerinden indirir; bunun için telefonda Google Play Hizmetleri ve internet
> gerekir (neredeyse tüm Android telefonlarda vardır).

## Yerelde derlemek (Android Studio)

`belge-tarayici/` klasörünü Android Studio ile açıp **Run** veya
`./gradlew assembleDebug` çalıştır. Çıktı:
`app/build/outputs/apk/debug/app-debug.apk`.
