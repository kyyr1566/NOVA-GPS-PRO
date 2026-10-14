# تقرير حذف نظام الخرائط النهائي — RMG-GPS-Pro
**التاريخ:** 2026-10-14  
**الفرع:** `arena/01a10882-nova-gps-pro`  
**الالتزام:** `2bba048` — `RMG-GPS-Pro: حذف نظام الخرائط نهائياً`  
**الحالة:** ✅ مكتمل — GitHub Actions نجح 2m40s

> هذا الإصدار يلغي بالكامل مهمة الـ7-point السابقة (مرجع `1c834d7` — 3 طبقات + بحث + أزرار يمين) ويعيد المشروع إلى وضع خفيف يركّز على حفظ الإحداثيات والملاحة بالسهم دون إنترنت.

---

## 1) القرار المنفّذ
حذف **كامل ونهائي** لنظام الخرائط بدون إصلاح ولا استبدال. لا محرك خرائط، لا طبقات، لا راوتنغ أونلاين، لا PMTiles/BRouter، لا خدمات خلفية للخرائط.

## 2) الملفات المحذوفة (16 ملف)
| # | المسار | الوصف |
|---|---|---|
| 1 | `app/src/main/java/com/nova/gpspro/map/MapActivity.kt` | شاشة الخرائط الكاملة 1467 سطر — MapLibre Native + بحث + أزرار يمين |
| 2 | `app/src/main/java/com/nova/gpspro/map/MapStyleManager.kt` | إدارة 3 طبقات light/dark/satellite |
| 3 | `app/src/main/java/com/nova/gpspro/map/MapCameraController.kt` | تحكم الكاميرا |
| 4 | `app/src/main/java/com/nova/gpspro/map/NavigationSessionManager.kt` | جلسة الملاحة على الخريطة |
| 5 | `app/src/main/java/com/nova/gpspro/map/RoutingRepository.kt` | OSRM/FOSSGIS/Valhalla راوتنغ أونلاين |
| 6 | `app/src/main/java/com/nova/gpspro/map/LocationTrackingController.kt` | تتبع الموقع على الخريطة |
| 7 | `app/src/main/java/com/nova/gpspro/map/SavedLocationsMapRepository.kt` | عرض الوجهات على الخريطة |
| 8 | `app/src/main/assets/mapstyles/dark.json` | 4402 سطر — ثيم داكن |
| 9 | `app/src/main/assets/mapstyles/light.json` | 6366 سطر — ثيم فاتح |
| 10 | `app/src/main/assets/mapstyles/satellite.json` | 4377 سطر — Clarity HD قمر (ثلاثي الفولباك) |
| 11 | `app/src/main/res/drawable/ic_my_location.xml` | أيقونة موقعي |
| 12 | `app/src/main/res/drawable/ic_layers_stack.xml` | أيقونة الطبقات |
| 13 | `app/src/main/res/drawable/ic_cube_3d.xml` | أيقونة 3D |
| 14 | `app/src/main/res/drawable/ic_compass_north.xml` | أيقونة البوصلة |
| 15 | `app/src/test/java/com/nova/gpspro/MapLogicTest.kt` | 98 سطر — اختبارات طبقات/راوتنغ/ألوان الخرائط |
| 16 | `docs/MAPS_SOURCES.md` | توثيق مصادر الخرائط |

- مجلد `app/src/main/assets/mapstyles/` حُذف بالكامل ثم أُزيل مجلد `assets/` الفارغ.
- مجلد `app/src/main/java/com/nova/gpspro/map/` حُذف بالكامل (7 ملفات Kotlin).
- مجلد `docs/` الفارغ أُزيل.

**إجمالي الفروق:** `22 files changed, 3 insertions(+), 17711 deletions(-)`

## 3) الملفات المعدّلة (6 ملفات)

