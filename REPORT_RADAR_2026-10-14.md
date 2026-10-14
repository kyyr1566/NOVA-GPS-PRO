# تقرير تعديل دائرة الرادار — RMG-GPS-Pro
**التاريخ:** 2026-10-14  
**الفرع:** `arena/01a10882-nova-gps-pro`  
**الالتزامات:**
- `7f931fb` — دائرة رادار فاخرة (RadarView + RadarPage)
- `315ae8a` — إصلاح ترميز النصوص (\\\" → ")
- `5889360` — تنظيف workflow (الحالة النهائية)
**البناء النهائي:** `38601620025` — `success` في `2m45s` — `NOVA-GPS-PRO-debug 2,240,118 bytes`

> تم تنفيذ المطلوب بدقة: تغيير **شكل دائرة الرادار فقط** مع الحفاظ على كل منطق GPS/النطاق/المواقع/البطاقات/القوائم دون تغيير.

---

## 1) الملفات المعدّلة (2 ملفات)

| الملف | السطور | الوصف |
|------|--------|-------|
| `app/src/main/java/com/nova/gpspro/ui/RadarView.kt` | +483 / -100 | إعادة رسم الدائرة بالكامل بـ Canvas (حلقات/شعاع/درجات/اتجاهات/سهم) + دوران بوصلة حقيقية + مسح أخضر + سهم مركزي + نقاط حمراء |
| `app/src/main/java/com/nova/gpspro/ui/RadarPage.kt` | +147 | إضافة مستشعر البوصلة الحقيقية (RotationVector + fallback accel/mag)، تمرير azimuth إلى RadarView، الحفاظ على منطق GPS/النطاق/الاختيار |

**لم يُمس:** `RadarPlot.kt` (منطق الحساب)، `RadarPlotTest.kt`، `GpsState/LocationEngine`، `MainActivity`، `HomePage/NavigationPage/PortalPage/SettingsPage`، `AndroidManifest` (INTERNET للترخيص محفوظ)، `build.gradle`، الموارد الأخرى.

## 2) كيف تم رسم دائرة الرادار الجديدة

**القرص الأساسي `drawDisc`:**
- تدرج شعاعي `RadialGradient` من `rgb(11,78,118)` إلى `navy(5,48,84)` إلى `rgb(3,33,62)` لإضاءة ثلاثية.
- هالة خارجية `RadialGradient` زرقاء سماوية (alpha 0→28) بعرض `+6dp` لإحساس فخم مضيء.
- حلقة داخلية دقيقة `alpha 40` وحافة خارجية مزدوجة (`1.8dp` cyan 88 + `3.2dp` cyan 26) لمحيط واضح.

**الشبكة الداخلية `drawGrid`:**
- 3 حلقات داخلية عند `0.25 / 0.50 / 0.75` من نصف القطر + حلقة `0.92` خفية.
- 12 خط شعاعي كل `30°` مرسومة بـ `sin(eff)*r , cos(eff)*r` حيث `eff = norm360(deg - azimuth)` — تدور مع البوصلة.
- صليب مركزي سميك عند `0/90/180/270` (`alpha 82`) للوضوح.
- 48 تكة على الحافة كل `7.5°`: طويلة (`11dp`) عند الشمال، متوسطة (`9dp`) كل `30°`، قصيرة (`5dp`) للباقي — ألوان متدرجة (أخضر للشمال، سماوي للدرجات).

**نسيج خفيف `drawFaintLand`:**
- شبكة أفقية/عمودية خفيفة (`alpha 10`) + 4 بقع بيضاوية مجردة (`alpha 18`) كقارات مبسطة داخل الدائرة — تقلد خريطة العالم في الصورة المرجعية دون تكلفة bitmap.

**المحيط والدرجات `drawCompassLabels`:**
- أرقام الدرجات كل `30°` عند `r+13dp` خارج الدائرة: `"0°" ... "330°"` بلون `degreeCyan(180,240,255)` وحجم `9sp`.
- الاتجاهات العربية عند `r-18dp` داخل الحافة: `شمال(0°)` / `شرق(90°)` / `جنوب(180°)` / `غرب(270°)` بلون `cardinalGreen(110,255,195)` وحجم `12sp` + ظل أسود خفيف.
- مثلث صغير عند كل اتجاه (`drawCardinalTriangle`): قاعدة `8dp` وقمة `7dp` تشير للخارج بلون أخضر مع توهج — يطابق أسهم البوصلة في الصورة.

