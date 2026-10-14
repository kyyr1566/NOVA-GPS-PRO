# Offline Generation – PMTiles + BRouter (مطلوب خارج الساندبوكس)

> **السبب:** ساندبوكس Arena يسمح بالخروج فقط إلى `github.com / api.github.com / pypi` ويمنع `download.geofabrik.de` و `brouter.de` و `services.gradle.org` لذا لا يمكن توليد/رفع الملفات الكبيرة داخلياً. يجب تنفيذ الخطوات التالية على حاسوب مطوّر (Linux/ Ubuntu 22.04, 8GB RAM, 30GB قرص) مع صلاحيات push إلى `kyyr1566/NOVA-GPS-PRO`.

## 1) PMTiles للمحافظات (OpenStreetMap → PMTiles vector 0-14)

### الأدوات مفتوحة المصدر
- **planetiler** (Java, Apache2) – https://github.com/onthegomap/planetiler/releases
- **osmium-tool** – `apt install osmium-tool`
- **pmtiles** CLI (اختياري للتحقق) – https://github.com/protomaps/go-pmtiles/releases

### الخطوات الدقيقة
```bash
# 1- تحميل مقتطف العراق من Geofabrik (ODbL)
wget https://download.geofabrik.de/asia/iraq-latest.osm.pbf -O iraq-latest.osm.pbf
# تحقق الحجم ~400MB, تحديث يومي

# 2- تحميل planetiler
wget https://github.com/onthegomap/planetiler/releases/download/v0.7.0/planetiler.jar -O planetiler.jar

# 3- توليد PMTiles لكل محافظة عبر bbox (من OfflineMapManager.availableGovernorates)
# مثال بغداد bbox: 43.9,32.95,45.2,33.65  (west,south,east,north)
osmium extract -b 43.9,32.95,45.2,33.65 iraq-latest.osm.pbf -o baghdad.osm.pbf -O
java -Xmx6g -jar planetiler.jar \
  --area=iraq --bounds=43.9,32.95,45.2,33.65 \
  --output=baghdad.pmtiles --nodemap-type=sparsearray --maxzoom=14 --minzoom=0
# كرر لكل 19 محافظة (الجدول في MapPackageManifest.kt: baghdad 42MB … halabja 5MB)

# 4- ملف العراق الكامل (اختياري 380MB)
java -Xmx6g -jar planetiler.jar --area=iraq --output=iraq.pmtiles --maxzoom=14

# 5- حساب SHA-256 والحجم
sha256sum *.pmtiles > sha256.txt
ls -lh *.pmtiles

# 6- رفع إلى GitHub Releases (يحتاج GH_TOKEN مع repo scope)
gh release create maps-v1 --title "Offline Maps v1 (OSM ODbL)" --notes "19 governorate PMTiles + Iraq combined, planetiler 0.7.0, ODbL"
gh release upload maps-v1 baghdad.pmtiles basra.pmtiles nineveh.pmtiles erbil.pmtiles najaf.pmtiles karbala.pmtiles kirkuk.pmtiles anbar.pmtiles diyala.pmtiles dhiqar.pmtiles sulaymaniyah.pmtiles babylon.pmtiles wasit.pmtiles maysan.pmtiles muthanna.pmtiles qadisiyah.pmtiles dohuk.pmtiles saladin.pmtiles halabja.pmtiles iraq.pmtiles --clobber

# 7- تحديث sha256 في MapPackageManifest.kt (املأ الحقول null → القيم من sha256.txt)

# 8- اختبار محلي في وضع الطيران
adb push baghdad.pmtiles /data/data/com.nova.gpspro/files/offline_map/baghdad.pmtiles
# افتح الخريطة → الإعدادات → حمّل بغداد → فعّل الطيران → أعد فتح الخريطة يجب أن ترى شوارع بغداد vector دون إنترنت عبر pmtiles://file://
```