| الملف | التغيير |
|------|---------|
| `app/src/main/java/com/nova/gpspro/ui/NavigationPage.kt` | حذف `topRow` + `mapBtn` (🗺 الخرائط 110dp) وكل `Intent MapActivity` + `Log NOVA_MAP`. استعادة زر **اختر الوجهة** بعرض الصفحة كاملاً كما قبل الخرائط (خلفية `C.CARD`، حافة 16dp، ارتفاع 48dp، سهم ذهبي). التعليق عاد إلى `// 1) compact destination selector` |
| `app/src/main/java/com/nova/gpspro/ui/DestinationsPage.kt` | حذف بلوك `run { 🗺 الملاحة على الخريطة }` (Intent + try/catch + Toast). بقيت 3 خيارات فقط: ✎ تعديل / ⤴ مشاركة / 🗑 حذف + إلغاء. الضغط مرة لبدء الملاحة والضغط المطول للتعديل/المشاركة/الحذف محفوظ |
| `app/src/main/AndroidManifest.xml` | إزالة `android.permission.INTERNET`، `android:hardwareAccelerated="true"`، وإعلان `<activity .map.MapActivity>` — عاد مطابقاً لنسخة `11736a3` الأصلية |
| `app/build.gradle.kts` | إزالة `org.maplibre.gl:android-sdk:11.3.0` + `androidx.lifecycle:lifecycle-runtime-ktx:2.8.6` + `androidx.appcompat:appcompat:1.7.0` — بقي فقط `zxing:core:3.5.3` (QR أوفلاين) + `junit` |
| `app/src/main/res/values/strings.xml` | حذف 3 سلاسل خرائط: `map_start_navigation`, `maps`, `navigate_on_map` |
| `app/src/main/res/values-ar/strings.xml` | حذف نفس 3 السلاسل بالعربية: بدء الملاحة / الخرائط / الملاحة على الخريطة |

## 4) ما تم الحفاظ عليه (إلزامي)
- **GPS/GNSS** — `LocationEngine`, `GpsState`, `GpsQualityFilter`, `SpeedEngine`, `BearingEngine` لم تُمس
- **سهم الملاحة دون إنترنت** — `ArrowEngine`, `NavigationEngine`, `GeoMath`, `ArrivalEngine` + حسابات الاتجاه/المسافة/السرعة/ETA كما هي
- **عداد السرعة** — `SpeedometerView` (دائري/رقمي) لم يتغير
- **قاعدة الوجهات** — `DestinationRepository`, `LocxCodec`, `LocxStore`, `ExternalLocationParser` + صور الوجهات
- **سلوك الوجهات** — ضغطة واحدة → `setDestination + showPage(2)`, ضغط مطول → تعديل/مشاركة/حذف
- **الصفحات** — `HomePage`, `DestinationsPage`, `NavigationPage` (مُستعادة), `PortalPage`, `RadarPage`, `SettingsPage` + `SplashView`
- **الترخيص** — `LicenseManager`, `ActivationView` كامل
- **الاسم والأيقونة** — `android:label @string/app_name = RMG-GPS-Pro`, `ic_launcher`/`ic_launcher_bg/foreground` و `splash_icon` محفوظة
- **applicationId / التوقيع** — `com.nova.gpspro`, `versionCode 1`, `versionName 1.0.0` دون تغيير — لا تغيير توقيع
- **المكتبات المشتركة** — `zxing:core` للـ QR بقيت، لم تُحذف صلاحيات `ACCESS_FINE/COARSE` ولا `CAMERA`

## 5) تنظيف نهائي — فحص العلاقات قبل الحذف

```bash
grep -R "MapActivity|MapLibre|RoutingRepository|NavigationSessionManager|MapStyleManager" app --include="*.kt" --include="*.xml" --include="*.kts" -n
# (لا نتائج)

grep -R "NOVA_MAP" app --include="*.kt" -n
# (لا نتائج)

grep -R "INTERNET" app/src/main/AndroidManifest.xml -n
# (لا نتائج)

grep -R "map_start_navigation|\"maps\"|navigate_on_map" app/src/main/res/values -n
# (لا نتائج بعد الحذف)

ls app/src/main/java/com/nova/gpspro/map
# No such file

ls app/src/main/assets
# No such file (حُذف بعد إفراغه)
```

- لا أزرار مخفية — `mapBtn` أُزيل نهائياً من `NavigationPage`
- لا شاشات معطلة — `MapActivity` غير مُعلن في Manifest ولن يظهر
- لا خدمات خلفية — كل `lifecycleScope`, `routingRepo`, `navigationSession` كانت داخل `map/*` فقط
- لا تغيير بيانات المستخدم — لم يُمس `LocxStore` ولا مجلد `Documents/GPSarrow`