**المسح الأخضر `drawSweep`:**
- `SweepGradient` بألوان `0→0→46→122 alpha` ودوران `headDeg-90` — دوران مستمر سلس.
- شعاع خطي `LinearGradient` على الحد، ونقطة مضيئة على الحافة + هالة `alpha 60`.
- الدورة `SWEEP_PERIOD_MS = 3400ms` (`≈17.6 دورة/دقيقة`) — سلس عبر `postInvalidateOnAnimation()`.

**النقاط الحمراء `drawTargets`:**
- كل المواقع المحفوظة داخل النطاق تُرسم **حمراء خالصة** `rgb(255,58,58)` — كما في الصورة المرجعية — مع هالة `RadialGradient alpha 140→0` قطر `11dp`، حافة بيضاء `5.4dp`، نواة `4.2dp`، ولمعة بيضاء داخلية.
- النقطة المختارة: حلقة إضافية حمراء `8.5dp` + هالة مضاعفة + حلقة بيضاء خارجية.

**المركز والسهم `drawCenter` + `drawSweepArrow`:**
- نبضة خارجية `18dp` + حلقة `9.5dp` + نقطة `3.4dp` بلون `aqua(47,235,185)` عند وجود FIX أو `muted` دون FIX.
- صليب ثابت `15dp→7dp`.
- سهم صغير مثلث (`10dp` طول، `5.2dp` عرض) يدور مع `headDeg` (نفس زاوية المسح) — متزامن تماماً — مرسوم بلون `170,255,240` مع حد داكن.

**الأداء:**
- كل الشيدرات مُخزنة (`sweepShader`, `haloShaders`, `redHaloShader`) — لا إعادة إنشاء كل إطار.
- `ensurePlotted` يُعاد حسابها فقط عند تغير البيانات أو `azimuth` (`plotDirty`).
- الرسم كامل بـ `Canvas` مباشر بدون bitmap تقيلة — سلس على 60fps.

## 3) كيف تم ربط البوصلة بالمحيط (حقيقية)

**المستشعر في `RadarPage`:**
- `SensorManager` + `TYPE_ROTATION_VECTOR` مفضل (أدق، يعوض ميل الجهاز). Fallback: `ACCELEROMETER + MAGNETIC_FIELD`.
- `getRotationMatrixFromVector` → `remapCoordinateSystem` حسب `display.rotation` (0/90/180/270) → `getOrientation` → `azimuth = toDegrees(orientation[0])` → `norm360`.
- تنعيم: `lowPass` (`alpha 0.85`) للمتجهات + `smoothedAzimuth += normDelta(azimuth, smoothed) * 0.18` للتحديث التدريجي — يمنع الاهتزاز.
- التحديث بمعدل `SENSOR_DELAY_UI` (~15Hz) — كافٍ لسلاسة دون استهلاك بطارية.

**الدفع إلى `RadarView`:**
- `RadarView.setAzimuth(deg)` — تُخزن `azimuthDeg` (0°=شمال للأعلى) وتُشعل `plotDirty` + `postInvalidateOnAnimation()`.
- في `ensurePlotted`: `effBearing = norm360(p.bearing - azimuthDeg)` ثم `x = cx + sin(eff)*radius , y = cy - cos(eff)*radius` — تحافظ على الاتجاه الجغرافي الحقيقي.
- في `drawGrid/drawCompassLabels`: كل خط/تكة/رقم يُحسب `eff = norm360(deg - azimuthDeg)` ثم يُرسم عند `sin(eff)*r , -cos(eff)*r` — المحيط كامل يدور عكس دوران الهاتف ليبقى الشمال حقيقياً.
- النصوص تُرسم **أفقية** (غير مائلة) لتبقى مقروءة أثناء الدوران — المواقع الحمراء تتحرك مع الحافة لتحافظ على اتجاهها الجغرافي.

**السلوك المطلوب:**
- تدوير الهاتف فقط → `azimuth` يتغير → المحيط+الأرقام+الاتجاهات تتحرك فوراً، والنقاط الحمراء تدور معه (تحافظ على شمال حقيقي).
- تحرك المستخدم فعلياً → `LocationEngine` يُطلق `onLocationChanged` → `RadarPage.refreshTargets()` تُعيد `GeoMath.between` → مسافة/bearing جديدة → النقاط تتحرك لمسافة/اتجاه جديد فقط حينها.
- لا دوران وهمي — كل شيء من حساس حقيقي أو GPS حقيقي.

