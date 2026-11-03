# تقرير المرحلة 2 — لاسلكي Wi-Fi Direct فقط، بلا رمز قناة، وبموافقة مضيف إلزامية
**التاريخ:** 2026-11-03 · **الفرع:** `arena/519cf98c-nova-gps-pro` · **Phase 2 report**

---

## 1) ملخّص تنفيذي

| المطلوب | الحالة |
|---|---|
| إزالة «نقطة الاتصال المحلية (Local-Only Hotspot)» بالكامل | ✅ حُذفت من الواجهة، منطق الاتصال، معالجات الفشل، والـ API — لا يوجد أي أثر متبقٍ |
| إزالة رموز القناة بالكامل | ✅ لا حقول إدخال، لا تحقق بكلمة مرور قناة، لا كلمة مرور Wi-Fi/PIN يدوية، وحُذفت `lastChannelCode` و`isValidChannelCode`/`generateChannelCode` |
| عدم خلق اتصال غير موثّق أو موافقة تلقائية بعد حذف الرمز | ✅ الهوية الآن مفتاح شهادة في Keystore، وكل طلب يبقى «معلّقاً» حتى يضغط المضيف «قبول» |
| إصلاح المسار: تكوين المجموعة، الاكتشاف، DNS-SD، `connect()`، `groupFormed`، GO IP/port، TCP، تسليم طلب الانضمام، قبول/رفض، مصافحة، صوت، تنظيف | ✅ أُعيد بناء المصافحة حول الشهادة + nonce الموافقة؛ الرسائل صارت محددة لا «مهلة/مسافة» |
| الحفاظ على الهوية والتنقّل وGPS والرادار والوجهات وSupabase والمظهرين والترجمتين والألوان ونصف الازدواج | ✅ لم يُمَس أي ملف خارج حزمة `ptt` + سطر تعليق في الـ Manifest |
| اختبارات انحدار + بناء + Lint + Actions + APK | ✅ أدناه |

---

## 2) تحليل السبب الجذري (المرحلة 2)

المرحلة 1 أصلحت **مسار الربط** (الانضمام بـ SSID/PSK بدل دعوة WPS-PBC إلى مجموعة دائمة).
لكن ما تبقّى كان سببه أن **سرّ الجلسة كان يأتي من رمز يكتبه المستخدم**، وهذا يفرض بنيوياً:

1. **الرمز هو كل الأمان**: أي طرف يعرف الرمز يُقبل. لا هوية للجهاز، ولا ربط بجلسة بعينها، ولا إثبات لِمن تكلّم معه.
2. لذلك كان لا بد من **إدخال يدوي** في كل مرة، وكان فشل الربط يُعرض كـ«رمز خاطئ» أو كمهلة عامة، لأن السرّ
   لم يكن مرتبطاً بالجلسة المعلَنة عبر DNS-SD.
3. ولأن الربط قد يفشل، كان التطبيق يحاول «نقطة اتصال محلية» كخطة بديلة — وهذا مسار ثانٍ كامل (شبكة، كلمة مرور،
   بوابة) لا علاقة له بـ Wi-Fi Direct، ويتعارض مع شرط «Wi-Fi Direct فقط».
4. الأهم ميدانياً: **لا يمكن للمضيف أن يثق بطلب انضمام قبل أن يتحقق من هوية الجهاز**، ولا يمكن للضيف أن يتأكد أنه
   وصل إلى الجلسة الصحيحة لا إلى جهاز آخر. أي أن «الموافقة» كانت تُعرض على طلب غير موثّق.

**الجذر:** الثقة كانت مبنية على **سرّ يكتبه الإنسان**، لا على **هوية يثبتها الجهاز**. وبالتالي فإن إزالة الرمز دون
استبدال هذا الجذر كانت ستعني اتصالاً غير موثّق.

---

## 3) التصميم الجديد (يحلّ الجذر بدل إخفائه)