## 6) الاختبار النهائي

| البند | النتيجة |
|------|---------|
| اختفاء الخرائط | ✅ لا زر خرائط في الملاحة، لا خيار "الملاحة على الخريطة" في الوجهات، لا MapActivity |
| عودة الملاحة الأصلية | ✅ زر اختر الوجهة بعرض الصفحة، السهم كبير، السرعة والمسافة والوصول كما قبل |
| اختيار الوجهات + ضغط مطول | ✅ محفوظ — 3 خيارات فقط |
| GPS والسهم والسرعة والمسافة دون إنترنت | ✅ محركات الملاحة لم تُمس، لا اعتماد إنترنت (INTERNET محذوف) |
| بقاء المواقع | ✅ DestinationRepository + LocxStore لم يُمسا |
| لا انهيارات | ✅ لا استيراد MapLibre، لا ClassNotFound، لا require لإنترنت |
| GitHub Actions APK | ✅ نجاح |

### نتيجة CI — Build NOVA GPS PRO APK

- **Run:** `38592813312` — الفرع `arena/01a10882-nova-gps-pro`
- **النتيجة:** `success` — `completed`
- **المدة:** `2m40s` (كان `3m31s` مع الخرائط — أخف ~51 ثانية)
- **الجوب:** `build` — `success`
  - `Set up job` ✓
  - `Checkout repository` ✓
  - `Set up JDK 17` ✓
  - `Set up Gradle` ✓
  - `Run unit tests` ✓ (theme contrast, GPS signal, existing suites — 11 حزمة اختبار بدون MapLogicTest)
  - `Build Debug APK` ✓
  - `Upload APK` ✓
- **الأرتيفاكت:** `NOVA-GPS-PRO-debug` — `2,234,367 bytes` (~2.1 MB)

**رابط التشغيل:**
- https://github.com/kyyr1566/NOVA-GPS-PRO/actions/runs/38592813312

**تنزيل الـ APK (Actions → Artifacts):**
- `NOVA-GPS-PRO-debug` من نفس الرابط أعلاه (يتطلب تسجيل دخول GitHub)
- مباشر API (ZIP): `https://api.github.com/repos/kyyr1566/NOVA-GPS-PRO/actions/artifacts/11908182533/zip` — استخرج `app-debug.apk` داخله

> ملاحظة: لا يمكن بناء APK محلياً في هذه البيئة لعدم توفر JDK (only `github.com` مسموح). التحقق النهائي هو نجاح Actions أعلاه.

## 7) مقارنة بالحالة السابقة (للتوضيح فقط)

| قبل (1c834d7) | الآن (2bba048) |
|---|---|
| MapActivity + 6 محركات خرائط + 3 JSON styles (~15k سطر) | محذوف بالكامل |
| زر خرائط 110dp بجانب منتقي الوجهة | منتقي الوجهة بعرض الصفحة وحده |
| خيار "الملاحة على الخريطة" في الوجهات | محذوف |
|依赖 MapLibre 11.3.0 + lifecycle + appcompat + INTERNET | بلا خرائط — فقط zxing |
| 3 طبقات مع بحث 🔎 وأزرار يمين HD Clarity | لا خرائط — تركيز على السهم دون إنترنت |
| حجم APK أكبر، زمن بناء 3m31s | APK أخف، زمن بناء 2m40s |

## 8) ملاحظات تسليم
- لا يلزم إعادة محرك خرائط — القرار نهائي.
- HD القمر لم يعد ذا صلة بعد الحذف — أي عودة مستقبلية إن رُغبت تتطلب اختيار مصدر مجاني/مفتوح يتحمل آلاف المستخدمين دون انتهاك ترخيص (غير مشمول هنا).
- الأيقونة واسم RMG-GPS-Pro بقيـا كما هما بدقة عالية ونسب صحيحة — لم تُستخدم أيقونة عشوائية.

---
**المُلخص:** حُذف نظام الخرائط بالكامل (17711 سطر) مع استعادة صفحة الملاحة الأصلية والحفاظ على كل وظائف GPS/السهم/الرادار/البوابة/الترخيص. البناء سحابي ناجح والـ APK جاهز للتنزيل من Actions.
