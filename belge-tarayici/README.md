# Eser Lens (Android belge tarayıcı)

vFlat tarzı sade belge tarayıcı. Yapay zekâ yok; her işlem öngörülebilir
ve sayfayı bozmaz.

## Nasıl kullanılır

1. **Uygulamayı aç:** Kamera kendiliğinden açılır. Belgeyi çerçeveye al;
   kenarları bulunca kendisi çeker. İstersen deklanşöre kendin de basabilirsin.
   Galerideki bir fotoğraf için kamera ekranındaki resim simgesine bas.
2. **Sayfaları kontrol et:** Her sayfanın altında kendi düğmeleri var:
   - **Kırp:** Yeşil köşeleri belgenin köşelerine sürükle.
   - **Döndür:** Sayfayı 90° çevirir.
   - **İkiye Böl:** Yalnızca geniş (iki sayfalık kitap) çekimlerde görünür.
     Sayfayı ortadaki cilt çizgisinden ikiye ayırır.
   - **Sil:** Sormadan silmez.
3. **Görünüm seç (alt sıra):** Orijinal / Net Belge / Siyah-Beyaz.
   Varsayılan: **Net Belge**. Kâğıt beyazlar, gölge gider, silik yazı korunur.
4. **Kaydet:**
   - **+ Sayfa:** Sona yeni sayfa ekler.
   - **PDF Kaydet:** İndirilenler › EserLens klasörüne kaydeder.
   - **Paylaş:** WhatsApp, e-posta vb. ile gönderir.

Üstteki **Yeni** yeni bir tarama başlatır. Üç nokta menüsünde
**Yazıyı çıkar** (OCR ve çeviri) ile **Resim olarak kaydet** (JPG) bulunur.

## Kurulabilir APK'yı nereden indiririm?

APK, GitHub üzerinde otomatik olarak derlenir. `belge-tarayici/` klasöründe
bir değişiklik olunca `.github/workflows/belge-tarayici-apk.yml` iş akışı çalışır ve:

1. **Actions** sekmesindeki ilgili çalışmanın altında `EserLens-APK`
   adlı çıktıyı (artifact) oluşturur,
2. **Releases** bölümündeki `eser-lens` sürüm sayfasını her seferinde
   sıfırdan açar. Başlıkta sürüm ve tarih yazar (ör. "Eser Lens 1.0.47 (17.11.2026)").
   Telefona kurulacak dosya `EserLens.apk`.

Sürüm sayfası: https://github.com/aydineser-spec/Eser-Pdks-Update/releases/tag/eser-lens

Telefonda: APK'yı indir → aç → "bilinmeyen kaynaklardan kuruluma izin ver" →
kur. İmza sabit olduğu için eski sürümü silmeden üstüne kurulur.

> Not: Kamera tarayıcı (Google ML Kit) ilk açılışta Google Play Hizmetleri
> üzerinden indirilir. Bunun için telefonda Google Play Hizmetleri ve internet
> gerekir.

## Yerelde derlemek (Android Studio)

`belge-tarayici/` klasörünü Android Studio ile aç, sonra **Run** düğmesine bas
ya da `./gradlew assembleDebug` komutunu çalıştır. Çıktı:
`app/build/outputs/apk/debug/app-debug.apk`.