### 3.1 هوية المضيف = مفتاح في Android Keystore (جديد: `PttHostIdentity.kt`)
* مفتاح EC P-256 (secp256r1) داخل `AndroidKeyStore`، الاسم المستعار `rmg_ptt_identity_v1`، لا يخرج منه أبداً.
* يُصدَّر فقط **SPKI** العام، ومنه **pin** = SHA-256(`"RMG-PTT-HOST|" + SHA-256(SPKI)`) بصيغة 64 hex.
* الـ pin يُنشر في سجل DNS-SD عبر حقل TXT جديد `fp` (بجانب `v`, `sid`, `host`, `port`, `goip`, `gn`, `gp`).

### 3.2 التحقق قبل أي إثبات (في `PttRepository`)
1. العميل لا يرى إلا إعلانات تحمل `sid` + `fp` بطول 64 hex + إصدار البروتوكول المطابق
   (`DiscoveredPttSession.isVerifiedAppHost`). الجيران الخامّون (راوتر MAC فقط) **لا يظهرون كجلسات مؤكدة**.
2. `HelloChallenge` يحمل الآن `hostPublicKeyHex` (SPKI) + `hostPublicKeyHashHex` (الـ pin).
3. العميل يتحقق: `SHA-256` للـ SPKI المسلَّم == الـ pin المعلَن == الـ pin الذي رآه في DNS-SD، وأن `sessionId` مطابق.
   أي اختلاف ⇒ `AUTH_FAILED` مع تشخيص `[Verify] …` **قبل** إرسال أي إثبات.
4. سرّ الجلسة الأساسي: `PBKDF2-HmacSHA256(4096, SHA-256(SPKI), salt=SHA-256("RMG-PTT-CERT-V1|"+sessionId+"|"+sessionSalt))`
   — أي أن الطرفين يستندان إلى **نفس بايتات SPKI** (المضيف من مفتاحه، والضيف من SPKI الذي تحقق منه).

### 3.3 الموافقة تُقيّد المفتاح (لا موافقة تلقائية)
* بعد `JoinRequest` المُثبت (HMAC على serverNonce+clientNonce+deviceId+displayName) يرسل المضيف
  `JoinPendingApproval(nonce)` ويضع الطلب في بطاقة الموافقة. **لا يُسلَّم أي مفتاح صالح في هذه المرحلة.**
* عند «قبول»: `approved = HMAC-SHA256(baseKey, "APPROVAL|"+nonce)` ثم `JoinAccepted` + `hostProof` بهذا المفتاح.
* كل إطارات التحكم بعده تُوقّع بـ `approved` (`HostClientConn.controlKey` / `ClientControlConn.controlKey`)،
  والصوت يُشتق بـ `deriveMediaKey(approved, sessionId)` — أي **الصوت مقيّد بالموافقة أيضاً**.
* كل ضيف له nonce خاص ⇒ مفتاح تحكم/وسائط **لكل اتصال**؛ لا يستطيع ضيف معلّق أو مرفوض الحصول على مفتاح صالح،
  ولا يمكن استخدام مفتاح ضيف آخر.
* الحماية من الإعادة: `serverNonce`/`clientNonce` لكل اتصال، `JoinAccepted` بلا nonce موافقة يُرفض صراحة.
* رفض/مهلة: `REJECTED_BY_HOST` / `APPROVAL_TIMEOUT`، ويُغلق المقبس ويُنظَّف الطلب.

### 3.4 البروتوكول
* `PROTOCOL_VERSION = 2` — نسخة 1 (المبنية على الرمز) لا يمكن أن تُعتبر متوافقة.
* `HelloChallenge` + حقل `hostPublicKeyHex`.
* حُذف `PttSecurity.deriveSessionKey(channelCode, salt)` نهائياً.

---

## 4) ما حُذف فعلياً (ليس مخفياً)

