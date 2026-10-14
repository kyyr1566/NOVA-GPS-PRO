# مصادر الخرائط والتراخيص – RMG-GPS-Pro (Online فقط)

> **التاريخ:** 2026-10-14 – الفرع `arena/01a10882-nova-gps-pro` – **تحديث: 3 طبقات فقط**
> **الهدف:** 25 ألف مستخدم، 10 آلاف يومياً – بنية قانونية قابلة للتوسع، بدون Google Maps API مدفوع إجباري.

## 1) الطبقات الـ 3 المتبقية (MapLibre Native) — تلبية الطلب

جميع الطبقات تستخدم **MapLibre Native Android (BSD)** مع مصادر مفتوحة ومرخّصة. التبديل سريع (`Style.Builder.fromUri` + حفظ `CameraPosition`) ويحافظ على الموقع/التكبير/المسار. لا طبقات مخفية أو غير مستخدمة في الكود/القائمة.

| # | المفتاح | الاسم العربي | النوع | المصدر | الترخيص | الاستخدام التجاري | ملاحظات |
|---|---------|--------------|-------|---------|----------|-------------------|---------|
| 1 | `light` | النهارية الفاتحة | Vector | `https://tiles.openfreemap.org/planet` (OpenFreeMap Bright) | BSD + ODbL | مسموح | خلفية فاتحة نظيفة |
| 2 | `dark` | الليلية السوداء والذهبية | Vector | `https://tiles.openfreemap.org/planet` (OpenFreeMap Dark) | BSD + ODbL | مسموح | `openmaptiles` + ثيم RMG ليلي |
| 3 | `satellite` | القمر الصناعي عالي الجودة | Raster | `https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` + fallback `server.arcgisonline.com` (Esri Clarity HD) | Esri Terms – مجاني للعرض المباشر مع نسب | مسموح للعرض | **Clarity HD** `maxzoom 19`، `tileSize 256`، `raster-resampling linear` |

**تم حذف:** `streets`, `hybrid`, `terrain`, `topo`, `navigation` (ملفات `hybrid.json`, `streets.json`, `terrain.json`, `topo.json`, `navigation.json` محذوفة، و `MapStyleManager.orderedLayers` الآن 3 فقط).

**الصور الفضائية – إصلاح جذري “Map data not yet available”:** السبب **overzoom رقمي**: Esri مجاني حتى `z19` فقط. الحل الحالي: **مصدر Clarity HD** (أعلى جودة مجانية من Esri، يغطي العراق 0.3m مدن، 0.5–1m ريف) مع fallback لخوادم Esri الثلاثة + `maxZoom 19` مقفل (`MapLibreMap.setMaxZoomPreference`) + `raster-resampling linear` لمنع التشويش. عند `z>19` يُمنع التكبير الرقمي نهائياً فلا تظهر رسالة الخطأ في الاستخدام الطبيعي. مصدر مجاني أعلى من 19 بجودة بيت-شارع غير متاح للعراق — يتطلب دفع (MapTiler/Mapbox `z20–22` 0.15m أو Maxar). EOX Sentinel-2 (10m) غير مستخدم لضعف دقته. Google Satellite غير مستخدم لمخالفة الترخيص.

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

## 4) أدوات الخريطة الجديدة — إعادة تصميم احترافي يمين (RMG أزرق، RTL ثابت)

**الشريط العلوي:** `topPill` أبيض مستدير `cornerRadius 24dp` → **يمينه زر بحث 🔎 أزرق دائري 36dp** (`R.drawable بحث` داخل نفس الشريط، لا زر دائري × منفصل)، يساره زر رجوع ‹ 32dp، وسطه عنوان الوجهة. نظيف واحترافي، البحث جزء من نفس الشريط.

`sideCol` على `Gravity.RIGHT|CENTER_VERTICAL` مع `layoutDirection LTR` — يبقى يمين فعلي في RTL. `topMargin 90dp` `rightMargin 12dp`.

**الأزرار الستة — مميزة وغير متشابهة:**
- **تكبير/تصغير (+/−):** `TextView` 52dp دائري أزرق `#2856BC` نص 18sp، إحساس مميز
- **موقعي الحالي ⌖:** `vectorIconButton(R.drawable.ic_my_location)` — هدف دقيق مع صليب (أيقونة target واضحة)
- **البوصلة:** `ic_compass_north` — بوصلة بإبرة شمال واضحة
- **الطبقات:** `ic_layers_stack` — ثلاث طبقات متراكبة (stacked layers)
- **3D:** `ic_cube_3d` — مكعب ثلاثي الأبعاد (cube)
كل زر **52dp بصري / 60dp لمس**، `elevation 5dp`، Ripple أبيض 33%، خلفية زرقاء مع حدود `#1E3A5F`، **أيقونات بيضاء 26dp** واضحة، مسافة **8dp** منتظمة بلا ازدحام. زر الطبقات يفتح **قائمة صغيرة يدوية 3 طبقات فقط** مع ✓، لا تبديل تلقائي، ويحافظ على الكاميرا.

**موقعي:** `centerToMyLocation()` → `awaitFreshLocation()` → `zoom 16.0`.
**البحث:** `showMapSearchDialog()` — `EditText` + `ScrollView` يفلتر `destinations.all()` محلياً (12 نتيجة أولى)، اختيار وجهة ينقل الكاميرا `z15` ويبرزها.
**الكاميرا:** `tilt 45–50°` + `zoom ذكي 15.5–17.5 حسب السرعة` + `offset 75m` سهم أسفل المنتصف + تثبيت Bearing + Follow سلس.

## 5) الاختبارات — 3 طبقات

- `testDebugUnitTest` محدثة: **3 طبقات** (`light,dark,satellite`)، لا `OfflineMapManager`، مزودي توجيه، haversine.
- `assembleDebug` — بدون PMTiles/WorkManager.
- يدوي (جهاز حقيقي): زر بحث ظاهر أعلى، لا زر × يمين، 3 طبقات فقط تختار يدوياً، قمر Clarity HD واضح حتى `z19` بلا رسالة، 6 أزرار يمين مميزة (target/layers/cube مفهومة)، تكبير/تصغير متناسق، حفظ الموقع والتكبير عند تبديل الطبقة.
- CI: GitHub Actions `assembleDebug` → `app-debug.apk`.

---
**الخلاصة القانونية:** كل المصادر تسمح بالاستخدام التجاري مع نسب OSM/Esri/OpenTopoMap. Esri/Terrarium/OpenTopoMap مجانية للعرض لكن ليست بلا حدود – لـ 10k يومياً يجب Cache أو استضافة ذاتية. لا Google API مدفوع إجباري.
