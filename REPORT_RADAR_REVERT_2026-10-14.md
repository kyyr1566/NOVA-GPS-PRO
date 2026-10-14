# تقرير استعادة دائرة الرادار الأصلية — RMG-GPS-Pro
**التاريخ:** 2026-10-14  
**الفرع:** `arena/01a10882-nova-gps-pro`  
**الالتزام:** `04ad1d8` — `revert(radar): استعادة دائرة الرادار الأصلية — إرجاع RadarView/RadarPage إلى 7953b26`  
**البناء:** `38603185786` — `success` في `2m42s` — `NOVA-GPS-PRO-debug 2,234,404 bytes`

## 1) السبب
التصميم الفاخر `7f931fb` (حلقات مضيئة/بوصلة حقيقية/درجات 0-330/اتجاهات عربية/مسح أخضر/سهم مركزي) اعتُبر فاشلاً — طُلب تراجع انتقائي **فقط** عن دائرة الرادار دون مس أي تحديث آخر.

## 2) الفحص — Git History
```bash
git log --oneline
cbe8181 docs: تقرير تعديل الرادار (7f931fb)
5889360 ci: تنظيف workflow
315ae8a fix(radar): إصلاح ترميز
7f931fb feat(radar): دائرة رادار فاخرة — 2 files +530/-100
7953b26 fix: إعادة INTERNET (آخر نسخة أصلية للرادار)
```
النسخة الأصلية قبل التعديل هي `7953b26` — آخر حالة نظيفة بعد حذف الخرائط وإصلاح الترخيص، قبل أي لمس للرادار.

## 3) الاستعادة الانتقائية (بدون Git Reset شامل)
```bash
git checkout 7953b26 -- app/src/main/java/com/nova/gpspro/ui/RadarView.kt \
                       app/src/main/java/com/nova/gpspro/ui/RadarPage.kt
```
- **2 files فقط** — لا `git reset --hard` ولا `git revert` شامل
- كل تحسينات أخرى محفوظة: حذف الخرائط (`2bba048`), إصلاح الترخيص `INTERNET` (`7953b26`), تقارير `REPORT_MAP_REMOVAL`, `REPORT_RADAR`, الـ workflow النظيف.

**الإحصاء:**
```
2 files changed, 100 insertions(+), 530 deletions(-)
```
عكس تماماً `7f931fb` (+530/-100).

**التحقق:**
```bash
git diff 7953b26 HEAD -- RadarView.kt RadarPage.kt  # → 0 (متطابق)
grep -c "azimuthDeg\|drawSweepArrow\|cardinalGreen" RadarView.kt  # → 0
grep -c "SensorManager\|RotationVector" RadarPage.kt  # → 0
```

## 4) ما أُزيل
- تدرجات وهالات فاخرة، حلقات إضافية، نسيج قارات، 48 تكة، أرقام `0°..330°`، labels `شمال/شرق/جنوب/غرب`، مثلثات شمال
- مستشعر البوصلة الحقيقية (`SensorManager`, `RotationVector`, `azimuthDeg`, `setAzimuth`, `lowPass`, `remapCoordinateSystem`)
- السهم المركزي المتزامن مع المسح (`drawSweepArrow`, `headDeg` للمركز)
- `sweepGreen` المحفوظ كـ SweepGradient مع `headDeg-90` — عاد إلى الأصلي

## 5) ما استُعيد (الألوان والحركة الأصلية)
- **القرص:** `RadialGradient` أزرق-كحلي بسيط + حافة سماوية خفيفة (الأصلي) — بدون هالة خارجية كبيرة
- **الشبكة:** 3 حلقات (`0.25/0.50/0.75`) + 12 شعاع `30°` + صليب سميك — **بدون** دوران بوصلة (North-up ثابت، GPS فقط)
- **المسح:** `SweepGradient` أخضر بدورة `3800ms` (`3.8s`) — يدور مع `postInvalidateOnAnimation()`، لا يرتبط بسهم مركزي
- **المركز:** نقطة `aqua/muted` + صليب `±14dp` — **بدون** سهم
- **النقاط:** `RadarPlot` الأصلي — حلقات `separation 14dp / ringStep 16dp` — ألوان حسب المسافة `NEAR=أخضر / MEDIUM=أصفر / FAR=أحمر` مع هالة بيضاء — ليست كلها حمراء
- **الحركة:** مشتقة فقط من `GeoMath.between` الحقيقي عند كل `GPS fix` — لا حساس حركة، لا دوران وهمي

## 6) ما حُوفظ عليه
- GPS/اتجاه/مسافة/دقة/سرعة — `LocationEngine`, `GeoMath`, `GpsState` لم تُمس
- النطاق `100m..400km` + مخصص — فلترة `distanceM <= selectedRange` كما كانت
- أزرار `تلقائي/إيقاف`، بطاقات `GPS FIX/أقمار/دقة`، شريط النطاق، القائمة السفلية، ترتيب الصفحة — دون تغيير
- الترخيص `INTERNET`، حذف الخرائط، `NavigationPage` بعرض كامل، والإعدادات — كلها باقية

## 7) الاختبار
- `grep` بعد الاستعادة → لا أثر لـ `azimuth` أو `SweepArrow`
- `git diff` → تطابق تام مع `7953b26` للملفين
- يعمل دون إنترنت: الرادار الحالي `GPS only` — لا يعتمد على شبكة أو حساس بوصلة (تمت إزالة المستشعر)
- **CI:** `38603185786` — `success`، `2m42s`، `Run unit tests: success`, `Build Debug APK: success`, `Upload APK: success`

**رابط APK بعد الاستعادة:**
- https://github.com/kyyr1566/NOVA-GPS-PRO/actions/runs/38603185786 → Artifact `NOVA-GPS-PRO-debug` (2,234,404 bytes)

## 8) الملفات المُرجعة
- `app/src/main/java/com/nova/gpspro/ui/RadarView.kt` — إلى `7953b26` (قبل 7f931fb)
- `app/src/main/java/com/nova/gpspro/ui/RadarPage.kt` — إلى `7953b26`
- لم يُمس `RadarPlot.kt`, `RadarPlotTest.kt`, `AndroidManifest`, `build.gradle`, `NavigationPage`, `DestinationsPage`, `Portal`, `workflow`

---
**الخلاصة:** تم تراجع انتقائي دقيق عن تعديلات الرادار فقط — عاد كود الرسم الأصلي بألوانه/حلقاته/مسحه دون سهم أو بوصلة أو تدرجات، مع بقاء كل تحسينات التطبيق الأخرى سليمة، والـ APK الجديد مبني بنجاح.