| العنصر | الملف | الحالة |
|---|---|---|
| `LocalHotspotCredentials`, `hotspotInfo`, `pendingJoinViaHotspot`, `requestJoinLocalHotspotGateway`, فرع الـ hotspot في `createHostSession` | `PttRepository.kt` | محذوف |
| `startLocalOnlyHotspotFallback`, `onLocalHotspotStarted`, `checkLocalHotspotGateway`, `LocalHotspotGatewayCheck` | `WifiDirectManager.kt` | محذوف |
| `PttErrorCode.HOTSPOT_START_FAILED` / `HOTSPOT_NOT_CONNECTED` + تعيينهما | `PttRepository.kt`, `PttPage.kt` | محذوف |
| بطاقة الـ hotspot في اللوبي، بطاقة SSID/كلمة المرور في الجلسة، `showJoinLocalHotspotDialog`, المفتاح المقسّم Wi-Fi Direct/Hotspot، زر «نقطة اتصال محلية بديلة» | `PttPage.kt` | محذوف |
| حقول إدخال رمز القناة، `showJoinChannelPrompt`, زر «توليد رمز»، التحقق من الرمز | `PttPage.kt` | محذوف (استُبدل بتأكيد تحقّق + طلب اتصال) |
| `PttSettingsStore.lastChannelCode`, `lastChannelCode` المخزّن, `normalizeChannelCode`, `isValidChannelCode`, `generateChannelCode` | `PttSettings.kt` | محذوف |
| 23 مفتاح نص (`ptt_channel_code_*`, `ptt_hotspot_*`, `ptt_use_local_hotspot`, `ptt_err_hotspot_*`, `ptt_session_active_channel_fmt`, …) | `strings.xml` + `values-ar` | محذوف من اللغتين |
| `ptt_notif_body_fmt` صار وسيطين (عدد المتصلين + الحالة) بدل ثلاثة | `PttService.kt` + اللغتين | محدّث |

الواجهة الجديدة للجلسة: شارة دور (Host/Guest) بدل شارة القناة، بنفس المقاس والألوان.
المفاتيح المضافة في اللغتين: `ptt_guest_tag`, `ptt_create_session_hint`, `ptt_join_request_message_fmt`,
`ptt_join_confirm_body`, `ptt_diag_identity_proof_rejected` (EN/AR متطابقتان 1:1 — اختبار التطابق يمرّ).

---

## 5) المسار الكامل كما هو الآن

**المضيف:** اسم → «إنشاء جلسة» → بلا أي رمز → `createHostSession()` يحسب الـ pin من Keystore →
`startHostingGroup(sid, name, port, pin)` → `createGroup()` → انتظار `groupFormed` الحقيقي → قراءة SSID/PSK
(مع إعادة محاولة) → نشر DNS-SD مع `fp`/`gn`/`gp`/`goip` → «الجلسة جاهزة».
عند وصول طلب: تحقق HMAC → `JoinPendingApproval` → **بطاقة موافقة بزرّي قبول/رفض متساويين** → قبول = تسليم مفتاح مقيد.

**الضيف:** اسم → «بحث» → قائمة **الجلسات المتحقّقة فقط** (تُسقط الإعلانات غير المتجددة بعد 45 ثانية) →
«طلب اتصال» (تأكيد بلا إدخال أي رمز) → `connectWithGroupCredentials(ssid, psk)` على المجموعة القائمة →
TCP إلى `goip`/`192.168.49.1`/عنوان واجهة `p2p*` → تحقق pin + session → `JoinRequest` → «بانتظار موافقة المضيف» →
`JoinAccepted` بمفتاح مقيد → دخول الجلسة والصوت.

**رسائل الفشل محددة:** `[Verify] …` (هوية/جلسة غير مطابقة)، `[Approval] …`، `[P2P-Group] … P2P_BUSY(2)`،
`[P2P-MAC] …`، `[Protocol] …` — لا رسالة «مسافة/مهلة» عامة.

---

## 6) الاختبارات والبناء

| الخطوة | النتيجة |
|---|---|
| `testDebugUnitTest` (27 اختباراً في `PttWalkieTalkieTest` + `PttRegressionTest`، بما فيها 13 اختباراً آخر غير PTT) | ✅ |
| `lintDebug` | ✅ |
| `assembleDebug` + التحقق من حجم APK | ✅ **2,378,211 بايت** |
| رفع الـ APK كـ Artifact | ✅ |

