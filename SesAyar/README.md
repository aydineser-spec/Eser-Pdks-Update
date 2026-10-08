# Ses Ayar

Android için küçük bir bas / tiz / ses yükseltme uygulaması. Marshall (veya başka kulaklık)
uygulamasındaki ayarlara dokunmaz; telefonun ses efekt katmanında çalışır.

- **Bas, Tiz, Ses yükseltme** kaydırıcıları, hazır ayarlar
- **Otomatik profil:** Çıkış cihazının adında "Minor" geçiyorsa Minor IV profili, değilse "Diğer cihaz" profili uygulanır
- Telefon açılınca (Ses Ayar açık bırakıldıysa) kendiliğinden başlar

## Telefona kurulum (bilgisayar gerekmez)

1. GitHub'da repoda **Actions → "Ses Ayar APK"** sekmesine gir. Son çalışma yeşilse **Releases → "Ses Ayar (son sürüm)"** altındaki `SesAyar.apk` dosyasını indir
   (veya çalışmanın altındaki **Artifacts → SesAyar-apk**).
2. APK'ya dokun, "bilinmeyen kaynaklardan yükleme" iznini ver ve kur.
3. Uygulamayı aç, **Ses Ayar çalışsın**'ı aç, bildirim ve Bluetooth izinlerini ver.
4. Müzik uygulamasını başlat (ya da durdurup tekrar oynat).

## Sınırlar

- Efekt, ses oturumunu bildiren çalarlarda (Spotify, YouTube Music, Samsung Music…) işler.
  Bazı uygulamalar (ör. tarayıcı videoları) bildirmez, onlarda etki olmayabilir.
- Telefondaki Dolby Atmos / Ekolayzer / Adapt sound ve Marshall EQ'su ile üst üste biner; birini kapat.
- Ses yükseltme en fazla +10 dB. Yüksek ses kulağa zarar verir.

## Derleme (isteğe bağlı)

    cd SesAyar && ./gradlew assembleDebug
