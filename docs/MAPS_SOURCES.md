# مصادر الخرائط والتراخيص – NOVA GPS PRO (Online فقط)

> **التاريخ:** 2026-10-14 – الفرع `arena/01a10882-nova-gps-pro`
> **الهدف:** 25 ألف مستخدم، 10 آلاف يومياً – بنية قانونية قابلة للتوسع، بدون Google Maps API مدفوع إجباري.

## 1) الطبقات الـ 8 الحقيقية (MapLibre Native)

جميع الطبقات تستخدم **MapLibre Native Android (BSD)** مع مصادر مفتوحة ومرخّصة. التبديل سريع (`Style.Builder.fromUri` + حفظ `CameraPosition`) ويحافظ على الموقع/التكبير/المسار. لا طبقات شكلية مكررة.

| # | المفتاح | الاسم العربي | النوع | المصدر | الترخيص | الاستخدام التجاري | ملاحظات |
|---|---------|--------------|-------|---------|----------|-------------------|---------|
| 1 | `dark` | الليلية السوداء والذهبية | Vector | `https://tiles.openfreemap.org/planet` (OpenFreeMap) | BSD + ODbL (بيانات OSM) | مسموح مع نسب OSM | `openmaptiles` vector + `natural_earth_shaded_relief` |
| 2 | `streets` | الشوارع التفصيلية | Vector | OpenFreeMap Liberty | BSD + ODbL | مسموح | الأكثر تفصيلاً |
| 3 | `light` | النهارية الفاتحة | Vector | OpenFreeMap Positron/Bright | BSD + ODbL | مسموح | خلفية `#F2F3F5` |
| 4 | `satellite` | القمر الصناعي عالي الجودة | Raster | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` (Esri) | Esri Terms – مجاني للعرض المباشر مع نسب، غير مسموح التخزين الكثيف | مسموح للعرض | حتى `z19` |
| 5 | `hybrid` | الهجين (قمر + طرق وأسماء) | Raster + Vector Overlay | Esri raster + OpenFreeMap vector شفاف | Esri + ODbL | مسموح للعرض | نفس القمر + طبقة `transportation` و `place` |
| 6 | `terrain` | التضاريس والمرتفعات | Raster-DEM + Hillshade | `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png` (Mapzen Terrarium) | MIT | مسموح | `hillshade-exaggeration 0.55` |
| 7 | `topo` | الطبوغرافية وخطوط الارتفاعات | Raster | `https://{a,b,c}.tile.opentopomap.org/{z}/{x}/{y}.png` (OpenTopoMap) | CC BY-SA 3.0 | مسموح مع نسب | كنتور وارتفاعات `maxzoom 17` |
| 8 | `navigation` | الطرق والملاحة | Vector | OpenFreeMap مع إبراز الطرق | BSD + ODbL | مسموح | خلفية `#EAF0F8` |

**الصور الفضائية – تشخيص “Map data not yet available”:** السبب ليس خطأً في التطبيق بل **overzoom رقمي**: مصدر Esri World Imagery مجاني بدقة عالية يغطي العراق كاملاً حتى `maxzoom 19 / tile 256` (≈ 0.3m في المدن). عند التكبير إلى `z20+` لا توجد بلاطات، فالخادم يعيد بلاطة 404 عليها النص “Map data not yet available”. الحل: **قفل الكاميرا عند `maxZoom 19` للقمر والهجين و `17` للطبوغرافي** (`MapLibreMap.setMaxZoomPreference`) لمنع التشويش/التكبير الرقمي المشوش. للعراق لا يوجد مصدر مجاني أعلى من 19 بجودة شارع-بيت بدون تشويش — الأعلى يتطلب اشتراكاً مدفوعاً (MapTiler Satellite `z20-22` بدقة 0.15m أو Maxar SecureWatch). جرى الحفاظ على السرعة عبر عدم تحميل بلاطات خارج النطاق، وEsri يتحمل آلاف المستخدمين مع Rate Limit معقول؛ بينما EOX Sentinel-2 (10m) أقل دقة فلا يستخدم كبديل للقمر. Google Satellite غير مستخدم لأنه يمنع التخزين بدون ترخيص.

**2D/3D:** كل Vector تدعم `FillExtrusion` للمباني: `building-3d` يستخدم `coalesce(get render_height, get height, * get levels 3.5, 6)` مع `fillExtrusionOpacity 0.88` في 3D و `NONE` في 2D. زر `3D` منفصل عن زر الطبقات – لا طبقات مكررة.

## 2) محرك التوجيه (Routing) – Online فقط

**الحالة:** لا BRouter Offline. الحساب عبر الشوارع يتطلب إنترنت (يحافظ على GPS الأصلي دون إنترنت للسهم).