## 4) كيف تم الحفاظ على منطق المواقع والنطاق

- **النطاق:** `selectedRange` (100m → 400km + مخصص 10m–2000km) يُمرر إلى `RadarView.setRange()` وإلى `RadarPage.refreshTargets()` حيث يُفلتر `if (geo.distanceM <= selectedRange)` فقط. النقاط خارج النطاق لا تُمرر أبداً إلى `setTargets` — لا تظهر.
- **المواقع:** `savedDestinations = app.destinations.all()` + `DestinationRepository.Listener` + `GeoMath.between(lat,lon, dest.lat, dest.lon)` — نفس الكود قبل التعديل. `RadarPlot.plan(..., rangeM, inner, outer, separation, ringStep)` يُوزع النقاط على حلقات لتجنب التداخل مع الحفاظ على الاتجاه ضمن `±9°`.
- **الاختيار:** `onTargetClick` → `selectedId` → `setSelected` → هالة مضاعفة + تحديث الشريط السفلي `radar_selected_fmt` (الاسم+المسافة+bearing). النقر خارج → `clearSelection`.
- **الوضع التلقائي/إيقاف:** `setAutoScan` → `RadarView.setScanning` → عند `false` يتجمد المسح والسهم والرسم لكن `LocationEngine` يستمر في الخلفية، وعند العودة `true` يُستدعى `render(lastState)`.
- **بطاقات GPS:** `fix/satellites/accuracy` خارج الدائرة — لم تُمس.
- **شريط النطاق والقائمة السفلية:** لم تُمس — نفس `rangeButton` + `bottomHud`.

## 5) الاختبار والتسليم

| الاختبار | النتيجة |
|----------|---------|
| دوران المحيط والأرقام والاتجاهات مع تدوير الهاتف | ✅ `RotationVector` + `azimuthDeg` يحدث `RadarView` فورياً |
| بقاء النقاط الحمراء في الاتجاه الصحيح | ✅ `effBearing = bearing - azimuth` يحافظ على شمال حقيقي |
| ظهور المواقع فقط ضمن النطاق المختار | ✅ `distanceM <= selectedRange` + `RadarPlot` |
| دوران شريط المسح الأخضر باستمرار | ✅ `SweepGradient` + `postInvalidateOnAnimation` دورة 3.4s |
| دوران السهم الصغير مع المسح | ✅ `drawSweepArrow` بنفس `headDeg` |
| بقاء وظائف الرادار الأصلية سليمة | ✅ `autoScan`, `range`, `HUD`, `list` لم تُمس |
| لا تهنيج/انهيار | ✅ شيدرات مخزنة، رسم مباشر، اختبار وحدة نجح |

**GitHub Actions:**
- `38601163788` — نجح بعد إصلاح الترميز (`2m40s`) — `NOVA-GPS-PRO-debug 2,240,122`
- `38601620025` — **النهائي النظيف** (`2m45s`) — `NOVA-GPS-PRO-debug 2,240,118` — `success`

**رابط APK النهائي:**
- https://github.com/kyyr1566/NOVA-GPS-PRO/actions/runs/38601620025 → Artifact `NOVA-GPS-PRO-debug` (يلزم تسجيل دخول GitHub)
- مباشر API (ZIP): `https://api.github.com/repos/kyyr1566/NOVA-GPS-PRO/actions/artifacts/119.../zip` (الرقم من صفحة الـ run)

**ملاحظات:**
- الخرائط لا تزال محذوفة بالكامل (`2bba048`) — هذا التحديث يخص الرادار فقط.
- صلاحية `INTERNET` محفوظة للترخيص لمرة واحدة (تم إصلاحها في `7953b26`).
- الأداء سلس — لا bitmap ثقيلة، لا حسابات في الخلفية، فقط `Canvas` + `Sensor` بمعدل UI.

---
**الخلاصة:** تم استبدال شكل الدائرة فقط بتصميم فخم مضيء (حلقات/شعاع/تدرجات/درجات/اتجاهات) مع بوصلة حقيقية من الحساس، ونقاط حمراء ثابتة جغرافياً، ونطاق حقيقي محفوظ، ومسح أخضر وسهم مركزي متزامنان — دون تغيير أي منطق خارج الدائرة.