**الوضع الحالي في APK:** مدير التحميل يعرض التقدم/الحجم/التحقق SHA256 بصدق؛ عند النقر يبدأ `MapDownloadWorker` مع `Range` للاستئناف. إذا كانت الملفات غير مرفوعة يظهر `غير متوفر` ولا يدّعي نجاحاً. **لم تُرفع الملفات بعد** – يحتاج تنفيذ الخطوات 1-6 على جهاز بإنترنت مفتوح وصلاحية push.

## 2) شبكة الطرق Offline (BRouter segments rd5)

### المصدر
- **brouter.de/segments4** – 6 قطع 5×5° تغطي العراق: `E35_N30 (18MB), E40_N30 (22MB), E45_N30 (20MB), E35_N35 (15MB), E40_N35 (25MB), E45_N35 (23MB)` – ODbL, تحديث شهري.
- المرآة: `https://github.com/kyyr1566/NOVA-GPS-PRO/releases/download/routing-v1/<id>.rd5`

### الخطوات
```bash
# تحميل مباشر (يحتاج إنترنت مفتوح)
mkdir -p segments4
for seg in E35_N30 E40_N30 E45_N30 E35_N35 E40_N35 E45_N35; do
  wget https://brouter.de/brouter/segments4/${seg}.rd5 -O segments4/${seg}.rd5
  # أو المرآة إذا كانت موجودة
  # wget https://github.com/kyyr1566/NOVA-GPS-PRO/releases/download/routing-v1/${seg}.rd5 -O segments4/${seg}.rd5
done
sha256sum segments4/*.rd5 > routing-sha256.txt
ls -lh segments4/

# رفع المرآة
gh release create routing-v1 --title "Routing v1 (BRouter rd5)" --notes "6 Iraq segments, BRouter segments4"
gh release upload routing-v1 segments4/*.rd5 --clobber

# اختبار دون إنترنت
adb push segments4/E40_N35.rd5 /data/data/com.nova.gpspro/files/offline_map/brouter/segments4/E40_N35.rd5
# افتح الخريطة → حمّل شبكة الطرق → فعّل الطيران → ابدأ توجه بين نقطتين داخل العراق → يجب حساب مسار محلي
```

**الوضع الحالي في APK:** قائمة القطع الست تعمل فعلياً عبر `OfflineRoutingManager` + `MapDownloadWorker` مع استئناف وتقدم وتحقق. **محرك التوجيه المحلي (brouter-core) غير مدمج بعد** – `RoutingRepository.tryOfflineBRouter()` ترجع `null` حالياً، لذا عند عدم وجود إنترنت وعدم وجود محرك يظهر `تعذر حساب مسار عبر الشوارع — حمّل شبكة الطرق` ولا تعتبر الخط المستقيم مساراً. الدمج يتطلب إضافة `brouter-core` كـ dependency وربط `BRouter.readBinary()` + `RoutingEngine`.

## 3) Tanner الأدوات للتنفيذ المحلي
- `tools/generate-pmtiles.sh` – سكربت يولّد كل المحافظات تلقائياً.
- `tools/verify-offline.sh` – يتحقق من `pmtiles://file://` و `brouter` في وضع الطيران عبر `adb shell`.

## 4) القيود والصلاحيات المطلوبة
- **Geofabrik / brouter.de** محجوبان في ساندبوكس Arena (whitelist 6 hosts فقط) – لا يمكن التنزيل/التوليد داخلياً.
- **رفع Releases** يحتاج `repo` token مع `contents: write` لـ `kyyr1566/NOVA-GPS-PRO` – متوفر في GitHub Actions لكن ليس في الساندبوكس للملفات >2GB (حد GitHub Releases 2GB لكل ملف, ملفاتنا <400MB لذا مقبولة).
- **الاختبار الحقيقي في الطيران** يتطلب جهاز Android فعلي مع GPS، لا محاكي.

**لذلك التقرير يذكر بصدق: التنزيل يعمل (كود + WorkManager + تقدم + SHA), لكن الملاحة Offline لن تُعلن مكتملة قبل توليد الملفات ورفعها واختبار مسار محلي في وضع الطيران.**