اختبارات جديدة/مُعاد كتابتها في هذه المرحلة:
1. `certificatePinIsStableMatchesTheKeyAndRejectsAnyOtherIdentity`
2. `sessionKeyIsDerivedFromTheVerifiedHostKeyAndSessionOnly` (نفس SPKI ⇒ نفس المفتاح؛ SPKI/جلسة/salt مختلفة ⇒ مفتاح مختلف)
3. `joinProofBindsTheVerifiedHostAndRejectsForeignOrReplayedProofs` (طرف لم يتحقق من الشهادة يُرفض، والإعادة تُرفض)
4. `approvalNonceBindsTheFinalSessionKeyAndNeverLeaksItBeforeApproval` (المفتاح المقيد ≠ الأساسي؛ بلا موافقة لا يوجد مفتاح صالح)
5. `phase2_joinWithoutWifiIsRejectedWithWifiDisabledAndNoFallbackExists`
6. `phase2_joinOfUnverifiedHostIsRefusedBeforeAnySocketIsOpened`
7. `phase2_hostSessionRequiresAHostIdentityAndAdvertisesItsPin`
8. `scenarios4to7_…` أُعيدت كتابتها: pin مغشوش ⇒ `AUTH_FAILED` وبلا أي طلب على المضيف؛ pin صحيح ⇒ رفض ⇒ `REJECTED_BY_HOST`؛ ثم قبول ⇒ جلسة + صوت UDP نصف ازدواجي + إنهاء نظيف
9. اختبار end-to-end على مقابس localhost حقيقية: `CreateSession` بلا رمز → بطاقة موافقة → قبول → `IN_SESSION` من الطرفين → إنهاء المضيف يُنظّف جدول العملاء
10. اختبار الـ fallback: الجيران الخامّون **لا يُعرضون** كجلسات مؤكدة

- التشغيل الناجح: https://github.com/kyyr1566/NOVA-GPS-PRO/actions/runs/41713650289
- الـ APK: تبويب **Artifacts** في نفس الرابط → `RMG-GPS-Pro-WalkieTalkie-Fixed-debug.apk` (2,378,211 بايت)

---

## 7) ما لم يُنفَّذ — إفصاح صريح ⚠️

**لم أختبر Wi-Fi Direct ولا الصوت على هاتفين حقيقيين.** لا يوجد جهازان ولا راديو P2P في بيئتي؛ لذلك:
* **مثبت**: الشيفرة تُبنى، كل الاختبارات تمر، وLint يمر، وسلوك المصافحة/الموافقة/التنظيف يُثبَّت باختبارات حتمية على مقابس حقيقية.
* **غير مثبت**: أن `createGroup` → `connectWithGroupCredentials` ينجح على هاتفيك تحديداً، وأن الصوت يُسمع فعلياً،
  وأن نافذة التأكيد النظامية لـ Wi-Fi Direct تظهر أو لا تظهر على ROM الجهاز.

**خطوات التحقق الميداني على هاتفيك (يجب تثبيت نفس البناء على الاثنين):**
1. المضيف: اسم → «إنشاء جلسة». إن ظهرت نافذة نظام لـ Wi-Fi Direct فوافق عليها؛ انتظر «الجلسة جاهزة».
2. الضيف: اسم → «بحث» → يجب أن يظهر اسم المضيف **مع** شارة جلسة متحققة (وليس جيراناً عشوائيين) → «طلب اتصال».
3. المتوقع: «بانتظار موافقة المضيف» على الضيف، وبطاقة موافقة باسم الضيف على المضيف. اضغط «قبول» ⇒ دخول الطرفين.
4. «رفض» ⇒ يجب أن تظهر للضيف `REJECTED_BY_HOST` (لا مهلة). عدم الرد 38 ثانية ⇒ `APPROVAL_TIMEOUT`.
5. جرّب الضغط على زر التحدث: أخضر لك، كهرماني للطرف الآخر، وصوت مسموع.
6. إن ظهر خطأ، أرسل سطر التشخيص (`[Verify]`/`[P2P-Group]`/`[P2P-MAC]`/`[Approval]`) أو لوجكات الوسم `RMG_PTT`
   — كل مسار فشل يطبع رمز السبب الحقيقي من إطار أندرويد، فأستطيع إكمال الإصلاح من نقطة السبب الفعلية دون تخمين.
