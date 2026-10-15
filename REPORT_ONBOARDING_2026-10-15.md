# تقرير تجربة البداية الاحترافية — RMG-GPS-Pro Onboarding

**التاريخ:** 2026-10-15  
**الفرع:** `arena/01a10882-nova-gps-pro`  
**الالتزام الأساسي:** `49c0e38` + `e8d862d`  
**البناء الناجح:** `38745934035` (3m06s) — `NOVA-GPS-PRO-debug`  
**الرابط:** https://github.com/kyyr1566/NOVA-GPS-PRO/actions/runs/38745934035

---

## 1) الملخص
تم استبدال شاشة البدء الطويلة (4s مع رسائل/قرص دوار) بتجربة بداية حديثة وراقية:
1. **Splash قصيرة نظيفة 700ms** — خلفية عاجية #F7F4EC مع شعار RMG-GPS-Pro واسم التطبيق فقط، انتقال ناعم fade/scale بدون رسائل.
2. **3 شاشات تعريفية تظهر مرة واحدة فقط** بعد التفعيل وقبل الدخول للتطبيق، مع دعم RTL والسحب والإنتقال الناعم.

لا يتم المساس بأي ميزة: GPS/الرادار الأصلي/البوابة/الملاحة/الإعدادات/الأصوات — كلها محفوظة.

---

## 2) الملفات المعدلة/المضافة

| الملف | الإجراء | الوصف |
|---|---|---|
| `app/src/main/java/com/nova/gpspro/onboarding/OnboardingManager.kt` | **جديد** | يحفظ `onboarding_completed` في `nova_onboarding` عبر `SharedPreferences.commit()` — يبقى بعد الإغلاق/إعادة تشغيل الهاتف، لا يُحذف إلا بحذف البيانات/إعادة التثبيت. يوفر overload بـ `SharedPreferences` للاختبار |
| `app/src/main/java/com/nova/gpspro/onboarding/ShortSplashView.kt` | **جديد** | Splash عاجية 700ms — أيقونة 120dp مع elevation وظل ناعم + عنوان RMG-GPS-Pro #2F5FE3 + تاغلاين #8E877F، دخول 260ms وخروج 220ms |
| `app/src/main/java/com/nova/gpspro/onboarding/OnboardingView.kt` | **جديد** | View كاملة 3 صفحات — خلفية #F7F4EC، بطاقات #FFFFFF بحواف 22dp، ظلال خفيفة. شريط علوي زر **تخطي**، حاوية pager مع 3 صفحات، صف dots (نشط #2F5FE3 بعرض 22dp / غير نشط #D9D1BE 8dp)، زر **التالي/ابدأ الآن** بتدرج #2F5FE3→#3C6BFF. يدعم `GestureDetector` للسحب (التالي/السابق) مع RTL |
| `app/src/main/java/com/nova/gpspro/MainActivity.kt` | **تعديل** | قبل `createMainUi` يفحص `OnboardingManager.isCompleted()` — إذا false يعرض `showOnboardingFlow()` (splash 700ms → onboarding)، إذا true يعرض `showShortSplashThenMain()` (splash 700ms → main). `showOnboardingFlow` يحفظ true عند تخطي أو ابدأ الآن ويعرض main بدون وميض. `onStart` يحترم `onboardingShowing/splashShowing` ولا يعيد الإنشاء. `beginActivation` يفحص onboarding بعد النجاح. `createMainUi(showSplash=true)` أصبح short بدل 4s |
| `app/src/main/res/values/strings.xml` + `values-ar/strings.xml` | **تعديل** | إضافة 8 سلاسل: `onboarding_skip/next/start` + 3 عناوين + 3 أوصاف + `gps_connected` + `accuracy_mock` بالعربية/الإنجليزية |
| `app/src/test/java/com/nova/gpspro/OnboardingManagerTest.kt` | **جديد** | 7 اختبارات JVM بدون Robolectric عبر FakePrefs in-memory — تغطي كل الحالات المطلوبة |

**لم يتم حذف أي ملف/صفحة/مكتبة:** `RadarView.kt/RadarPage.kt` بقيا على الأصلي 7953b26 (04ad1d8)، لا تغيير في `applicationId/package/التوقيع/الترخيص/Supabase/الخرائط`.

---

## 3) تفاصيل الصفحات الثلاث

### الصفحة 1 — GPS
- **العنوان:** "دقة GPS بين يديك" — "Precise GPS in your hands"
- **الوصف:** متابعة الموقع والإحداثيات وحالة الاتصال.
- **الرسم:** بطاقة بيضاء 22dp، دائرة بتدرج #2F5FE3→#3C6BFF (96dp) بداخلها `splash_icon` 72dp، حبة "● GPS متصل" #EEF3FD، بطاقة إحداثيات "33.3152° N • 44.3661° E — الدقة ± 3.5 م" #FDFBF6.

### الصفحة 2 — ملاحة
- **العنوان:** "اتجه بدقة نحو وجهتك"
- **الرسم:** حقل "اختر الوجهة — النجف" #FDFBF6، سهم كبير 120dp بخلفية #F0F5FF→#E8FFF وحبة "السهم يتجه نحو وجهتك" #EEF3FD، صف 3 إحصائيات (المسافة 1.2 كم / السرعة 42 كم/س / الوصول 14:32) خلفية #F7F4EC.

### الصفحة 3 — رادار+بوابة
- **العنوان:** "اكتشف وشارك مواقعك بسهولة"
- **الرسم:** قرص رادار 120dp تدرج #0A1830→#0F2A4A، حلقتان #3A5A7A، نقطة مركزية #7ED8A6 وخط مسح -35°، 3 نقاط حمراء #FF3A3A، نص "رادار حي • 3 مواقع قريبة"، صف 4 أيقونات دائرية #F7F4EC (استيراد/تصدير/إنشاء QR/مسح QR) بـ `ic_nav_portal` tint #2F5FE3.

