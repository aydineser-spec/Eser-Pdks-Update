# Eser Lens (Android belge tarayıcı)

vFlat tarzı sade belge tarayıcı. Kendi kamerasıyla telefonun tam
çözünürlüğünde çeker, kamerada belgeyi canlı olarak mavi alanla gösterir,
kâğıdın kenarlarını bulup sayfayı düzleştirir. Yapay zekâ yok.

**Otomatik iyileştirme** (menüden kapatılabilir), kullanıcının vFlat çıktısı
ölçülerek ayarlandı:
- Kâğıt belge: gölge silinir, kâğıt beyazlar, yazı koyulaşır; mühür rengi korunur.
- Kutu, dergi, fotoğraf: siyah koyu, beyaz beyaz, yazı net, renk canlı.
  İnce kumlanma büyütülmez.

## Nasıl kullanılır

1. **Uygulamayı aç:** Kamera kendiliğinden açılır. Belgeyi çerçeveye al ve
   büyük beyaz düğmeyle çek; art arda birden çok sayfa çekebilirsin. Yakın
   çekimde ekrana dokunarak netle. Bitince **Bitti**'ye bas. Kenarlar otomatik
   bulunur ve sayfa düzleştirilir; emin olmadığı fotoğrafı olduğu gibi bırakır.
   Galeriden fotoğraf eklemek ya da Google'ın tarayıcısını kullanmak için
   sağ üstteki menüyü aç.
2. **Sayfaları kontrol et:** Her sayfanın altında kendi düğmeleri var:
   - **Kırp:** Orijinal fotoğraf açılır, yeşil köşeler otomatik bulunan yerde
     başlar; sürükleyip **Tamam**'a bas. Kesilen kısım kaybolmaz, tekrar
     kırpabilirsin.
   - **Döndür:** Sayfayı 90° çevirir.
   - **İkiye Böl:** Yalnızca geniş (iki sayfalık kitap) çekimlerde görünür.
     Sayfayı ortadaki cilt çizgisinden ikiye ayırır.
   - **Sil:** Sormadan silmez.
3. **Alt sıra:**
   - **+ Sayfa:** Kamerayı açar, yeni sayfaları sona ekler.
   - **Kaydet:** PDF (İndirilenler › EserLens), JPG ya da PNG (Galeri › EserLens).
   - **Paylaş:** PDF'i WhatsApp, e-posta vb. ile gönderir.

Üstteki **Yeni** yeni bir tarama başlatır. Üç nokta menüsünde
**Yazıyı çıkar** (OCR ve çeviri), **Galeriden ekle** ve
**Google tarayıcı ile tara** bulunur.

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