| المصدر | الرابط | الترخيص | ملاءمة الإنتاج | القيود |
|--------|--------|----------|----------------|--------|
| **FOSSGIS OSRM** (افتراضي) | `https://routing.openstreetmap.de/routed-car/route/v1/driving/{lon},{lat};{lon},{lat}?overview=full&geometries=geojson` | ODbL مجاني، FOSSGIS e.V. | **مناسب للإنتاج مع Fair-Use** – يتحمل آلاف يومياً مع Cache | لا SLA، يُنصح بـ User-Agent وتخزين مؤقت |
| **Valhalla OSM.de** (بديل) | `https://valhalla1.openstreetmap.de/route` (POST JSON) | ODbL مجاني | مناسب كبديل مجتمع | يتطلب POST و polyline6 |
| **OSRM Demo** (احتياطي فقط) | `https://router.project-osrm.org` | ODbL مجاني | **غير مناسب للإنتاج** – تجريبي | Rate Limit صارم |

**التبديل:** `RoutingRepository.provider: Enum(FOSSGIS, VALHALLA, OSRM_DEMO)` محفوظ في `SharedPreferences nova_routing`. الإعدادات تسمح بالتبديل بنقرة. لإضافة GraphHopper ذاتي يكفي إضافة `tryGraphHopper()`.

**التوسع لـ 10k يومياً:** يُنصح باستضافة ذاتية OSRM/Valhalla/GraphHopper على VPS ~$20/شهر (Hetzner) بلا حدود، أو خدمات مدفوعة بSLA (MapTiler, Stadia, GraphHopper API من $49/شهر لـ 100k طلب).

**Map Matching:** `snapToRoad` عبر `.../nearest/v1/driving` بحد 75m (driving) – يحافظ على GPS، خط متقطع برتقالي إذا `>12m`.

## 3) ما تم حذفه

- `OfflineMapManager.kt`, `OfflineRoutingManager.kt`, `MapDownloadWorker.kt`, `MapPackageManifest.kt`, `SatelliteManager.kt`, `SatelliteDownloadWorker.kt` – حذف نهائي.
- `tools/generate-pmtiles.sh`, `tools/README_OFFLINE.md` – حذف.
- زري «تنزيل الخرائط» و«إدارة الخرائط المحمّلة» – إزالة من `sideCol`.
- خيارات `Online/Smart/Offline` – إزالة (`cycleMode` حذف).
- مكتبة `androidx.work:work-runtime-ktx` – إزالة من `build.gradle.kts`.
- لا حذف للمواقع المحفوظة ولا لنظام GPS/السهم الأصلي (`ArrowEngine`, `DestinationRepository` بقيت).

## 4) أدوات الخريطة الجديدة (يمين عمودي فعلي، أزرق NOVA – إصلاح RTL)

`sideCol` على `Gravity.RIGHT|CENTER_VERTICAL` مع `layoutDirection LTR` إجباري – حتى في الوضع العربي (RTL) تبقى الأزرار على اليمين الفعلي (لا `END` المنعكس). `topMargin 90dp` و `bottomMargin 90dp` و `rightMargin 12dp`.
الترتيب: **تكبير (+) → تصغير (−) → موقعي (◎) → البوصلة (◉) → الطبقات (⬢) → 2D/3D** – كل زر دائري **48dp بصري / 56dp لمس** (مطابق للمواصفة 44–56dp)، خلفية `C.PRIMARY_A` (أزرق NOVA #2856BC)، أيقونة بيضاء **16sp** أكبر، `elevation 4dp`, Ripple, مسافة منتظمة **6dp** بلا تداخل. زر الطبقات الآن يفتح **قائمة اختيار أنيقة** (8 طبقات + مؤشر ✓) لا يغير تلقائياً.

**موقعي:** `centerToMyLocation()` → `LocationEngine.awaitFreshLocation()` → `animateCamera 16.0`.
**الكاميرا:** منظور قيادة حقيقي `tilt 45–50°` + `zoom ذكي 15.5–17.5 حسب السرعة` (أبطأ=أقرب، أسرع=أوسع) + `offset 75m` سهم أسفل المنتصف + تثبيت Bearing عند التوقف + Follow Camera سلس بلا قفزات.

## 5) الاختبارات

- `testDebugUnitTest` محدثة: 8 طبقات، عدم وجود `OfflineMapManager`, توفر `RoutingProvider`, haversine, ألوان.
- `assembleDebug` – يبني بدون PMTiles.
- يدوي (خطة جهاز حقيقي): سهم أزرق 48dp/144px لا يختفي تحت المسار، بداية المسار من أقرب شارع مع وصلة برتقالية إذا داخل البيت، كاميرا ملاحة `tilt 45–50 zoom 15.8–17.0 offset 75m` سلسة وتثبت عند التوقف، سرعة/مسافة/ETA من GPS حقيقي، 6 أزرار يمين فعلي (RTL) بلا تداخل، اختيار طبقات أنيق 8، جودة قمر عند `z19` مع منع `z20` المشوش.
- CI: GitHub Actions `assembleDebug` يبني APK ويُحمّل Artifact `app-debug.apk` – لا يُعتبر نجاح البناء وحده دليل عمل القمر/GPS دون اختبار جهاز حقيقي.

---
**الخلاصة القانونية:** كل المصادر تسمح بالاستخدام التجاري مع نسب OSM/Esri/OpenTopoMap. Esri/Terrarium/OpenTopoMap مجانية للعرض لكن ليست بلا حدود – لـ 10k يومياً يجب Cache أو استضافة ذاتية. لا Google API مدفوع إجباري.