كل صفحة: title 22sp #2E2A25 medium، desc 14sp #8E877F line 1.35، دخول scale 0.96→1 + fade، عنوان يتحرك 8dp.

---

## 4) منطق "مرة واحدة فقط"

```kotlin
// OnboardingManager.kt — FILE = "nova_onboarding", KEY = "onboarding_completed"
isCompleted(ctx) = prefs.getBoolean(KEY, false) // افتراضي false
setCompleted(ctx) = prefs.edit().putBoolean(KEY, true).commit() // persistent
```

- **onCreate:** بعد التحقق من الترخيص، إذا `!isCompleted` → `showOnboardingFlow()`، وإلا `showShortSplashThenMain()`
- **تخطي/ابدأ الآن:** `OnboardingManager.setCompleted(ctx)` ثم fade 220ms → `createMainUi(false)`
- **يستمر بعد:** إغلاق التطبيق/إعادة تشغيل الهاتف (SharedPreferences file يبقى) — لا يظهر مجدداً
- **لا يعود إلا:** حذف البيانات أو إعادة التثبيت (حذف الملف)
- **لا وميض:** `activityRoot.removeAllViews()` فقط عند الانتقال، و`onboardingShowing/splashShowing` تمنع إعادة الإنشاء في `onStart`

---

## 5) اختبار 7 حالات

| # | الحالة | الطريقة | النتيجة |
|---|---|---|---|
| 1 | أول تشغيل — لم يكتمل | `isCompleted == false` → يعرض splash→3 onboarding | ✅ `case1_firstLaunch_notCompleted` |
| 2 | الضغط على **تخطي** | `setCompleted()` → يحفظ true → يذهب للرئيسية | ✅ `case2_skip_savesCompleted` |
| 3 | الضغط على **التالي** مرتين ثم **ابدأ الآن** | كل "التالي" ينتقل صفحة مع animation، "ابدأ" يحفظ true | ✅ `case3_start_savesCompleted` + يدوي: dots تتحول، سحب يعمل |
| 4 | إغلاق وإعادة فتح (ثاني تشغيل) | `isCompleted == true` → لا onboarding، splash قصيرة فقط → رئيسية | ✅ `case4_secondLaunch_noOnboarding` |
| 5 | إعادة تشغيل الهاتف | نفس prefs file → true يبقى | ✅ `case5_persistsAfterReboot_simulated` |
| 6 | حذف البيانات/إعادة التثبيت | `reset()` → false → يظهر onboarding مجدداً | ✅ `case6_onlyOnce_untilDataClear` |
| 7 | لا وميض/لا تهنيج + خلفية عاجية | فحص ثوابت `FILE/KEY` ووجود `ShortSplashView` (700ms) و`OnboardingView` (3 صفحات) والانتقالات | ✅ `case7_constants_andSplashDuration` + يدوي: انتقالات DecelerateInterpolator |

**CI:** `38745934035 success` — كل اختبارات الوحدة نجحت (SplashTimeline + OnboardingManager + LocxCodec + PortalLogic).

---

## 6) التصميم

- **خلفية:** #F7F4EC عاجية هادئة (ثابتة حتى في الوضع الداكن)
- **بطاقات:** #FFFFFF بحواف 22dp وحدود #EFE7D6 وظل 4dp
- **أزرار:** تدرج #2F5FE3→#3C6BFF بحواف 16dp وelevation 4dp وripple أبيض 20%
- **نص:** رئيسي #2E2A25، ثانوي #8E877F، مميز #2F5FE3
- **Dots:** نشط 22×8 pill #2F5FE3، غير نشط 8×8 #D9D1BE
- **حركات:** دخول 340ms Decelerate، خروج 220ms، offset 24dp

---

## 7) الحفاظ على الميزات

- لم يُحذف أي صفحة/زر/صوت/رادار/بوابة/ملاحة — `RadarView.kt` لا azimuth/sweepArrow (مطابق 7953b26)
- الترخيص يبقى قبل onboarding — لا onboarding قبل التفعيل
- لا مكتبة QR جديدة، لا تغيير `applicationId/package/توقيع`
- `SplashView.kt` و`SplashTimeline.kt` بقيا لكن لم يعد يُستدعى 4s — الاختبارات القديمة تمر

---

## 8) البناء

```
commit 49c0e38 — feat(onboarding) 809 insertions
commit e8d862d — test(onboarding)  119 insertions
CI 38745402556 — 3m10s success (build/upload OK)
CI 38745934035 — 3m06s success (build/upload OK + 7 tests pass)
Artifact: NOVA-GPS-PRO-debug (من actions)
```

للتحميل: GitHub → Actions → 38745934035 → Artifacts → NOVA-GPS-PRO-debug

---

## 9) خطوات التحقق اليدوي

1. ثبّت APK جديد على جهاز نظيف (حذف البيانات)
2. افتح — يجب: splash 0.7s → صفحة 1 (GPS) مع تخطي أعلى وdots و"التالي"
3. اسحب يساراً أو اضغط "التالي" → صفحة 2، كرر → صفحة 3 (زر "ابدأ الآن")
4. اضغط "ابدأ الآن" → يذهب للرئيسية (Home) بدون وميض
5. أغلق وأعد فتح — يجب: splash 0.7s فقط → رئيسية مباشرة (لا onboarding)
6. أعد تشغيل الهاتف وأعد فتح — نفس النتيجة
7. احذف بيانات التطبيق وأعد فتح — يظهر onboarding مجدداً
